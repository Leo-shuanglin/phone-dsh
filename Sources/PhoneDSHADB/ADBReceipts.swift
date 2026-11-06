// SPDX-License-Identifier: MIT
import Foundation
import CryptoKit
import PhoneDSHAgentCore

/// Crash receipts are written before sending OPEN to adbd. A saved intent with
/// no result is an unknown outcome, never permission to repeat a device action.
struct ADBReceipt: Codable, Sendable {
    var fingerprint: Data
    var result: JSONValue?
}

struct ADBReceiptStore: Sendable {
    let directory: URL
    static var defaultDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("phone-dsh/android-adb-receipts", isDirectory: true)
    }
    private func path(_ key: String) -> URL {
        let hash = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(hash + ".json")
    }
    func load(_ key: String) throws -> ADBReceipt? {
        let url = path(key)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        // Corrupt receipts fail closed; do not assume the action never ran.
        return try JSONDecoder().decode(ADBReceipt.self, from: Data(contentsOf: url))
    }
    func save(_ key: String, _ receipt: ADBReceipt) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        var stored = receipt
        if let result = stored.result, try result.encoded().count > 16 * 1024 {
            stored.result = ADBTools.textResult("This Android operation finished, but its large result is no longer retained after restart. It was not executed again. Capture a new screenshot or inspect device state as needed.", error: true)
        }
        let url = path(key)
        try JSONEncoder().encode(stored).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        let file = try FileHandle(forWritingTo: url)
        defer { try? file.close() }
        try file.synchronize()
    }
}
