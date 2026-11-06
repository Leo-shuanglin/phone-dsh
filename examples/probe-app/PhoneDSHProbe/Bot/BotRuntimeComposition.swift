// SPDX-License-Identifier: MIT

import Foundation
import PhoneDSH
import PhoneDSHAgentCore
import PhoneDSHBot
#if canImport(ProbeKit)
import ProbeKit
#endif

actor BotDeferredVolatileContextProvider: BotVolatileContextProviding {
    private var provider: (any BotVolatileContextProviding)?

    func attach(_ provider: any BotVolatileContextProviding) {
        self.provider = provider
    }

    func snapshot(botId: String, sessionId: String, kind: BotSessionKind) async throws -> BotVolatileBlocks {
        guard let provider else { throw BotMCPError.unavailable("volatile provider is not connected") }
        return try await provider.snapshot(botId: botId, sessionId: sessionId, kind: kind)
    }
}

actor BotRemoteActionPlanner: BotUserActionPlanning {
    let remote: BotDSHRemoteRuntime
    let runtime: BotMCPRuntime
    let handler: BotMCPHandler
    let store: SQLiteDomainStore
    let botID: String

    init(remote: BotDSHRemoteRuntime, runtime: BotMCPRuntime,
         handler: BotMCPHandler, store: SQLiteDomainStore, botID: String) {
        self.remote = remote
        self.runtime = runtime
        self.handler = handler
        self.store = store
        self.botID = botID
    }

    func plan(_ action: BotUserAction, trust: BotUserActionTrust) async throws -> BotUserActionPlanOutcome {
        guard trust == .ownerDirect, action.botID == botID else {
            return .rejected(reason: "untrusted_action")
        }
        switch action.kind {
        case .chat:
            guard let text = action.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
                return .rejected(reason: "missing_text")
            }
            return try await complete(action, result: remote.sendUserMessage(text))
        case .cardAnswer:
            guard let cardID = action.cardID, let optionID = action.optionID,
                  let card = try await card(cardID), card.kind == "question" || card.kind == "steps" else {
                return .stale(reason: "unknown_card")
            }
            guard let option = card.options.first(where: { $0.id == optionID }) else {
                return .stale(reason: "unknown_option")
            }
            let prompt = "<user_action>Owner selected card \(cardID), option \(optionID): \(option.label)</user_action>"
            return try await complete(action, result: remote.sendHiddenInteraction(prompt))
        case .cardDismiss:
            guard let cardID = action.cardID, let card = try await card(cardID), card.kind == "question" else {
                return .rejected(reason: "only_question_cards_can_be_dismissed")
            }
            let prompt = "<user_action>Owner dismissed question card \(cardID). Do not show this card again unless a new turn makes it necessary.</user_action>"
            return try await complete(action, result: remote.sendHiddenInteraction(prompt))
        case .approval:
            guard let approvalID = action.approvalID, let decision = action.decision,
                  let digest = action.digest else { return .rejected(reason: "invalid_approval") }
            switch await handler.resolveApproval(approvalId: approvalID, decision: decision, digest: digest) {
            case .resolved:
                return try await accept(action, turnID: nil)
            case .stale(let reason):
                return .stale(reason: reason)
            }
        case .stopTask:
            guard let threadID = action.threadID else { return .rejected(reason: "missing_thread_id") }
            let stopped = try await remote.stopTaskFromUser(taskID: threadID)
            guard stopped.status == "stopped" else { return .rejected(reason: stopped.error ?? "task_not_stopped") }
            return try await accept(action, turnID: nil)
        }
    }

    private func complete(_ action: BotUserAction,
                          result: BotRemoteTurnResult) async throws -> BotUserActionPlanOutcome {
        guard result.status == "completed" else {
            return .rejected(reason: result.error ?? "turn_not_completed")
        }
        return try await accept(action, turnID: result.turnId)
    }

    private func accept(_ action: BotUserAction, turnID: String?) async throws -> BotUserActionPlanOutcome {
        guard let sessionID = await remote.mainSessionIdentifier() else {
            throw BotMCPError.unavailable("main session unavailable")
        }
        let event = BotEvent(
            type: "conversation.turn_started", eventId: "action:\(action.nonce)", time: currentMilliseconds(),
            origin: .local(source: "user"), traceId: "action:\(action.nonce)",
            principal: BotPrincipal(botId: botID, sessionId: sessionID, kind: "chat"),
            causationId: nil, correlationId: action.nonce, turnId: turnID,
            data: .object([
                "action": .string(action.kind.rawValue), "nonce": .string(action.nonce),
                "ownerDirect": .bool(true),
            ]))
        return .commit(BotUserActionPlan(event: event))
    }

    private func card(_ id: String) async throws -> (kind: String, options: [(id: String, label: String)])? {
        var cursor = 0
        while true {
            let events = try await store.events(after: cursor, limit: 500)
            for event in events where event.type == "card.presented" && event.principal.botId == botID {
                guard event.data["id"]?.stringValue == id || event.eventId == id,
                      let kind = event.data["cardType"]?.stringValue ?? event.data["card"]?["kind"]?.stringValue
                        ?? event.data["kind"]?.stringValue else { continue }
                let options = (event.data["options"]?.arrayValue ?? event.data["card"]?["options"]?.arrayValue ?? [])
                    .compactMap { option -> (id: String, label: String)? in
                        guard let id = option["id"]?.stringValue ?? option["optionId"]?.stringValue,
                              let label = option["label"]?.stringValue ?? option["text"]?.stringValue else { return nil }
                        return (id, label)
                    }
                return (kind, options)
            }
            guard events.count == 500, let last = events.last?.commitSeq else { return nil }
            cursor = last
        }
    }
}

