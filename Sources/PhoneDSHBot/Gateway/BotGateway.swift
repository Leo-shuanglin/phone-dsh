// SPDX-License-Identifier: MIT

import Foundation
import PhoneDSHAgentCore

public struct BotResolvedToolCall: Sendable, Equatable {
    public let sessionId: String
    public let callId: String
    public let agentId: String
    public let principal: BotGatewayPrincipal
    public let attribution: BotAttribution

    public init(sessionId: String, callId: String, agentId: String,
                principal: BotGatewayPrincipal, attribution: BotAttribution) {
        self.sessionId = sessionId
        self.callId = callId
        self.agentId = agentId
        self.principal = principal
        self.attribution = attribution
    }
}

public enum BotGatewayError: Error, Sendable, Equatable {
    case unknownSession
}

public actor BotGateway {
    public static let chatTTLMilliseconds: Int64 = 30 * 24 * 60 * 60 * 1_000
    public static let ephemeralTTLMilliseconds: Int64 = 12 * 60 * 60 * 1_000

    public let tokens: BotTokenRegistry
    public let attribution: BotAttributionRegistry

    public init(now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
                tokenTTLMilliseconds: Int64 = BotGateway.ephemeralTTLMilliseconds,
                attributionTTLMilliseconds: Int64 = BotAttributionRegistry.defaultTTLMilliseconds,
                maximumTokens: Int = BotTokenRegistry.defaultMaximumTokens,
                maximumAttributions: Int = BotAttributionRegistry.defaultMaximumEntries) {
        tokens = BotTokenRegistry(now: now, defaultTTLMilliseconds: tokenTTLMilliseconds,
                                  maximumTokens: maximumTokens)
        attribution = BotAttributionRegistry(now: now, ttlMilliseconds: attributionTTLMilliseconds,
                                             maximumEntries: maximumAttributions)
    }

    @discardableResult
    public func issueSession(botId: String, sessionId: String, kind: BotSessionKind,
                             capabilities: [String] = [], ttlMilliseconds: Int64? = nil) async throws -> BotGatewayPrincipal {
        if let existing = await tokens.lookupSession(sessionId) {
            if existing.botId == botId && existing.kind == kind { return existing }
            await tokens.revokeSession(sessionId)
        }
        let ttl = ttlMilliseconds ?? (kind == .chat ? Self.chatTTLMilliseconds : Self.ephemeralTTLMilliseconds)
        return try await tokens.issue(botId: botId, sessionId: sessionId, kind: kind,
                                      capabilities: capabilities, ttlMilliseconds: ttl).principal
    }

    @discardableResult
    public func issueToken(botId: String, sessionId: String, kind: BotSessionKind,
                           capabilities: [String] = [], expiresAt: Int64? = nil,
                           ttlMilliseconds: Int64? = nil) async throws -> BotIssuedToken {
        try await tokens.issue(botId: botId, sessionId: sessionId, kind: kind,
                               capabilities: capabilities, expiresAt: expiresAt,
                               ttlMilliseconds: ttlMilliseconds)
    }

    @discardableResult
    public func registerToolCall(sessionId: String, callId: String, name: String,
                                 arguments: BotJSONValue = .object([:]), argsDigest: String? = nil,
                                 runtimeId: String, expiresAt: Int64? = nil,
                                 ttlMilliseconds: Int64? = nil) async throws -> BotAttribution {
        guard await tokens.lookupSession(sessionId) != nil else { throw BotGatewayError.unknownSession }
        return try await attribution.register(sessionId: sessionId, callId: callId, name: name,
                                              arguments: arguments, argsDigest: argsDigest,
                                              runtimeId: runtimeId, expiresAt: expiresAt,
                                              ttlMilliseconds: ttlMilliseconds)
    }

    public func resolveToolCall(runtimeId: String, argsDigest: String) async -> BotResolvedToolCall? {
        guard !runtimeId.isEmpty, !argsDigest.isEmpty,
              let entry = try? await attribution.claimByDigest(argsDigest: argsDigest,
                                                               runtimeId: runtimeId),
              let principal = await tokens.lookupSession(entry.sessionId) else { return nil }
        return BotResolvedToolCall(sessionId: entry.sessionId, callId: entry.callId,
                                   agentId: principal.botId, principal: principal,
                                   attribution: entry)
    }

    public func resolveToolCall(runtimeId: String, arguments: BotJSONValue) async -> BotResolvedToolCall? {
        guard let digest = try? BotArgumentsDigest.string(for: arguments) else { return nil }
        return await resolveToolCall(runtimeId: runtimeId, argsDigest: digest)
    }

    @discardableResult
    public func cancelToolCall(id: String) async -> Bool {
        await attribution.cancel(id: id)
    }
}
