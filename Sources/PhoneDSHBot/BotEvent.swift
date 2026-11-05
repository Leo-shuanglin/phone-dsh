// SPDX-License-Identifier: MIT

import Foundation
import PhoneDSHAgentCore

public typealias BotJSONValue = JSONValue

public enum BotEventOrigin: Codable, Sendable, Equatable {
    case dsh(sessionId: String, seq: Int)
    case local(source: String)
}

public struct BotPrincipal: Codable, Sendable, Equatable {
    public let botId: String?
    public let sessionId: String?
    public let kind: String
    public let capabilities: [String]

    public init(botId: String?, sessionId: String?, kind: String, capabilities: [String] = []) {
        self.botId = botId
        self.sessionId = sessionId
        self.kind = kind
        self.capabilities = capabilities
    }
}

public struct BotEventBatchRef: Codable, Sendable, Equatable {
    public let id: String
    public let index: Int
    public let total: Int

    public init(id: String, index: Int, total: Int) {
        self.id = id
        self.index = index
        self.total = total
    }
}

public struct BotEvent: Codable, Sendable, Equatable, Identifiable {
    public static let currentSchemaVersion = 1

    public let schemaVersion: Int
    public let type: String
    public let eventId: String
    public let commitSeq: Int?
    public let time: Int64
    public let origin: BotEventOrigin
    public let traceId: String
    public let principal: BotPrincipal
    public let causationId: String?
    public let correlationId: String?
    public let turnId: String?
    public let runId: String?
    public let attempt: Int?
    public let batch: BotEventBatchRef?
    public let ignorable: Bool?
    public let data: BotJSONValue

    public var id: String { eventId }

    public init(schemaVersion: Int = Self.currentSchemaVersion, type: String, eventId: String,
                commitSeq: Int? = nil, time: Int64, origin: BotEventOrigin, traceId: String,
                principal: BotPrincipal, causationId: String?, correlationId: String?,
                turnId: String? = nil, runId: String? = nil, attempt: Int? = nil,
                batch: BotEventBatchRef? = nil, ignorable: Bool? = nil, data: BotJSONValue) {
        self.schemaVersion = schemaVersion
        self.type = type
        self.eventId = eventId
        self.commitSeq = commitSeq
        self.time = time
        self.origin = origin
        self.traceId = traceId
        self.principal = principal
        self.causationId = causationId
        self.correlationId = correlationId
        self.turnId = turnId
        self.runId = runId
        self.attempt = attempt
        self.batch = batch
        self.ignorable = ignorable
        self.data = data
    }
}