private func currentMilliseconds() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

actor BotRuntimeTurnStopper: BotProductionTurnStopping {
    let remote: BotDSHRemoteRuntime
    init(remote: BotDSHRemoteRuntime) { self.remote = remote }
    func stopCurrentTurn(turnID: String?) async throws { try await remote.stopMainTurn(turnID: turnID) }
}

@MainActor
final class BotRuntimeComposition {
    struct Launch {
        let composition: BotRuntimeComposition
        let workerEnvironment: [String: String]
    }

    let environment: BotProductionEnvironment
    let runtime: BotMCPRuntime
    let handler: BotMCPHandler
    let server: BotMCPHTTPServer
    let remote: BotDSHRemoteRuntime
    let scheduler: RoutineScheduler
    let planner: BotRemoteActionPlanner
    let wakeInbox: WakeInbox
    let outbox: BotProductionEffectRunner
    let routineOutbox: BotRoutineOutbox

    private var host: PhoneDSHHost?
    private var hostTask: Task<Void, Never>?
    private var tickTask: Task<Void, Never>?
    private var started = false

    static func prepare(environment: BotProductionEnvironment,
                        botID: String = "main") async throws -> Launch {
        let store = environment.domain
        let deferred = BotDeferredVolatileContextProvider()
        let runtime = BotMCPRuntime(store: store, volatileProvider: deferred)
        let language = Locale.preferredLanguages.first?.hasPrefix("zh") == true ? "zh" : "en"
        let volatile = await BotDomainVolatileContextProvider(
            memory: await runtime.memory, routines: await runtime.routineMachine,
            personaAsync: { _, _ in
                let context = try await environment.context()
                let name = context.botName.replacingOccurrences(of: "\n", with: " ").prefix(80)
                return "你的名字是\(name)。用户问起名字时如实回答。"
            }, language: language)
        await deferred.attach(volatile)
        let handler = BotMCPHandler(runtime: runtime)
        let server = BotMCPHTTPServer(handler: handler)
        let url = try await server.start()
        let inbox = WakeInbox(store: SQLiteWakeInboxStore(domainStore: store, botId: botID))
        let remote = try await BotDSHRemoteRuntime(transport: UnstartedBotTransport(), runtime: runtime,
                                                   botId: botID, wakeInbox: inbox)
        let composition = await BotRuntimeComposition(
            environment: environment, runtime: runtime, handler: handler, server: server,
            remote: remote, wakeInbox: inbox, botID: botID)
        return Launch(composition: composition, workerEnvironment: [
            "PDSH_PHONE_MCP_URL": url.absoluteString,
            "PDSH_PHONE_MCP_TOKEN": server.token,
        ])
    }

