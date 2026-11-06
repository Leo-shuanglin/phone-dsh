// SPDX-License-Identifier: MIT
import XCTest
import CryptoKit
import PhoneDSHAgentCore
import PhoneDSHLoopback
@testable import PhoneDSHADB

extension ADBMCPTests {
    func testPackageManagementGoldenAndFailureAcknowledgement() async throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let golden = try JSONValue.parse(Data(contentsOf: root.appendingPathComponent("spec/v1/golden/native-mcp/package-management.json")))
        guard case .array(let vectors) = golden else { return XCTFail("missing vectors") }
        for vector in vectors {
            let fields = vector.fields
            let output = try XCTUnwrap(fields["stdout"]?.text)
            let (server, recorder) = try fixture(executor: RecordingADB(output: ADBShellResult(stdout: Data(output.utf8))))
            let response = await server.handle(LoopbackRequest(method: "POST", path: "/mcp/native", body: try fields["request"]!.encoded()))
            XCTAssertEqual(try JSONValue.parse(response.body), fields["response"])
            let commands = await recorder.commands
            XCTAssertEqual(commands, [fields["android_command"]!.text!])
        }
        for output in ["Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n", "", "Success\nFailure [vendor error]\n"] {
            let (server, _) = try fixture(executor: RecordingADB(output: ADBShellResult(stdout: Data(output.utf8))))
            let value = try result(await server.handle(call("android_install_apk", ["source": .string("device"), "apkPath": .string("/data/local/tmp/test.apk")])))
            XCTAssertEqual(value.fields["isError"], .bool(true), output)
        }
    }

    func testWorkspaceInstallStreamsExactVerifiedBytesAndDeduplicates() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        // Protocol fixture only, not a valid signed APK; Android is simulated.
        let bytes = Data([80, 75, 3, 4, 0, 255, 13, 10])
        let path = root.appendingPathComponent("sample.apk")
        try bytes.write(to: path)
        let recorder = RecordingADB(output: ADBShellResult(stdout: Data("Success\n".utf8)))
        let (server, _) = try fixture(executor: recorder)
        try await server.setWorkspaceDirectory(root)
        let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let req = try call("android_install_apk", ["source": .string("workspace"), "apkPath": .string("/dsh/workspace/sample.apk"), "sha256": .string(hash)], key: "install-once")
        let first = try result(await server.handle(req))
        XCTAssertEqual(first.fields["isError"], .bool(false))
        try Data("changed".utf8).write(to: path)
        let replay = try result(await server.handle(req))
        XCTAssertEqual(first, replay)
        let commands = await recorder.commands
        let inputs = await recorder.inputs
        XCTAssertEqual(commands, ["pm install --user current -r -S 8 -"])
        XCTAssertEqual(inputs, [bytes])
        let mismatch = try result(await server.handle(call("android_install_apk", ["source": .string("workspace"), "apkPath": .string("/dsh/workspace/sample.apk"), "sha256": .string(hash)])))
        XCTAssertEqual(mismatch.fields["isError"], .bool(true))
        let commandsAfter = await recorder.commands
        XCTAssertEqual(commandsAfter.count, 1, "mismatched bytes must never reach Android")
    }

    func testWorkspaceAPKConfinesFilesAndRejectsUnboundedOrNonAPKData() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let bytes = Data([80, 75, 3, 4, 1])
        let hash = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        try bytes.write(to: root.appendingPathComponent("real.apk"))
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("link.apk"), withDestinationURL: root.appendingPathComponent("real.apk"))
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("dir"), withDestinationURL: root)
        for path in ["/dsh/workspace/link.apk", "/dsh/workspace/dir/real.apk", "/dsh/workspace/../real.apk", "/dsh/workspace//real.apk", "/etc/real.apk"] {
            XCTAssertThrowsError(try ADBWorkspaceAPK.read(path: path, root: root, sha256: hash), path)
        }
        XCTAssertThrowsError(try ADBWorkspaceAPK.read(path: "/dsh/workspace/real.apk", root: nil, sha256: hash))
        let big = root.appendingPathComponent("big.apk")
        FileManager.default.createFile(atPath: big.path, contents: nil)
        let handle = try FileHandle(forWritingTo: big)
        try handle.truncate(atOffset: UInt64(ADBWorkspaceAPK.limit + 1)); try handle.close()
        XCTAssertThrowsError(try ADBWorkspaceAPK.read(path: "/dsh/workspace/big.apk", root: root, sha256: hash))
        let notAPK = Data("ordinary file".utf8)
        try notAPK.write(to: root.appendingPathComponent("plain.apk"))
        let plainHash = SHA256.hash(data: notAPK).map { String(format: "%02x", $0) }.joined()
        XCTAssertThrowsError(try ADBWorkspaceAPK.read(path: "/dsh/workspace/plain.apk", root: root, sha256: plainHash))
    }

    func testPackageArgumentValidationPreventsWrongTargetAndShellInjection() async throws {
        let (server, recorder) = try fixture()
        let invalid: [(String, [String: JSONValue])] = [
            ("android_uninstall_app", ["packageName": .string("com.example;reboot")]),
            ("android_uninstall_app", ["packageName": .string("com.example"), "userId": .number(-1)]),
            ("android_set_app_enabled", ["packageName": .string("com.example"), "enabled": .string("false")]),
            ("android_install_apk", ["apkPath": .string("/tmp/a.apk")]),
            ("android_install_apk", ["apkPath": .string("/dsh/workspace/a.apk"), "source": .string("workspace")]),
            ("android_install_apk", ["apkPath": .string("/tmp/a.apks"), "source": .string("device")]),
            ("android_delete_apk_file", ["apkPath": .string("/sdcard/../data/app/base.apk")]),
            ("android_delete_apk_file", ["apkPath": .string("/data/app/base.apk")]),
            ("android_app_permission", ["packageName": .string("com.example"), "permission": .string("android.permission.CAMERA;id"), "action": .string("grant")]),
            ("android_logcat", ["lines": .number(2001)]),
            ("android_list_packages", ["filter": .string("-u")]),
            ("android_command_help", ["topic": .string("package;reboot")]),
        ]
        for (name, arguments) in invalid {
            let value = try result(await server.handle(call(name, arguments)))
            XCTAssertEqual(value.fields["isError"], .bool(true), name)
        }
        let commands = await recorder.commands
        XCTAssertTrue(commands.isEmpty)
        let quoted = try ADBTools.plan(name: "android_install_apk", arguments: .object(["source": .string("device"), "apkPath": .string("/data/local/tmp/';$(id).apk")]))
        XCTAssertEqual(quoted.command, "pm install --user current -r '/data/local/tmp/'\\'';$(id).apk'")
    }
}
