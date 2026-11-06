// SPDX-License-Identifier: MIT

import Foundation
import PhoneDSHBot
#if canImport(ProbeKit)
import ProbeKit
#endif

enum BotProductionError: LocalizedError {
    case brainUnavailable
    case invalidService(String)

    var errorDescription: String? {
        switch self {
        case .brainUnavailable: return "Bot 大脑 planner 尚未接入，消息和审批动作未执行。"
        case .invalidService(let message): return message
        }
    }
}

protocol BotProductionTurnStopping: Actor {
    func stopCurrentTurn(turnID: String?) async throws
}

actor BotProductionUnavailablePlanner: BotUserActionPlanning {
    func plan(_ action: BotUserAction, trust: BotUserActionTrust) async throws -> BotUserActionPlanOutcome {
        .rejected(reason: BotProductionError.brainUnavailable.localizedDescription)
    }
}

struct BotProductionContext: Equatable, Sendable {
    var botID: String
    var botName: String
}

struct BotProductionRuntimeContext: Sendable {
    let botID: String

    func current(domain: SQLiteDomainStore) async throws -> BotProductionContext {
        var name = "大肥鱼"
        for event in try await BotProductionEventReader.all(domain: domain) {
            guard event.type == "ui.system_event", event.principal.botId == botID,
                  event.data["event"]?.runtimeString == "renamed",
                  let candidate = event.data["botName"]?.runtimeString?
                    .trimmingCharacters(in: .whitespacesAndNewlines), !candidate.isEmpty else { continue }
            name = candidate
        }
        return BotProductionContext(botID: botID, botName: name)
    }
}

struct BotProductionComposition {
    let sessionID: String
    let planner: any BotUserActionPlanning
    var turnStopping: (any BotProductionTurnStopping)? = nil
}

actor BotProductionEnvironment {
    private static var cached: BotProductionEnvironment?

    let botID: String
    private(set) var sessionID: String
    let sessionLogURL: URL?
    let domain: SQLiteDomainStore
    let routineMachine: RoutineMachine
    let runtimeContext: BotProductionRuntimeContext
    private var planner: any BotUserActionPlanning
    private var turnStopping: (any BotProductionTurnStopping)?

    private init(botID: String = "main", domain: SQLiteDomainStore) {
        self.botID = botID
        self.sessionID = botID
        self.sessionLogURL = nil
        self.domain = domain
        self.runtimeContext = BotProductionRuntimeContext(botID: botID)
        self.routineMachine = RoutineMachine(store: SQLiteRoutineStore(domainStore: domain))
        self.planner = BotProductionUnavailablePlanner()
    }

    init(domain: SQLiteDomainStore, botID: String, sessionID: String,
         routineStore: SQLiteRoutineStore, planner: (any BotUserActionPlanning)? = nil,
         turnStopping: (any BotProductionTurnStopping)? = nil, sessionLogURL: URL? = nil) {
        self.botID = botID
        self.sessionID = sessionID
        self.sessionLogURL = sessionLogURL
        self.domain = domain
        self.runtimeContext = BotProductionRuntimeContext(botID: botID)
        self.routineMachine = RoutineMachine(store: routineStore)
        self.planner = planner ?? BotProductionUnavailablePlanner()
        self.turnStopping = turnStopping
    }

    static func live() throws -> BotProductionEnvironment {
        if let cached { return cached }
        let environment = try BotProductionEnvironment(domain: SQLiteDomainStore.applicationSupport())
        cached = environment
        return environment
    }

    func connect(_ composition: BotProductionComposition) throws {
        guard !composition.sessionID.isEmpty else {
            throw BotProductionError.invalidService("Bot 主会话 ID 不能为空")
        }
        sessionID = composition.sessionID
        planner = composition.planner
        turnStopping = composition.turnStopping
    }

    func context() async throws -> BotProductionContext {
        try await runtimeContext.current(domain: domain)
    }

    func currentPlanner() -> any BotUserActionPlanning { planner }
    func currentTurnStopping() -> (any BotProductionTurnStopping)? { turnStopping }
}

enum BotProductionEventReader {
    static func all(domain: SQLiteDomainStore) async throws -> [BotEvent] {
        var result: [BotEvent] = []
        var cursor = 0
        while true {
            let page = try await domain.events(after: cursor, limit: 500)
            guard !page.isEmpty else { return result }
            result.append(contentsOf: page)
            cursor = page.last?.commitSeq ?? cursor
            if page.count < 500 { return result }
        }
    }
}

enum BotProductionPaths {
    static func workspaceRoot() -> URL {
        #if canImport(ProbeKit)
        return ChatHostConfiguration.workspaceRoot()
        #else
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat-host", isDirectory: true)
        #endif
    }
}

enum BotSessionLogLocator {
    static func latestFile(sessionID: String) -> URL? {
        let root = BotProductionPaths.workspaceRoot()
            .appendingPathComponent("materialized/home/sessions", isDirectory: true)
        let manager = FileManager.default
        guard let enumerator = manager.enumerator(
            at: root, includingPropertiesForKeys: [.isRegularFileKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return nil }
        var candidates: [(url: URL, modified: Date)] = []
        for case let url as URL in enumerator {
            guard url.lastPathComponent.hasPrefix("session.v"), url.pathExtension == "jsonl",
                  url.deletingLastPathComponent().lastPathComponent == sessionID,
                  let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .contentModificationDateKey]),
                  values.isRegularFile == true else { continue }
            candidates.append((url, values.contentModificationDate ?? .distantPast))
        }
        return candidates.sorted { $0.modified > $1.modified }.first?.url
    }

    static func events(sessionID: String) throws -> [BotDSHSessionEvent] {
        guard let url = latestFile(sessionID: sessionID) else { return [] }
        return try BotDSHSessionEvent.parseJSONL(Data(contentsOf: url))
    }
}

