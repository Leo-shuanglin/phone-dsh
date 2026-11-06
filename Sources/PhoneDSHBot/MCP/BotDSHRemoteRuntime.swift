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
        var generation: Int
        var work: Task<BotTaskRecord, Error>?
        var stopWork: Task<BotTaskRecord, Error>?
    }

    private struct TurnCoordinator {
        let sessionId: String
        let attemptId: String
        let kind: BotSessionKind
        let hidden: Bool
        let work: Task<BotRemoteTurnResult, Error>
        var cancelRequested: Bool
        var promptAccepted: Bool
    }

    private struct QueuedTurn {
        let attemptId: String
        let work: Task<BotRemoteTurnResult, Error>
        var cancelled: Bool
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
    private var coordinators: [String: TurnCoordinator] = [:]
    private var queuedTurns: [String: QueuedTurn] = [:]
    private var taskStates: [String: TaskState] = [:]
    private var routineSessions: [String: String] = [:]
    private var mainSessionTask: Task<String, Error>?

    public private(set) var mainSessionId: String?

    public init(
        transport: any DSHRemoteTransport,
        runtime: BotMCPRuntime,
        botId: String = "main",
        model: String = "deepseek-flash",
        provider: String = "deepseek-official",
        wakeInbox: WakeInbox? = nil,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
    ) async throws {
        self.transport = transport
        self.runtime = runtime
        self.botId = botId
        self.model = model
        providerName = provider
        self.wakeInbox = wakeInbox
        self.now = now
        ledger = BotLLMLedger(eventLog: runtime.store)
        watchdog = BotWatchdogMonitor(eventLog: runtime.store, now: now)
        try await runtime.grants.recover()
        await runtime.attachTaskExecutor(self)
    }

    public func sendUserMessage(_ text: String) async throws -> BotRemoteTurnResult {
        let sessionId = try await ensureMainSession()
        let steer = canSteer(sessionId: sessionId)
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
            let result = try await runTurn(sessionId: sessionId, kind: .chat, prompt: prompt, hidden: false, held: held)
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
        do {
            let sessionId = try await ensureRoutineSession(request.routine.id)
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
        guard let sessionId = routineSessions[cancellation.routineId] else {
            return RoutineCancellationResult(cancelled: false, reason: "routine session is not active")
        }
        do {
            try await cancelTurn(sessionId: sessionId)
            return RoutineCancellationResult(cancelled: true)
        } catch {
            return RoutineCancellationResult(cancelled: false, reason: String(describing: error))
        }
    }

    public func start(request: BotTaskRequest) async throws -> BotTaskRecord {
        let taskId = "task-\(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12))"
        let sessionId = try await createSession(kind: .task)
        let record = BotTaskRecord(
            taskId: taskId, sessionId: sessionId, status: "running", title: request.title,
            result: nil, partial: "")
        taskStates[taskId] = TaskState(request: request, record: record, generation: 1, work: nil, stopWork: nil)
        if request.asynchronous {
            let work = Task { try await self.executeTask(taskId: taskId, generation: 1) }
            taskStates[taskId]?.work = work
            return record
        }
        return try await executeTask(taskId: taskId, generation: 1)
    }

    public func await(taskId: String, timeoutMs: Int, parent: BotResolvedToolCall) async throws -> BotTaskRecord {
        guard let state = try ownedTask(taskId: taskId, parent: parent) else {
            throw BotMCPError.invalidRequest("unknown task: \(taskId)")
        }
        if let work = state.work {
            let completed = await waitForTask(work, timeoutMs: timeoutMs)
            if !completed { return taskStates[taskId]?.record ?? state.record }
        }
        return taskStates[taskId]?.record ?? state.record
    }

    public func stop(taskId: String, parent: BotResolvedToolCall) async throws -> BotTaskRecord {
        guard let state = try ownedTask(taskId: taskId, parent: parent) else {
            throw BotMCPError.invalidRequest("unknown task: \(taskId)")
        }
        if let stopWork = state.stopWork { return try await stopWork.value }
        if ["stopped", "completed", "failed"].contains(state.record.status) { return state.record }
        let generation = state.generation + 1
        taskStates[taskId]?.generation = generation
        taskStates[taskId]?.record = BotTaskRecord(
            taskId: state.record.taskId, sessionId: state.record.sessionId,
            status: "stopping", title: state.record.title,
            result: nil, partial: state.record.partial, error: nil)
        let work = Task { try await self.performStop(taskId: taskId, generation: generation) }
        taskStates[taskId]?.stopWork = work
        return try await work.value
    }

    private func performStop(taskId: String, generation: Int) async throws -> BotTaskRecord {
        guard let state = taskStates[taskId], state.generation == generation else {
            throw BotMCPError.invalidRequest("unknown task")
        }
        try await cancelTurn(sessionId: state.record.sessionId, includeQueued: true)
        state.work?.cancel()
        if let work = state.work { _ = try? await work.value }
        let stopped = BotTaskRecord(
            taskId: state.record.taskId, sessionId: state.record.sessionId,
            status: "stopped", title: state.record.title,
            result: nil, partial: state.record.partial, error: nil)
        if taskStates[taskId]?.generation == generation {
            taskStates[taskId]?.record = stopped
            taskStates[taskId]?.work = nil
            taskStates[taskId]?.stopWork = nil
            let principal = try await ensureSession(sessionId: stopped.sessionId, kind: .task)
            _ = try await runtime.eventWriter.append(
                type: "task.settled", principal: principal, data: stopped.jsonValue,
                eventId: "settled:\(taskId)")
        }
        return stopped
    }

    private func executeTask(taskId: String, generation: Int) async throws -> BotTaskRecord {
        guard let state = taskStates[taskId], state.generation == generation,
              state.record.status == "running" else {
            return taskStates[taskId]?.record ?? BotTaskRecord(
                taskId: taskId, sessionId: "", status: "stopped", title: "")
        }
        do {
            let turn = try await runTurn(
                sessionId: state.record.sessionId, kind: .task,
                prompt: state.request.prompt, hidden: true)
            guard let current = taskStates[taskId], current.generation == generation,
                  current.record.status == "running" else {
                return taskStates[taskId]?.record ?? state.record
            }
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
            if state.request.asynchronous, state.request.parent.principal.kind == .chat,
               taskStates[taskId]?.generation == generation {
                let prompt = "[SAND_HIDDEN_PROMPT][subagent]\n\(state.record.title) | \(record.status) | \(taskId)\n\(String(turn.text.prefix(1_200)))"
                _ = try await runTurn(
                    sessionId: state.request.parent.sessionId, kind: .chat, prompt: prompt, hidden: true)
            }
            return record
        } catch {
            guard let current = taskStates[taskId], current.generation == generation,
                  current.record.status == "running" else {
                return taskStates[taskId]?.record ?? state.record
            }
            let record = BotTaskRecord(
                taskId: taskId, sessionId: state.record.sessionId,
                status: "failed", title: state.record.title,
                result: nil, partial: nil, error: String(describing: error))
            taskStates[taskId]?.record = record
            taskStates[taskId]?.work = nil
            return record
        }
    }

    private func ownedTask(taskId: String, parent: BotResolvedToolCall) throws -> TaskState? {
        guard let state = taskStates[taskId] else { return nil }
        guard state.request.parent.principal.botId == parent.principal.botId,
              state.request.parent.sessionId == parent.sessionId,
              state.request.parent.principal.incarnation == parent.principal.incarnation else {
            throw BotMCPError.unauthorized
        }
        return state
    }

    private func waitForTask(_ work: Task<BotTaskRecord, Error>, timeoutMs: Int) async -> Bool {
        await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                _ = try? await work.value
                return true
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(0, timeoutMs)) * 1_000_000)
                return false
            }
            let result = await group.next() ?? false
            group.cancelAll()
            return result
        }
    }

    private func ensureMainSession() async throws -> String {
        if let mainSessionId { return mainSessionId }
        if let mainSessionTask { return try await mainSessionTask.value }
        let work = Task { try await self.recoverOrCreateMainSession() }
        mainSessionTask = work
        do {
            let sessionId = try await work.value
            mainSessionId = sessionId
            mainSessionTask = nil
            return sessionId
        } catch {
            mainSessionTask = nil
            throw error
        }
    }

    private func recoverOrCreateMainSession() async throws -> String {
        if let recovered = try await latestMainSessionEvent() {
            if await validateRemoteSession(recovered) {
                let principal = try await runtime.issueSession(botId: botId, sessionId: recovered, kind: .chat)
                sessions[recovered] = principal
                _ = try await runtime.eventWriter.append(
                    type: "runtime.started", principal: principal,
                    data: .object(["sessionId": .string(recovered), "botId": .string(botId)]))
                return recovered
            }
        }
        let sessionId = try await createSession(kind: .chat)
        let principal = try await ensureSession(sessionId: sessionId, kind: .chat)
        _ = try await runtime.eventWriter.append(
            type: "runtime.started", principal: principal,
            data: .object(["sessionId": .string(sessionId), "botId": .string(botId)]))
        return sessionId
    }

    private func latestMainSessionEvent() async throws -> String? {
        var cursor = 0
        var latest: String?
        while true {
            let events = try await runtime.store.events(after: cursor, limit: 1_000)
            for event in events where event.type == "runtime.started"
                && event.principal.botId == botId && event.principal.kind == BotSessionKind.chat.rawValue {
                latest = event.data["sessionId"]?.stringValue ?? event.principal.sessionId
            }
            guard events.count == 1_000, let commitSeq = events.last?.commitSeq else { return latest }
            cursor = commitSeq
        }
    }

    private func validateRemoteSession(_ sessionId: String) async -> Bool {
        do {
            let page = try await transport.call("session/page", args: .object(["request": .object([
                "address": .object(["kind": .string("session"), "sessionId": .string(sessionId)]),
                "throughSeq": .number(-1), "maxMessages": .number(1),
            ])]))
            if let returned = page["sessionId"]?.stringValue ?? page["session"]?["sessionId"]?.stringValue {
                return returned == sessionId
            }
            return true
        } catch {
            return false
        }
    }

    private func ensureRoutineSession(_ routineId: String) async throws -> String {
        if let existing = routineSessions[routineId] {
            if await validateRemoteSession(existing) { return existing }
            routineSessions.removeValue(forKey: routineId)
        }
        let sessionId = try await createSession(kind: .routine)
        routineSessions[routineId] = sessionId
        return sessionId
    }

    private func createSession(kind: BotSessionKind) async throws -> String {
        let created = try await transport.call("session/create", args: .object(["request": .object([:])]))
        guard let sessionId = created["sessionId"]?.stringValue, !sessionId.isEmpty else {
            throw BotMCPError.unavailable("session/create returned no sessionId")
        }
        let principal = try await runtime.issueSession(botId: botId, sessionId: sessionId, kind: kind)
        sessions[sessionId] = principal
        return sessionId
    }

    private func ensureSession(sessionId: String, kind: BotSessionKind) async throws -> BotGatewayPrincipal {
        if let existing = sessions[sessionId] { return existing }
        guard await validateRemoteSession(sessionId) else {
            throw BotMCPError.unavailable("session/page could not resume \(sessionId)")
        }
        let principal = try await runtime.issueSession(botId: botId, sessionId: sessionId, kind: kind)
        sessions[sessionId] = principal
        return principal
    }

    private func canSteer(sessionId: String) -> Bool {
        guard queuedTurns[sessionId] == nil, let active = coordinators[sessionId],
              active.promptAccepted, !active.cancelRequested, active.kind == .chat, !active.hidden else { return false }
        return true
    }

    private func cancelTurn(sessionId: String, includeQueued: Bool = false) async throws {
        if includeQueued, var queued = queuedTurns[sessionId] {
            queued.cancelled = true
            queuedTurns[sessionId] = queued
        }
        guard var active = coordinators[sessionId] else {
            if includeQueued, let queued = queuedTurns[sessionId] {
                queued.work.cancel()
                _ = try? await queued.work.value
                queuedTurns.removeValue(forKey: sessionId)
            }
            return
        }
        active.cancelRequested = true
        coordinators[sessionId] = active
        _ = try await transport.call("session/cancel", args: .object(["request": .object([
            "sessionId": .string(sessionId),
            "cause": .object(["kind": .string("user")]),
        ])]))
        await waitForCancellation(active)
        if includeQueued, let queued = queuedTurns[sessionId] {
            queued.work.cancel()
            _ = try? await queued.work.value
            queuedTurns.removeValue(forKey: sessionId)
        }
    }

    private func waitForCancellation(_ coordinator: TurnCoordinator) async {
        let completed = await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                _ = try? await coordinator.work.value
                return true
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                return false
            }
            let result = await group.next() ?? false
            group.cancelAll()
            return result
        }
        if !completed {
            coordinator.work.cancel()
            if coordinators[coordinator.sessionId]?.attemptId == coordinator.attemptId {
                coordinators.removeValue(forKey: coordinator.sessionId)
            }
        }
    }

    private func runTurn(sessionId: String, kind: BotSessionKind,
                         prompt: String, hidden: Bool, held: Bool = false) async throws -> BotRemoteTurnResult {
        _ = try await ensureSession(sessionId: sessionId, kind: kind)
        if canSteer(sessionId: sessionId), kind == .chat, !hidden,
           let active = coordinators[sessionId] {
            try await sendPrompt(sessionId: sessionId, prompt: prompt, mode: "steer")
            return try await active.work.value
        }
        if let predecessor = queuedTurns[sessionId]?.work ?? coordinators[sessionId]?.work {
            let attemptId = UUID().uuidString.lowercased()
            let work = Task { () throws -> BotRemoteTurnResult in
                _ = try? await predecessor.value
                if let queued = queuedTurns[sessionId], queued.attemptId == attemptId {
                    queuedTurns.removeValue(forKey: sessionId)
                    if queued.cancelled {
                        return BotRemoteTurnResult(
                            sessionId: sessionId, turnId: nil, status: "cancelled", text: "", error: "cancelled")
                    }
                }
                return try await startCoordinatedTurn(
                    sessionId: sessionId, kind: kind, prompt: prompt, hidden: hidden, held: held,
                    attemptId: attemptId)
            }
            queuedTurns[sessionId] = QueuedTurn(attemptId: attemptId, work: work, cancelled: false)
            return try await work.value
        }
        return try await startCoordinatedTurn(
            sessionId: sessionId, kind: kind, prompt: prompt, hidden: hidden, held: held,
            attemptId: UUID().uuidString.lowercased())
    }

    private func startCoordinatedTurn(sessionId: String, kind: BotSessionKind,
                                      prompt: String, hidden: Bool, held: Bool,
                                      attemptId: String) async throws -> BotRemoteTurnResult {
        let work = Task { try await self.performCoordinatedTurn(
            sessionId: sessionId, kind: kind, prompt: prompt, hidden: hidden, held: held,
            attemptId: attemptId) }
        coordinators[sessionId] = TurnCoordinator(
            sessionId: sessionId, attemptId: attemptId, kind: kind, hidden: hidden,
            work: work, cancelRequested: false, promptAccepted: false)
        return try await work.value
    }

    private func performCoordinatedTurn(sessionId: String, kind: BotSessionKind,
                                        prompt: String, hidden: Bool, held: Bool,
                                        attemptId: String) async throws -> BotRemoteTurnResult {
        await runtime.beginTurn(sessionId: sessionId, hidden: hidden, held: held)
        var mux: (any DSHMuxConnection)?
        do {
            let opened = try await transport.openMux()
            mux = opened
            try await opened.send(.object([
                "type": .string("open"), "streamId": .string("follow"),
                "endpoint": .string("session/follow"),
                "payload": .object(["args": .object(["request": .object([
                    "address": .object(["kind": .string("session"), "sessionId": .string(sessionId)]),
                    "maxMessages": .number(64), "assistantStream": .bool(true),
                ])])]),
            ]))
            try await waitForSnapshot(mux: opened)
            if coordinators[sessionId]?.attemptId != attemptId {
                await opened.close()
                await finishCoordinatedTurn(sessionId: sessionId, attemptId: attemptId)
                return BotRemoteTurnResult(
                    sessionId: sessionId, turnId: nil, status: "cancelled", text: "", error: "stale turn attempt")
            }
            if coordinators[sessionId]?.cancelRequested == true {
                await opened.close()
                await finishCoordinatedTurn(sessionId: sessionId, attemptId: attemptId)
                return BotRemoteTurnResult(
                    sessionId: sessionId, turnId: nil, status: "cancelled", text: "", error: "cancelled")
            }
            try await sendPrompt(sessionId: sessionId, prompt: prompt, mode: "queue")
            coordinators[sessionId]?.promptAccepted = true
            let result = try await collectTurn(
                mux: opened, sessionId: sessionId, kind: kind, hidden: hidden, attemptId: attemptId)
            await opened.close()
            await finishCoordinatedTurn(sessionId: sessionId, attemptId: attemptId)
            return result
        } catch {
            if let mux { await mux.close() }
            await finishCoordinatedTurn(sessionId: sessionId, attemptId: attemptId)
            throw error
        }
    }

    private func finishCoordinatedTurn(sessionId: String, attemptId: String) async {
        guard coordinators[sessionId]?.attemptId == attemptId else { return }
        await runtime.endTurn(sessionId: sessionId)
        coordinators.removeValue(forKey: sessionId)
    }

    private func sendPrompt(sessionId: String, prompt: String, mode: String) async throws {
        let requestId = "bot-\(UUID().uuidString.lowercased())"
        _ = try await transport.call("session/prompt", args: .object(["request": .object([
            "requestId": .string(requestId), "sessionId": .string(sessionId), "mode": .string(mode),
            "content": .array([.object(["type": .string("text"), "text": .string(prompt)])]),
        ])]))
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
                             kind: BotSessionKind, hidden: Bool,
                             attemptId: String) async throws -> BotRemoteTurnResult {
        var currentTurn: String?
        var stepStartedAt: Int64?
        var firstDeltaAt: Int64?
        var texts: [String] = []
        var resultTexts: [String] = []
        var toolNames: [String: String] = [:]
        var suppressWrapUp = false
        while true {
            guard coordinators[sessionId]?.attemptId == attemptId else {
                return BotRemoteTurnResult(
                    sessionId: sessionId, turnId: currentTurn, status: "cancelled",
                    text: "", error: "stale turn attempt")
            }
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
                if coordinators[sessionId]?.cancelRequested == true { continue }
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
            let eventTurn = data["turn"]?.intValue.map(String.init)
            if let currentTurn, let eventTurn, currentTurn != eventTurn { continue }
            if coordinators[sessionId]?.cancelRequested == true, type != "turn/end" { continue }
            let eventTime = Int64((event["time"]?.numberValue ?? Double(now())).rounded())
            switch type {
            case "turn/start":
                currentTurn = eventTurn ?? currentTurn
                if let currentTurn { await runtime.updateTurn(sessionId: sessionId, turnId: currentTurn) }
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
                        data: data, principalKind: kind, time: eventTime, attemptId: attemptId)
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
                            data: .object(["text": .string(text), "role": .string("assistant")]),
                            principalKind: kind, time: eventTime, attemptId: attemptId)
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
                    data: data, principalKind: kind, time: eventTime, attemptId: attemptId)
            case "tool/result":
                let callId = data["message"]?["toolCallId"]?.stringValue ?? ""
                let name = toolNames[callId] ?? ""
                let failed = data["message"]?["isError"]?.boolValue == true || data["error"] != nil
                if isQuestionTool(name), !failed {
                    suppressWrapUp = true
                    await runtime.suppressText(sessionId: sessionId)
                }
                _ = await watchdog.observeToolResult(
                    kind: watchdogKind(kind), id: "\(sessionId):\(currentTurn ?? "unknown")",
                    name: name, status: failed ? "error" : "ok", callId: callId, at: eventTime)
                _ = try await appendConversation(
                    type: "conversation.tool_result", sessionId: sessionId, seq: seq,
                    data: data, principalKind: kind, time: eventTime, attemptId: attemptId)
            case "turn/end":
                await watchdog.finishUnit(kind: watchdogKind(kind), id: "\(sessionId):\(currentTurn ?? "unknown")")
                let reason = data["reason"]?["kind"]?.stringValue ?? "completed"
                let cancelled = coordinators[sessionId]?.cancelRequested == true
                    || reason == "aborted" || reason == "interrupted"
                let status = cancelled ? "cancelled" : reason == "completed" ? "completed" : "failed"
                return BotRemoteTurnResult(
                    sessionId: sessionId, turnId: currentTurn ?? eventTurn, status: status,
                    text: kind == .chat && hidden ? "" : resultTexts.joined(),
                    error: status == "completed" ? nil : data["reason"]?["error"]?["message"]?.stringValue ?? reason)
            default:
                continue
            }
        }
    }

    private func isQuestionTool(_ name: String) -> Bool {
        ["ask_user", "show_next_steps"].contains { bare in
            name == bare || name.hasSuffix("__\(bare)")
        }
    }

    private func appendConversation(type: String, sessionId: String, seq: Int,
                                    data: BotJSONValue, principalKind: BotSessionKind,
                                    time eventTime: Int64? = nil,
                                    attemptId: String) async throws -> Int {
        guard coordinators[sessionId]?.attemptId == attemptId else { return -1 }
        let principal = try await ensureSession(sessionId: sessionId, kind: principalKind)
        guard coordinators[sessionId]?.attemptId == attemptId else { return -1 }
        return try await runtime.eventWriter.append(
            type: type, principal: principal, data: data,
            eventId: "dsh:\(sessionId):\(seq):\(type)", origin: .dsh(sessionId: sessionId, seq: seq),
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
