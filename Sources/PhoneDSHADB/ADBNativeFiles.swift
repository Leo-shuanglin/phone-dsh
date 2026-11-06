// SPDX-License-Identifier: MIT
import Foundation
import Darwin

/// Files outside the in-memory Worker VFS, for bounded-memory 1 GiB transfers.
enum ADBNativeFiles {
    static let limit = 1_073_741_824
    static func parent(path: String, root: URL) throws -> (Int32, String) {
        let prefix = "/dsh/transfers/"
        guard path.hasPrefix(prefix) else { throw ADBFailure("Large files must use /dsh/transfers/NAME") }
        let parts = path.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !parts.isEmpty, parts.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." && !$0.contains("\0") }) else { throw ADBFailure("Invalid transfer path") }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var fd = open(root.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { throw ADBFailure("Cannot open iPhone transfer directory") }
        do {
            for part in parts.dropLast() {
                let next = openat(fd, part, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
                guard next >= 0 else { throw ADBFailure("Transfer parent is missing or is a symbolic link") }
                close(fd); fd = next
            }
            return (fd, parts.last!)
        } catch { close(fd); throw error }
    }
    static func input(parent: Int32, name: String) throws -> FileHandle {
        let fd = openat(parent, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        guard fd >= 0 else { throw ADBFailure("Cannot open transfer source") }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_size >= 0, info.st_size <= limit else {
            close(fd); throw ADBFailure("Transfer source must be a regular file no larger than 1 GiB")
        }
        return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }
    static func output(parent: Int32, name: String) throws -> FileHandle {
        let fd = openat(parent, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw ADBFailure("Cannot create transfer temporary file") }
        return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }
}
