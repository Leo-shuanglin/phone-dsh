// SPDX-License-Identifier: MIT
import XCTest
import Darwin
import PhoneDSHAgentCore
@testable import PhoneDSHADB

final class ADBFileTransferTests: XCTestCase {
    func fixture(output: ADBShellResult = ADBShellResult(), limit: Int = 1024) throws -> (ADBExecProvider, RecordingADB, URL) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let suite = "adb-files-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let settings = ADBSettings(defaults: defaults)
        try settings.save(ADBConfiguration(enabled: true, host: "192.0.2.1"))
        addTeardownBlock { try? FileManager.default.removeItem(at: root); defaults.removePersistentDomain(forName: suite) }
        let recorder = RecordingADB(output: output)
        return (ADBExecProvider(workspace: root, fallback: UnavailableADBTestProvider(), byteLimit: limit, transfersDirectory: root.appendingPathComponent("transfers"), settings: settings, executor: recorder), recorder, root)
    }
    func run(_ provider: ADBExecProvider, _ action: String, local: String = "/dsh/workspace/file", remote: String = "/sdcard/file") async throws -> (Data, Int32?) {
        var output = Data(); var code: Int32?
        let request = ExecRequest(execID: UUID().uuidString, argv: ["adb", "--pdsh-v1", action, local, remote], cwd: "/dsh/workspace")
        for try await event in provider.run(request) {
            switch event {
            case .stdout(_, let data): output.append(data)
            case .exit(let value, _, _): code = value
            default: break
            }
        }
        return (output, code)
    }
    func testPushPreservesBinaryAndEmptyFilesAndRejectsSymlinksAndOversize() async throws {
        let (provider, recorder, root) = try fixture(limit: 10)
        let bytes = Data([0, 255, 128, 13, 10])
        try bytes.write(to: root.appendingPathComponent("file"))
        let first = try await run(provider, "push", remote: "/sdcard/a ' file")
        XCTAssertEqual(first.1, 0)
        try Data().write(to: root.appendingPathComponent("file"))
        let empty = try await run(provider, "push")
        XCTAssertEqual(empty.1, 0)
        let inputs = await recorder.inputs
        XCTAssertEqual(inputs, [bytes, Data()])
        try Data(repeating: 1, count: 11).write(to: root.appendingPathComponent("file"))
        let oversized = try await run(provider, "push")
        XCTAssertEqual(oversized.1, 1)
        try FileManager.default.createSymbolicLink(atPath: root.appendingPathComponent("link").path, withDestinationPath: "file")
        let link = try await run(provider, "push", local: "/dsh/workspace/link")
        XCTAssertEqual(link.1, 1)
        let commands = await recorder.commands
        XCTAssertEqual(commands.count, 2)
        XCTAssertTrue(commands[0].contains("mv -f"))
    }
    func testNativeDiskTransferAndOneGiBBoundaryWithoutLoadingFile() async throws {
        let bytes = Data([0, 255, 10])
        let (provider, recorder, root) = try fixture(output: ADBShellResult(stdout: bytes))
        let pulled = try await run(provider, "pull", local: "/dsh/transfers/file")
        XCTAssertEqual(pulled.1, 0)
        let file = root.appendingPathComponent("transfers/file")
        XCTAssertEqual(try Data(contentsOf: file), bytes)
        let pushed = try await run(provider, "push", local: "/dsh/transfers/file")
        XCTAssertEqual(pushed.1, 0)
        let inputs = await recorder.inputs
        XCTAssertEqual(inputs, [bytes])
        let handle = try FileHandle(forWritingTo: file)
        defer { try? handle.close() }
        try handle.truncate(atOffset: UInt64(ADBNativeFiles.limit))
        let (parent, name) = try ADBNativeFiles.parent(path: "/dsh/transfers/file", root: root.appendingPathComponent("transfers"))
        defer { close(parent) }
        let accepted = try ADBNativeFiles.input(parent: parent, name: name)
        XCTAssertEqual(try accepted.seekToEnd(), 1_073_741_824)
        try accepted.close()
        try handle.truncate(atOffset: UInt64(ADBNativeFiles.limit) + 1)
        XCTAssertThrowsError(try ADBNativeFiles.input(parent: parent, name: name))
    }
    func testNativeFailedPullPreservesOldFileAndRemovesTemporary() async throws {
        let (provider, _, root) = try fixture(output: ADBShellResult(stdout: Data("partial".utf8), exitCode: 1))
        let directory = root.appendingPathComponent("transfers")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("original".utf8).write(to: directory.appendingPathComponent("file"))
        let result = try await run(provider, "pull", local: "/dsh/transfers/file")
        XCTAssertEqual(result.1, 1)
        XCTAssertEqual(try String(contentsOf: directory.appendingPathComponent("file"), encoding: .utf8), "original")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: directory.path), ["file"])
    }

    func testStatusDistinguishesRunningAndSavedAndRejectsDuplicateTransfer() async throws {
        let (_, _, root) = try fixture()
        let suite = "adb-status-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let settings = ADBSettings(defaults: defaults)
        try settings.save(ADBConfiguration(enabled: true, host: "192.0.2.1"))
        let gate = StatusTransferGate()
        let provider = ADBExecProvider(workspace: root, fallback: UnavailableADBTestProvider(), transfersDirectory: root.appendingPathComponent("transfers"), settings: settings, executor: gate)
        func status(_ provider: ADBExecProvider) async throws -> [String: Any] {
            var output = Data()
            let request = ExecRequest(execID: UUID().uuidString, argv: ["adb", "--pdsh-v1", "stat", "/dsh/transfers/file"], cwd: "/dsh/workspace")
            for try await event in provider.run(request) {
                if case .stdout(_, let data) = event { output.append(data) }
                if case .exit(let code, _, _) = event { XCTAssertEqual(code, 0) }
            }
            return try JSONSerialization.jsonObject(with: output) as! [String: Any]
        }
        let first = Task { try await self.run(provider, "pull", local: "/dsh/transfers/file") }
        await gate.waitStarted()
        let running = try await status(provider)
        XCTAssertEqual(running["status"] as? String, "running")
        XCTAssertNil(running["size"])
        let duplicate = try await run(provider, "pull", local: "/dsh/transfers/file")
        XCTAssertEqual(duplicate.1, 1)
        await gate.complete()
        let completed = try await first.value
        XCTAssertEqual(completed.1, 0)
        try settings.save(ADBConfiguration(enabled: false))
        let restarted = ADBExecProvider(workspace: root, fallback: UnavailableADBTestProvider(), transfersDirectory: root.appendingPathComponent("transfers"), settings: settings)
        let saved = try await status(restarted)
        XCTAssertEqual(saved["status"] as? String, "saved")
        XCTAssertEqual(saved["size"] as? Int, 3)
    }

    func testPullMatchesTransferGolden() async throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("spec/v1/golden/native-mcp/file-transfer.json"))) as! [String: Any]
        let (provider, _, _) = try self.fixture(output: ADBShellResult(stdout: Data([0, 255, 10])))
        let (output, code) = try await run(provider, "pull", local: "/dsh/workspace/result.bin", remote: "/sdcard/result.bin")
        let rows = try String(decoding: output, as: UTF8.self).split(separator: "\n").map { try JSONSerialization.jsonObject(with: Data($0.utf8)) }
        XCTAssertEqual(code, 0)
        XCTAssertEqual(rows as NSArray, fixture["stdout_records"] as? NSArray)
    }

    func testPullEmitsExactBinaryOnlyOnSuccess() async throws {
        let bytes = Data([0, 255, 128, 13, 10])
        let (provider, _, _) = try fixture(output: ADBShellResult(stdout: bytes))
        let (output, code) = try await run(provider, "pull")
        XCTAssertEqual(code, 0)
        let rows = try String(decoding: output, as: UTF8.self).split(separator: "\n").map { try JSONSerialization.jsonObject(with: Data($0.utf8)) as! [String: Any] }
        XCTAssertEqual(rows.count, 3)
        XCTAssertEqual(rows[0]["size"] as? Int, bytes.count)
        XCTAssertEqual(Data(base64Encoded: rows[1]["data"] as! String), bytes)
        let (failed, _, _) = try fixture(output: ADBShellResult(stdout: bytes, stderr: Data("denied".utf8), exitCode: 1))
        let failure = try await run(failed, "pull")
        XCTAssertEqual(failure.1, 1)
        XCTAssertTrue(failure.0.isEmpty)
    }
}

