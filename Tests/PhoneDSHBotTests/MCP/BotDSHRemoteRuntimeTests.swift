// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class BotDSHRemoteRuntimeTests: XCTestCase {
    func testRealTurnWritesEveryUsageFieldToSQLiteLedger() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[
                .turn(), .delta("hello"), .assistant("hello"), .end(),
            ]],
        ])
        let remote = BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        let result = try await remote.sendUserMessage("hello")
        XCTAssertEqual(result.status, "completed")
        XCTAssertEqual(result.text, "hello")
        let prompt = try XCTUnwrap(transport.prompts["gb-main"])
        XCTAssertEqual(prompt.mode, "queue")
        let records = try await BotLLMLedger(eventLog: fixture.store).records()
        XCTAssertEqual(records.count, 1)
        let record = try XCTUnwrap(records.first)
        XCTAssertEqual(record.purpose, .frontTurn)
        XCTAssertEqual(record.inputTokens, 10)
        XCTAssertEqual(record.outputTokens, 5)
        XCTAssertEqual(record.cacheReadTokens, 4)
        XCTAssertEqual(record.cacheWriteTokens, 2)
        XCTAssertEqual(record.ttftMs, 5)
        XCTAssertEqual(record.durationMs, 10)
        XCTAssertEqual(record.cacheHitRatio, 4.0 / 16.0, accuracy: 1e-12)
        let events = try await botEvents(fixture.store)
        XCTAssertTrue(events.contains { $0.type == "conversation.assistant_message" && $0.data["text"] == .string("hello") })
        XCTAssertTrue(events.contains { $0.type == "ledger.usage_recorded" })
    }

    func testQuestionSuppressesModelWrapUpAndSendToUser() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-main", kind: .chat)
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[
                .turn(), .toolCall("ask_user"), .toolResult(), .assistant("must not escape"), .end(),
            ]],
        ])
        let remote = BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        let result = try await remote.sendUserMessage("choose")
        XCTAssertEqual(result.text, "")
        let sent = try await registerAndExecute(
            fixture.runtime, name: "SendToUser", arguments: .object(["message": .string("must not escape")]))
        XCTAssertEqual(sent.isError, true)
        let events = try await botEvents(fixture.store)
        XCTAssertFalse(events.contains { $0.type == "conversation.assistant_message" && $0.data["text"] == .string("must not escape") })
    }

    func testAsyncTaskUsesSameRuntimeAndWakesParentWithHiddenTurn() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let parent = BotResolvedToolCall(
            sessionId: "gb-main", callId: "parent-call", agentId: "main",
            principal: BotGatewayPrincipal(botId: "main", sessionId: "gb-main", kind: .chat, expiresAt: 10_000),
            attribution: BotAttribution(
                id: "parent", sessionId: "gb-main", callId: "parent-call", name: "Task",
                argsDigest: String(repeating: "0", count: 64), runtimeId: "phone-worker",
                createdAt: 1_000, expiresAt: 2_000))
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[.turn(), .assistant("hidden result must not render"), .end()]],
        ])
        let remote = BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        let started = try await remote.start(request: BotTaskRequest(
            parent: parent, title: "Investigate", prompt: "inspect", asynchronous: true, timeoutMs: 1_000))
        XCTAssertEqual(started.status, "running")
        let taskId = started.taskId
        let taskSession = started.sessionId
        transport.queue(sessionId: taskSession, events: [.turn(), .assistant("done"), .end()])
        let settled = try await remote.await(taskId: taskId, timeoutMs: 1_000)
        XCTAssertEqual(settled.status, "completed")
        let parentPrompt = try XCTUnwrap(transport.prompts["gb-main"])
        XCTAssertTrue(parentPrompt.text.hasPrefix("[SAND_HIDDEN_PROMPT][subagent]"))
        XCTAssertFalse(parentPrompt.text.contains("hidden result must not render"))
        let records = try await BotLLMLedger(eventLog: fixture.store).records()
        XCTAssertEqual(Set(records.map(\.purpose)), [.frontTurn, .workerTurn])
        let events = try await botEvents(fixture.store)
        XCTAssertTrue(events.contains { $0.type == "task.settled" })
        XCTAssertFalse(events.contains { $0.type == "conversation.assistant_message" && $0.data["text"] == .string("hidden result must not render") })
    }

    func testCancelOnlyCancelsCurrentSessionTurn() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[.turn(), .end("aborted")]],
        ])
        let remote = BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        let running = Task { try await remote.sendUserMessage("stop") }
        try await remote.cancelMainTurn()
        let result = try await running.value
        XCTAssertEqual(result.status, "cancelled")
        XCTAssertEqual(transport.cancellations, ["gb-main"])
    }
}
