// SPDX-License-Identifier: MIT

import Foundation

public enum BotGrantScope: String, Codable, Sendable {
    case once
    case session
    case until
}

public enum BotGrantDecision: String, Codable, Sendable {
    case allow
    case deny
}

public struct BotGrant: Codable, Sendable, Equatable, Identifiable {
    public let id: String
    public let botId: String
    public let tool: String
    public let operation: String
    public let resource: String
    public let scope: BotGrantScope
    public let sessionId: String?
    public let expiresAt: Int64?
    public let createdAt: Int64
    public let source: String
    public let decision: BotGrantDecision

    public init(id: String = "", botId: String, tool: String, operation: String,
                resource: String, scope: BotGrantScope, sessionId: String? = nil,
                expiresAt: Int64? = nil, createdAt: Int64 = 0, source: String = "approval",
                decision: BotGrantDecision) {
        self.id = id
        self.botId = botId
        self.tool = tool
        self.operation = operation
        self.resource = resource
        self.scope = scope
        self.sessionId = sessionId
        self.expiresAt = expiresAt
        self.createdAt = createdAt
        self.source = source
        self.decision = decision
    }
}

public enum BotGrantError: Error, Sendable, Equatable {
    case invalidGrant
    case invalidExpiry
}

public enum BotResourceNormalizer {
    public static func normalize(_ value: String) -> String {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return "" }
        if text.hasSuffix("/**") {
            let base = normalize(String(text.dropLast(3)))
            return base.isEmpty ? "" : base + "/**"
        }
        let expanded = (text as NSString).expandingTildeInPath
        if (expanded as NSString).isAbsolutePath {
            return (expanded as NSString).standardizingPath
        }
        return text.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }.joined(separator: " ")
    }
}

