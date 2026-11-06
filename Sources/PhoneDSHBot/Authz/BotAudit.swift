// SPDX-License-Identifier: MIT

import Foundation

public struct BotAuditPrincipal: Codable, Sendable, Equatable {
    public let botId: String?
    public let kind: String
    public let certain: Bool

    public init(botId: String?, kind: String, certain: Bool) {
        self.botId = botId
        self.kind = kind
        self.certain = certain
    }
}

public struct BotAuditEffect: Codable, Sendable, Equatable {
    public let tool: String
    public let operation: String
    public let resourceDigest: String
    public let argsDigest: String?
    public let mutability: BotToolMutability
    public let policy: BotToolPolicy

    public init(tool: String, operation: String, resourceDigest: String, argsDigest: String?,
                mutability: BotToolMutability, policy: BotToolPolicy) {
        self.tool = tool
        self.operation = operation
        self.resourceDigest = resourceDigest
        self.argsDigest = argsDigest
        self.mutability = mutability
        self.policy = policy
    }
}

public struct BotAuditGrant: Codable, Sendable, Equatable {
    public let id: String
    public let scope: BotGrantScope
    public let decision: BotGrantDecision
    public let resourceDigest: String

    public init(id: String, scope: BotGrantScope, decision: BotGrantDecision, resourceDigest: String) {
        self.id = id
        self.scope = scope
        self.decision = decision
        self.resourceDigest = resourceDigest
    }
}

public struct BotAuditRecord: Codable, Sendable, Equatable {
    public let schemaVersion: Int
    public let at: Int64
    public let event: String
    public let source: String
    public let decision: BotGrantDecision
    public let principal: BotAuditPrincipal
    public let effects: [BotAuditEffect]
    public let grants: [BotAuditGrant]
    public let reason: String

    public init(at: Int64, event: String = "tool-execution", source: String,
                decision: BotGrantDecision, principal: BotAuditPrincipal,
                effects: [BotAuditEffect] = [], grants: [BotAuditGrant] = [], reason: String) {
        schemaVersion = 1
        self.at = at
        self.event = event
        self.source = source
        self.decision = decision
        self.principal = principal
        self.effects = effects
        self.grants = grants
        self.reason = reason
    }
}

public protocol BotAuditRecording: Actor {
    @discardableResult
    func append(_ record: BotAuditRecord) async -> Bool
}

public actor BotAuditLog: BotAuditRecording {
    private let dataRoot: URL
    private let encoder: JSONEncoder

    public init(dataRoot: URL) {
        self.dataRoot = dataRoot
        encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    }

    @discardableResult
    public func append(_ record: BotAuditRecord) async -> Bool {
        do {
            let directory = dataRoot.appendingPathComponent("authz", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                     attributes: [.posixPermissions: 0o700])
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
            let file = directory.appendingPathComponent("tool-audit-\(Self.dateKey(record.at)).jsonl")
            if !FileManager.default.fileExists(atPath: file.path) {
                guard FileManager.default.createFile(atPath: file.path, contents: nil,
                                                     attributes: [.posixPermissions: 0o600]) else { return false }
            }
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
            var data = try encoder.encode(record)
            data.append(0x0a)
            let handle = try FileHandle(forWritingTo: file)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: data)
            return true
        } catch {
            return false
        }
    }

    private static func dateKey(_ milliseconds: Int64) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar.current
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date(timeIntervalSince1970: Double(milliseconds) / 1_000))
    }
}
