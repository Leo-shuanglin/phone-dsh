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
        let remote = try await BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
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
        XCTAssertTrue(events.contains { $0.eventId.contains("dsh:gb-main:3:conversation.assistant_message") })
    }

    func testSecondMessageSteersTheOnlyCollectorAndNextTurnGetsANewAttempt() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[.turn(), .assistant("combined"), .end()]],
        ])
        transport.pause("gb-main")
        let remote = try await BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        let first = Task { try await remote.sendUserMessage("first") }
        try await waitUntil { transport.promptHistory.count == 1 }
        let second = Task { try await remote.sendUserMessage("second") }
        try await waitUntil { transport.promptHistory.count == 2 }
        XCTAssertEqual(transport.openMuxCount, 1)
        XCTAssertEqual(transport.promptHistory.map(\.mode), ["queue", "steer"])
        transport.resume("gb-main")
        let results = try await [first.value, second.value]
        XCTAssertEqual(results.map(\.text), ["combined", "combined"])
        XCTAssertEqual(results.map(\.turnId), ["1", "1"])
        transport.queue(sessionId: "gb-main", events: [.turn(2), .assistant("third result"), .end(turn: 2)])
        let third = try await remote.sendUserMessage("third")
        XCTAssertEqual(third.text, "third result")
        XCTAssertEqual(transport.openMuxCount, 2)
        XCTAssertEqual(transport.promptHistory.last?.mode, "queue")
    }

    func testMainSessionRecoversFromSQLiteAndFailedPageCreatesOnce() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let firstTransport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[.turn(), .assistant("first"), .end()]],
        ])
        let first = try await BotDSHRemoteRuntime(transport: firstTransport, runtime: fixture.runtime)
        _ = try await first.sendUserMessage("first")

        let resumedTransport = BotScriptedRemoteTransport(mainId: "gb-new", scripts: [
            "gb-main": [[.turn(2), .assistant("resumed"), .end(turn: 2)]],
        ])
        let resumed = try await BotDSHRemoteRuntime(transport: resumedTransport, runtime: fixture.runtime)
        let resumedResult = try await resumed.sendUserMessage("resume")
        XCTAssertEqual(resumedResult.sessionId, "gb-main")
        XCTAssertEqual(resumedResult.text, "resumed")
        XCTAssertEqual(resumedTransport.createCount, 0)
        let recoveredID = await resumed.mainSessionId
        XCTAssertEqual(recoveredID, "gb-main")

        let replacementTransport = BotScriptedRemoteTransport(mainId: "gb-new", scripts: [
            "gb-new": [[.turn(), .assistant("replacement"), .end()]],
        ])
        replacementTransport.failPage("gb-main")
        let replacement = try await BotDSHRemoteRuntime(transport: replacementTransport, runtime: fixture.runtime)
        let replaced = try await replacement.sendUserMessage("replace")
        XCTAssertEqual(replaced.sessionId, "gb-new")
        XCTAssertEqual(replaced.text, "replacement")
        XCTAssertEqual(replacementTransport.createCount, 1)
    }

    func testQuestionSuppressesModelWrapUpAndSendToUser() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-main", kind: .chat)
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[
                .turn(), .toolCall("mcp__phone__ask_user"), .toolResult(), .assistant("must not escape"), .end(),
            ]],
        ])
        let remote = try await BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        let result = try await remote.sendUserMessage("choose")
        XCTAssertEqual(result.text, "")
        let sent = try await registerAndExecute(
            fixture.runtime, name: "SendToUser", arguments: .object(["message": .string("must not escape")]))
        XCTAssertEqual(sent.isError, true)
        let events = try await botEvents(fixture.store)
        XCTAssertFalse(events.contains { $0.type == "conversation.assistant_message" && $0.data["text"] == .string("must not escape") })
        XCTAssertTrue(events.contains { $0.eventId.contains("dsh:gb-main:3:conversation.tool_result") })
    }

    func testAsyncTaskUsesCreatedSessionAndWakesParentWithHiddenTurn() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let parent = try await makeParent(fixture.runtime)
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [
                [.turn(), .assistant("parent"), .end()],
                [.turn(2), .assistant("hidden result must not render"), .end(turn: 2)],
            ],
        ])
        let remote = try await BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        _ = try await remote.sendUserMessage("parent")
        transport.pause("gb-created-1")
        transport.queue(sessionId: "gb-created-1", events: [.turn(), .assistant("done"), .end()])
        let started = try await remote.start(request: BotTaskRequest(
            parent: parent, title: "Investigate", prompt: "inspect", asynchronous: true, timeoutMs: 1_000))
        XCTAssertEqual(started.status, "running")
        XCTAssertEqual(started.sessionId, "gb-created-1")
        XCTAssertNotEqual(started.sessionId, parent.sessionId)
        let taskId = started.taskId
        let taskSession = started.sessionId
        let waiting = Task { try await remote.await(taskId: taskId, timeoutMs: 1_000, parent: parent) }
        transport.resume(taskSession)
        let settled = try await waiting.value
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

    func testTaskStopFencesLateCompletionAndParentOwnership() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let parent = try await makeParent(fixture.runtime)
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[.turn(), .assistant("parent"), .end()]],
        ])
        let remote = try await BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        _ = try await remote.sendUserMessage("parent")
        transport.pause("gb-created-1")
        transport.queue(sessionId: "gb-created-1", events: [
            .turn(), .assistant("late completion"), .end(),
        ])
        let started = try await remote.start(request: BotTaskRequest(
            parent: parent, title: "Stop me", prompt: "inspect", asynchronous: true, timeoutMs: 1_000))
        let stopping = Task { try await remote.stop(taskId: started.taskId, parent: parent) }
        try await waitUntil { transport.cancellations == [started.sessionId] }
        transport.resume(started.sessionId)
        let stopped = try await stopping.value
        XCTAssertEqual(stopped.status, "stopped")
        let awaited = try await remote.await(taskId: started.taskId, timeoutMs: 1_000, parent: parent)
        XCTAssertEqual(awaited.status, "stopped")
        XCTAssertNil(awaited.result)
        XCTAssertEqual(transport.promptHistory.filter { $0.sessionId == "gb-main" }.count, 1)
        let events = try await botEvents(fixture.store)
        let settled = events.filter { $0.type == "task.settled" }
        XCTAssertEqual(settled.count, 1)
        XCTAssertEqual(settled.first?.data["status"], .string("stopped"))

        let other = try await makeParent(fixture.runtime, sessionId: "gb-other", callId: "other-parent")
        await assertUnauthorized { try await remote.await(taskId: started.taskId, timeoutMs: 1_000, parent: other) }
        await assertUnauthorized { try await remote.stop(taskId: started.taskId, parent: other) }
        let nextIncarnation = try await makeParent(fixture.runtime, callId: "next-incarnation")
        await assertUnauthorized { try await remote.await(taskId: started.taskId, timeoutMs: 1_000, parent: nextIncarnation) }
    }

    func testCancelOnlyCancelsCurrentSessionTurn() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        let transport = BotScriptedRemoteTransport(scripts: [
            "gb-main": [[.turn(), .end("aborted")]],
        ])
        transport.pause("gb-main")
        let remote = try await BotDSHRemoteRuntime(transport: transport, runtime: fixture.runtime)
        let running = Task { try await remote.sendUserMessage("stop") }
        try await waitUntil { transport.openMuxCount == 1 }
        let cancelling = Task { try await remote.cancelMainTurn() }
        try await waitUntil { transport.cancellations == ["gb-main"] }
        transport.resume("gb-main")
        try await cancelling.value
        let result = try await running.value
        XCTAssertEqual(result.status, "cancelled")
        XCTAssertEqual(transport.cancellations, ["gb-main"])
    }

    private func makeParent(_ runtime: BotMCPRuntime, sessionId: String = "gb-main",
                            callId: String = UUID().uuidString) async throws -> BotResolvedToolCall {
        let principal = try await runtime.issueSession(botId: "main", sessionId: sessionId, kind: .chat)
        let arguments = BotJSONValue.object(["prompt": .string("parent")])
        let attribution = try await runtime.registerIdentity(
            runtimeId: BotMCPRuntime.defaultRuntimeID, sessionId: sessionId,
            callId: callId, name: "Task", arguments: arguments)
        guard let call = BotResolvedToolCall(
            sessionId: sessionId, callId: callId, agentId: "main",
            principal: principal, attribution: attribution) else {
            throw BotMCPError.unauthorized
        }
        return call
    }

    private func waitUntil(_ condition: @escaping @Sendable () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        XCTFail("condition did not become true")
    }

    private func assertUnauthorized(_ operation: @escaping @Sendable () async throws -> Void) async {
        do {
            try await operation()
            XCTFail("expected cross-parent operation to fail")
        } catch BotMCPError.unauthorized {
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }
}
