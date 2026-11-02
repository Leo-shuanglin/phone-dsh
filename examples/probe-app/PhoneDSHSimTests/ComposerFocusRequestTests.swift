// SPDX-License-Identifier: MIT

import Foundation
import ProbeKit
import XCTest

@MainActor
final class ComposerFocusRequestTests: XCTestCase {
    func testSendRevokesFocusBeforeTheDeferredRequestRuns() async {
        var focused = true
        var focusCalls = 0
        ComposerFocusRequest.schedule(isCurrent: { focused }, focus: { focusCalls += 1 })
        focused = false
        await drainMainQueue()
        XCTAssertEqual(focusCalls, 0, "a queued focus request must not undo send's keyboard dismissal")
    }

    func testCurrentRequestFocusesOnce() async {
        var responder = false
        var focusCalls = 0
        ComposerFocusRequest.schedule(isCurrent: { !responder }, focus: {
            responder = true
            focusCalls += 1
        })
        await drainMainQueue()
        XCTAssertTrue(responder)
        XCTAssertEqual(focusCalls, 1)
    }

    func testResponderAcquiredBeforeDeliveryIsNotFocusedAgain() async {
        var responder = false
        var focusCalls = 0
        ComposerFocusRequest.schedule(isCurrent: { !responder }, focus: { focusCalls += 1 })
        responder = true
        await drainMainQueue()
        XCTAssertEqual(focusCalls, 0)
    }

    private func drainMainQueue() async {
        await withCheckedContinuation { continuation in
            DispatchQueue.main.async { continuation.resume() }
        }
    }
}