public actor BotGrantStore {
    public static let onceTTLMilliseconds: Int64 = 2 * 60 * 1_000
    public static let sessionTTLMilliseconds: Int64 = 24 * 60 * 60 * 1_000
    public static let untilTTLMilliseconds: Int64 = 30 * 24 * 60 * 60 * 1_000
    public static let maximumGrants = 500

    private let now: @Sendable () -> Int64
    private let audit: (any BotAuditRecording)?
    private var grants: [BotGrant] = []

    public init(now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
                audit: (any BotAuditRecording)? = nil) {
        self.now = now
        self.audit = audit
    }

    @discardableResult
    public func add(_ raw: BotGrant) async throws -> BotGrant {
        let timestamp = now()
        let botId = raw.botId.trimmingCharacters(in: .whitespacesAndNewlines)
        let tool = raw.tool.trimmingCharacters(in: .whitespacesAndNewlines)
        let resource = BotResourceNormalizer.normalize(raw.resource)
        guard !botId.isEmpty, !tool.isEmpty, !resource.isEmpty else { throw BotGrantError.invalidGrant }
        let createdAt = raw.createdAt == 0 ? timestamp : raw.createdAt
        let operation = raw.operation.trimmingCharacters(in: .whitespacesAndNewlines)
        let expiry = try raw.expiresAt ?? defaultExpiry(scope: raw.scope, createdAt: createdAt)
        guard expiry > timestamp else { throw BotGrantError.invalidExpiry }
        let grant = BotGrant(id: raw.id.isEmpty ? Self.newID() : raw.id,
                             botId: botId, tool: tool,
                             operation: operation.isEmpty ? "unknown" : operation,
                             resource: resource, scope: raw.scope,
                             sessionId: raw.sessionId?.trimmingCharacters(in: .whitespacesAndNewlines),
                             expiresAt: expiry, createdAt: createdAt,
                             source: raw.source.isEmpty ? "approval" : raw.source,
                             decision: raw.decision)
        prune()
        if let index = grants.firstIndex(where: {
            $0.botId == grant.botId && $0.tool == grant.tool && $0.operation == grant.operation
                && $0.resource == grant.resource && $0.decision == grant.decision
                && $0.scope == grant.scope
                && (grant.scope != .session || $0.sessionId == grant.sessionId)
        }) {
            let existing = grants[index]
            let extendedExpiry = max(existing.expiresAt ?? Int64.max, grant.expiresAt ?? Int64.max)
            let extended = BotGrant(id: existing.id, botId: existing.botId, tool: existing.tool,
                                    operation: existing.operation, resource: existing.resource,
                                    scope: existing.scope, sessionId: existing.sessionId,
                                    expiresAt: extendedExpiry == Int64.max ? nil : extendedExpiry,
                                    createdAt: existing.createdAt, source: existing.source,
                                    decision: existing.decision)
            grants[index] = extended
            await recordGrant(event: "grant.added", grant: extended, reason: "extended")
            return extended
        }
        grants.append(grant)
        grants = Array(grants.suffix(Self.maximumGrants))
        await recordGrant(event: "grant.added", grant: grant, reason: "created")
        return grant
    }

    public func find(botId: String, sessionId: String?, effect: BotToolEffect) -> BotGrant? {
        let wantedBot = botId.trimmingCharacters(in: .whitespacesAndNewlines)
        let wantedResource = BotResourceNormalizer.normalize(effect.resource)
        guard !wantedBot.isEmpty, !effect.tool.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !wantedResource.isEmpty else { return nil }
        let timestamp = now()
        return grants.filter {
            $0.expiresAt == nil || $0.expiresAt! > timestamp
        }.filter {
            $0.botId == wantedBot || ($0.botId == "*" && $0.decision == .deny)
        }.filter {
            $0.scope != .session || ($0.sessionId != nil && $0.sessionId == sessionId)
        }.filter {
            ($0.tool == "*" || $0.tool == effect.tool)
                && ($0.operation == "*" || $0.operation == effect.operation)
                && $0.resource == wantedResource
        }.sorted { lhs, rhs in
            if lhs.decision != rhs.decision { return lhs.decision == .deny }
            return specificity(lhs) > specificity(rhs)
        }.first
    }

    @discardableResult
    public func consume(id: String) async -> Bool {
        prune()
        guard let index = grants.firstIndex(where: { $0.id == id && $0.scope == .once }) else { return false }
        let removed = grants.remove(at: index)
        await recordGrant(event: "grant.consumed", grant: removed, reason: "used-once")
        return true
    }

    internal func consumeOnceAtomically(ids: [String]) async -> Bool {
        prune()
        let uniqueIDs = Set(ids)
        guard uniqueIDs.allSatisfy({ id in
            grants.contains { $0.id == id && $0.scope == .once }
        }) else { return false }
        let removed = grants.filter { uniqueIDs.contains($0.id) }
        grants.removeAll { uniqueIDs.contains($0.id) }
        for grant in removed {
            await recordGrant(event: "grant.consumed", grant: grant, reason: "used-once")
        }
        return true
    }

    internal var auditRecorder: (any BotAuditRecording)? { audit }

    @discardableResult
    public func revoke(id: String) async -> Bool {
        prune()
        guard let index = grants.firstIndex(where: { $0.id == id }) else { return false }
        let removed = grants.remove(at: index)
        await recordGrant(event: "grant.revoked", grant: removed, reason: "user-revoked")
        return true
    }

    @discardableResult
    public func revokeBot(_ botId: String) async -> Int {
        prune()
        let removed = grants.filter { $0.botId == botId }
        grants.removeAll { $0.botId == botId }
        for grant in removed {
            await recordGrant(event: "grant.revoked", grant: grant, reason: "bot-deleted")
        }
        return removed.count
    }

    @discardableResult
    public func revokeSession(_ sessionId: String, botId: String? = nil) async -> Int {
        prune()
        let removed = grants.filter {
            $0.scope == .session && $0.sessionId == sessionId && (botId == nil || $0.botId == botId)
        }
        grants.removeAll {
            $0.scope == .session && $0.sessionId == sessionId && (botId == nil || $0.botId == botId)
        }
        for grant in removed {
            await recordGrant(event: "grant.revoked", grant: grant, reason: "session-ended")
        }
        return removed.count
    }

    public func list(botId: String? = nil) -> [BotGrant] {
        prune()
        return grants.filter { botId == nil || $0.botId == botId }
            .sorted { $0.createdAt > $1.createdAt }
    }

    @discardableResult
    public func prune() -> Int {
        let timestamp = now()
        let before = grants.count
        grants.removeAll { $0.expiresAt != nil && $0.expiresAt! <= timestamp }
        return before - grants.count
    }

    internal func recordGrant(event: String, grant: BotGrant, reason: String) async {
        guard let audit else { return }
        let effect = BotToolEffect(tool: grant.tool, operation: grant.operation,
                                   resource: grant.resource, mutability: .write, policy: .ask)
        let record = BotAuditRecord(at: now(), event: event, source: grant.source,
                                    decision: grant.decision,
                                    principal: BotAuditPrincipal(botId: grant.botId,
                                                                 sessionId: grant.sessionId,
                                                                 kind: "ipc", certain: true),
                                    effects: [BotExecutionAuditing.effect(effect, argsDigest: nil)],
                                    grants: [BotExecutionAuditing.grant(grant)], reason: reason)
        await audit.append(record)
    }

    private func defaultExpiry(scope: BotGrantScope, createdAt: Int64) throws -> Int64 {
        let ttl: Int64
        switch scope {
        case .once: ttl = Self.onceTTLMilliseconds
        case .session: ttl = Self.sessionTTLMilliseconds
        case .until: ttl = Self.untilTTLMilliseconds
        }
        let result = createdAt.addingReportingOverflow(ttl)
        guard !result.overflow else { throw BotGrantError.invalidExpiry }
        return result.partialValue
    }

    private func specificity(_ grant: BotGrant) -> Int {
        (grant.botId == "*" ? 0 : 100) + (grant.tool == "*" ? 0 : 10)
            + (grant.operation == "*" ? 0 : 5) + 1
    }

    private static func newID() -> String {
        var generator = SystemRandomNumberGenerator()
        let suffix = (0..<8).map { _ in
            String(format: "%02x", UInt8.random(in: .min ... .max, using: &generator))
        }.joined()
        return "grant-\(suffix)"
    }
}

internal enum BotExecutionAuditing {
    static func effect(_ effect: BotToolEffect, argsDigest: String?) -> BotAuditEffect {
        BotAuditEffect(tool: effect.tool, operation: effect.operation,
                       resourceDigest: BotArgumentsDigest.digest(ofUTF8: effect.resource),
                       argsDigest: argsDigest, mutability: effect.mutability, policy: effect.policy)
    }

    static func grant(_ grant: BotGrant) -> BotAuditGrant {
        BotAuditGrant(id: grant.id, scope: grant.scope, decision: grant.decision,
                      resourceDigest: BotArgumentsDigest.digest(ofUTF8: grant.resource))
    }
}
