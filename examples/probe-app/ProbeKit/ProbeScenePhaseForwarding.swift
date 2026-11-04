// SPDX-License-Identifier: MIT
// One SwiftUI scene-phase change, delivered to chat lifecycle and to the
// background-matrix runner. The two tasks are separate: a chat park awaits
// the host drain, and the matrix records the background timestamp while
// that drain is still in progress.

import Foundation

@MainActor
public enum ProbeScenePhaseForwarding {
    /// Invokes `chat` and `backgroundMatrix` once each for this phase.
    ///
    /// The two tasks run independently, so a suspension inside chat (the
    /// host park) does not delay the matrix runner. Call this from the
    /// single `onChange(of: scenePhase)` in `PhoneDSHProbeApp`.
    public static func deliver<Phase>(
        _ phase: Phase,
        chat: @escaping (Phase) async -> Void,
        backgroundMatrix: @escaping (Phase) async -> Void
    ) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let join = ScenePhaseJoin(continuation)
            Task { @MainActor in
                await chat(phase)
                join.finish()
            }
            Task { @MainActor in
                await backgroundMatrix(phase)
                join.finish()
            }
        }
    }
}

/// Resumes the caller's continuation after both deliveries finish.
private final class ScenePhaseJoin: @unchecked Sendable {
    private let lock = NSLock()
    private var remaining = 2
    private var continuation: CheckedContinuation<Void, Never>?

    init(_ continuation: CheckedContinuation<Void, Never>) {
        self.continuation = continuation
    }

    func finish() {
        lock.lock()
        remaining -= 1
        let done = remaining == 0
        let continuation = done ? self.continuation : nil
        if done { self.continuation = nil }
        lock.unlock()
        continuation?.resume()
    }
}
