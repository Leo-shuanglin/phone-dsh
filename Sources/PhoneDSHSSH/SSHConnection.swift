// SPDX-License-Identifier: MIT
import Citadel
import CryptoKit
import Foundation
import NIO
import NIOSSH

/// Credentials stay in the native process; only the connection alias is sent
/// to the Worker. The application supplies a Keychain-backed resolver.
public struct SSHConnection: Sendable {
    public var host: String
    public var port: Int
    public var username: String
    public var password: String
    public var privateKey: String
    public var passphrase: String
    public var trustedHostKey: String

    public init(host: String, port: Int = 22, username: String, password: String = "",
                privateKey: String = "", passphrase: String = "", trustedHostKey: String) {
        self.host = host; self.port = port; self.username = username
        self.password = password; self.privateKey = privateKey
        self.passphrase = passphrase; self.trustedHostKey = trustedHostKey
    }

    func connect(timeout: TimeInterval) async throws -> SSHClient {
        guard !host.isEmpty, !host.contains("\0"), (1...65535).contains(port), !username.isEmpty, !username.contains("\0") else {
            throw SSHFailure("Invalid SSH host, port or username")
        }
        guard !trustedHostKey.isEmpty else {
            throw SSHFailure("Server key is not trusted. Verify the connection in Settings → SSH first.")
        }
        let key = try NIOSSHPublicKey(openSSHPublicKey: trustedHostKey)
        let authentication: @Sendable () -> SSHAuthenticationMethod
        if privateKey.isEmpty {
            let username = username, password = password
            authentication = { .passwordBased(username: username, password: password) }
        } else {
            let privateKey = privateKey.replacingOccurrences(of: "\r\n", with: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            let decryptionKey = passphrase.isEmpty ? nil : Data(passphrase.utf8)
            let username = username
            if let key = try? Curve25519.Signing.PrivateKey(sshEd25519: privateKey, decryptionKey: decryptionKey) {
                authentication = { .ed25519(username: username, privateKey: key) }
            } else if let key = try? Insecure.RSA.PrivateKey(sshRsa: privateKey, decryptionKey: decryptionKey) {
                authentication = { .rsa(username: username, privateKey: key) }
            } else {
                throw SSHFailure("Invalid OpenSSH Ed25519/RSA private key or passphrase (encrypted keys require AES-128/256-CTR and bcrypt rounds < 18)")
            }
        }
        var settings = SSHClientSettings(host: host, port: port, authenticationMethod: authentication,
                                         hostKeyValidator: .trustedKeys([key]))
        settings.connectTimeout = .milliseconds(Int64(max(1, min(15, timeout)) * 1000))
        do {
            return try await Self.connect(settings: settings, timeout: timeout)
        } catch let error as SSHClientError {
            switch error {
            case .allAuthenticationOptionsFailed:
                throw SSHFailure(privateKey.isEmpty
                    ? "SSH authentication failed: the server rejected the password or requires an authorized key. Check Settings → SSH; do not keep retrying the same credential."
                    : "SSH key authentication failed. Check the username and whether this public key is authorized on the server.")
            case .unsupportedPasswordAuthentication:
                throw SSHFailure("Server does not allow password authentication. Configure an authorized key in Settings → SSH.")
            case .unsupportedPrivateKeyAuthentication:
                throw SSHFailure("Server does not allow this SSH key authentication method.")
            default: throw error
            }
        }
    }

    /// Observe the server key and deliberately reject the handshake before
    /// authentication. The settings UI asks the user to trust this exact key.
    public static func inspectHostKey(host: String, port: Int = 22) async throws -> String {
        guard !host.isEmpty, (1...65535).contains(port) else { throw SSHFailure("Invalid host or port") }
        let inspector = HostKeyInspector()
        var settings = SSHClientSettings(host: host, port: port,
            authenticationMethod: { .passwordBased(username: "key-verification", password: "") },
            hostKeyValidator: .custom(inspector))
        settings.connectTimeout = .seconds(10)
        do { let client = try await connect(settings: settings, timeout: 10); try? await client.close() }
        catch { if inspector.key == nil { throw error } }
        guard let key = inspector.key else { throw SSHFailure("Server did not provide a host key") }
        return key
    }

    /// Own the TCP channel before authentication so cancellation and deadlines
    /// also close stalled handshakes (not just authenticated SSH sessions).
    private static func connect(settings: SSHClientSettings, timeout: TimeInterval) async throws -> SSHClient {
        let pending = ConnectingChannel()
        let timer = Task {
            do { try await Task.sleep(for: .seconds(max(0.001, timeout))) }
            catch { return }
            pending.cancel()
        }
        defer { timer.cancel(); pending.disarm() }
        return try await withTaskCancellationHandler {
            do {
                let client = try await SSHClient.connect(
                    host: settings.host, port: settings.port,
                    authenticationMethod: settings.authenticationMethod(),
                    hostKeyValidator: settings.hostKeyValidator, reconnect: .never,
                    algorithms: settings.algorithms, protocolOptions: settings.protocolOptions,
                    channelHandlers: [pending],
                    connectTimeout: settings.connectTimeout)
                try Task.checkCancellation()
                return client
            } catch {
                pending.cancel()
                throw error
            }
        } onCancel: { pending.cancel() }
    }

    public static func fingerprint(of key: String) -> String {
        let fields = key.split(separator: " ")
        guard fields.count >= 2, let bytes = Data(base64Encoded: String(fields[1])) else { return "Invalid key" }
        return "SHA256:" + Data(SHA256.hash(data: bytes)).base64EncodedString().replacingOccurrences(of: "=", with: "")
    }
}

public struct SSHFailure: LocalizedError, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

private final class HostKeyInspector: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?
    var key: String? { lock.withLock { value } }
    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        lock.withLock { value = String(openSSHPublicKey: hostKey) }
        validationCompletePromise.fail(SSHFailure("Host key captured for verification"))
    }
}

private final class ConnectingChannel: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = ByteBuffer
    func handlerAdded(context: ChannelHandlerContext) { install(context.channel) }
    private let lock = NSLock()
    private var channel: Channel?
    private var cancelled = false
    func install(_ value: Channel) {
        let close = lock.withLock { channel = value; return cancelled }
        if close { value.close(promise: nil) }
    }
    func cancel() {
        let current = lock.withLock { cancelled = true; return channel }
        if let current {
            current.eventLoop.execute {
                // Citadel's handshake future does not observe channelInactive.
                // Fail that future before closing, so cancellation need not
                // wait for the library's independent ten-second login timer.
                current.pipeline.fireErrorCaught(CancellationError())
                current.close(promise: nil)
            }
        }
    }
    func disarm() { lock.withLock { channel = nil } }
}
