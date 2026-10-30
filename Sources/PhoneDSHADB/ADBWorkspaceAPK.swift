// SPDX-License-Identifier: MIT
import Foundation
import CryptoKit
import Darwin

enum ADBWorkspaceAPK {
    static let limit = 128 * 1024 * 1024

    /// Open each component relative to the preceding directory without following
    /// symlinks. A resolved-path prefix check alone would permit a rename race.
    /// Copy and hash the bytes before contacting Android; the Worker-provided
    /// digest also fences a stale materialized copy without guessing WAL timing.
    static func read(path: String, root: URL?, sha256: String) throws -> Data {
        guard let root else { throw ADBFailure("The host has not configured an APK workspace directory") }
        let prefix = "/dsh/workspace/"
        guard path.hasPrefix(prefix) else { throw ADBFailure("APK must be inside /dsh/workspace/") }
        let components = path.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !components.isEmpty, components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." && !$0.utf8.contains(0) }) else {
            throw ADBFailure("Invalid workspace APK path")
        }
        var fd = open(root.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { throw ADBFailure("Cannot open APK workspace directory") }
        defer { close(fd) }
        for (index, component) in components.enumerated() {
            let flags = O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK | (index == components.count - 1 ? 0 : O_DIRECTORY)
            let next = openat(fd, component, flags)
            guard next >= 0 else { throw ADBFailure("APK file is unavailable; workspace symlinks are not supported. Check the path and workspace synchronization.") }
            close(fd); fd = next
        }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_size >= 4, info.st_size <= limit else { throw ADBFailure("APK must be a regular file of 4 bytes to 128 MiB") }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        var bytes = Data()
        while true {
            try Task.checkCancellation()
            let part = try handle.read(upToCount: min(256 * 1024, limit + 1 - bytes.count)) ?? Data()
            if part.isEmpty { break }
            bytes.append(part)
            guard bytes.count <= limit else { throw ADBFailure("APK exceeds 128 MiB") }
        }
        guard bytes.count == info.st_size else { throw ADBFailure("APK changed while being read; recompute its sha256") }
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard digest == sha256 else { throw ADBFailure("APK sha256 mismatch: workspace may not be synchronized or file changed. No Android command was sent; verify sha256sum and retry after synchronization.") }
        guard bytes.prefix(4) == Data([80, 75, 3, 4]) else { throw ADBFailure("File is not an APK/ZIP archive") }
        return bytes
    }
}
