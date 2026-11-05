// SPDX-License-Identifier: MIT

import ProbeKit
import XCTest

@MainActor
final class ProbeScenePhaseForwardingTests: XCTestCase {
    func testDeliverInvokesChatAndMatrixOnce() async {
        let log = PhaseLog()
        await ProbeScenePhaseForwarding.deliver(
            "background",
            chat: { phase in log.add("chat:\(phase)") },
            backgroundMatrix: { phase in log.add("matrix:\(phase)") })
        XCTAssertEqual(log.snapshot.filter { $0.hasPrefix("chat:") }, ["chat:background"])
        XCTAssertEqual(log.snapshot.filter { $0.hasPrefix("matrix:") }, ["matrix:background"])
    }

    func testDeliverForwardsEachPhase() async {
        let log = PhaseLog()
        for phase in ["inactive", "background", "active"] {
            await ProbeScenePhaseForwarding.deliver(
                phase,
                chat: { log.add($0) },
                backgroundMatrix: { _ in })
        }
        XCTAssertEqual(log.snapshot, ["inactive", "background", "active"])
    }

    /// A repeated identical phase while the first delivery is still running
    /// (the lock/unlock notification storm) is dropped, not queued.
    func testDeliverDropsARepeatOfThePhaseInFlight() async {
        let log = PhaseLog()
        let gate = Gate()
        let first = Task { @MainActor in
            await ProbeScenePhaseForwarding.deliver(
                "background",
                chat: { phase in await gate.wait(); log.add("chat:\(phase)") },
                backgroundMatrix: { log.add("matrix:\($0)") })
        }
        await gate.started()
        await ProbeScenePhaseForwarding.deliver(
            "background",
            chat: { log.add("chat:again:\($0)") },
            backgroundMatrix: { log.add("matrix:again:\($0)") })
        await gate.open()
        await first.value
        XCTAssertEqual(log.snapshot.filter { $0.hasPrefix("chat:") }, ["chat:background"])
        XCTAssertEqual(log.snapshot.filter { $0.hasPrefix("matrix:") }, ["matrix:background"])
    }

    /// A newer phase that arrives during a long park must not start a second
    /// delivery of its own. Two parks overlapping is what the un-serialized
    /// onChange + Task per phase produced, and the second one re-parked a host
    /// the first had just resumed.
    func testDeliverNeverRunsTwoChatDeliveriesAtOnce() async {
        let log = PhaseLog()
        let inFlight = Counter()
        let peak = Counter()
        let gate = Gate()
        func chat(_ phase: String) async {
            let live = inFlight.incrementAndGet()
            peak.raise(to: live)
            if phase == "background" { await gate.wait() }
            log.add("chat:\(phase)")
            inFlight.decrement()
        }
        let parked = Task { @MainActor in
            await ProbeScenePhaseForwarding.deliver(
                "background", chat: chat, backgroundMatrix: { _ in })
        }
        await gate.started()
        let resumed = Task { @MainActor in
            await ProbeScenePhaseForwarding.deliver(
                "active", chat: chat, backgroundMatrix: { _ in })
        }
        for _ in 0..<50 { await Task.yield() }
        XCTAssertEqual(peak.value, 1, "a second chat delivery started while a park was in flight")
        await gate.open()
        await parked.value
        await resumed.value
        XCTAssertEqual(peak.value, 1)
        XCTAssertEqual(log.snapshot, ["chat:background", "chat:active"])
    }
}

@MainActor
final class PhoneAudioSessionLeaseTests: XCTestCase {
    override func setUp() {
        super.setUp()
        PhoneAudioSessionLease.endKeepAlive()
    }

    override func tearDown() {
        PhoneAudioSessionLease.endKeepAlive()
        super.tearDown()
    }

    func testReleaseDeactivatesWhenKeepAliveIsIdle() {
        let deactivated = Counter()
        PhoneAudioSessionLease.releaseMicrophoneUser { deactivated.increment() }
        XCTAssertEqual(deactivated.value, 1)
        XCTAssertFalse(PhoneAudioSessionLease.isKeepAliveHeld)
    }

    func testReleaseReclaimsWhileKeepAliveHoldsTheSession() {
        let deactivated = Counter()
        let reclaimed = Counter()
        PhoneAudioSessionLease.beginKeepAlive { reclaimed.increment() }
        PhoneAudioSessionLease.releaseMicrophoneUser { deactivated.increment() }
        XCTAssertEqual(deactivated.value, 0)
        XCTAssertEqual(reclaimed.value, 1)
        XCTAssertTrue(PhoneAudioSessionLease.isKeepAliveHeld)
    }

    func testEndingKeepAliveRestoresDeactivation() {
        let deactivated = Counter()
        PhoneAudioSessionLease.beginKeepAlive {}
        PhoneAudioSessionLease.endKeepAlive()
        PhoneAudioSessionLease.releaseMicrophoneUser { deactivated.increment() }
        XCTAssertEqual(deactivated.value, 1)
        XCTAssertFalse(PhoneAudioSessionLease.isKeepAliveHeld)
    }
}

private final class PhaseLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []

    func add(_ item: String) {
        lock.lock()
        items.append(item)
        lock.unlock()
    }

    var snapshot: [String] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = 0

    func increment() {
        lock.lock()
        stored += 1
        lock.unlock()
    }

    func decrement() {
        lock.lock()
        stored -= 1
        lock.unlock()
    }

    /// Increments and returns the new value.
    func incrementAndGet() -> Int {
        lock.lock()
        stored += 1
        let value = stored
        lock.unlock()
        return value
    }

    func raise(to candidate: Int) {
        lock.lock()
        stored = max(stored, candidate)
        lock.unlock()
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}

/// Lets a test hold one delivery open until another has been attempted.
private final class Gate: @unchecked Sendable {
    private let lock = NSLock()
    private var isOpen = false
    private var started = false
    private var startWaiters: [CheckedContinuation<Void, Never>] = []
    private var openWaiters: [CheckedContinuation<Void, Never>] = []

    /// Resumes once `wait()` has been entered, so the caller cannot race the
    /// open below.
    func started() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            if started {
                lock.unlock()
                continuation.resume()
                return
            }
            startWaiters.append(continuation)
            lock.unlock()
        }
    }

    func wait() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            started = true
            let waiters = startWaiters
            startWaiters = []
            let alreadyOpen = isOpen
            if !alreadyOpen { openWaiters.append(continuation) }
            lock.unlock()
            waiters.forEach { $0.resume() }
            if alreadyOpen { continuation.resume() }
        }
    }

    func open() async {
        lock.lock()
        isOpen = true
        let waiters = openWaiters
        openWaiters = []
        lock.unlock()
        waiters.forEach { $0.resume() }
        // Give the resumed delivery a chance to run to completion.
        for _ in 0..<50 { await Task.yield() }
    }
}
