// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

private final class BotE2EClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Int64

    init(_ value: Int64) { self.value = value }

    func now() -> Int64 {
        lock.withLock { value }
    }

    func advance(_ milliseconds: Int64) {
        lock.withLock { value += milliseconds }
    }
}

private actor BotE2ESink: RoutineResultSink {
    private(set) var deliveries: [RoutineAutomation] = []

    func deliver(_ automation: RoutineAutomation) async throws -> Bool {
        deliveries.append(automation)
        return true
    }
}

final class BotConversationE2ETests: XCTestCase {
    func testConversationCardApprovalRoutineAndHeldDelivery() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let clock = BotE2EClock(1_000)
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [
                [.turn(), .assistant("hello"), .end()],
                [.turn(2, time: 2_000), .assistant("held reply", time: 2_010), .end(turn: 2, time: 2_020)],
            ],
        ])
        let inbox = WakeInbox(store: JSONWakeInboxStore(fileURL: fixture.directory.appendingPathComponent("held.json")))
        let remote = BotDSHRemoteRuntime(
            transport: transport, runtime: fixture.runtime, wakeInbox: inbox, now: { clock.now() })

        let reply = try await remote.sendUserMessage("hello")
        XCTAssertEqual(reply.text, "hello")

        let card = try await registerAndExecute(fixture.runtime, name: "ask_user", arguments: .object([
            "question": .string("Continue?"),
            "options": .array([.object(["label": .string("Yes")]), .object(["label": .string("No")])]),
        ]))
        XCTAssertEqual(card.structuredContent?["card"]?["cardType"], .string("question"))

        let approvalTask = Task {
            try await registerAndExecute(fixture.runtime, name: "RequestApproval", arguments: .object([
                "action": .string("Delete report"), "detail": .string("Delete one generated report"),
            ]))
        }
        var approvalId: String?
        for _ in 0..<100 {
            if let first = await fixture.runtime.approvals.pendingIDs.first { approvalId = first; break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        let id = try XCTUnwrap(approvalId)
        _ = await fixture.runtime.approvals.resolve(approvalId: id, decision: .allowOnce)
        let approval = try await approvalTask.value
        XCTAssertEqual(approval.structuredContent?["allowed"], .bool(true))

        let created = try await registerAndExecute(fixture.runtime, name: "UpdateRoutine", arguments: .object([
            "action": .string("create"), "title": .string("Morning"),
            "prompt": .string("Report the morning result"), "schedule": .string("@every 1m"),
        ]))
        let routineId = try XCTUnwrap(created.structuredContent?["id"]?.stringValue)
        transport.queue(sessionId: "gb-main--routine-\(routineId)", events: [
            .turn(), .assistant("Morning result"), .end(),
        ])
        let sink = BotE2ESink()
        let machine = RoutineMachine(store: JSONRoutineStore(
            fileURL: fixture.directory.appendingPathComponent("routines.json")))
        let scheduler = RoutineScheduler(
            botId: "main", machine: machine, inbox: inbox, sink: sink,
            runner: remote.runRoutine, cancelRunner: remote.cancelRoutine,
            now: { clock.now() },
            sleep: { milliseconds in
                try? await Task.sleep(nanoseconds: UInt64(milliseconds) * 1_000_000)
            })
        clock.advance(60_000)
        try await scheduler.tick()
        let deliveries = await sink.deliveries
        XCTAssertEqual(deliveries.count, 1)
        let heldItems = try await inbox.items()
        XCTAssertEqual(heldItems.count, 1)
        XCTAssertEqual(heldItems.map(\.kind), ["routine-result"])
        XCTAssertEqual(heldItems.map(\.text), ["Morning result"])

        _ = try await remote.sendUserMessage("next")
        let nextPrompt = try XCTUnwrap(transport.prompts["gb-main"])
        XCTAssertTrue(nextPrompt.text.contains("<held_agent_messages>"))
        XCTAssertTrue(nextPrompt.text.contains("Morning result"))
        let remainingHeld = try await inbox.items()
        XCTAssertEqual(remainingHeld.count, 0)
        let events = try await botEvents(fixture.store)
        for type in ["card.presented", "approval.requested", "approval.decided", "routine.defined", "routine.run_finished"] {
            XCTAssertTrue(events.contains { $0.type == type }, "missing \(type)")
        }
    }
}
