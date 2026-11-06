// SPDX-License-Identifier: MIT

import Foundation

public struct BotDeviceReply: Codable, Sendable, Equatable {
    public let envelope: BotDeviceEnvelope
    public let transport: BotDeviceTransport
    public let sourceMessageID: String

    public init(source: BotDeviceEnvelope, transport: BotDeviceTransport,
                sessionID: String, text: String, downSeq: Int64 = 0) throws {
        self.envelope = try BotDeviceEnvelope(
            msgID: BotDeviceIdentifiers.downMessageID(msgID: source.msgID),
            deviceID: source.deviceID, sessionID: sessionID, dir: "down", kind: "text",
            origin: "agent", audience: "device", seq: downSeq, text: text)
        self.transport = transport
        self.sourceMessageID = source.msgID
    }

    public init(envelope: BotDeviceEnvelope, transport: BotDeviceTransport, sourceMessageID: String) {
        self.envelope = envelope
        self.transport = transport
        self.sourceMessageID = sourceMessageID
    }

    public var effectKey: String { BotDeviceIdentifiers.durableID(msgID: sourceMessageID) }
}

public protocol BotDeviceReplyWriting: Sendable {
    func send(_ reply: BotDeviceReply) async throws -> String?
}

public enum BotDeviceReplyError: Error, Sendable {
    case unauthorized
    case payloadConflict
}

public actor BotDeviceReplyService {
    public let store: SQLiteDomainStore
    private let writer: any BotDeviceReplyWriting
    private let now: @Sendable () -> Int64
    private let owner: String
    private var requests: [String: BotDeviceReply] = [:]
    private var loaded = false

    public init(store: SQLiteDomainStore, writer: any BotDeviceReplyWriting,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
                owner: String = "bot-device-reply") {
        self.store = store
        self.writer = writer
        self.now = now
        self.owner = owner
    }

    @discardableResult
    public func stage(_ reply: BotDeviceReply, authorized: BotExecutionDecision) async throws -> String {
        let effect = try Self.effect(reply)
        let resource = "device:\(reply.transport.rawValue):\(reply.envelope.deviceID)"
        guard authorized.allowed, authorized.effects.contains(where: {
            $0.tool == "device.reply" && $0.operation == "device.reply" && $0.resource == resource
                && $0.mutability == .externalSend && $0.policy == .ask
        }) else {
            throw BotDeviceReplyError.unauthorized
        }
        try await restore()
        if let previous = requests[reply.effectKey] {
            guard previous == reply else { throw BotDeviceReplyError.payloadConflict }
            return reply.effectKey
        }
        let event = BotEvent(
            type: "device.reply.staged", eventId: "staged:\(reply.effectKey)", time: now(),
            origin: .local(source: "device:\(reply.transport.rawValue):\(reply.envelope.deviceID)"),
            traceId: reply.effectKey,
            principal: BotPrincipal(
                botId: authorized.principal.botId, sessionId: authorized.principal.sessionId, kind: "device"),
            causationId: nil, correlationId: reply.effectKey,
            data: .object([
                "envelope": try reply.envelope.jsonValue,
                "transport": .string(reply.transport.rawValue),
                "sourceMessageID": .string(reply.sourceMessageID),
            ]))
        _ = try await store.append(event)
        requests[reply.effectKey] = reply
        return try await store.enqueue(effect)
    }

    public func runNext() async throws -> BotEffectState? {
        try await restore()
        let pending = try await store.effects(state: .pending) + store.effects(state: .failed)
        guard let next = pending.sorted(by: {
            ($0.effect.notBefore ?? 0, $0.enqueuedCommitSeq, $0.effectKey)
                < ($1.effect.notBefore ?? 0, $1.enqueuedCommitSeq, $1.effectKey)
        }).first, next.effect.kind == "device-reply" else { return nil }
        guard let lease = try await store.lease(owner: owner, now: now()) else { return nil }
        return try await deliver(lease)
    }

    public func deliver(_ lease: BotEffectLease) async throws -> BotEffectState {
        try await restore()
        guard let request = requests[lease.effectKey] else {
            try await store.fail(lease, errorClass: "permanent", reason: "device reply payload is missing")
            return .deadLetter
        }
        do {
            let resultRef = try await writer.send(request)
            try await store.ack(lease, resultRef: resultRef)
            return .acked
        } catch {
            try await store.fail(lease, errorClass: "unknown", reason: String(describing: error))
            return .needsReconciliation
        }
    }

    public func restore() async throws {
        guard !loaded else { return }
        var cursor = 0
        while true {
            let events = try await store.events(after: cursor, limit: 500)
            for event in events where event.type == "device.reply.staged" {
                guard let data = try? event.data["envelope"]?.encoded(),
                      let envelope = try? BotDeviceEnvelope(data: data),
                      let rawTransport = event.data["transport"]?.stringValue,
                      let transport = BotDeviceTransport(rawValue: rawTransport),
                      let sourceMessageID = event.data["sourceMessageID"]?.stringValue else {
                    throw SQLiteDomainStoreError.corrupted("invalid device reply projection")
                }
                let reply = BotDeviceReply(
                    envelope: envelope, transport: transport, sourceMessageID: sourceMessageID)
                requests[reply.effectKey] = reply
                if try await store.effect(reply.effectKey) == nil {
                    _ = try await store.enqueue(try Self.effect(reply))
                }
            }
            guard events.count == 500, let last = events.last?.commitSeq else { break }
            cursor = last
        }
        loaded = true
    }

    private static func effect(_ reply: BotDeviceReply) throws -> BotEffect {
        let resource = "device:\(reply.transport.rawValue):\(reply.envelope.deviceID)"
        let digest = try BotArgumentsDigest.string(for: .object([
            "envelope": try reply.envelope.jsonValue,
            "transport": .string(reply.transport.rawValue),
        ]))
        return BotEffect(
            effectKey: reply.effectKey, kind: "device-reply", operation: "device.reply",
            target: resource, canonicalResource: resource, argsDigest: digest,
            logicalKey: reply.effectKey, idempotent: true, retryPolicy: "network-idempotent")
    }

    public static func requiredEffect(for reply: BotDeviceReply,
                                      sessionID: String) -> BotToolEffect {
        let resource = "device:\(reply.transport.rawValue):\(reply.envelope.deviceID)"
        return BotToolEffect(
            tool: "device.reply", operation: "device.reply", resource: resource,
            target: sessionID, mutability: .externalSend, policy: .ask,
            label: "Reply to source device", detail: "Replies only through the host-mapped device transport session.")
    }
}
