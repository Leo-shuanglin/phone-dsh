// SPDX-License-Identifier: MIT

import Foundation

public enum BotDeviceIngressDisposition: String, Sendable {
    case ready
    case buffered
    case duplicate
}

public struct BotDeviceIngressReceipt: Sendable, Equatable {
    public let eventID: String
    public let disposition: BotDeviceIngressDisposition

    public init(eventID: String, disposition: BotDeviceIngressDisposition) {
        self.eventID = eventID
        self.disposition = disposition
    }
}

public protocol BotDeviceMessageDelivering: Actor {
    func deliverDeviceMessages() async throws
}

public actor BotDeviceIngress {
    public typealias WakeHandler = @Sendable (WakeSignal) -> Void

    private struct Accepted {
        let envelope: BotDeviceEnvelope
        let transport: BotDeviceTransport
        let displayName: String
        let receivedAt: Int64
    }

    public let store: SQLiteDomainStore
    public let inbox: WakeInbox
    public let botID: String

    private let now: @Sendable () -> Int64
    private let onWake: WakeHandler?
    private var accepted: [String: Accepted] = [:]
    private var loaded = false
    private var driver: (any BotDeviceMessageDelivering)?
    private var delivering = false

    public init(store: SQLiteDomainStore, botID: String = "main", inbox: WakeInbox? = nil,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
                onWake: WakeHandler? = nil) {
        self.store = store
        self.botID = botID
        self.inbox = inbox ?? WakeInbox(store: SQLiteWakeInboxStore(domainStore: store, botId: botID))
        self.now = now
        self.onWake = onWake
    }

    public func attach(_ driver: any BotDeviceMessageDelivering) async throws {
        self.driver = driver
        try await recover()
    }

    public func detach() {
        driver = nil
    }

    public func receive(data: Data, transport: BotDeviceTransport,
                        expectedSessionID: String, displayName: String) async throws -> BotDeviceIngressReceipt {
        let envelope = try BotDeviceEnvelope(data: data, expectedSessionID: expectedSessionID)
        try await synchronize()
        return try await receive(envelope, transport: transport, displayName: displayName)
    }

    public func receive(_ envelope: BotDeviceEnvelope, transport: BotDeviceTransport,
                        expectedSessionID: String, displayName: String) async throws -> BotDeviceIngressReceipt {
        try envelope.validateIngress(expectedSessionID: expectedSessionID)
        try await synchronize()
        return try await receive(envelope, transport: transport, displayName: displayName)
    }

    public func recover() async throws {
        try await synchronize()
        guard driver != nil else { return }
        _ = try await inbox.recover(kind: "device-event")
        try await restoreReadyItems()
        try await deliverAvailable()
    }

    public func deliverAvailable() async throws {
        guard let driver, !delivering else { return }
        delivering = true
        defer { delivering = false }
        for _ in 0..<WakeInbox.maxItems {
            guard try await readyPendingItems().isEmpty == false else { return }
            try await driver.deliverDeviceMessages()
        }
        if try await readyPendingItems().isEmpty == false {
            throw BotDeviceEnvelopeError.sequence("device delivery did not drain the ready inbox")
        }
    }

    private func receive(_ envelope: BotDeviceEnvelope, transport: BotDeviceTransport,
                         displayName: String) async throws -> BotDeviceIngressReceipt {
        let eventID = BotDeviceIdentifiers.durableID(msgID: envelope.msgID)
        if let existing = accepted[envelope.msgID] {
            guard try existing.envelope.canonicalData() == envelope.canonicalData(),
                  existing.transport == transport else {
                throw BotDeviceEnvelopeError.conflict(eventID)
            }
            return BotDeviceIngressReceipt(eventID: eventID, disposition: .duplicate)
        }
        let related = accepted.values.filter { $0.envelope.deviceID == envelope.deviceID }
        if related.contains(where: { $0.envelope.seq == envelope.seq }) {
            throw BotDeviceEnvelopeError.conflict("duplicate device seq \(envelope.seq)")
        }
        let heldSequences = try await inbox.items().filter {
            $0.kind == "device-event" && $0.deviceID == envelope.deviceID
        }.compactMap(\.deviceSeq)
        if let oldestHeld = heldSequences.min(), envelope.seq + 1 < oldestHeld {
            throw BotDeviceEnvelopeError.sequence("message precedes the next device sequence gap")
        }
        if heldSequences.isEmpty, let baseline = related.map(\.envelope.seq).min(), envelope.seq < baseline {
            throw BotDeviceEnvelopeError.sequence("message precedes the accepted device sequence baseline")
        }
        let sessionID = try await requireMainSession()
        let receivedAt = now()
        let event = try envelope.ingressEvent(
            botID: botID, mainSessionID: sessionID, transport: transport, receivedAt: receivedAt)
        _ = try await store.append(event)
        accepted[envelope.msgID] = Accepted(
            envelope: envelope, transport: transport, displayName: displayName, receivedAt: receivedAt)
        let ready = try await releaseReadyItems(deviceID: envelope.deviceID)
        return BotDeviceIngressReceipt(
            eventID: eventID, disposition: ready.contains(eventID) ? .ready : .buffered)
    }

    private func synchronize() async throws {
        if loaded { return }
        var cursor = 0
        while true {
            let events = try await store.events(after: cursor, limit: 500)
            for event in events where event.type == "device.event" && event.principal.botId == botID {
                guard event.principal.kind == "device",
                      event.eventId == event.traceId, event.eventId == event.correlationId,
                      case .local(let source) = event.origin,
                      let transportValue = event.data["transport"]?.stringValue,
                      let transport = BotDeviceTransport(rawValue: transportValue),
                      source == "device:\(transport.rawValue):\(event.data["envelope"]?["device_id"]?.stringValue ?? "")",
                      let data = try? event.data["envelope"]?.encoded(),
                      let envelope = try? BotDeviceEnvelope(data: data) else {
                    throw SQLiteDomainStoreError.corrupted("invalid device.event projection")
                }
                let existing = accepted[envelope.msgID]
                if let existing,
                   (try existing.envelope.canonicalData()) != (try envelope.canonicalData()) {
                    throw SQLiteDomainStoreError.corrupted("conflicting committed device msg_id")
                }
                let displayName = try await existingDeviceItem(msgID: envelope.msgID)?.fromAgentName ?? envelope.deviceID
                accepted[envelope.msgID] = Accepted(
                    envelope: envelope, transport: transport, displayName: displayName,
                    receivedAt: event.time)
            }
            guard events.count == 500, let last = events.last?.commitSeq else { break }
            cursor = last
        }
        for group in Dictionary(grouping: accepted.values, by: \.envelope.deviceID) {
            let sequences = group.value.map(\.envelope.seq)
            guard Set(sequences).count == sequences.count else {
                throw SQLiteDomainStoreError.corrupted("duplicate committed device seq")
            }
        }
        loaded = true
    }

    private func restoreReadyItems() async throws {
        for deviceID in Set(accepted.values.map(\.envelope.deviceID)) {
            _ = try await releaseReadyItems(deviceID: deviceID)
        }
    }

    private func requireMainSession() async throws -> String {
        var cursor = 0
        var found: String?
        while true {
            let events = try await store.events(after: cursor, limit: 500)
            for event in events where event.type == "runtime.started"
                && event.principal.botId == botID && event.principal.kind == "chat" {
                found = event.data["sessionId"]?.stringValue ?? event.principal.sessionId
            }
            guard events.count == 500, let last = events.last?.commitSeq else { break }
            cursor = last
        }
        guard let found, !found.isEmpty else { throw BotMCPError.unavailable("main Bot session is not started") }
        return found
    }

    private func existingDeviceItem(msgID: String) async throws -> WakeInboxItem? {
        let eventID = BotDeviceIdentifiers.durableID(msgID: msgID)
        return try await inbox.items().first { $0.kind == "device-event" && $0.requestId == eventID }
    }

    private func releaseReadyItems(deviceID: String) async throws -> Set<String> {
        var released: Set<String> = []
        for value in try await readyEnvelopes(deviceID: deviceID) {
            let result = try await inbox.enqueue(value.envelope.heldItem(
                transport: value.transport, displayName: value.displayName, receivedAt: value.receivedAt))
            guard !result.duplicate else { continue }
            released.insert(BotDeviceIdentifiers.durableID(msgID: value.envelope.msgID))
            onWake?(WakeSignal(botId: botID, reason: "device:\(value.envelope.msgID)", at: now()))
        }
        return released
    }

    private func readyEnvelopes(deviceID: String) async throws -> [Accepted] {
        let ordered = accepted.values.filter { $0.envelope.deviceID == deviceID }.sorted {
            ($0.envelope.seq, $0.envelope.msgID) < ($1.envelope.seq, $1.envelope.msgID)
        }
        guard !ordered.isEmpty else { return [] }
        let heldSequences = try await inbox.items().filter {
            $0.kind == "device-event" && $0.deviceID == deviceID
        }.compactMap(\.deviceSeq)
        let start = heldSequences.min() ?? ordered[0].envelope.seq
        var expected = start
        var ready: [Accepted] = []
        for value in ordered where value.envelope.seq >= start {
            guard value.envelope.seq == expected else { break }
            ready.append(value)
            expected += 1
        }
        return ready
    }

    private func readyPendingItems() async throws -> [WakeInboxItem] {
        let pending = try await inbox.items().filter { $0.kind == "device-event" && $0.state == .pending }
        var ready: [WakeInboxItem] = []
        for deviceID in Set(pending.compactMap(\.deviceID)) {
            let ids = Set(try await readyEnvelopes(deviceID: deviceID).map {
                BotDeviceIdentifiers.durableID(msgID: $0.envelope.msgID)
            })
            ready.append(contentsOf: pending.filter { $0.deviceID == deviceID && ids.contains($0.requestId ?? "") })
        }
        return ready.sorted { ($0.deviceID ?? "", $0.deviceSeq ?? 0, $0.id) < ($1.deviceID ?? "", $1.deviceSeq ?? 0, $1.id) }
    }
}
