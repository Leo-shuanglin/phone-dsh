// SPDX-License-Identifier: MIT

import Foundation

public actor BotMCPRuntime {
    public static let defaultRuntimeID = "phone-worker"

    public let store: SQLiteDomainStore
    public let gateway: BotGateway
    public let grants: BotGrantStore
    public let authorizer: BotExecutionAuthorizer
    public let eventWriter: BotMCPEventWriter
    public let approvals: BotApprovalCoordinator
    public let volatileContext: BotVolatileContextProducer
    public private(set) var tasks: any BotTaskExecuting
    public let memory: BotMemory
    public let routineMachine: RoutineMachine

    private let now: @Sendable () -> Int64
    private let memorySource: @Sendable (BotResolvedToolCall) -> BotMemorySourceKind
    private var registrations: [String: BotAttribution] = [:]
    private var registrationArguments: [String: BotJSONValue] = [:]
    private var suppressingTextSessions: Set<String> = []
    private var hiddenTextSessions: Set<String> = []
    private var heldMessageSessions: Set<String> = []
    private var turnIDs: [String: String] = [:]

    public init(
        store: SQLiteDomainStore,
        gateway: BotGateway? = nil,
        volatileProvider: (any BotVolatileContextProviding)? = nil,
        taskExecutor: (any BotTaskExecuting)? = nil,
        routineStore: (any RoutineStore)? = nil,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
        memorySource: (@Sendable (BotResolvedToolCall) -> BotMemorySourceKind)? = nil
    ) {
        self.store = store
        self.now = now
        let gateway = gateway ?? BotGateway(now: now)
        self.gateway = gateway
        let audit = BotAuditLog(eventLog: store)
        let grants = BotGrantStore(domainStore: store, now: now, audit: audit)
        self.grants = grants
        authorizer = BotExecutionAuthorizer(grants: grants)
        eventWriter = BotMCPEventWriter(eventLog: store, now: now)
        approvals = BotApprovalCoordinator(grants: grants, eventWriter: eventWriter, now: now)
        volatileContext = BotVolatileContextProducer(provider: volatileProvider ?? EmptyBotVolatileContextProvider())
        tasks = taskExecutor ?? UnimplementedBotTaskExecutor()
        memory = BotMemory(
            store: SQLiteBotMemoryFactStore(eventLog: store, now: now),
            dataRoot: store.fileURL.deletingLastPathComponent().path,
            botID: "main", now: now)
        routineMachine = RoutineMachine(store: routineStore ?? SQLiteRoutineStore(domainStore: store))
        self.memorySource = memorySource ?? { call in
            switch call.principal.kind {
            case .chat: return .userTurn
            case .task: return .task
            case .routine: return .routine
            case .team, .proactive, .watchdog: return .hiddenWake
            }
        }
    }

    public func attachTaskExecutor(_ executor: any BotTaskExecuting) {
        tasks = executor
    }

    @discardableResult
    public func issueSession(botId: String, sessionId: String, kind: BotSessionKind) async throws -> BotGatewayPrincipal {
        try await gateway.issueSession(botId: botId, sessionId: sessionId, kind: kind)
    }

    public func currentTurnID(sessionId: String) -> String? { turnIDs[sessionId] }

    public func beginTurn(sessionId: String, hidden: Bool, held: Bool = false) {
        suppressingTextSessions.remove(sessionId)
        turnIDs.removeValue(forKey: sessionId)
        if hidden { hiddenTextSessions.insert(sessionId) } else { hiddenTextSessions.remove(sessionId) }
        if held { heldMessageSessions.insert(sessionId) } else { heldMessageSessions.remove(sessionId) }
    }

    public func updateTurn(sessionId: String, turnId: String) {
        turnIDs[sessionId] = turnId
    }

    public func endTurn(sessionId: String) {
        hiddenTextSessions.remove(sessionId)
        heldMessageSessions.remove(sessionId)
        turnIDs.removeValue(forKey: sessionId)
    }

    public func suppressText(sessionId: String) {
        suppressingTextSessions.insert(sessionId)
    }

    public func registerIdentity(runtimeId: String, sessionId: String, callId: String,
                                 name: String, arguments: BotJSONValue,
                                 argsDigest: String? = nil) async throws -> BotAttribution {
        let bareName = bareToolName(name)
        guard BotToolCatalog.isRegistered(bareName)
                || ["bash", "write", "edit", "phone"].contains(bareName)
                || BotDeviceToolProvider.tools.contains(bareName) else {
            throw BotMCPError.unknownTool(name)
        }
        let attribution = try await gateway.registerToolCall(
            sessionId: sessionId, callId: callId, name: name,
            arguments: arguments, argsDigest: argsDigest, runtimeId: runtimeId)
        let key = key(runtimeId, callId)
        registrations[key] = attribution
        registrationArguments[key] = arguments
        return attribution
    }

    @discardableResult
    public func cancelIdentity(id: String) async -> Bool {
        guard let match = registrations.first(where: { $0.value.id == id }) else { return false }
        registrations.removeValue(forKey: match.key)
        registrationArguments.removeValue(forKey: match.key)
        return await gateway.cancelToolCall(id: id)
    }

    public func identityForApproval(runtimeId: String, callId: String) async -> BotResolvedToolCall? {
        guard let attribution = registrations[key(runtimeId, callId)],
              let principal = await gateway.tokens.lookupSession(attribution.sessionId) else { return nil }
        return BotResolvedToolCall(
            sessionId: attribution.sessionId, callId: attribution.callId,
            agentId: principal.botId, principal: principal, attribution: attribution)
    }

    public func approve(runtimeId: String, callId: String, name: String,
                        action: String, detail: String, command: String,
                        argsDigest: String, alwaysScope: String, askAction: String,
                        suppliedEffects: [BotToolEffect]? = nil) async -> BotApprovalOutcome {
        guard let call = await identityForApproval(runtimeId: runtimeId, callId: callId),
              bareToolName(call.attribution.name) == bareToolName(name),
              let arguments = registrationArguments[key(runtimeId, callId)] else {
            return BotApprovalOutcome(ok: false, allowed: false, message: "unattributed")
        }
        guard let suppliedDigest = try? BotArgumentsDigest.validated(argsDigest),
              suppliedDigest == call.attribution.argsDigest,
              let registeredDigest = try? BotArgumentsDigest.string(for: arguments),
              registeredDigest == suppliedDigest else {
            return BotApprovalOutcome(ok: false, allowed: false, message: "stale:digest_mismatch")
        }
        let bareName = bareToolName(name)
        if bareName == "bash", arguments["command"]?.stringValue != command {
            return BotApprovalOutcome(ok: false, allowed: false, message: "stale:command_mismatch")
        }
        let effects: [BotToolEffect]
        if askAction == "sandbox-escalation" {
            guard bareName == "bash", alwaysScope == "none",
                  let suppliedEffects, suppliedEffects.count == 1,
                  suppliedEffects[0].operation == "sandbox-escalation",
                  !BotResourceNormalizer.normalize(suppliedEffects[0].resource).isEmpty else {
                return BotApprovalOutcome(ok: false, allowed: false, message: "stale:sandbox_effect")
            }
            effects = [BotToolEffect(
                tool: "bash", operation: "sandbox-escalation",
                resource: suppliedEffects[0].resource, target: suppliedEffects[0].target,
                mutability: .write, policy: .ask, label: action, detail: detail)]
        } else {
            effects = effectsForApproval(
                name: bareName,
                arguments: bareName == "bash" ? .object(["command": .string(command)]) : arguments)
        }
        guard ["until", "session", "none"].contains(alwaysScope) else {
            return BotApprovalOutcome(ok: false, allowed: false, message: "stale:always_scope")
        }
        let expectedScope = !effects.isEmpty && effects.allSatisfy { !$0.known } ? "session" : "until"
        guard alwaysScope == expectedScope || alwaysScope == "none" else {
            return BotApprovalOutcome(ok: false, allowed: false, message: "stale:always_scope")
        }
        if effects.isEmpty { return BotApprovalOutcome(ok: true, allowed: true, message: "auto-policy") }
        return await approvals.request(
            call: call, effects: effects, action: action, detail: detail,
            command: command, alwaysScope: alwaysScope)
    }

    public func execute(name: String, arguments: BotJSONValue,
                        context: BotMCPRequestContext) async -> BotMCPCallResult {
        guard BotToolCatalog.isRegistered(name) else { return .failure("unknown_tool", "Unknown tool: \(name)") }
        guard let call = await gateway.resolveToolCall(runtimeId: context.runtimeId, arguments: arguments) else {
            return .failure("unattributed", "The tool call could not be attributed to an active session")
        }
        registrations.removeValue(forKey: key(context.runtimeId, call.callId))
        registrationArguments.removeValue(forKey: key(context.runtimeId, call.callId))
        do {
            if name == BotToolCatalog.volatileContextName {
                let read = BotToolEffect(
                    tool: name, operation: "context.volatile", resource: "session=\(call.sessionId)",
                    mutability: .read, policy: .auto, label: "Read current volatile context")
                guard await authorize(call: call, effect: read) == nil else {
                    return .failure("permission_denied", "Volatile context authorization failed")
                }
                let snapshot = try await volatileContext.snapshot(request: arguments, principal: call.principal)
                return .success(snapshot.jsonValue)
            }
            let effect = BotToolEffectClassifier.effect(for: name, arguments: arguments)
            if let reason = await authorize(call: call, effect: effect) {
                return .failure("permission_denied", reason)
            }
            return try await dispatch(name: name, arguments: arguments, call: call)
        } catch let error as BotMCPError {
            return .failure(Self.code(error), Self.message(error))
        } catch let error as BotCardPayloadError {
            switch error {
            case .invalid(let message): return .failure("invalid_arguments", message)
            }
        } catch {
            return .failure("execution_failed", String(describing: error))
        }
    }

    private func authorize(call: BotResolvedToolCall, effect: BotToolEffect) async -> String? {
        let decision = await authorizer.authorizeExecution(
            principal: BotExecutionPrincipal(gateway: call.principal), effects: [effect],
            source: "app-mcp-execution", argsDigest: call.attribution.argsDigest)
        return decision.allowed ? nil : decision.reason
    }

    private func dispatch(name: String, arguments: BotJSONValue,
                          call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        if BotToolCatalog.cardToolNames.contains(name) {
            let built = try BotCardPayloads.build(tool: name, arguments: arguments)
            guard case .object(let result) = built, let card = result["card"] else {
                throw BotMCPError.invalidRequest("card builder returned no card")
            }
            var fields = card.objectValue ?? [:]
            fields["card"] = card
            fields["id"] = .string("card-\(UUID().uuidString.lowercased())")
            fields["tool"] = .string(name)
            fields["callId"] = .string(call.callId)
            _ = try await eventWriter.append(type: "card.presented", principal: call.principal, data: .object(fields))
            if name == "ask_user" || name == "show_next_steps" {
                suppressingTextSessions.insert(call.sessionId)
                let text = name == "ask_user"
                    ? "已显示提问卡；回合应在此结束，等待用户选择。"
                    : "已显示建议下一步卡片；回合应在此结束，等待用户选择。"
                return .success(built, text: text)
            }
            return .success(built)
        }
        switch name {
        case "UpdateMemory": return try await updateMemory(arguments, call: call)
        case "RecallMemory": return try await recallMemory(arguments)
        case "Task": return try await startTask(arguments, call: call)
        case "AwaitTask": return try await awaitTask(arguments, call: call)
        case "StopSubagent": return try await stopTask(arguments, call: call)
        case "UpdateRoutine": return try await updateRoutine(arguments, call: call)
        case "ListRoutines": return .success(try await BotRoutineTool.list(machine: routineMachine, principal: call.principal))
        case "RequestApproval": return try await requestApproval(arguments, call: call)
        case "SendToUser": return try await sendToUser(arguments, call: call)
        default: throw BotMCPError.unknownTool(name)
        }
    }

    private func updateMemory(_ arguments: BotJSONValue, call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        guard case .object(let args) = arguments else { throw BotMCPError.invalidRequest("arguments must be an object") }
        let action = string(args["action"]) ?? "write"
        let scope = BotMemoryScope(rawValue: string(args["scope"]) ?? "agent") ?? .agent
        switch action {
        case "write":
            guard let text = string(args["fact"]), !text.isEmpty else {
                throw BotMCPError.invalidRequest("UpdateMemory write requires fact")
            }
            let tier = BotMemoryTier(rawValue: string(args["tier"]) ?? "log") ?? .log
            let request = BotMemoryWriteRequest(
                text: text, tier: tier, scope: scope, key: string(args["key"]),
                source: BotMemorySource(
                    kind: hiddenTextSessions.contains(call.sessionId)
                        ? .hiddenWake
                        : heldMessageSessions.contains(call.sessionId) ? .agentMessage : memorySource(call),
                    sessionID: call.principal.sessionId, turnID: turnIDs[call.sessionId],
                    role: call.principal.kind.rawValue))
            let result = try await memory.write(
                request, context: BotMemoryWriteContext(source: request.source))
            return .success(.object([
                "ok": .bool(result.ok), "fact_id": result.factID.map(BotJSONValue.string) ?? .null,
                "duplicated": .bool(result.duplicated), "tier": .string(result.tier.rawValue),
                "scope": .string(result.scope.rawValue), "count": .number(Double(result.count ?? 0)),
                "error": result.error.map(BotJSONValue.string) ?? .null,
            ]))
        case "forget":
            let result = try await memory.forget(text: string(args["fact"]), factID: string(args["fact_id"]), scope: scope)
            return .success(.object([
                "ok": .bool(result.ok), "removed": .number(Double(result.removed)),
                "removed_facts": .number(Double(result.removedFacts)),
                "fact_ids": .array(result.factIDs.map(BotJSONValue.string)),
                "count": .number(Double(result.count)), "scope": .string(result.scope.rawValue),
                "error": result.error.map(BotJSONValue.string) ?? .null,
            ]))
        default: throw BotMCPError.invalidRequest("UpdateMemory action must be write or forget")
        }
    }

    private func recallMemory(_ arguments: BotJSONValue) async throws -> BotMCPCallResult {
        guard case .object(let args) = arguments else { throw BotMCPError.invalidRequest("arguments must be an object") }
        let limit = try optionalInt(args["limit"]) ?? 40
        guard (1...50).contains(limit) else { throw BotMCPError.invalidRequest("RecallMemory limit must be 1…50") }
        let scope = string(args["scope"]) ?? "all"
        guard ["agent", "user", "all"].contains(scope) else { throw BotMCPError.invalidRequest("invalid memory scope") }
        var hits = try await memory.recall(string(args["query"]) ?? "", limit: limit)
        if scope != "all" { hits = hits.filter { $0.scope.rawValue == scope } }
        return .success(.object([
            "ok": .bool(true), "facts": .array(try hits.map { try encode($0) }), "count": .number(Double(hits.count)),
        ]))
    }

    private func startTask(_ arguments: BotJSONValue, call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        guard call.principal.kind == .chat || call.principal.kind == .task else {
            throw BotMCPError.invalidRequest("Task can only start from a chat or task session")
        }
        guard case .object(let args) = arguments else { throw BotMCPError.invalidRequest("arguments must be an object") }
        guard args["task_id"] == nil, args["resume"] == nil else {
            throw BotMCPError.invalidRequest("Task resume is not implemented")
        }
        let title = firstNonEmpty(args["task_title"], args["description"]) ?? "Delegated task"
        guard let prompt = firstNonEmpty(args["prompt"], args["task_description"]), !prompt.isEmpty else {
            throw BotMCPError.invalidRequest("Task requires prompt or task_description")
        }
        let timeout = try optionalInt(args["timeout_ms"]) ?? 180_000
        guard (1_000...300_000).contains(timeout) else { throw BotMCPError.invalidRequest("Task timeout_ms must be 1000…300000") }
        let request = BotTaskRequest(
            parent: call, title: title, prompt: prompt,
            asynchronous: bool(args["async"]) ?? false, timeoutMs: timeout)
        let record = try await tasks.start(request: request)
        _ = try await eventWriter.append(type: "task.created", principal: call.principal, data: record.jsonValue)
        let taskPrincipal = BotGatewayPrincipal(
            botId: call.principal.botId, sessionId: record.sessionId, kind: .task,
            capabilities: call.principal.capabilities, expiresAt: call.principal.expiresAt)
        _ = try await eventWriter.append(type: "task.started", principal: taskPrincipal, data: record.jsonValue)
        if ["completed", "failed", "stopped"].contains(record.status) {
            _ = try await eventWriter.append(
                type: "task.settled", principal: taskPrincipal, data: record.jsonValue,
                eventId: "settled:\(record.taskId)")
        }
        return .success(record.jsonValue)
    }

    private func awaitTask(_ arguments: BotJSONValue, call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        guard case .object(let args) = arguments, let id = string(args["task_id"]), !id.isEmpty else {
            throw BotMCPError.invalidRequest("AwaitTask requires task_id")
        }
        let timeout = try optionalInt(args["timeout_ms"]) ?? 180_000
        guard (1_000...600_000).contains(timeout) else { throw BotMCPError.invalidRequest("AwaitTask timeout_ms must be 1000…600000") }
        let record = try await tasks.await(taskId: id, timeoutMs: timeout, parent: call)
        if ["completed", "failed", "stopped"].contains(record.status) {
            let principal = BotGatewayPrincipal(
                botId: call.principal.botId, sessionId: record.sessionId, kind: .task, expiresAt: Int64.max)
            _ = try await eventWriter.append(
                type: "task.settled", principal: principal, data: record.jsonValue,
                eventId: "settled:\(record.taskId)")
        }
        return .success(record.jsonValue)
    }

    private func stopTask(_ arguments: BotJSONValue, call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        guard case .object(let args) = arguments else { throw BotMCPError.invalidRequest("arguments must be an object") }
        guard let id = firstNonEmpty(args["task_id"], args["id"]), !id.isEmpty else {
            throw BotMCPError.invalidRequest("StopSubagent requires task_id")
        }
        let record = try await tasks.stop(taskId: id, parent: call)
        let principal = BotGatewayPrincipal(
            botId: call.principal.botId, sessionId: record.sessionId, kind: .task, expiresAt: Int64.max)
        _ = try await eventWriter.append(
            type: "task.stop_requested", principal: principal, data: record.jsonValue,
            eventId: "stop:\(record.taskId)")
        _ = try await eventWriter.append(
            type: "task.settled", principal: principal, data: record.jsonValue,
            eventId: "settled:\(record.taskId)")
        return .success(record.jsonValue)
    }

    private func updateRoutine(_ arguments: BotJSONValue, call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        let result = try await BotRoutineTool.handle(
            machine: routineMachine, principal: call.principal, arguments: arguments, now: now())
        guard case .object(let args) = arguments, case .string(let action)? = args["action"] else {
            throw BotMCPError.invalidRequest("UpdateRoutine requires action")
        }
        if !["get", "list"].contains(action) {
            let type = action == "create" ? "routine.defined" : action == "delete" ? "routine.deleted" : "routine.updated"
            _ = try await eventWriter.append(type: type, principal: call.principal, data: result)
        }
        return .success(result)
    }

    private func requestApproval(_ arguments: BotJSONValue, call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        guard case .object(let args) = arguments, let action = string(args["action"]), !action.isEmpty else {
            throw BotMCPError.invalidRequest("RequestApproval requires action")
        }
        let detail = string(args["detail"]) ?? ""
        let command = string(args["command"]) ?? ""
        let resource = BotResourceNormalizer.normalize("action=\(action)")
        var effects = command.isEmpty ? [] : BotApprovalPatternAnalyzer.effects(
            tool: "bash", arguments: .object(["command": .string(command)]), workspace: "/dsh/workspace")
        effects.append(BotToolEffect(
            tool: "RequestApproval", operation: "approval.weak-action", resource: resource,
            target: command.isEmpty ? resource : command, mutability: .destructive, policy: .ask,
            label: action, detail: detail, known: false, isMcp: true))
        let alwaysScope = effects.contains(where: \.known) ? "until" : "session"
        let outcome = await approvals.request(
            call: call, effects: effects, action: action, detail: detail,
            command: command, alwaysScope: alwaysScope)
        return .success(.object([
            "allowed": .bool(outcome.allowed), "decision": .string(outcome.allowed ? "allowed" : "denied"),
            "approvalId": outcome.approvalId.map(BotJSONValue.string) ?? .null,
            "message": outcome.message.map(BotJSONValue.string) ?? .null,
        ]))
    }

    private func sendToUser(_ arguments: BotJSONValue, call: BotResolvedToolCall) async throws -> BotMCPCallResult {
        guard call.principal.kind == .chat else { throw BotMCPError.invalidRequest("SendToUser is only available in the main chat") }
        guard !hiddenTextSessions.contains(call.sessionId), !suppressingTextSessions.contains(call.sessionId) else {
            throw BotMCPError.invalidRequest("Text output is suppressed for this turn")
        }
        guard case .object(let args) = arguments, let message = string(args["message"]), !message.isEmpty else {
            throw BotMCPError.invalidRequest("SendToUser requires message")
        }
        let rowId = "message-\(UUID().uuidString.lowercased())"
        _ = try await eventWriter.append(
            type: "ui.send_to_user", principal: call.principal,
            data: .object([
                "id": .string(rowId), "type": .string("text"), "role": .string("assistant"),
                "text": .string(message), "content": .string(message),
            ]))
        return .success(.object(["ok": .bool(true), "rowId": .string(rowId)]))
    }

    private func effectsForApproval(name: String, arguments: BotJSONValue) -> [BotToolEffect] {
        if name == BotToolCatalog.volatileContextName { return [] }
        if ["bash", "write", "edit"].contains(name) {
            if name == "bash" {
                return BotApprovalPatternAnalyzer.effects(tool: name, arguments: arguments, workspace: "/dsh/workspace")
            }
            return [BotToolEffectClassifier.effect(for: name, arguments: arguments)]
        }
        let effect = BotToolEffectClassifier.effect(for: name, arguments: arguments)
        return effect.mutability == .read || effect.ui || effect.policy == .auto ? [] : [effect]
    }

    private func encode<T: Encodable>(_ value: T) throws -> BotJSONValue {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try BotJSONValue.parse(encoder.encode(value))
    }

    private func firstNonEmpty(_ values: BotJSONValue?...) -> String? {
        for value in values {
            if let text = string(value), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return text }
        }
        return nil
    }

    private func string(_ value: BotJSONValue?) -> String? {
        guard case .string(let text)? = value else { return nil }
        return text
    }

    private func bool(_ value: BotJSONValue?) -> Bool? {
        guard case .bool(let flag)? = value else { return nil }
        return flag
    }

    private func optionalInt(_ value: BotJSONValue?) throws -> Int? {
        guard let value else { return nil }
        guard case .number(let number) = value, number.isFinite, number.rounded() == number else {
            throw BotMCPError.invalidRequest("timeout/limit must be an integer")
        }
        return Int(number)
    }

    private func bareToolName(_ name: String) -> String {
        guard name.hasPrefix("mcp__") else { return name }
        return name.components(separatedBy: "__").last ?? name
    }

    private func key(_ runtimeId: String, _ callId: String) -> String { "\(runtimeId)\u{0}\(callId)" }

    private static func code(_ error: BotMCPError) -> String {
        switch error {
        case .invalidRequest: return "invalid_arguments"
        case .unauthorized: return "unauthorized"
        case .unknownTool: return "unknown_tool"
        case .unattributed: return "unattributed"
        case .crossSessionDenied: return "cross_session_read_denied"
        case .timeout: return "timeout"
        case .unavailable: return "unavailable"
        }
    }

    private static func message(_ error: BotMCPError) -> String {
        switch error {
        case .invalidRequest(let message), .unavailable(let message): return message
        case .unauthorized: return "Unauthorized"
        case .unknownTool(let name): return "Unknown tool: \(name)"
        case .unattributed: return "The call is not attributable"
        case .crossSessionDenied: return "The requested session is outside the caller's principal"
        case .timeout: return "The operation timed out"
        }
    }
}
