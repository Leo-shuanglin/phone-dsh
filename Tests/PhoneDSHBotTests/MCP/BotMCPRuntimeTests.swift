// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class BotMCPRuntimeTests: XCTestCase {
    func testCardMemoryTaskAndRoutineCallsUseClaimedSessionAndSQLite() async throws {
        let fixture = try makeBotMCPFixture(taskExecutor: StubBotTaskExecutor())
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-main", kind: .chat)

        let question = try await registerAndExecute(fixture.runtime, name: "ask_user", arguments: .object([
            "question": .string("继续吗？"),
            "options": .array([.object(["label": .string("继续")]), .object(["label": .string("停止")])]),
        ]))
        XCTAssertEqual(question.isError, nil)
        XCTAssertEqual(question.structuredContent?["card"]?["cardType"], .string("question"))

        let running = try await registerAndExecute(fixture.runtime, name: "show_progress", arguments: .object([
            "title": .string("Generate"), "cardKey": .string("job-1"), "state": .string("running"),
        ]))
        let done = try await registerAndExecute(fixture.runtime, name: "show_progress", arguments: .object([
            "title": .string("Generate"), "cardKey": .string("job-1"), "state": .string("done"),
        ]))
        XCTAssertEqual(running.structuredContent?["card"]?["cardKey"], done.structuredContent?["card"]?["cardKey"])

        let memory = try await registerAndExecute(fixture.runtime, name: "UpdateMemory", arguments: .object([
            "action": .string("write"), "fact": .string("我喜欢简短回答"),
        ]))
        XCTAssertEqual(memory.structuredContent?["ok"], .bool(true))
        let recalled = try await registerAndExecute(fixture.runtime, name: "RecallMemory", arguments: .object([
            "query": .string("简短"),
        ]))
        XCTAssertEqual(recalled.structuredContent?["count"], .number(1))

        let created = try await registerAndExecute(fixture.runtime, name: "UpdateRoutine", arguments: .object([
            "action": .string("create"), "title": .string("Morning"), "prompt": .string("Report once"),
            "schedule": .string("@every 60m"),
        ]))
        XCTAssertNotNil(created.structuredContent?["id"]?.stringValue)
        let routines = try await registerAndExecute(fixture.runtime, name: "ListRoutines")
        XCTAssertEqual(routines.structuredContent?["count"], .number(1))

        let task = try await registerAndExecute(fixture.runtime, name: "Task", arguments: .object([
            "task_title": .string("Investigate"), "task_description": .string("inspect"), "async": .bool(true),
        ]))
        let taskId = try XCTUnwrap(task.structuredContent?["task_id"]?.stringValue)
        XCTAssertEqual(task.structuredContent?["status"], .string("running"))
        let awaited = try await registerAndExecute(fixture.runtime, name: "AwaitTask", arguments: .object([
            "task_id": .string(taskId),
        ]))
        XCTAssertEqual(awaited.structuredContent?["status"], .string("completed"))
        let stopped = try await registerAndExecute(fixture.runtime, name: "StopSubagent", arguments: .object([
            "task_id": .string(taskId),
        ]))
        XCTAssertEqual(stopped.structuredContent?["status"], .string("stopped"))

        await fixture.runtime.beginTurn(sessionId: "gb-main", hidden: false)
        let sent = try await registerAndExecute(fixture.runtime, name: "SendToUser", arguments: .object([
            "message": .string("Done"),
        ]))
        XCTAssertEqual(sent.structuredContent?["ok"], .bool(true))
        let events = try await botEvents(fixture.store)
        XCTAssertTrue(events.contains { $0.type == "card.presented" && $0.data["cardKey"] == .string("job-1") })
        XCTAssertTrue(events.contains { $0.type == "memory.written" })
        XCTAssertTrue(events.contains { $0.type == "ui.send_to_user" && $0.data["text"] == .string("Done") })
    }

    func testUnknownP1UnattributedAndCrossSessionCallsFailClosed() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-main", kind: .chat)
        let unknown = await fixture.runtime.execute(
            name: "show_table", arguments: .object([:]),
            context: BotMCPRequestContext(callId: "unknown"))
        XCTAssertEqual(unknown.isError, true)
        XCTAssertEqual(unknown.structuredContent?["error"]?["code"], .string("unknown_tool"))
        let unattributed = await fixture.runtime.execute(
            name: "ListRoutines", arguments: .object([:]),
            context: BotMCPRequestContext(callId: "missing"))
        XCTAssertEqual(unattributed.structuredContent?["error"]?["code"], .string("unattributed"))

        do {
            _ = try await fixture.runtime.registerIdentity(
                runtimeId: BotMCPRuntime.defaultRuntimeID, sessionId: "gb-main",
                callId: "volatile-cross", name: "BotVolatileContext",
                arguments: .object(["sessionId": .string("gb-other")]))
            let cross = await fixture.runtime.execute(
                name: "BotVolatileContext", arguments: .object(["sessionId": .string("gb-other")]),
                context: BotMCPRequestContext(callId: "volatile-cross"))
            XCTAssertEqual(cross.structuredContent?["error"]?["code"], .string("cross_session_read_denied"))
        }
    }

    func testApprovalGrantIsConsumedOnlyAtExecution() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-main", kind: .chat)
        let args = BotJSONValue.object([
            "action": .string("forget"), "scope": .string("user"), "fact": .string("private"),
        ])
        let callId = "approval-call"
        _ = try await fixture.runtime.registerIdentity(
            runtimeId: BotMCPRuntime.defaultRuntimeID, sessionId: "gb-main",
            callId: callId, name: "UpdateMemory", arguments: args)
        let approvalTask = Task {
            await fixture.runtime.approve(
                runtimeId: BotMCPRuntime.defaultRuntimeID,
                callId: callId, name: "UpdateMemory", action: "Forget shared memory", detail: "test")
        }
        var approvalId: String?
        for _ in 0..<100 {
            let ids = await fixture.runtime.approvals.pendingIDs
            if let first = ids.first { approvalId = first; break }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        let id = try XCTUnwrap(approvalId)
        let resolved = await fixture.runtime.approvals.resolve(approvalId: id, decision: .allowOnce)
        XCTAssertTrue(resolved)
        let outcome = await approvalTask.value
        XCTAssertTrue(outcome.allowed)
        let executed = await fixture.runtime.execute(
            name: "UpdateMemory", arguments: args, context: BotMCPRequestContext(callId: callId))
        XCTAssertNotEqual(executed.isError, true)
        let events = try await botEvents(fixture.store)
        XCTAssertTrue(events.contains { $0.type == "approval.requested" })
        XCTAssertTrue(events.contains { $0.type == "approval.decided" })
        XCTAssertTrue(events.contains { $0.type == "grant.consumed" })
    }

    func testRoutineAndTaskSessionsCannotSendToUser() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-routine", kind: .routine)
        let result = try await registerAndExecute(
            fixture.runtime, name: "SendToUser", arguments: .object(["message": .string("hidden")]),
            sessionId: "gb-routine")
        XCTAssertEqual(result.isError, true)
    }
}
