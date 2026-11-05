// SPDX-License-Identifier: MIT
// One SwiftUI scene-phase change, delivered to chat lifecycle and to the
// background-matrix runner. The two deliveries are independent: a chat park
// awaits the host drain, and the matrix records the background timestamp while
// that drain is still in progress.
//
// Delivery is serialized. A chat park can run for the host's 20 s drain bound
// and a resume can wait for the 240 s hello timeout, so a second phase that
// arrived meanwhile must not start its own copy of that work. A newer phase
// supersedes an older pending one: `inactive` → `background` → `active` while a
// resume is in flight delivers `active` once, after the resume, instead of
// replaying `inactive` afterwards.

import Foundation

@MainActor
public enum ProbeScenePhaseForwarding {
    private static var activePhase: String?
    private static var delivery: Task<Void, Never>?

    /// Delivers `phase` to both ends, in order. Returns once this phase has been
    /// delivered (or superseded by a newer one).
    ///
    /// Call this from the single `onChange(of: scenePhase)` in
    /// `PhoneDSHProbeApp`; do not spawn another task around it.
    public static func deliver<Phase: Equatable>(
        _ phase: Phase,
        chat: @escaping @MainActor (Phase) async -> Void,
        backgroundMatrix: @escaping @MainActor (Phase) async -> Void
    ) async {
        let key = String(describing: phase)
        // Same phase twice in a row: the previous delivery owns it.
        guard activePhase != key else { return }
        activePhase = key

        // A newer phase cancelled an older delivery; that delivery resumed its
        // caller already, so wait for it to finish before starting another.
        if let delivery {
            await delivery.value
            // A phase that landed while that delivery was unwinding wins.
            guard activePhase == key else { return }
        }
        let task = Task { @MainActor in
            await withTaskGroup(of: Void.self) { group in
                group.addTask { @MainActor in await chat(phase) }
                group.addTask { @MainActor in await backgroundMatrix(phase) }
            }
            if activePhase == key { activePhase = nil }
        }
        delivery = task
        await task.value
    }
}
