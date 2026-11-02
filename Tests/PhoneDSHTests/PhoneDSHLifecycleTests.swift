// SPDX-License-Identifier: MIT

import Foundation
import XCTest
@testable import PhoneDSH

@MainActor
final class PhoneDSHLifecycleTests: XCTestCase {
    func testStartEmitsTheCurrentBackgroundPhase() {
        let observer = PhoneDSHProcessLifecycle(
            notificationCenter: NotificationCenter(),
            currentPhaseProvider: { .background })
        var phases: [PhoneDSHProcessLifecycle.Phase] = []

        observer.start { phases.append($0) }
        defer { observer.stop() }

        XCTAssertEqual(phases, [.background])
        XCTAssertEqual(observer.phase, .background)
    }
}