private struct UnavailableADBTestProvider: ExecProvider {
    var capabilities: ExecCapabilities { .init(environment: "test", network: false, persistentWorkspace: true, tiers: [.interactive]) }
    func run(_ request: ExecRequest) -> AsyncThrowingStream<ExecEvent, Error> { AsyncThrowingStream { $0.finish(throwing: ADBFailure("unexpected fallback")) } }
    func cancel(execID: String) async {}
}

private actor StatusTransferGate: ADBCommandExecuting {
    private var started = false
    private var startWaiter: CheckedContinuation<Void, Never>?
    private var completion: CheckedContinuation<Void, Never>?
    func waitStarted() async {
        if started { return }
        await withCheckedContinuation { startWaiter = $0 }
    }
    func complete() { completion?.resume(); completion = nil }
    func transfer(endpoint: ADBEndpoint, command: String, input: FileHandle?, output: FileHandle?, byteLimit: Int, timeout: TimeInterval) async throws -> ADBShellResult {
        try output?.write(contentsOf: Data([0, 255, 10]))
        await withCheckedContinuation { continuation in
            completion = continuation; started = true; startWaiter?.resume(); startWaiter = nil
        }
        return ADBShellResult()
    }
    func shell(endpoint: ADBEndpoint, command: String, timeout: TimeInterval, outputLimit: Int) async throws -> ADBShellResult { throw ADBFailure("unexpected shell") }
}