struct BotProductionHistoryPage: Sendable, Equatable {
    var rows: [BotRow]
    var nextCursor: String?
    var hasMore: Bool
}

actor BotProductionRowService {
    private let environment: BotProductionEnvironment
    private let history: BotRowHistory
    private var dispatcher: BotUserActionDispatcher?
    private var plannerIdentity: ObjectIdentifier?
    private var started = false
    private var currentPresentation = BotRuntimePresentation()

    init(environment: BotProductionEnvironment, history: BotRowHistory = BotRowHistory()) {
        self.environment = environment
        self.history = history
    }

    func start() async throws {
        guard !started else { return }
        try await rebuild()
        await updateDispatcher()
        started = true
    }

    private func updateDispatcher() async {
        let planner = await environment.currentPlanner()
        let identity = ObjectIdentifier(planner)
        guard identity != plannerIdentity else { return }
        plannerIdentity = identity
        dispatcher = BotUserActionDispatcher(store: environment.domain, planner: planner)
    }

    private func rebuild() async throws {
        let sessionID = await environment.sessionID
        let sessionEvents: [BotDSHSessionEvent]
        if let sessionLogURL = environment.sessionLogURL {
            sessionEvents = try BotDSHSessionEvent.parseJSONL(Data(contentsOf: sessionLogURL))
        } else {
            sessionEvents = try BotSessionLogLocator.events(sessionID: sessionID)
        }
        let uiEvents = try await BotProductionEventReader.all(domain: environment.domain)
        _ = try await history.rebuild(botID: environment.botID, sessionID: sessionID,
                                      sessionEvents: sessionEvents, uiEvents: uiEvents)
        currentPresentation = BotRuntimePresentationFactory.make(
            botID: environment.botID, sessionID: sessionID,
            sessionEvents: sessionEvents, uiEvents: uiEvents)
    }

    func presentation() async throws -> BotRuntimePresentation {
        try await start()
        return currentPresentation
    }

    func watch(after cursor: Int) async throws -> BotRowBatch {
        try await rebuild()
        return await history.watch(after: cursor)
    }

    func history(before rowID: String?, limit: Int) async throws -> BotProductionHistoryPage {
        try await rebuild()
        let rows = await history.history()
        let end = try rowID.map { id in
            guard let index = rows.firstIndex(where: { $0.id == id }) else {
                throw BotProductionError.invalidService("历史行游标不可用")
            }
            return index
        } ?? rows.endIndex
        let eligible = rows[..<end]
        let page = eligible.suffix(max(1, limit))
        return BotProductionHistoryPage(rows: Array(page), nextCursor: page.first?.id,
                                        hasMore: eligible.count > page.count)
    }

    func perform(_ action: BotUserAction) async throws -> BotActionResult {
        try await start()
        await updateDispatcher()
        guard let dispatcher else { return .rejected(reason: BotProductionError.brainUnavailable.localizedDescription) }
        return try await dispatcher.dispatch(action)
    }

    func stopCurrentTurn(turnID: String?) async throws {
        guard let stopping = await environment.currentTurnStopping() else {
            throw BotProductionError.brainUnavailable
        }
        try await stopping.stopCurrentTurn(turnID: turnID)
    }
}

struct BotProductionApproval: Sendable, Equatable, Identifiable {
    var id: String
    var parameterSummary: String
    var decision: BotGrantDecision
    var decidedAtMS: Int64
}

struct BotProductionInfoSnapshot: Sendable {
    var botName: String
    var memories: [PhoneDSHBot.BotMemoryFact]
    var routines: [Routine]
    var approvals: [BotProductionApproval]
}

actor BotProductionInfoService {
    private let environment: BotProductionEnvironment
    private let memoryStore: SQLiteBotMemoryFactStore

    init(environment: BotProductionEnvironment) {
        self.environment = environment
        self.memoryStore = SQLiteBotMemoryFactStore(eventLog: environment.domain, botID: environment.botID)
    }

    func load() async throws -> BotProductionInfoSnapshot {
        async let facts = memoryStore.facts(scope: nil)
        async let routines = environment.routineMachine.list(botId: environment.botID)
        async let approvals = approvalRecords()
        return try await BotProductionInfoSnapshot(
            botName: environment.context().botName,
            memories: facts, routines: routines, approvals: approvals)
    }

    private func approvalRecords() async throws -> [BotProductionApproval] {
        var records: [BotProductionApproval] = []
        for event in try await BotProductionEventReader.all(domain: environment.domain) {
            guard ["grant.added", "grant.consumed", "grant.revoked"].contains(event.type),
                  let record = try? JSONDecoder().decode(BotAuditRecord.self, from: event.data.encoded()),
                  record.principal.botId == environment.botID else { continue }
            let effects = record.effects.map {
                "\($0.tool)/\($0.operation) · \($0.resourceDigest.prefix(16))"
            }.joined(separator: "\n")
            let grants = record.grants.map {
                "Grant \($0.id) · \($0.scope.rawValue) · \($0.resourceDigest.prefix(16))"
            }.joined(separator: "\n")
            records.append(BotProductionApproval(
                id: event.eventId,
                parameterSummary: [effects, grants].filter { !$0.isEmpty }.joined(separator: "\n"),
                decision: record.decision, decidedAtMS: record.at))
        }
        return records.sorted { $0.decidedAtMS > $1.decidedAtMS }
    }
}

struct BotProductionInfoDriver {
    var load: () async throws -> BotProductionInfoSnapshot

    static func production(environment: BotProductionEnvironment) -> BotProductionInfoDriver {
        let service = BotProductionInfoService(environment: environment)
        return BotProductionInfoDriver(load: { try await service.load() })
    }
}