    private init(environment: BotProductionEnvironment, runtime: BotMCPRuntime,
                 handler: BotMCPHandler, server: BotMCPHTTPServer,
                 remote: BotDSHRemoteRuntime, wakeInbox: WakeInbox, botID: String) async {
        self.environment = environment
        self.runtime = runtime
        self.handler = handler
        self.server = server
        self.remote = remote
        self.wakeInbox = wakeInbox
        let machine = await runtime.routineMachine
        planner = BotRemoteActionPlanner(remote: remote, runtime: runtime, handler: handler,
                                         store: environment.domain, botID: botID)
        routineOutbox = BotRoutineOutbox(
            store: environment.domain, machine: machine, botID: botID)
        outbox = BotProductionEffectRunner(store: environment.domain)
        scheduler = RoutineScheduler(
            botId: botID, machine: machine, inbox: wakeInbox,
            sink: routineOutbox,
            runner: { [remote] request in await remote.runRoutine(request) },
            cancelRunner: { [remote] cancellation in await remote.cancelRoutine(cancellation) },
            available: { [remote] _ in await remote.isAvailable() },
            busy: { [remote] _ in await remote.isMainTurnBusy() },
            leaseOwner: "bot-runtime-composition")
    }

    func start(host newHost: PhoneDSHHost) async throws {
        if let current = host {
            if current === newHost, started { return }
            guard current === newHost else {
                throw BotMCPError.unavailable("a different Host is already attached")
            }
        } else {
            host = newHost
        }
        try await newHost.start()
        let transport = try await newHost.makeTransport()
        await remote.attachTransport(transport)
        let sessionID = try await remote.startMainSession()
        try await environment.connect(BotProductionComposition(
            sessionID: sessionID, planner: planner,
            turnStopping: BotRuntimeTurnStopper(remote: remote)))
        await outbox.register(kind: BotRoutineOutbox.kind) { [routineOutbox] lease in
            try await routineOutbox.apply(lease)
        }
        #if canImport(ProbeKit)
        if let deviceBinding {
            try await deviceBinding.attach(runtime: remote)
            if let writer = try? deviceBinding.replyWriter() {
                let replies = BotDeviceReplyService(store: environment.domain, writer: writer)
                await outbox.register(kind: "device-reply") { [replies] lease in
                    try await replies.deliver(lease)
                }
            }
        }
        #endif
        started = true
        observeHost(newHost)
        await outbox.start()
        await scheduler.start()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                _ = try? await self.remote.watchdogMonitor().tick()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    #if canImport(ProbeKit)
    var deviceBinding: BotDeviceProviderBinding?
    #endif

    func stop() async {
        await scheduler.stop()
        await outbox.stop()
        tickTask?.cancel()
        hostTask?.cancel()
        await runtime.approvals.cancelAll()
        started = false
    }

    private func observeHost(_ host: PhoneDSHHost) {
        hostTask?.cancel()
        hostTask = Task { [weak self, remote] in
            for await event in host.eventStream() {
                guard let self else { return }
                switch event {
                case .webContentTerminated(let count):
                    _ = await remote.watchdogMonitor().observeMobile(
                        .webcontentTerminated, kind: .turn, id: "phone-host",
                        botId: await self.environment.botID,
                        evidence: ["count": .number(Double(count))])
                    await self.recoverAfterWorkerTermination(host)
                case .restarted:
                    await self.recoverAfterWorkerTermination(host)
                default:
                    break
                }
            }
        }
    }

    private func recoverAfterWorkerTermination(_ host: PhoneDSHHost) async {
        do {
            let transport = try await host.makeTransport()
            await remote.attachTransport(transport)
            _ = try await remote.startMainSession()
            _ = try await scheduler.recover()
            #if canImport(ProbeKit)
            try await deviceBinding?.ingress.recover()
            #endif
        } catch {
            return
        }
    }
}

private struct UnstartedBotTransport: DSHRemoteTransport {
    func call(_ endpoint: String, args: PhoneDSHBot.BotJSONValue) async throws -> PhoneDSHBot.BotJSONValue {
        throw BotMCPError.unavailable("Host transport is not attached")
    }
    func openMux() async throws -> any DSHMuxConnection {
        throw BotMCPError.unavailable("Host transport is not attached")
    }
    func authenticationFailed() async {}
}
