// SPDX-License-Identifier: MIT

import Foundation

public struct BotApprovalOutcome: Codable, Sendable, Equatable {
    public let ok: Bool
    public let allowed: Bool
    public let approvalId: String?
    public let message: String?

    public init(ok: Bool, allowed: Bool, approvalId: String? = nil, message: String? = nil) {
        self.ok = ok
        self.allowed = allowed
        self.approvalId = approvalId
        self.message = message
    }

    public var jsonValue: BotJSONValue {
        var fields: [String: BotJSONValue] = ["ok": .bool(ok), "allowed": .bool(allowed)]
        if let approvalId { fields["approvalId"] = .string(approvalId) }
        if let message { fields["message"] = .string(message) }
        return .object(fields)
    }
}

public actor BotApprovalCoordinator {
    private struct Pending {
        let principal: BotGatewayPrincipal
        let effects: [BotToolEffect]
        let continuation: CheckedContinuation<BotApprovalOutcome, Never>
    }

    private let grants: BotGrantStore
    private let authorizer: BotExecutionAuthorizer
    private let eventWriter: BotMCPEventWriter
    private let now: @Sendable () -> Int64
    private var pending: [String: Pending] = [:]

    public init(grants: BotGrantStore, eventWriter: BotMCPEventWriter,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }) {
        self.grants = grants
        authorizer = BotExecutionAuthorizer(grants: grants)
        self.eventWriter = eventWriter
        self.now = now
    }

    public func standingDecision(call: BotResolvedToolCall, effects: [BotToolEffect]) async -> BotApprovalOutcome? {
        let decision = await authorizer.authorizeExecution(
            principal: BotExecutionPrincipal(gateway: call.principal), effects: effects,
            source: "approval-standing", argsDigest: call.attribution.argsDigest, consumeOnce: false)
        if decision.allowed {
            return BotApprovalOutcome(ok: true, allowed: true, message: decision.reason)
        }
        if decision.denied || decision.reason == "standing-deny" {
            return BotApprovalOutcome(ok: true, allowed: false, message: decision.reason)
        }
        return nil
    }

    public func request(call: BotResolvedToolCall, effects: [BotToolEffect],
                        action: String, detail: String) async -> BotApprovalOutcome {
        if let standing = await standingDecision(call: call, effects: effects) { return standing }
        let approvalId = "approval-\(UUID().uuidString.lowercased())"
        let principal = call.principal
        do {
            try await eventWriter.append(
                type: "approval.requested", principal: principal,
                data: .object([
                    "approvalID": .string(approvalId), "status": .string("pending"),
                    "action": .string(action), "detail": .string(detail),
                    "effects": .array(effects.map(Self.effectJSON)),
                ]))
        } catch {
            return BotApprovalOutcome(ok: false, allowed: false, message: "approval audit failed")
        }
        let outcome = await withCheckedContinuation { continuation in
            pending[approvalId] = Pending(principal: principal, effects: effects, continuation: continuation)
        }
        return outcome
    }

    @discardableResult
    public func resolve(approvalId: String, decision: BotApprovalDecision) async -> Bool {
        guard let request = pending.removeValue(forKey: approvalId) else { return false }
        let timestamp = now()
        var allowed = false
        do {
            for effect in request.effects {
                let scope: BotGrantScope = decision == .always && request.principal.kind == .chat ? .session : .once
                _ = try await grants.add(BotGrant(
                    botId: request.principal.botId, tool: effect.tool, operation: effect.operation,
                    resource: effect.resource, scope: scope,
                    sessionId: scope == .session ? request.principal.sessionId : nil,
                    createdAt: timestamp, source: "approval-card",
                    decision: decision == .deny ? .deny : .allow))
            }
            allowed = decision != .deny
            try await eventWriter.append(
                type: "approval.decided", principal: request.principal,
                data: .object([
                    "approvalID": .string(approvalId), "decision": .string(decision.rawValue),
                    "status": .string(allowed ? "allowed" : "denied"),
                ]))
        } catch {
            allowed = false
        }
        request.continuation.resume(returning: BotApprovalOutcome(
            ok: true, allowed: allowed, approvalId: approvalId,
            message: allowed ? "allowed" : "approval denied"))
        return true
    }

    public func cancel(approvalId: String) -> Bool {
        guard let request = pending.removeValue(forKey: approvalId) else { return false }
        request.continuation.resume(returning: BotApprovalOutcome(
            ok: false, allowed: false, approvalId: approvalId, message: "approval cancelled"))
        return true
    }

    public func cancelAll() {
        let requests = pending
        pending.removeAll()
        for (id, request) in requests {
            request.continuation.resume(returning: BotApprovalOutcome(
                ok: false, allowed: false, approvalId: id, message: "approval unavailable"))
        }
    }

    public var pendingIDs: [String] { pending.keys.sorted() }

    private static func effectJSON(_ effect: BotToolEffect) -> BotJSONValue {
        .object([
            "tool": .string(effect.tool), "operation": .string(effect.operation),
            "resource": .string(effect.resource), "target": .string(effect.target),
            "mutability": .string(effect.mutability.rawValue), "policy": .string(effect.policy.rawValue),
            "label": .string(effect.label), "detail": .string(effect.detail),
            "ui": .bool(effect.ui), "known": .bool(effect.known), "isMcp": .bool(effect.isMcp),
        ])
    }
}

public actor BotMCPEventWriter {
    private let eventLog: any BotEventLog
    private let now: @Sendable () -> Int64

    public init(eventLog: any BotEventLog,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }) {
        self.eventLog = eventLog
        self.now = now
    }

    @discardableResult
    public func append(type: String, principal: BotGatewayPrincipal, data: BotJSONValue,
                       eventId: String = UUID().uuidString.lowercased(), origin: BotEventOrigin? = nil,
                       time: Int64? = nil) async throws -> Int {
        let id = "evt-\(type)-\(eventId)"
        let event = BotEvent(
            type: type, eventId: id, time: max(0, time ?? now()),
            origin: origin ?? .local(source: "mcp"),
            traceId: "trace-\(id)",
            principal: BotPrincipal(botId: principal.botId, sessionId: principal.sessionId,
                                    kind: principal.kind.rawValue, capabilities: principal.capabilities),
            causationId: nil, correlationId: principal.sessionId, data: data)
        return try await eventLog.append(event)
    }
}
