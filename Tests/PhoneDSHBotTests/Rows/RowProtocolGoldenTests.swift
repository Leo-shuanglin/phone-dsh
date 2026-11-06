// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class RowProtocolGoldenTests: XCTestCase {
    func testSharedRowProtocolCodableVectors() throws {
        let fixture = try RowTestSupport.golden("row-protocol")
        let valid = try RowTestSupport.object(fixture["valid"], in: "row-protocol.valid")

        for key in ["row", "widgetRow"] {
            let expected = try RowTestSupport.object(valid[key], in: "row-protocol.valid.\(key)")
            let row = try RowTestSupport.decode(BotRow.self, expected)
            try RowTestSupport.assertEncoded(row, equals: expected)
            XCTAssertEqual(try JSONDecoder().decode(BotRow.self, from: JSONEncoder().encode(row)), row)
        }

        let batchObject = try RowTestSupport.object(valid["batch"], in: "row-protocol.valid.batch")
        let batch = try RowTestSupport.decode(BotRowBatch.self, batchObject)
        try RowTestSupport.assertEncoded(batch, equals: batchObject)

        let actionObject = try RowTestSupport.object(valid["action"], in: "row-protocol.valid.action")
        let action = try RowTestSupport.decode(BotUserAction.self, actionObject)
        try RowTestSupport.assertEncoded(action, equals: actionObject)

        let resultObject = try RowTestSupport.object(valid["result"], in: "row-protocol.valid.result")
        let result = try RowTestSupport.decode(BotActionResult.self, resultObject)
        try RowTestSupport.assertEncoded(result, equals: resultObject)
    }

    func testSharedActionAcceptDuplicateAndStaleVectorsUseRealDispatcher() async throws {
        let fixture = try RowTestSupport.golden("row-protocol")
        let valid = try RowTestSupport.object(fixture["valid"], in: "row-protocol.valid")
        let invalid = try RowTestSupport.array(fixture["invalid"], in: "row-protocol.invalid")
        let action = try RowTestSupport.decode(BotUserAction.self, valid["action"])
        let expectedAccepted = try RowTestSupport.decode(BotActionResult.self, valid["result"])
        let store = RowNonceStoreDouble()
        let handler = RowActionHandlerDouble()
        if case .accepted(let eventID) = expectedAccepted { await handler.setDefaultEventID(eventID) }
        let dispatcher = BotUserActionDispatcher(nonceStore: store, handler: handler)

        let accepted = try await dispatcher.dispatch(action)
        XCTAssertEqual(accepted, expectedAccepted)
        try RowTestSupport.assertEncoded(accepted, equals: valid["result"] as Any)

        let duplicateVector = invalid[0]
        let duplicateAction = try RowTestSupport.decode(BotUserAction.self, duplicateVector)
        let duplicate = try await dispatcher.dispatch(duplicateAction)
        let expectedDuplicate = try RowTestSupport.decode(BotActionResult.self, duplicateVector["result"])
        XCTAssertEqual(duplicate, expectedDuplicate)
        try RowTestSupport.assertEncoded(duplicate, equals: duplicateVector["result"] as Any)

        let staleVector = invalid[1]
        let staleAction = try RowTestSupport.decode(BotUserAction.self, staleVector)
        let stale = try await dispatcher.dispatch(staleAction)
        let expectedStale = try RowTestSupport.decode(BotActionResult.self, staleVector["result"])
        XCTAssertEqual(stale, expectedStale)
        try RowTestSupport.assertEncoded(stale, equals: staleVector["result"] as Any)

        let actions = await handler.actions
        XCTAssertEqual(actions, [action])
        let trusts = await handler.trusts
        XCTAssertEqual(trusts.map(\.rawValue), [BotUserActionTrust.ownerDirect.rawValue])

        let replacementDispatcher = BotUserActionDispatcher(nonceStore: store, handler: handler)
        let persistedDuplicate = try await replacementDispatcher.dispatch(action)
        XCTAssertEqual(persistedDuplicate, expectedDuplicate)
        let persistedActions = await handler.actions
        XCTAssertEqual(persistedActions.count, 1)
    }

    func testConcurrentDuplicateNonceOnlyCallsHostOnce() async throws {
        let store = RowNonceStoreDouble()
        let handler = RowActionHandlerDouble()
        await handler.setDefaultEventID("evt-concurrent")
        let dispatcher = BotUserActionDispatcher(nonceStore: store, handler: handler)
        let action = BotUserAction(nonce: "same-nonce", botID: "main", kind: .chat, text: "继续")

        async let first = dispatcher.dispatch(action)
        async let second = dispatcher.dispatch(action)
        async let third = dispatcher.dispatch(action)
        let results = try await [first, second, third]
        XCTAssertEqual(results.filter { $0 == .accepted(eventID: "evt-concurrent") }.count, 1)
        XCTAssertEqual(results.filter { $0 == .duplicate(eventID: "evt-concurrent") }.count, 2)
        let actions = await handler.actions
        XCTAssertEqual(actions.count, 1)
    }
}
