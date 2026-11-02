// SPDX-License-Identifier: MIT

import Foundation

/// A deferred focus request must use the intent at delivery time.
/// Sending or leaving the editor may revoke focus during the main-queue hop.
public enum ComposerFocusRequest {
    @MainActor
    public static func schedule(
        isCurrent: @escaping @MainActor @Sendable () -> Bool,
        focus: @escaping @MainActor @Sendable () -> Void
    ) {
        DispatchQueue.main.async {
            guard isCurrent() else { return }
            focus()
        }
    }
}
