// SPDX-License-Identifier: MIT
// phone-dsh phone DSH — PhoneDSH: process foreground/background lifecycle.
//
// Why this observation lives in the library and not in the App
// ------------------------------------------------------------
// The host's park / drain / resume contract was written as a caller duty: the
// App was expected to observe its own scene phase and forward it to the host.
// The function written for exactly that — `ChatModel.scenePhaseChanged` at
// examples/probe-app/PhoneDSHProbe/ChatModel.swift:1311 — has no call site
// anywhere in the repository. The wiring that does exist (PhoneDSHProbeApp
// line 37 -> `AppModel.scenePhaseChanged`, AppModel.swift:142) forwards into
// ProbeKit.BackgroundProbe and never reaches it. So the lifecycle events were
// observed and then dropped: iOS froze the WKWebView Worker mid-turn, the
// connections it held died, and the host's keep-alive, drain and resume paths
// became dead code that no test could reach.
//
// A safety mechanism cannot depend on every App remembering to forward an
// event, so the observation itself is a library concern. This type registers
// the platform notifications itself, needs nothing from the App, and never
// calls the host: turning a phase into `PhoneDSHHost.setForeground(_:)` is the
// integrator's one line (see the DEBUG note at the bottom of this file).

import Foundation

#if canImport(UIKit) && os(iOS)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Observes the process foreground/background lifecycle without App help.
///
/// The first ``start(emitCurrentPhase:_:)`` registers the documented platform
/// notifications and every call adds one handler, so the notifications are
/// registered once however often `start()` runs. `stop()` removes them all and
/// a later `start()` installs a fresh set instead of doubling the old one.
///
/// Platform mapping:
///
/// - iOS (`#if canImport(UIKit) && os(iOS)`):
///   `UIApplication.didBecomeActiveNotification` -> `Phase.active`,
///   `willResignActiveNotification` -> `Phase.inactive`,
///   `didEnterBackgroundNotification` -> `Phase.background`.
/// - macOS (`#if canImport(AppKit)`):
///   `NSApplication.didBecomeActiveNotification` -> `Phase.active`,
///   `willResignActiveNotification` -> `Phase.inactive`,
///   `willHideNotification` -> `Phase.background`. `NSApplication.shared`
///   (`isHidden`, `isActive`) supplies ``phase`` and the first value, because
///   the notification history says nothing about the state before `start()`.
/// - Every other platform: no notification is observed and no handler is ever
///   called.
///
/// Thread model: handlers are `@MainActor`, so they run on the main thread and
/// may touch UI state directly. `start()` and `stop()` are synchronous, may be
/// called from any thread, and never hold the internal lock while a handler
/// runs — a handler may call `stop()` (or register another handler) from inside
/// itself.
public final class PhoneDSHProcessLifecycle: @unchecked Sendable {
    /// One observed lifecycle phase.
    public enum Phase: String, CaseIterable, Hashable, Sendable {
        /// Frontmost and taking input.
        case active
        /// Foregrounded but not taking input: an incoming call, the app
        /// switcher, a system dialog, or a macOS menu being pulled down.
        case inactive
        /// Sent to the background. On iOS the process is suspended shortly
        /// after this notification unless it holds a background task.
        case background
    }

    /// A handle for one registered handler.
    ///
    /// A token is inert once its registration is gone: after `stop()` a later
    /// `start()` mints new tokens, so an old one is simply not found and
    /// ``stop(_:)`` does nothing.
    public struct Token: Hashable, Sendable, CustomStringConvertible {
        /// Unique identity of this registration.
        public let id: UUID

        /// Creates a token. Callers normally keep the one `start()` returns.
        public init(id: UUID = UUID()) {
            self.id = id
        }

        public var description: String {
            "PhoneDSHProcessLifecycle.Token(\(id.uuidString))"
        }
    }

    /// The callback shape: main-actor isolated, so a handler never has to hop
    /// before it touches UI or host state that belongs to the main actor.
    public typealias Handler = @MainActor @Sendable (Phase) -> Void

    private let center: NotificationCenter
    private let currentPhaseProvider: @Sendable () -> Phase?
    private let lock = NSLock()
    private var handlers: [Token: Handler] = [:]
    private var observerTokens: [NSObjectProtocol] = []
    private var started = false
    private var lastEmitted: Phase?
    private var lastKnown: Phase = .active
    /// Bumped whenever observation starts or stops, so a delivery that was
    /// queued to the main thread by an older generation can be dropped instead
    /// of reaching the handlers of a newer one.
    private var generation: UInt64 = 0

