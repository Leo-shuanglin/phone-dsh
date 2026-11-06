// SPDX-License-Identifier: MIT

import Foundation
import PhoneDSHBot

public struct BotDeviceTransportFrame: Sendable {
    public let deviceID: String
    public let transport: BotDeviceTransport
    public let data: Data

    public init(deviceID: String, transport: BotDeviceTransport, data: Data) {
        self.deviceID = deviceID
        self.transport = transport
        self.data = data
    }
}

public protocol BotDeviceEnvelopeConnector: DeviceConnector {
    func envelopes() -> AsyncStream<BotDeviceTransportFrame>
    func nextOutboundSeq(deviceID: String, transport: BotDeviceTransport) -> Int64
    func send(_ envelope: BotDeviceEnvelope, transport: BotDeviceTransport) async throws -> String?
}

public enum BotDeviceProviderBindingError: LocalizedError {
    case unavailableTransport
    case unknownOnlineDevice
    case missingMapping

    public var errorDescription: String? {
        switch self {
        case .unavailableTransport: return "当前连接器没有真实设备 envelope 传输。"
        case .unknownOnlineDevice: return "设备未由全局连接器发布为在线配对设备。"
        case .missingMapping: return "设备没有宿主分配的传输 Session 映射。"
        }
    }
}

@MainActor
public final class BotDeviceProviderBinding {
    public let ingress: BotDeviceIngress
    public let tools: BotDeviceToolProvider
    public let coordinator: DeviceSessionCoordinator

    private let connector: any DeviceConnector
    private var envelopeTask: Task<Void, Never>?

    public init(domain: SQLiteDomainStore, runtime: BotMCPRuntime,
                bridge: PhoneCapabilityBridge, coordinator: DeviceSessionCoordinator? = nil,
                onWake: BotDeviceIngress.WakeHandler? = nil) async throws {
        let coordinator = coordinator ?? .shared
        guard let connector = coordinator.activeConnector else {
            throw BotDeviceProviderBindingError.unavailableTransport
        }
        self.coordinator = coordinator
        self.connector = connector
        ingress = BotDeviceIngress(store: domain, inbox: WakeInbox(
            store: SQLiteWakeInboxStore(domainStore: domain, botId: "main")), onWake: onWake)
        tools = BotDeviceToolProvider(authorizer: await runtime.authorizer) { name, arguments, _ in
            let data = try arguments.encoded()
            let text = String(decoding: data, as: UTF8.self)
            let result = try await bridge.callTool(name: name, arguments: text)
            return try BotJSONValue.parse(Data(result.utf8))
        }
    }

    public func attach(runtime: BotDSHRemoteRuntime) async throws {
        guard let transport = connector as? any BotDeviceEnvelopeConnector else {
            throw BotDeviceProviderBindingError.unavailableTransport
        }
        try await ingress.attach(runtime)
        envelopeTask?.cancel()
        envelopeTask = Task { @MainActor [weak self] in
            for await frame in transport.envelopes() {
                guard let self else { return }
                do {
                    _ = try await self.receive(frame)
                } catch {
                    self.coordinator.reportBotIngressFailure(error)
                }
            }
        }
    }

    public func stop() async {
        envelopeTask?.cancel()
        envelopeTask = nil
        await ingress.detach()
    }

    @discardableResult
    public func receive(_ frame: BotDeviceTransportFrame) async throws -> BotDeviceIngressReceipt {
        guard let online = coordinator.onlineDevices.first(where: { $0.deviceID == frame.deviceID }) else {
            throw BotDeviceProviderBindingError.unknownOnlineDevice
        }
        guard let mapping = coordinator.savedMapping(for: frame.deviceID) else {
            throw BotDeviceProviderBindingError.missingMapping
        }
        return try await ingress.receive(
            data: frame.data, transport: frame.transport,
            expectedSessionID: mapping.sessionID, displayName: online.displayName)
    }

    public func mappedReply(source: BotDeviceEnvelope, transport: BotDeviceTransport,
                            text: String) throws -> BotDeviceReply {
        guard let mapping = coordinator.savedMapping(for: source.deviceID) else {
            throw BotDeviceProviderBindingError.missingMapping
        }
        guard let liveTransport = connector as? any BotDeviceEnvelopeConnector else {
            throw BotDeviceProviderBindingError.unavailableTransport
        }
        return try BotDeviceReply(
            source: source, transport: transport, sessionID: mapping.sessionID, text: text,
            downSeq: liveTransport.nextOutboundSeq(deviceID: source.deviceID, transport: transport))
    }

    public func replyWriter() throws -> any BotDeviceReplyWriting {
        guard let transport = connector as? any BotDeviceEnvelopeConnector else {
            throw BotDeviceProviderBindingError.unavailableTransport
        }
        return BotDeviceConnectorReplyWriter(connector: transport)
    }
}

private struct BotDeviceConnectorReplyWriter: BotDeviceReplyWriting {
    let connector: any BotDeviceEnvelopeConnector

    func send(_ reply: BotDeviceReply) async throws -> String? {
        try await connector.send(reply.envelope, transport: reply.transport)
    }
}
