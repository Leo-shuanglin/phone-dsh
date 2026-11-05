// SPDX-License-Identifier: MIT

import Foundation

public protocol BotEventLog: Actor {
    @discardableResult
    func append(_ event: BotEvent) async throws -> Int
    func events(after commitSeq: Int, limit: Int) async throws -> [BotEvent]
}

public struct BotEffect: Codable, Sendable, Equatable, Identifiable {
    public let effectKey: String
    public let operation: String
    public let canonicalResource: String
    public let argsDigest: String
    public let logicalKey: String
    public let idempotent: Bool
    public let generation: Int
    public let notBefore: Int64?

    public var id: String { effectKey }

    public init(effectKey: String, operation: String, canonicalResource: String, argsDigest: String,
                logicalKey: String, idempotent: Bool, generation: Int = 1, notBefore: Int64? = nil) {
        self.effectKey = effectKey
        self.operation = operation
        self.canonicalResource = canonicalResource
        self.argsDigest = argsDigest
        self.logicalKey = logicalKey
        self.idempotent = idempotent
        self.generation = generation
        self.notBefore = notBefore
    }
}

public struct BotEffectLease: Codable, Sendable, Equatable {
    public let effectKey: String
    public let attempt: Int
    public let leaseOwner: String
    public let leaseExpiresAt: Int64
    public let generation: Int

    public init(effectKey: String, attempt: Int, leaseOwner: String, leaseExpiresAt: Int64, generation: Int) {
        self.effectKey = effectKey
        self.attempt = attempt
        self.leaseOwner = leaseOwner
        self.leaseExpiresAt = leaseExpiresAt
        self.generation = generation
    }
}

public protocol BotEffectOutbox: Actor {
    @discardableResult
    func enqueue(_ effect: BotEffect) async throws -> String
    func lease(owner: String, now: Int64) async throws -> BotEffectLease?
    func ack(_ lease: BotEffectLease, resultRef: String?) async throws
    func fail(_ lease: BotEffectLease, errorClass: String, reason: String) async throws
    func markNeedsReconciliation(_ effectKey: String, reason: String) async throws
}

public enum BotEventStoreError: Error, Sendable, Equatable {
    case unimplemented
}

public actor UnimplementedBotEventLog: BotEventLog {
    public init() {}
    public func append(_ event: BotEvent) async throws -> Int { throw BotEventStoreError.unimplemented }
    public func events(after commitSeq: Int, limit: Int) async throws -> [BotEvent] { [] }
}
