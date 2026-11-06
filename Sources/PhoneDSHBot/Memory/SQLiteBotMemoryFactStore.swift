// SPDX-License-Identifier: MIT

import CryptoKit
import Foundation

public actor SQLiteBotMemoryFactStore: BotMemoryFactStore {
    private struct TombstoneEntry {
        let scope: BotMemoryScope
        let tombstone: BotMemoryTombstone
    }

    private struct ReplayState {
        var order: [String] = []
        var live: [String: BotMemoryFact] = [:]
        var tombstones: [String: TombstoneEntry] = [:]

        func facts(scope: BotMemoryScope?) -> [BotMemoryFact] {
            order.compactMap { id in
                guard let fact = live[id], scope == nil || fact.scope == scope else { return nil }
                return fact
            }
        }
    }

    private enum ForgottenReason: String, Codable {
        case forget
        case erase
    }

    private struct ForgottenData: Codable {
        let factId: String
        let at: Int64
        let reason: ForgottenReason
        let scope: BotMemoryScope
        let textDigest: String?
    }

    private let eventLog: SQLiteDomainStore
    private let botID: String?
    private let now: @Sendable () -> Int64
    private let replayPageSize = 500

    public init(
        eventLog: SQLiteDomainStore,
        botID: String? = nil,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
    ) {
        self.eventLog = eventLog
        self.botID = botID
        self.now = now
    }

    public func facts(scope: BotMemoryScope? = nil) async throws -> [BotMemoryFact] {
        try await replay().facts(scope: scope)
    }

    @discardableResult
    public func append(_ fact: BotMemoryFact) async throws -> BotMemoryFact {
        let state = try await replay()
        if state.live[fact.factID] != nil {
            throw BotMemoryFactStoreError.duplicateFactID(fact.factID)
        }
        if state.tombstones[fact.factID] != nil {
            throw BotMemoryFactStoreError.erasedFactID(fact.factID)
        }
        _ = try await eventLog.append(try writtenEvent(fact, at: now()))
        return fact
    }

    @discardableResult
    public func remove(
        factIDs: [String],
        scope: BotMemoryScope,
        tombstone: BotMemoryTombstone?
    ) async throws -> BotMemoryRemoval {
        if let tombstone, !factIDs.contains(tombstone.factID) {
            throw BotMemoryFactStoreError.invalidOperation(
                "erase tombstone \(tombstone.factID) must identify a removed fact"
            )
        }
        let state = try await replay()
        let ids = Set(factIDs)
        let targets = state.facts(scope: scope).filter { ids.contains($0.factID) }
        let forgetAt = now()
        var events: [BotEvent] = []
        for fact in targets {
            let erasing = tombstone?.factID == fact.factID
            events.append(try forgottenEvent(
                factID: fact.factID,
                scope: scope,
                reason: erasing ? .erase : .forget,
                at: erasing ? tombstone!.erasedAtMS : forgetAt,
                textDigest: Self.digest(fact.text),
                fact: fact
            ))
        }
        if let tombstone,
           !targets.contains(where: { $0.factID == tombstone.factID }),
           state.tombstones[tombstone.factID] == nil {
            events.append(try forgottenEvent(
                factID: tombstone.factID,
                scope: scope,
                reason: .erase,
                at: tombstone.erasedAtMS,
                textDigest: nil,
                fact: nil
            ))
        }
        if !events.isEmpty {
            _ = try await eventLog.appendBatch(events)
        }
        let remaining = try await replay().facts(scope: scope).count
        return BotMemoryRemoval(removed: targets, count: remaining, tombstone: tombstone)
    }

    public func touch(factIDs: [String], lastUsedMS: Int64) async throws {
        let state = try await replay()
        let ids = Set(factIDs)
        let touched = state.facts(scope: nil).filter { ids.contains($0.factID) }.map { fact -> BotMemoryFact in
            var updated = fact
            updated.lastUsedMS = lastUsedMS
            return updated
        }
        if touched.isEmpty { return }
        let events = try touched.map { try writtenEvent($0, at: lastUsedMS) }
        _ = try await eventLog.appendBatch(events)
    }

    public func tombstone(factID: String, scope: BotMemoryScope) async throws -> BotMemoryTombstone? {
        let state = try await replay()
        guard let entry = state.tombstones[factID], entry.scope == scope else { return nil }
        return entry.tombstone
    }

    private func replay() async throws -> ReplayState {
        var state = ReplayState()
        var cursor = 0
        while true {
            let events = try await eventLog.events(after: cursor, limit: replayPageSize)
            if events.isEmpty { break }
            for event in events {
                guard let commitSeq = event.commitSeq else {
                    throw BotMemoryFactStoreError.corrupted("durable memory event has no commitSeq")
                }
                switch event.type {
                case "memory.written":
                    let fact = try Self.decode(BotMemoryFact.self, from: event.data)
                    if state.tombstones[fact.factID] == nil {
                        if state.live[fact.factID] == nil {
                            state.order.append(fact.factID)
                        }
                        state.live[fact.factID] = fact
                    }
                case "memory.forgotten":
                    let forgotten = try Self.decode(ForgottenData.self, from: event.data)
                    if state.live[forgotten.factId]?.scope == forgotten.scope {
                        state.live.removeValue(forKey: forgotten.factId)
                        state.order.removeAll { $0 == forgotten.factId }
                    }
                    if forgotten.reason == .erase {
                        state.tombstones[forgotten.factId] = TombstoneEntry(
                            scope: forgotten.scope,
                            tombstone: BotMemoryTombstone(factID: forgotten.factId, erasedAtMS: forgotten.at)
                        )
                    }
                default:
                    break
                }
                cursor = commitSeq
            }
            if events.count < replayPageSize { break }
        }
        return state
    }

    private func writtenEvent(_ fact: BotMemoryFact, at: Int64) throws -> BotEvent {
        try makeEvent(
            type: "memory.written",
            data: Self.encode(fact),
            at: at,
            fact: fact
        )
    }

    private func forgottenEvent(
        factID: String,
        scope: BotMemoryScope,
        reason: ForgottenReason,
        at: Int64,
        textDigest: String?,
        fact: BotMemoryFact?
    ) throws -> BotEvent {
        let data = ForgottenData(
            factId: factID,
            at: at,
            reason: reason,
            scope: scope,
            textDigest: textDigest
        )
        return try makeEvent(
            type: "memory.forgotten",
            data: Self.encode(data),
            at: at,
            fact: fact
        )
    }

    private func makeEvent(
        type: String,
        data: BotJSONValue,
        at: Int64,
        fact: BotMemoryFact?
    ) throws -> BotEvent {
        let eventID = "evt-memory-\(UUID().uuidString.lowercased())"
        return BotEvent(
            type: type,
            eventId: eventID,
            time: max(0, at),
            origin: .local(source: "memory-service"),
            traceId: "trace-\(eventID)",
            principal: BotPrincipal(botId: botID, sessionId: nil, kind: "ipc"),
            causationId: nil,
            correlationId: nil,
            turnId: fact?.source?.turnID,
            runId: fact?.extractRunID,
            data: data
        )
    }

    private static func encode<T: Encodable>(_ value: T) throws -> BotJSONValue {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try BotJSONValue.parse(encoder.encode(value))
    }

    private static func decode<T: Decodable>(_ type: T.Type, from value: BotJSONValue) throws -> T {
        try JSONDecoder().decode(type, from: value.encoded())
    }

    private static func digest(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