    // MARK: - Construction

    /// Creates an observer that is not observing yet.
    ///
    /// - Parameter notificationCenter: Center the platform notifications are
    ///   read from. The default is the process-wide center; tests inject a
    ///   private one and post to it.
    public init(notificationCenter: NotificationCenter = .default) {
        self.center = notificationCenter
        self.currentPhaseProvider = Self.platformPhase
    }

    init(notificationCenter: NotificationCenter,
         currentPhaseProvider: @escaping @Sendable () -> Phase?) {
        self.center = notificationCenter
        self.currentPhaseProvider = currentPhaseProvider
    }

    /// The phase this observer last recorded.
    ///
    /// `.active` until a phase is observed; the first `start()` seeds it from
    /// the platform (when it runs on the main thread), so a caller that reads
    /// the property before the next lifecycle event already has the right
    /// answer.
    public var phase: Phase {
        lock.withLock { lastKnown }
    }

    // MARK: - Observation

    /// Registers `onChange` and, on the first call, starts observing.
    ///
    /// Calling `start()` again while the observer runs only adds a handler; the
    /// platform notifications stay registered once. After `stop()`, the next
    /// `start()` registers them again.
    ///
    /// - Parameters:
    ///   - emitCurrentPhase: When `true` (the default) the first `start()` also
    ///     delivers the phase the platform reports right now, so an observer
    ///     that starts after a backgrounding is not left with a stale state.
    ///     The delivery is synchronous when `start()` is called on the main
    ///     thread and runs before `start()` returns; when `start()` is called
    ///     off the main thread the read happens on the main thread and the
    ///     handler may run before or after `start()` returns. Pass `false` to
    ///     receive notifications only.
    ///   - onChange: Handler for every phase change. Runs on the main actor.
    /// - Returns: A token that removes this handler via ``stop(_:)``.
    @discardableResult
    public func start(emitCurrentPhase: Bool = true,
                      _ onChange: @escaping Handler) -> Token {
        let token = Token()
        let firstStart: Bool = lock.withLock {
            handlers[token] = onChange
            guard !started else { return false }
            started = true
            lastEmitted = nil
            generation &+= 1
            return true
        }
        guard firstStart else { return token }
        installPlatformObservers()
        seedCurrentPhase(emit: emitCurrentPhase)
        return token
    }

    /// Removes every handler and stops observing the platform lifecycle.
    ///
    /// Safe to call more than once and from any thread or from inside a
    /// handler. After it returns, no handler is called again, not even by a
    /// notification that was already in flight.
    public func stop() {
        let tokens: [NSObjectProtocol] = lock.withLock {
            handlers.removeAll()
            started = false
            lastEmitted = nil
            generation &+= 1
            let current = observerTokens
            observerTokens = []
            return current
        }
        for token in tokens {
            center.removeObserver(token)
        }
    }

    /// Removes one handler.
    ///
    /// The platform observers are removed too when this was the last handler,
    /// so a stopped observer costs no wakeups. Unknown (already removed) tokens
    /// are ignored, which makes a token from before a `stop()` harmless.
    public func stop(_ token: Token) {
        let tokens: [NSObjectProtocol] = lock.withLock {
            handlers.removeValue(forKey: token)
            guard started, handlers.isEmpty else { return [] }
            started = false
            lastEmitted = nil
            generation &+= 1
            let current = observerTokens
            observerTokens = []
            return current
        }
        for removed in tokens {
            center.removeObserver(removed)
        }
    }

    // MARK: - Platform observers

    /// Registers one observer per documented notification.
    ///
    /// The observer tokens are appended under the lock and only while `started`
    /// is still true, so a `stop()` that ran between the state change and this
    /// call leaves nothing behind (and cannot remove a later generation's
    /// observers).
    private func installPlatformObservers() {
        let notifications = Self.platformNotifications
        guard !notifications.isEmpty else { return }
        lock.withLock {
            guard started, observerTokens.isEmpty else { return }
            var tokens: [NSObjectProtocol] = []
            tokens.reserveCapacity(notifications.count)
            for notification in notifications {
                let token = center.addObserver(forName: notification.name,
                                               object: nil,
                                               queue: nil) { [weak self] _ in
                    self?.deliver(notification.phase)
                }
                tokens.append(token)
            }
            observerTokens = tokens
        }
    }

