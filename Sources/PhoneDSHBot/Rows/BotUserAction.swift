// SPDX-License-Identifier: MIT

import Foundation

public enum BotUserActionKind: String, Codable, Sendable {
    case chat
    case cardAnswer
    case cardDismiss
    case approval
    case stopTask
}

public enum BotApprovalDecision: String, Codable, Sendable {
    case allowOnce
    case always
    case deny
}

public struct BotUserAction: Codable, Sendable, Equatable {
    public var nonce: String
    public var botID: String
    public var kind: BotUserActionKind
    public var text: String?
    public var cardID: String?
    public var optionID: String?
    public var customText: String?
    public var approvalID: String?
    public var decision: BotApprovalDecision?
    public var digest: String?
    public var threadID: String?

    public init(nonce: String, botID: String, kind: BotUserActionKind, text: String? = nil,
                cardID: String? = nil, optionID: String? = nil, customText: String? = nil,
                approvalID: String? = nil, decision: BotApprovalDecision? = nil,
                digest: String? = nil, threadID: String? = nil) {
        self.nonce = nonce
        self.botID = botID
        self.kind = kind
        self.text = text
        self.cardID = cardID
        self.optionID = optionID
        self.customText = customText
        self.approvalID = approvalID
        self.decision = decision
        self.digest = digest
        self.threadID = threadID
    }
}

public enum BotActionResult: Codable, Sendable, Equatable {
    case accepted(eventID: String)
    case duplicate(eventID: String)
    case stale(reason: String)
    case rejected(reason: String)

    public var status: String {
        switch self {
        case .accepted: return "accepted"
        case .duplicate: return "duplicate"
        case .stale: return "stale"
        case .rejected: return "rejected"
        }
    }

    public var eventID: String? {
        switch self {
        case .accepted(let eventID), .duplicate(let eventID): return eventID
        case .stale, .rejected: return nil
        }
    }

    public var reason: String? {
        switch self {
        case .stale(let reason), .rejected(let reason): return reason
        case .accepted, .duplicate: return nil
        }
    }

    private enum CodingKeys: String, CodingKey { case status, eventID, reason }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        switch try container.decode(String.self, forKey: .status) {
        case "accepted": self = .accepted(eventID: try container.decode(String.self, forKey: .eventID))
        case "duplicate": self = .duplicate(eventID: try container.decode(String.self, forKey: .eventID))
        case "stale": self = .stale(reason: try container.decode(String.self, forKey: .reason))
        case "rejected": self = .rejected(reason: try container.decode(String.self, forKey: .reason))
        case let status:
            throw DecodingError.dataCorruptedError(forKey: .status, in: container,
                                                   debugDescription: "unknown action result status: \(status)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(status, forKey: .status)
        switch self {
        case .accepted(let eventID), .duplicate(let eventID):
            try container.encode(eventID, forKey: .eventID)
        case .stale(let reason), .rejected(let reason):
            try container.encode(reason, forKey: .reason)
        }
    }
}

public typealias UserAction = BotUserAction
public typealias ActionResult = BotActionResult

public struct BotUserActionNonceRecord: Sendable, Equatable {
    public var eventID: String
    public var inserted: Bool

    public init(eventID: String, inserted: Bool) {
        self.eventID = eventID
        self.inserted = inserted
    }
}

/// Implementations must make the nonce record durable and `record` atomic.
public protocol BotUserActionNonceStore: Actor {
    func eventID(botID: String, nonce: String) async throws -> String?
    @discardableResult
    func record(botID: String, nonce: String, eventID: String) async throws -> BotUserActionNonceRecord
}

public enum BotUserActionTrust: String, Sendable {
    case ownerDirect = "owner_direct"
}

/// The host maps actions to durable events. Repeating an action must reuse its event identity.
public protocol BotUserActionHandling: Actor {
    func perform(_ action: BotUserAction, trust: BotUserActionTrust) async throws -> BotActionResult
}

public actor BotUserActionDispatcher {
    private let nonceStore: any BotUserActionNonceStore
    private let handler: any BotUserActionHandling
    private var inFlight: [String: Task<BotActionResult, Error>] = [:]

    public init(nonceStore: any BotUserActionNonceStore, handler: any BotUserActionHandling) {
        self.nonceStore = nonceStore
        self.handler = handler
    }

    public func dispatch(_ action: BotUserAction) async throws -> BotActionResult {
        let key = "\(action.botID)\u{0}\(action.nonce)"
        if let eventID = try await nonceStore.eventID(botID: action.botID, nonce: action.nonce) {
            return .duplicate(eventID: eventID)
        }
        if let task = inFlight[key] {
            let result = try await task.value
            if case .accepted(let eventID) = result { return .duplicate(eventID: eventID) }
            return result
        }
        if let invalid = validate(action) { return invalid }

        let task = Task { try await handler.perform(action, trust: .ownerDirect) }
        inFlight[key] = task
        defer { inFlight.removeValue(forKey: key) }
        let result: BotActionResult
        do {
            result = try await task.value
        } catch {
            return .rejected(reason: "action_handler_failed")
        }
        guard case .accepted(let eventID) = result else { return result }
        guard !eventID.isEmpty else { return .rejected(reason: "invalid_event_id") }
        let record = try await nonceStore.record(botID: action.botID, nonce: action.nonce, eventID: eventID)
        return record.inserted ? .accepted(eventID: eventID) : .duplicate(eventID: record.eventID)
    }

    private func validate(_ action: BotUserAction) -> BotActionResult? {
        guard !action.nonce.isEmpty, !action.botID.isEmpty else { return .rejected(reason: "invalid_action") }
        switch action.kind {
        case .chat:
            guard action.text != nil else { return .rejected(reason: "missing_text") }
        case .cardAnswer:
            guard action.cardID?.isEmpty == false else { return .rejected(reason: "missing_card_id") }
            guard action.optionID != nil || action.customText != nil else {
                return .rejected(reason: "missing_card_answer")
            }
        case .cardDismiss:
            guard action.cardID?.isEmpty == false else { return .rejected(reason: "missing_card_id") }
        case .approval:
            guard action.approvalID?.isEmpty == false, action.decision != nil else {
                return .rejected(reason: "invalid_approval")
            }
            guard action.digest?.isEmpty == false else { return .stale(reason: "digest_mismatch") }
        case .stopTask:
            guard action.threadID?.isEmpty == false else { return .rejected(reason: "missing_thread_id") }
        }
        return nil
    }
}
