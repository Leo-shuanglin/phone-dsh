// SPDX-License-Identifier: MIT
import Foundation
import PhoneDSHAgentCore
import ProbeKit
import XCTest

@MainActor
final class ChatInputRecoveryTests: XCTestCase {
    func testQueuedInputSurvivesInterruptionThenItsDurableEchoWithoutDuplication() async throws {
        var items = [accepted("queued", text: "Run next")]
        let record = userMessage(20, requestID: "queued", text: "Run next")
        let echo = try XCTUnwrap(DSHEventMapper.map(record).first)
        var current = true
        var opens = 0
        await ChatEventPump.run(after: nil, retryDelay: .milliseconds(1), isCurrent: { current }, events: { _ in
            opens += 1
            return AsyncThrowingStream { continuation in
                if opens == 2 { continuation.yield(echo) }
                continuation.finish()
            }
        }, synchronize: {
            // A queued inbox entry has no user/message in the journal yet.
            items = NativeTranscriptProjector.restoring([], preservingInputsFrom: items)
            return 8
        }, handle: { event in
            NativeTranscriptProjector.acknowledgeInput(event, sessionID: "session", in: &items)
            current = false
        }, interrupted: { _ in }, recovered: {
            XCTAssertEqual(items.map(\.text), ["Run next"], "a successful queued send must stay visible")
        })
        XCTAssertEqual(opens, 2)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].promptJournalSeq, 20)

        let history = NativeTranscriptProjector.items(from: [record], sessionID: "session")
        items = NativeTranscriptProjector.restoring(history, preservingInputsFrom: items)
        items = NativeTranscriptProjector.restoring(history, preservingInputsFrom: items)
        XCTAssertEqual(items.count, 1, "later reconnects replace the local echo by request ID")
        XCTAssertEqual(items[0].promptRequestID, "queued")
    }

    func testSnapshotReconcilesByRequestIdentityAndDoesNotReviveOlderDurableInputs() {
        let history = NativeTranscriptProjector.items(from: [userMessage(90, requestID: "first", text: "Again")],
                                                     sessionID: "session")
        var older = accepted("old", text: "An older prompt outside the tail page")
        older.promptJournalSeq = 1
        let current = [older, accepted("first", text: "Again"), accepted("second", text: "Again")]
        let restored = NativeTranscriptProjector.restoring(history, preservingInputsFrom: current)
        XCTAssertEqual(restored.map(\.promptRequestID), ["first", "second"])
        XCTAssertEqual(restored.map(\.text), ["Again", "Again"], "different requests may have identical text")
        XCTAssertEqual(restored[0].promptJournalSeq, 90)
        XCTAssertNil(restored[1].promptJournalSeq)
    }

    func testAttachmentOnlyEchoBeforeRPCAcknowledgementKeepsDurablePresentationFacts() throws {
        let record = userMessage(12, requestID: "image", text: nil, image: true)
        let echo = try XCTUnwrap(DSHEventMapper.map(record).first)
        var items: [ChatItem] = []
        NativeTranscriptProjector.acknowledgeInput(echo, sessionID: "session", in: &items)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].attachments.count, 1)
        XCTAssertEqual(items[0].timestamp, echo.time)

        NativeTranscriptProjector.acceptInput(accepted("image", text: "[photo.png]"), in: &items)
        NativeTranscriptProjector.acknowledgeInput(echo, sessionID: "session", in: &items)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].text, "[photo.png]")
        XCTAssertEqual(items[0].promptJournalSeq, 12)
        XCTAssertEqual(items[0].attachments.count, 1)
        XCTAssertEqual(items[0].timestamp, echo.time)
        XCTAssertEqual(items[0].turn, 2)
        XCTAssertEqual(items[0].step, 1)
    }

    func testRetryReusingARowDoesNotInheritThePreviousRequestReceipt() {
        var previous = accepted("old", text: "Try again")
        previous.promptJournalSeq = 10
        var retry = ChatItem(id: previous.id, kind: .user, title: "You", text: "Try again")
        retry.promptRequestID = "retry"
        var items = [previous]
        NativeTranscriptProjector.acceptInput(retry, in: &items)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].promptRequestID, "retry")
        XCTAssertNil(items[0].promptJournalSeq)
        XCTAssertEqual(NativeTranscriptProjector.restoring([], preservingInputsFrom: items), items)
    }

    func testRetryReusingARowCollapsesItsNewEchoArrivingBeforeAcknowledgement() throws {
        var previous = accepted("old", text: "Try again")
        previous.promptJournalSeq = 10
        var items = [previous]
        let echo = try XCTUnwrap(DSHEventMapper.map(userMessage(30, requestID: "retry", text: "Try again")).first)
        NativeTranscriptProjector.acknowledgeInput(echo, sessionID: "session", in: &items)
        var retry = ChatItem(id: previous.id, kind: .user, title: "You", text: "Try again")
        retry.promptRequestID = "retry"
        NativeTranscriptProjector.acceptInput(retry, in: &items)
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].id, previous.id)
        XCTAssertEqual(items[0].promptRequestID, "retry")
        XCTAssertEqual(items[0].promptJournalSeq, 30)
        XCTAssertEqual(items[0].timestamp, echo.time)
    }

    func testMissedReceiptOutsideTailPageDoesNotMoveAnExecutedPromptToTheTail() async throws {
        let current = [accepted("consumed", text: "Old request"), accepted("queued", text: "Still queued")]
        let tail = page([userMessage(90, requestID: "tail", text: "Recent")], hasMore: true)
        let inbox: JSONValue = .object(["next-turn": .array([
            .object(["source": .object(["kind": .string("user"), "rpcId": .string("queued")])]),
        ])])
        var fetched: [Int] = []
        let receipts = try await NativeTranscriptProjector.inputReceipts(
            for: current, historyPage: tail, inbox: inbox
        ) { before in
            fetched.append(before)
            return self.page([self.userMessage(10, requestID: "consumed", text: "Old request")], hasMore: true)
        }
        XCTAssertEqual(fetched, [90], "stop looking once every nonqueued request is accounted for")
        XCTAssertEqual(receipts, ["consumed": 10])
        let history = NativeTranscriptProjector.items(from: [userMessage(90, requestID: "tail", text: "Recent")],
                                                     sessionID: "session")
        let restored = NativeTranscriptProjector.restoring(history, preservingInputsFrom: current, inputReceipts: receipts)
        XCTAssertEqual(restored.map(\.promptRequestID), ["tail", "queued"])
    }

    func testPendingInboxAndUncommittedClaimDoNotLoseTheirLocalInputs() async throws {
        let current = [accepted("queued", text: "Still queued")]
        let inbox: JSONValue = .object(["next-step": .array([
            .object(["source": .object(["kind": .string("user"), "rpcId": .string("queued")])]),
        ])])
        let receipts = try await NativeTranscriptProjector.inputReceipts(
            for: current, historyPage: page([], hasMore: true), inbox: inbox
        ) { _ in XCTFail("a confirmed pending input needs no older history"); return .null }
        XCTAssertTrue(receipts.isEmpty)
        let claimed = try await NativeTranscriptProjector.inputReceipts(
            for: current, historyPage: page([], hasMore: false), inbox: nil
        ) { _ in XCTFail("history is exhausted"); return .null }
        XCTAssertEqual(NativeTranscriptProjector.restoring([], preservingInputsFrom: current, inputReceipts: claimed), current,
                       "inbox absence alone does not prove that a claimed input has reached user/message")
    }

    private func page(_ records: [JSONValue], hasMore: Bool) -> JSONValue {
        .object(["records": .array(records.map { .object(["event": $0]) }), "hasMore": .bool(hasMore)])
    }

    private func accepted(_ requestID: String, text: String) -> ChatItem {
        var item = ChatItem(id: "session:prompt:\(requestID)", kind: .user, title: "You", text: text)
        item.promptRequestID = requestID
        return item
    }

    private func userMessage(_ seq: Int, requestID: String, text: String?, image: Bool = false) -> JSONValue {
        var content: [JSONValue] = []
        if let text { content.append(.object(["type": .string("text"), "text": .string(text)])) }
        if image {
            content.append(.object(["type": .string("image"), "name": .string("photo.png"),
                                    "mediaType": .string("image/png"), "path": .string("/images/photo.png")]))
        }
        return .object([
            "seq": .number(Double(seq)), "type": .string("user/message"), "time": .number(1_700_000_000_000),
            "data": .object(["turn": .number(2), "step": .number(1),
                             "source": .object(["kind": .string("user"), "rpcId": .string(requestID)]),
                             "content": .array(content)]),
        ])
    }
}
