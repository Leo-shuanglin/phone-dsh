// SPDX-License-Identifier: MIT
import Foundation
import XCTest
import PhoneDSHAgentCore
@testable import PhoneDSHSSH

final class SSHConnectionTests: XCTestCase {
    func testUntrustedConnectionRefusesBeforeNetworkAccess() async {
        let connection = SSHConnection(host: "127.0.0.1", port: 1, username: "test", trustedHostKey: "")
        do { _ = try await connection.connect(timeout: 1); XCTFail("Untrusted server accepted") }
        catch { XCTAssertTrue(error.localizedDescription.contains("not trusted")) }
    }

    func testStalledHandshakeDeadlineAndCancellation() async throws {
        let config = try fixture()
        var connection = config.connection
        connection.port = config.stalledPort
        let start = Date()
        do { _ = try await connection.connect(timeout: 0.2); XCTFail("Stalled handshake connected") } catch {}
        XCTAssertLessThan(Date().timeIntervalSince(start), 2)
        let task = Task { try await connection.connect(timeout: 30) }
        try await Task.sleep(for: .milliseconds(100))
        let cancelStart = Date()
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled handshake connected") } catch {}
        XCTAssertLessThan(Date().timeIntervalSince(cancelStart), 2)
    }

    func testMountConfinementIncludesSymlinksAndSiblingPrefixes() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("escape"), withDestinationURL: URL(fileURLWithPath: "/tmp"))
        let mounts = ["/dsh/workspace": root]
        XCTAssertEqual(try SSHExecProvider.resolveLocalPath("/dsh/workspace/file", mounts: mounts).lastPathComponent, "file")
        for path in ["/dsh/workspace/../secret", "/dsh/workspace-other/file", "/dsh/workspace/escape/file", "/tmp/file"] {
            XCTAssertThrowsError(try SSHExecProvider.resolveLocalPath(path, mounts: mounts))
        }
    }

    func testNativeCommandsAndExitStatus() async throws {
        let config = try fixture()
        let provider = try provider(config)
        let result = try await run(provider, ["ssh", "test", "output"])
        XCTAssertEqual(String(decoding: result.stdout, as: UTF8.self), "ssh stdout\n")
        XCTAssertEqual(String(decoding: result.stderr, as: UTF8.self), "ssh stderr\n")
        XCTAssertEqual(result.code, 7)
        let timeout = try await run(provider, ["ssh", "test", "wait"], seconds: 1)
        XCTAssertEqual(timeout.code, 124)
        let bounded = try await run(provider, ["ssh", "test", "large"])
        XCTAssertNotEqual(bounded.code, 0)
        XCTAssertTrue(bounded.truncated)
    }

    func testKeyAuthenticationAndChangedHostKeyRefusal() async throws {
        let config = try fixture()
        let observed = try await SSHConnection.inspectHostKey(host: "127.0.0.1", port: config.port)
        XCTAssertEqual(observed, config.hostKey)
        for (key, passphrase) in [(config.privateKey, ""), (config.encryptedKey, "fixture-passphrase"), (config.rsaKey, "")] {
            var connection = config.connection
            connection.password = ""
            connection.privateKey = key
            connection.passphrase = passphrase
            let client = try await connection.connect(timeout: 5)
            try await client.close()
        }
        // A valid but different public key must never authenticate.
        var connection = config.connection
        connection.trustedHostKey = config.otherHostKey
        do { _ = try await connection.connect(timeout: 2); XCTFail("Changed host key accepted") } catch {}
    }

    func testSFTPRoundTripAndDirectoryTransfer() async throws {
        let config = try fixture()
        let workspace = URL(fileURLWithPath: config.remote).deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: workspace.appendingPathComponent("tree"), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: workspace) }
        let bytes = Data((0..<65537).map { UInt8($0 % 256) })
        try bytes.write(to: workspace.appendingPathComponent("tree/binary"))
        try Data().write(to: workspace.appendingPathComponent("tree/empty"))
        let provider = try provider(config, workspace: workspace, limit: 100_000)
        let remote = "/roundtrip-" + UUID().uuidString
        defer { try? FileManager.default.removeItem(at: URL(fileURLWithPath: config.remote).appendingPathComponent(remote)) }
        let upload = try await run(provider, ["scp", "--pdsh-v1", "upload", "recursive", "test", "default", "/dsh/workspace/tree", remote])
        XCTAssertEqual(upload.code, 0, String(decoding: upload.stderr, as: UTF8.self))
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: config.remote).appendingPathComponent(remote + "/binary")), bytes)
        let download = try await run(provider, ["scp", "--pdsh-v1", "download", "recursive", "test", "default", remote + "/."])
        XCTAssertEqual(download.code, 0, String(decoding: download.stderr, as: UTF8.self))
        var restored = Data()
        var ends = 0
        for line in String(decoding: download.stdout, as: UTF8.self).split(separator: "\n") {
            let record = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
            if record["type"] as? String == "data" { restored.append(Data(base64Encoded: record["data"] as! String)!) }
            if record["type"] as? String == "end" { ends += 1 }
        }
        XCTAssertEqual(restored, bytes)
        XCTAssertEqual(ends, 2)
    }

    func testCancelAndStdinRefusal() async throws {
        let config = try fixture()
        let provider = try provider(config)
        let id = UUID().uuidString
        let stream = provider.run(.init(execID: id, argv: ["ssh", "test", "wait"], cwd: "/dsh/workspace"))
        let task = Task { () throws -> Int32? in
            for try await event in stream { if case .exit(let code, _, _) = event { return code } }
            return nil
        }
        try await Task.sleep(for: .milliseconds(300))
        await provider.cancel(execID: id)
        let code = try await task.value
        XCTAssertEqual(code, 130)
        let result = try await run(provider, ["ssh", "test", "output"], input: Data("input".utf8))
        XCTAssertEqual(result.code, 1)
        XCTAssertTrue(String(decoding: result.stderr, as: UTF8.self).contains("stdin"))
    }

    private struct Fixture: Decodable {
        var port: Int; var stalledPort: Int; var password: String; var hostKey: String; var otherHostKey: String; var privateKey: String; var encryptedKey: String; var rsaKey: String; var remote: String
        var connection: SSHConnection { SSHConnection(host: "127.0.0.1", port: port, username: "fixture", password: password, trustedHostKey: hostKey) }
    }
    private func fixture() throws -> Fixture {
        guard let path = ProcessInfo.processInfo.environment["PDSH_SSH_TEST_CONFIG"] else {
            throw XCTSkip("Start scripts/testing/ssh-fixture.py and set PDSH_SSH_TEST_CONFIG for live SSH/SFTP tests")
        }
        return try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: URL(fileURLWithPath: path)))
    }
    private func provider(_ config: Fixture, workspace: URL = FileManager.default.temporaryDirectory, limit: Int = 4096) throws -> SSHExecProvider {
        let connection = config.connection
        return SSHExecProvider(workspace: workspace, home: workspace, fallback: NoFallback(), byteLimit: limit,
                               connections: { "test" }, resolve: { _ in connection })
    }
    private func run(_ provider: SSHExecProvider, _ argv: [String], seconds: TimeInterval = 10, input: Data? = nil) async throws -> (stdout: Data, stderr: Data, code: Int32?, truncated: Bool) {
        var stdout = Data(), stderr = Data(), code: Int32?, truncated = false
        for try await event in provider.run(.init(execID: UUID().uuidString, argv: argv, cwd: "/dsh/workspace", deadline: Date().addingTimeInterval(seconds), standardInput: input)) {
            switch event {
            case .stdout(_, let bytes): stdout.append(bytes)
            case .stderr(_, let bytes): stderr.append(bytes)
            case .exit(let value, _, let clipped): code = value; truncated = clipped
            }
        }
        return (stdout, stderr, code, truncated)
    }
}

private struct NoFallback: ExecProvider {
    var capabilities: ExecCapabilities { .init(environment: "test", network: false, persistentWorkspace: false, tiers: [.interactive]) }
    func run(_ request: ExecRequest) -> AsyncThrowingStream<ExecEvent, Error> { AsyncThrowingStream { $0.finish(throwing: SSHFailure("Unexpected fallback")) } }
    func cancel(execID: String) async {}
}