    /// Records `phase` and calls every handler on the main actor.
    ///
    /// The handler list is copied under the lock and called after it is
    /// released: a handler that stops the observer or registers another one
    /// cannot deadlock, and a phase that was already delivered once is not
    /// delivered twice (the platform posts overlapping notifications around one
    /// transition). The copy carries the observation generation, so a delivery
    /// queued from a background thread is dropped when `stop()` runs first.
    private func deliver(_ phase: Phase) {
        let snapshot: (callbacks: [Handler], generation: UInt64) = lock.withLock {
            guard started else { return ([], 0) }
            lastKnown = phase
            guard lastEmitted != phase else { return ([], 0) }
            lastEmitted = phase
            return (Array(handlers.values), generation)
        }
        guard !snapshot.callbacks.isEmpty else { return }
        let callbacks = snapshot.callbacks
        let epoch = snapshot.generation
        let call: @MainActor @Sendable () -> Void = { [weak self] in
            guard let self else { return }
            let current: Bool = self.lock.withLock {
                self.started && self.generation == epoch
            }
            guard current else { return }
            for callback in callbacks {
                callback(phase)
            }
        }
        Self.runOnMain(call)
    }

    /// Seeds ``phase`` from the platform and optionally delivers it.
    private func seedCurrentPhase(emit: Bool) {
        let seed: @MainActor @Sendable () -> Void = { [weak self] in
            guard let self, let current = self.currentPhaseProvider() else { return }
            guard self.lock.withLock({ self.started }) else { return }
            if emit {
                self.deliver(current)
            } else {
                self.lock.withLock { self.lastKnown = current }
            }
        }
        Self.runOnMain(seed)
    }

    /// Runs main-actor work on the main thread, without blocking a caller that
    /// is not already on it.
    private static func runOnMain(_ work: @escaping @MainActor @Sendable () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated { work() }
        } else {
            DispatchQueue.main.async {
                MainActor.assumeIsolated { work() }
            }
        }
    }

    /// The phase the platform reports right now, when it can be read.
    ///
    /// `UIApplication.shared` and `NSApplication.shared` are main-thread
    /// objects, so the read is skipped (returning `nil`) off the main thread
    /// rather than risking an isolation violation; the first phase then comes
    /// from the next notification.
    private static func platformPhase() -> Phase? {
        #if canImport(UIKit) && os(iOS)
        guard Thread.isMainThread else { return nil }
        return MainActor.assumeIsolated {
            switch UIApplication.shared.applicationState {
            case .active: return Phase.active
            case .inactive: return Phase.inactive
            case .background: return Phase.background
            @unknown default: return nil
            }
        }
        #elseif canImport(AppKit)
        guard Thread.isMainThread else { return nil }
        return MainActor.assumeIsolated {
            // `NSApp` is nil in a process that never created an application
            // object (a test bundle, a headless tool). Reading `NSApplication
            // .shared` there would instantiate one as a side effect, so an
            // absent app simply has no platform phase to report.
            guard let application = NSApp else { return nil }
            if application.isHidden { return Phase.background }
            return application.isActive ? Phase.active : Phase.inactive
        }
        #else
        return nil
        #endif
    }

    /// The notifications this type observes, with the phase each one means.
    ///
    /// Empty on platforms with neither UIKit nor AppKit: the handlers are
    /// recorded but never called, so the class still starts and stops cleanly
    /// without claiming an observation it cannot make.
    private static var platformNotifications: [(name: Notification.Name, phase: Phase)] {
        #if canImport(UIKit) && os(iOS)
        return [
            (name: UIApplication.didBecomeActiveNotification, phase: .active),
            (name: UIApplication.willResignActiveNotification, phase: .inactive),
            (name: UIApplication.didEnterBackgroundNotification, phase: .background),
        ]
        #elseif canImport(AppKit)
        return [
            (name: NSApplication.didBecomeActiveNotification, phase: .active),
            (name: NSApplication.willResignActiveNotification, phase: .inactive),
            (name: NSApplication.willHideNotification, phase: .background),
        ]
        #else
        return []
        #endif
    }

    deinit {
        let tokens: [NSObjectProtocol] = lock.withLock {
            let current = observerTokens
            observerTokens = []
            return current
        }
        for token in tokens {
            center.removeObserver(token)
        }
    }
}

#if DEBUG
// Integration (the App passes nothing in; it only wires the phase to the host):
//   let lifecycle = PhoneDSHProcessLifecycle()
//   lifecycle.start { phase in Task { await host.setForeground(phase == .active) } }
#endif
