// SPDX-License-Identifier: MIT
// The silent keep-alive, dictation, and the phone record tool share one
// AVAudioSession. `setActive(false)` drops every owner. A microphone user
// deactivates the session only while the keep-alive does not hold it;
// otherwise the keep-alive reclaims `.playback` + `.mixWithOthers`.

import AVFoundation
import Foundation

@MainActor
public enum PhoneAudioSessionLease {
    private static var reclaim: (@MainActor () -> Void)?

    /// True between ``beginKeepAlive(reclaim:)`` and ``endKeepAlive()``.
    public static var isKeepAliveHeld: Bool { reclaim != nil }

    /// Marks the silent playback session as the owner that must survive a
    /// later microphone release. `reclaim` restores that session.
    public static func beginKeepAlive(reclaim: @escaping @MainActor () -> Void) {
        self.reclaim = reclaim
    }

    /// Drops the keep-alive claim. The next microphone release deactivates
    /// the shared session.
    public static func endKeepAlive() {
        reclaim = nil
    }

    /// Ends a microphone user's claim. Production callers use
    /// ``releaseMicrophoneUser()``; tests pass `deactivate` so they never
    /// touch the real session.
    public static func releaseMicrophoneUser(deactivate: @MainActor () -> Void) {
        if let reclaim {
            reclaim()
        } else {
            deactivate()
        }
    }

    /// Ends a microphone user's claim on the shared session.
    public static func releaseMicrophoneUser() {
        releaseMicrophoneUser {
            try? AVAudioSession.sharedInstance().setActive(
                false, options: .notifyOthersOnDeactivation)
        }
    }
}
