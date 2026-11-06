// SPDX-License-Identifier: MIT

import Foundation
import PhoneDSHAgentCore

public struct BotRemoteTurnResult: Sendable, Equatable {
    public let sessionId: String
    public let turnId: String?
    public let status: String
    public let text: String
    public let error: String?

    public init(sessionId: String, turnId: String?, status: String, text: String, error: String? = nil) {
        self.sessionId = sessionId
        self.turnId = turnId
        self.status = status
        self.text = text
        self.error = error
    }
}

public actor BotDSHRemoteRuntime: BotTaskExecuting {
    private struct TaskState {
        var request: BotTaskRequest
        var record: BotTaskRecord
        var work: Task<BotTaskRecord, Error>?
    }

    private let transport: any DSHRemoteTransport
    private let runtime: BotMCPRuntime
    private let botId: String
    private let model: String
    private let providerName: String
    private let now: @Sendable () -> Int64
    private let ledger: BotLLMLedger
    private let watchdog: BotWatchdogMonitor
    private let wakeInbox: WakeInbox?
    private var sessions: [String: BotGatewayPrincipal] = [:]
    private var runningSessions: Set<String> = []
    private var taskStates: [String: TaskState] = [:]

    public private(set) var mainSessionId: String?

    public init(
        transport: any DSHRemoteTransport,
        runtime: BotMCPRuntime,
        botId: String = "main",
        model: String = "deepseek-flash",
        provider: String = "deepseek-official",
        wakeInbox: WakeInbox? = nil,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
    ) {
        self.transport = transport
        self.runtime = runtime
        self.botId = botId
        self.model = model
        providerName = provider
        self.wakeInbox = wakeInbox
        self.now = now
        ledger = BotLLMLedger(eventLog: runtime.store)
        watchdog = BotWatchdogMonitor(eventLog: runtime.store, now: now)
    }

    public func sendUserMessage(_ text: String) async throws -> BotRemoteTurnResult {
        let sessionId = try await ensureMainSession()
        let steer = runningSessions.contains(sessionId)
        runningSessions.insert(sessionId)
        defer { runningSessions.remove(sessionId) }
        var prompt = text
        var stagedKey: String?
        var held = false
        if !steer, let wakeInbox {
            let staged = try await wakeInbox.stage(now: now())
            stagedKey = staged.key
            if !staged.items.isEmpty {
                prompt = HeldMessages.block(items: staged.items) + "\n\n" + text
                held = true
            }
        }
        do {
            let result = try await runTurn(
                sessionId: sessionId, kind: .chat, prompt: prompt, hidden: false, steer: steer, held: held)
            if let stagedKey { _ = try await wakeInbox?.commit(stagedKey) }
            return result
        } catch {
            if let stagedKey { _ = try await wakeInbox?.revert(stagedKey) }
            throw error
        }
    }

    public func cancelMainTurn() async throws {
        let sessionId = try await ensureMainSession()
        try await cancelTurn(sessionId: sessionId)
    }

    public func runRoutine(_ request: RoutineRunRequest) async -> RoutineExecutionResult {
        let sessionId = "gb-\(botId)--routine-\(request.routine.id)"
        do {
            let principal = try await ensureSession(sessionId: sessionId, kind: .routine)
            _ = try await runtime.eventWriter.append(
                type: "routine.run_started", principal: principal,
                data: .object([
                    "runId": .string(request.runId), "routineId": .string(request.routine.id),
                    "title": .string(request.routine.title), "attempt": .number(Double(request.attempt)),
                    "scheduledFor": .number(Double(request.startedAt)),
                ]), eventId: "started:\(request.runId)")
            let result = try await runTurn(sessionId: sessionId, kind: .routine, prompt: request.wake.text, hidden: true)
            let reply = RoutineWakeBuilder.normalizeReply(result.text)
            _ = try await runtime.eventWriter.append(
                type: "routine.run_finished", principal: principal,
                data: .object([
                    "runId": .string(request.runId), "routineId": .string(request.routine.id),
                    "title": .string(request.routine.title), "attempt": .number(Double(request.attempt)),
                    "status": .string(result.status), "text": .string(reply.text),
                    "quiet": .bool(reply.quiet), "delivery": .string(request.routine.delivery.rawValue),
                ]), eventId: "finished:\(request.runId)")
            if result.status == "completed" {
                return .success(result.text)
            }
            return RoutineExecutionResult(
                status: result.status == "cancelled" ? .cancelled : .failed,
                text: result.text, error: result.error ?? "routine turn failed")
        } catch {
            return .failure(String(describing: error))
        }
    }

    public func cancelRoutine(_ cancellation: RoutineCancellation) async -> RoutineCancellationResult {
        let sessionId = "gb-\(cancellation.botId)--routine-\(cancellation.routineId)"
        do {
            try await cancelTurn(sessionId: sessionId)
            return RoutineCancellationResult(cancelled: true)
        } catch {
            return RoutineCancellationResult(cancelled: false, reason: String(describing: error))
        }
    }

    public func start(request: BotTaskRequest) async throws -> BotTaskRecord {
        let taskId = "task-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12))"
        let sessionId = "gb-task-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16))"
        let record = BotTaskRecord(
            taskId: taskId, sessionId: sessionId, status: "running", title: request.title,
            result: nil, partial: "")
        taskStates[taskId] = TaskState(request: request, record: record, work: nil)
        _ = try await ensureSession(sessionId: sessionId, kind: .task)
        if request.asynchronous {
            let work = Task { try await self.executeTask(taskId: taskId) }
            taskStates[taskId]?.work = work
            return record
        }
        return try await executeTask(taskId: taskId)
    }

    public func await(taskId: String, timeoutMs: Int) async throws -> BotTaskRecord {
        guard let state = taskStates[taskId] else { throw BotMCPError.invalidRequest("unknown task: \(taskId)") }
        if let work = state.work {
            let timeout = Task { try await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000) }
            let completed = await withTaskGroup(of: Bool.self) { group in
                group.addTask {
                    _ = try? await work.value
                    return true
                }
                group.addTask {
                    try? await timeout.value
                    return false
                }
                let result = await group.next() ?? false
                group.cancelAll()
                return result
            }
            if !completed { return state.record }
        }
        return taskStates[taskId]?.record ?? state.record
    }

    public func stop(taskId: String) async throws -> BotTaskRecord {
        guard var state = taskStates[taskId] else { throw BotMCPError.invalidRequest("unknown task: \(taskId)") }
        try await cancelTurn(sessionId: state.record.sessionId)
        state.work?.cancel()
        let stopped = BotTaskRecord(
            taskId: state.record.taskId, sessionId: state.record.sessionId,
            status: "stopped", title: state.record.title,
            result: state.record.result, partial: state.record.partial)
        state.record = stopped
        taskStates[taskId] = state
        return stopped
    }

    private func executeTask(taskId: String) async throws -> BotTaskRecord {
        guard let state = taskStates[taskId] else { throw BotMCPError.invalidRequest("unknown task") }
        if state.record.status == "stopped" { return state.record }
        do {
            let turn = try await runTurn(
                sessionId: state.record.sessionId, kind: .task,
                prompt: state.request.prompt, hidden: true)
            let completed = turn.status == "completed"
            let record = BotTaskRecord(
                taskId: taskId, sessionId: state.record.sessionId,
                status: completed ? "completed" : "failed", title: state.record.title,
                result: completed ? turn.text : nil,
                partial: turn.text, error: completed ? nil : turn.error ?? "task turn failed")
            taskStates[taskId]?.record = record
            taskStates[taskId]?.work = nil
            let principal = try await ensureSession(sessionId: record.sessionId, kind: .task)
            _ = try await runtime.eventWriter.append(
                type: "task.settled", principal: principal, data: record.jsonValue,
                eventId: "settled:\(taskId)")
            if state.request.asynchronous, state.request.parent.principal.kind == .chat {
                let prompt = "[SAND_HIDDEN_PROMPT][subagent]\n\(state.record.title) | \(record.status) | \(taskId)\n\(String(turn.text.prefix(1_200)))"
                _ = try await runTurn(
                    sessionId: state.request.parent.sessionId, kind: .chat, prompt: prompt, hidden: true)
            }
            return record
        } catch {
            let record = BotTaskRecord(
                taskId: taskId, sessionId: state.record.sessionId,
                status: "failed", title: state.record.title,
                result: nil, partial: nil, error: String(describing: error))
            taskStates[taskId]?.record = record
            taskStates[taskId]?.work = nil
            return record
        }
    }

    private func ensureMainSession() async throws -> String {
        if let mainSessionId { return mainSessionId }
        let created = try await transport.call("session/create", args: .object(["request": .object([:])]))
        guard let sessionId = created["sessionId"]?.stringValue, !sessionId.isEmpty else {
            throw BotMCPError.unavailable("session/create returned no sessionId")
        }
        mainSessionId = sessionId
        let principal = try await runtime.issueSession(botId: botId, sessionId: sessionId, kind: .chat)
        sessions[sessionId] = principal
        _ = try await runtime.eventWriter.append(
            type: "runtime.started", principal: principal,
            data: .object(["sessionId": .string(sessionId), "botId": .string(botId)]))
        return sessionId
    }

    private func ensureSession(sessionId: String, kind: BotSessionKind) async throws -> BotGatewayPrincipal {
        if let existing = sessions[sessionId] { return existing }
        do {
            _ = try await transport.call("session/page", args: .object(["request": .object([
                "address": .object(["kind": .string("session"), "sessionId": .string(sessionId)]),
                "throughSeq": .number(-1), "maxMessages": .number(1),
            ])]))
        } catch {
            let created = try await transport.call("session/create", args: .object(["request": .object([:])]))
            guard let createdId = created["sessionId"]?.stringValue, createdId == sessionId else {
                throw BotMCPError.unavailable("could not resume the required session")
            }
        }
        let principal = try await runtime.issueSession(botId: botId, sessionId: sessionId, kind: kind)
        sessions[sessionId] = principal
        return principal
    }

    private func cancelTurn(sessionId: String) async throws {
        _ = try await transport.call("session/cancel", args: .object(["request": .object([
            "sessionId": .string(sessionId),
            "cause": .object(["kind": .string("user")]),
        ])]))
    }

    private func runTurn(sessionId: String, kind: BotSessionKind,
                         prompt: String, hidden: Bool, steer: Bool = false,
                         held: Bool = false) async throws -> BotRemoteTurnResult {
        _ = try await ensureSession(sessionId: sessionId, kind: kind)
        await runtime.beginTurn(sessionId: sessionId, hidden: hidden, held: held)
        defer { Task { await self.runtime.endTurn(sessionId: sessionId) } }
        let mux = try await transport.openMux()
        defer { Task { await mux.close() } }
        try await mux.send(.object([
            "type": .string("open"), "streamId": .string("follow"),
            "endpoint": .string("session/follow"),
            "payload": .object(["args": .object(["request": .object([
                "address": .object(["kind": .string("session"), "sessionId": .string(sessionId)]),
                "maxMessages": .number(64), "assistantStream": .bool(true),
            ])])]),
        ]))
        try await waitForSnapshot(mux: mux)
        let mode = steer && kind == .chat && !hidden ? "steer" : "queue"
        let requestId = "bot-\(UUID().uuidString.lowercased())"
        _ = try await transport.call("session/prompt", args: .object(["request": .object([
            "requestId": .string(requestId), "sessionId": .string(sessionId), "mode": .string(mode),
            "content": .array([.object(["type": .string("text"), "text": .string(prompt)])]),
        ])]))
        return try await collectTurn(mux: mux, sessionId: sessionId, kind: kind, hidden: hidden)
    }

    private func waitForSnapshot(mux: any DSHMuxConnection) async throws {
        while true {
            guard let frame = try await mux.receive() else {
                throw BotMCPError.unavailable("session/follow ended before its snapshot")
            }
            if frame["type"]?.stringValue == "error" {
                throw BotMCPError.unavailable(frame["error"]?["message"]?.stringValue ?? "session/follow failed")
            }
            guard frame["type"]?.stringValue == "item",
                  frame["streamId"]?.stringValue == "follow" else { continue }
            let value = frame["value"] ?? .null
            if value["type"]?.stringValue == "snapshot" { return }
            if value["type"]?.stringValue == "end" {
                throw BotMCPError.unavailable("session/follow ended before its snapshot")
            }
        }
    }

    private func collectTurn(mux: any DSHMuxConnection, sessionId: String,
                             kind: BotSessionKind, hidden: Bool) async throws -> BotRemoteTurnResult {
        var currentTurn: String?
        var stepStartedAt: Int64?
        var firstDeltaAt: Int64?
        var texts: [String] = []
        var resultTexts: [String] = []
        var toolNames: [String: String] = [:]
        var suppressWrapUp = false
        while true {
            guard let frame = try await mux.receive() else {
                return BotRemoteTurnResult(sessionId: sessionId, turnId: currentTurn,
                                           status: "failed", text: texts.joined(),
                                           error: "session/follow ended before turn/end")
            }
            if frame["type"]?.stringValue == "error" {
                return BotRemoteTurnResult(sessionId: sessionId, turnId: currentTurn,
                                           status: "failed", text: texts.joined(),
                                           error: frame["error"]?["message"]?.stringValue ?? "session/follow failed")
            }
            guard frame["type"]?.stringValue == "item",
                  frame["streamId"]?.stringValue == "follow" else { continue }
            let value = frame["value"] ?? .null
            if value["type"]?.stringValue == "assistant-stream" {
                if firstDeltaAt == nil, let text = value["frame"]?["chunk"]?["text"]?.stringValue,
                   !text.isEmpty, value["frame"]?["chunk"]?["type"]?.stringValue == "text-delta" {
                    firstDeltaAt = Int64((value["time"]?.numberValue ?? Double(now())).rounded())
                }
                continue
            }
            guard value["type"]?.stringValue == "event", let event = value["event"],
                  let type = event["type"]?.stringValue,
                  let seq = event["seq"]?.intValue else { continue }
            let data = event["data"] ?? .null
            let eventTime = Int64((event["time"]?.numberValue ?? Double(now())).rounded())
            switch type {
            case "turn/start":
                currentTurn = data["turn"]?.intValue.map(String.init) ?? currentTurn
                stepStartedAt = eventTime
                firstDeltaAt = nil
                let unit = currentTurn ?? "unknown"
                _ = await watchdog.startUnit(
                    kind: watchdogKind(kind), id: "\(sessionId):\(unit)",
                    botId: botId, sessionId: sessionId, startedAt: eventTime, at: eventTime)
            case "step/start":
                stepStartedAt = eventTime
                firstDeltaAt = nil
            case "user/message":
                if !hidden {
                    _ = try await appendConversation(
                        type: "conversation.user_message", sessionId: sessionId, seq: seq,
                        data: data, principalKind: kind, time: eventTime)
                }
            case "assistant/message":
                let text = textBlocks(data["message"]?["content"])
                let usage = data["usage"]
                let duration = max(0, eventTime - (stepStartedAt ?? eventTime))
                let ttft = firstDeltaAt.map { max(0, $0 - (stepStartedAt ?? $0)) }
                let record = BotLLMLedgerRecord(
                    ts: BotLLMLedgerRecord.timestamp(Date(timeIntervalSince1970: Double(eventTime) / 1_000)),
                    botId: botId, sessionId: sessionId,
                    purpose: kind == .chat ? .frontTurn : .workerTurn,
                    model: data["model"]?.stringValue ?? model,
                    provider: data["provider"]?.stringValue ?? providerName,
                    inputTokens: usage?["inputTokens"]?.intValue ?? 0,
                    outputTokens: usage?["outputTokens"]?.intValue ?? 0,
                    cacheReadTokens: usage?["cacheReadTokens"]?.intValue ?? 0,
                    cacheWriteTokens: usage?["cacheWriteTokens"]?.intValue ?? 0,
                    ttftMs: ttft.map(Int.init), durationMs: Int(duration), status: "ok", errorKind: nil)
                _ = try await ledger.append(record)
                _ = await watchdog.observeUsage(
                    kind: watchdogKind(kind), id: "\(sessionId):\(currentTurn ?? "unknown")",
                    tokens: record.inputTokens + record.outputTokens + record.cacheReadTokens + record.cacheWriteTokens,
                    at: eventTime)
                if !text.isEmpty, !suppressWrapUp {
                    resultTexts.append(text)
                    if !hidden {
                        texts.append(text)
                        _ = try await appendConversation(
                            type: "conversation.assistant_message", sessionId: sessionId, seq: seq,
                            data: .object(["text": .string(text), "role": .string("assistant")]), principalKind: kind, time: eventTime)
                    }
                }
            case "tool/call":
                let callId = data["callId"]?.stringValue ?? ""
                let name = data["name"]?.stringValue ?? ""
                toolNames[callId] = name
                _ = await watchdog.observeToolCall(
                    kind: watchdogKind(kind), id: "\(sessionId):\(currentTurn ?? "unknown")",
                    name: name, callId: callId, at: eventTime)
                _ = try await appendConversation(
                    type: "conversation.tool_call", sessionId: sessionId, seq: seq,
                    data: data, principalKind: kind, time: eventTime)
            case "tool/result":
                let callId = data["message"]?["toolCallId"]?.stringValue ?? ""
                let name = toolNames[callId] ?? ""
                let failed = data["message"]?["isError"]?.boolValue == true || data["error"] != nil
                if ["ask_user", "show_next_steps"].contains(name), !failed {
                    suppressWrapUp = true
                    await runtime.suppressText(sessionId: sessionId)
                }
                _ = await watchdog.observeToolResult(
                    kind: watchdogKind(kind), id: "\(sessionId):\(currentTurn ?? "unknown")",
                    name: name, status: failed ? "error" : "ok", callId: callId, at: eventTime)
                _ = try await appendConversation(
                    type: "conversation.tool_result", sessionId: sessionId, seq: seq,
                    data: data, principalKind: kind, time: eventTime)
            case "turn/end":
                await watchdog.finishUnit(kind: watchdogKind(kind), id: "\(sessionId):\(currentTurn ?? "unknown")")
                let reason = data["reason"]?["kind"]?.stringValue ?? "completed"
                let status = reason == "completed" ? "completed" : reason == "aborted" || reason == "interrupted" ? "cancelled" : "failed"
                return BotRemoteTurnResult(
                    sessionId: sessionId, turnId: currentTurn, status: status,
                    text: kind == .chat && hidden ? "" : resultTexts.joined(),
                    error: status == "completed" ? nil : data["reason"]?["error"]?["message"]?.stringValue ?? reason)
            default:
                continue
            }
        }
    }

    private func appendConversation(type: String, sessionId: String, seq: Int,
                                    data: BotJSONValue, principalKind: BotSessionKind,
                                    time eventTime: Int64? = nil) async throws -> Int {
        let principal = try await ensureSession(sessionId: sessionId, kind: principalKind)
        return try await runtime.eventWriter.append(
            type: type, principal: principal, data: data,
            eventId: "\(sessionId):\(seq):\(type)", origin: .dsh(sessionId: sessionId, seq: seq),
            time: eventTime ?? now())
    }

    private func watchdogKind(_ kind: BotSessionKind) -> BotWatchdogUnitKind {
        switch kind {
        case .chat: return .turn
        case .task: return .task
        case .routine: return .routine
        case .team, .proactive, .watchdog: return .turn
        }
    }

    private func textBlocks(_ content: BotJSONValue?) -> String {
        (content?.arrayValue ?? []).compactMap { block in
            block["type"]?.stringValue == "text" ? block["text"]?.stringValue : nil
        }.joined()
    }
}
