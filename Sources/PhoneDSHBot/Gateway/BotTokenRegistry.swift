// SPDX-License-Identifier: MIT

import CryptoKit
import Foundation

public enum BotSessionKind: String, Codable, Sendable {
    case chat
    case task
    case routine
    case team
    case proactive
    case watchdog
}

public struct BotGatewayPrincipal: Codable, Sendable, Equatable {
    public let botId: String
    public let sessionId: String
    public let kind: BotSessionKind
    public let capabilities: [String]
    public let expiresAt: Int64

    public init(botId: String, sessionId: String, kind: BotSessionKind,
                capabilities: [String] = [], expiresAt: Int64) {
        self.botId = botId
        self.sessionId = sessionId
        self.kind = kind
        self.capabilities = capabilities
        self.expiresAt = expiresAt
    }
}

public struct BotIssuedToken: Sendable, Equatable {
    public let token: String
    public let principal: BotGatewayPrincipal

    public init(token: String, principal: BotGatewayPrincipal) {
        self.token = token
        self.principal = principal
    }
}

public enum BotTokenRegistryError: Error, Sendable, Equatable {
    case invalidInput(String)
    case registryFull
}

public actor BotTokenRegistry {
    public static let defaultTTLMilliseconds: Int64 = 12 * 60 * 60 * 1_000
    public static let defaultMaximumTokens = 4_096

    private struct Record {
        let id: String
        let digest: [UInt8]
        let principal: BotGatewayPrincipal
    }

    private let now: @Sendable () -> Int64
    private let defaultTTLMilliseconds: Int64
    private let maximumTokens: Int
    private var records: [String: Record] = [:]

    public init(now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
                defaultTTLMilliseconds: Int64 = BotTokenRegistry.defaultTTLMilliseconds,
                maximumTokens: Int = BotTokenRegistry.defaultMaximumTokens) {
        precondition(defaultTTLMilliseconds > 0, "defaultTTLMilliseconds must be positive")
        precondition(maximumTokens > 0, "maximumTokens must be positive")
        self.now = now
        self.defaultTTLMilliseconds = defaultTTLMilliseconds
        self.maximumTokens = maximumTokens
    }

    public var count: Int {
        prune()
        return records.count
    }

    @discardableResult
    public func issue(botId: String, sessionId: String, kind: BotSessionKind,
                      capabilities: [String] = [], expiresAt: Int64? = nil,
                      ttlMilliseconds: Int64? = nil) throws -> BotIssuedToken {
        let validBotId = try Self.identifier(botId, name: "botId")
        let validSessionId = try Self.identifier(sessionId, name: "sessionId")
        let validCapabilities = try Self.capabilities(capabilities)
        let timestamp = now()
        let expiry: Int64
        if let expiresAt {
            expiry = expiresAt
        } else {
            let ttl = ttlMilliseconds ?? defaultTTLMilliseconds
            guard ttl > 0 else { throw BotTokenRegistryError.invalidInput("ttlMilliseconds") }
            let result = timestamp.addingReportingOverflow(ttl)
            guard !result.overflow else { throw BotTokenRegistryError.invalidInput("expiresAt") }
            expiry = result.partialValue
        }
        guard expiry > timestamp else { throw BotTokenRegistryError.invalidInput("expiresAt") }
        prune()
        guard records.count < maximumTokens else { throw BotTokenRegistryError.registryFull }

        let token = "dshg_" + Self.randomURLSafeString(byteCount: 32)
        let principal = BotGatewayPrincipal(botId: validBotId, sessionId: validSessionId,
                                            kind: kind, capabilities: validCapabilities,
                                            expiresAt: expiry)
        let id = Self.randomHexString(byteCount: 16)
        records[id] = Record(id: id, digest: Self.digest(token), principal: principal)
        return BotIssuedToken(token: token, principal: principal)
    }

    public func lookup(_ token: String) -> BotGatewayPrincipal? {
        guard !token.isEmpty, token.count <= 512 else { return nil }
        let wanted = Self.digest(token)
        var match: Record?
        for record in records.values where Self.constantTimeEquals(record.digest, wanted) {
            match = record
        }
        guard let match else { return nil }
        guard match.principal.expiresAt > now() else {
            records.removeValue(forKey: match.id)
            return nil
        }
        return match.principal
    }

    public func lookupBearer(_ header: String) -> BotGatewayPrincipal? {
        let parts = header.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace })
        guard parts.count == 2, parts[0].caseInsensitiveCompare("Bearer") == .orderedSame else {
            return nil
        }
        return lookup(String(parts[1]))
    }

    public func lookupSession(_ sessionId: String) -> BotGatewayPrincipal? {
        let target = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else { return nil }
        let timestamp = now()
        var match: Record?
        for record in records.values {
            guard record.principal.sessionId == target else { continue }
            guard record.principal.expiresAt > timestamp else {
                records.removeValue(forKey: record.id)
                continue
            }
            if match == nil || record.principal.expiresAt > match!.principal.expiresAt {
                match = record
            }
        }
        return match?.principal
    }

    @discardableResult
    public func revoke(_ token: String) -> Bool {
        guard !token.isEmpty, token.count <= 512 else { return false }
        let wanted = Self.digest(token)
        var match: Record?
        for record in records.values where Self.constantTimeEquals(record.digest, wanted) {
            match = record
        }
        guard let match else { return false }
        return records.removeValue(forKey: match.id) != nil
    }

    @discardableResult
    public func revokeSession(_ sessionId: String) -> Int {
        revokeWhere { $0.principal.sessionId == sessionId.trimmingCharacters(in: .whitespacesAndNewlines) }
    }

    @discardableResult
    public func revokeBot(_ botId: String) -> Int {
        revokeWhere { $0.principal.botId == botId.trimmingCharacters(in: .whitespacesAndNewlines) }
    }

    public func rotate(_ token: String, capabilities: [String]? = nil,
                       expiresAt: Int64? = nil, ttlMilliseconds: Int64? = nil) throws -> BotIssuedToken? {
        guard !token.isEmpty, token.count <= 512 else { return nil }
        let wanted = Self.digest(token)
        var current: Record?
        for record in records.values where Self.constantTimeEquals(record.digest, wanted) {
            current = record
        }
        guard let current else { return nil }
        guard current.principal.expiresAt > now() else {
            records.removeValue(forKey: current.id)
            return nil
        }
        let replacement = try issue(botId: current.principal.botId,
                                    sessionId: current.principal.sessionId,
                                    kind: current.principal.kind,
                                    capabilities: capabilities ?? current.principal.capabilities,
                                    expiresAt: expiresAt ?? (ttlMilliseconds == nil ? current.principal.expiresAt : nil),
                                    ttlMilliseconds: ttlMilliseconds)
        records.removeValue(forKey: current.id)
        return replacement
    }

    @discardableResult
    public func prune() -> Int {
        let timestamp = now()
        return revokeWhere { $0.principal.expiresAt <= timestamp }
    }

    private func revokeWhere(_ predicate: (Record) -> Bool) -> Int {
        let ids = records.values.filter(predicate).map(\.id)
        for id in ids { records.removeValue(forKey: id) }
        return ids.count
    }

    private static func identifier(_ value: String, name: String) throws -> String {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 256 else {
            throw BotTokenRegistryError.invalidInput(name)
        }
        return text
    }

    private static func capabilities(_ values: [String]) throws -> [String] {
        guard values.count <= 64 else { throw BotTokenRegistryError.invalidInput("capabilities") }
        var seen: Set<String> = []
        var result: [String] = []
        for value in values {
            let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, text.count <= 128 else {
                throw BotTokenRegistryError.invalidInput("capabilities")
            }
            if seen.insert(text).inserted { result.append(text) }
        }
        return result
    }

    private static func digest(_ token: String) -> [UInt8] {
        Array(SHA256.hash(data: Data(token.utf8)))
    }

    private static func constantTimeEquals(_ lhs: [UInt8], _ rhs: [UInt8]) -> Bool {
        var difference = UInt8(truncatingIfNeeded: lhs.count ^ rhs.count)
        for index in 0..<max(lhs.count, rhs.count) {
            let left = index < lhs.count ? lhs[index] : 0
            let right = index < rhs.count ? rhs[index] : 0
            difference |= left ^ right
        }
        return difference == 0
    }

    private static func randomBytes(_ count: Int) -> [UInt8] {
        var generator = SystemRandomNumberGenerator()
        return (0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
    }

    private static func randomHexString(byteCount: Int) -> String {
        randomBytes(byteCount).map { String(format: "%02x", $0) }.joined()
    }

    private static func randomURLSafeString(byteCount: Int) -> String {
        Data(randomBytes(byteCount)).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
