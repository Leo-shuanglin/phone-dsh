// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class RowUserActionTests: XCTestCase {
    func testEveryActionKindIsForwardedWithHostOwnedTrust() async throws {
        let actions = [
            BotUserAction(nonce: "chat", botID: "main", kind: .chat, text: "继续"),
            BotUserAction(nonce: "answer", botID: "main", kind: .cardAnswer, cardID: "card-1", optionID: "yes"),
            BotUserAction(nonce: "custom", botID: "main", kind: .cardAnswer, cardID: "card-1", customText: "自定义"),
            BotUserAction(nonce: "dismiss", botID: "main", kind: .cardDismiss, cardID: "card-1"),
            BotUserAction(nonce: "approval", botID: "main", kind: .approval, approvalID: "approval-1",
                          decision: .allowOnce, digest: "digest-1"),
            BotUserAction(nonce: "stop", botID: "main", kind: .stopTask, threadID: "thread-1")
        ]
        let store = RowNonceStoreDouble()
        let handler = RowActionHandlerDouble()
        for action in actions {
            await handler.setResult(.accepted(eventID: "evt-\(action.nonce)"), for: action.nonce)
        }
        let dispatcher = BotUserActionDispatcher(nonceStore: store, handler: handler)

        for action in actions {
            let result = try await dispatcher.dispatch(action)
            XCTAssertEqual(result, .accepted(eventID: "evt-\(action.nonce)"))
        }
        let forwarded = await handler.actions
        let trusts = await handler.trusts
        XCTAssertEqual(forwarded, actions)
        XCTAssertEqual(trusts.map(\.rawValue), Array(repeating: "owner_direct", count: actions.count))
    }

    func testRejectedOrStaleActionDoesNotConsumeNonce() async throws {
        let store = RowNonceStoreDouble()
        let handler = RowActionHandlerDouble()
        await handler.setResult(.accepted(eventID: "evt-approval"), for: "approval-retry")
        let dispatcher = BotUserActionDispatcher(nonceStore: store, handler: handler)
        let missingAnswer = BotUserAction(nonce: "bad-answer", botID: "main", kind: .cardAnswer, cardID: "card-1")
        let rejected = try await dispatcher.dispatch(missingAnswer)
        XCTAssertEqual(rejected, .rejected(reason: "missing_card_answer"))

        var approval = BotUserAction(nonce: "approval-retry", botID: "main", kind: .approval,
                                     approvalID: "approval-1", decision: .deny, digest: "wrong")
        let stale = try await dispatcher.dispatch(approval)
        XCTAssertEqual(stale, .stale(reason: "digest_mismatch"))
        approval.digest = "digest-1"
        let accepted = try await dispatcher.dispatch(approval)
        XCTAssertEqual(accepted, .accepted(eventID: "evt-approval"))
        let duplicate = try await dispatcher.dispatch(approval)
        XCTAssertEqual(duplicate, .duplicate(eventID: "evt-approval"))
    }
}
