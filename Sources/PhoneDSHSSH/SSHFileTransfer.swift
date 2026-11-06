// SPDX-License-Identifier: MIT
import Citadel
import Foundation
import NIO

/// Per-transfer limits apply to the entire tree, not just each file. Every
/// downloaded path is relative and validated again by the Worker VFS writer.
final class SSHFileTransfer {
    private let limit: Int
    private let emit: (Data) -> Void
    private var bytes = 0
    private var entries = 0
    init(limit: Int, emit: @escaping (Data) -> Void) { self.limit = limit; self.emit = emit }

    private func admit(size: UInt64 = 0, depth: Int) throws {
        try Task.checkCancellation()
        entries += 1
        guard entries <= 1000, depth <= 32, size <= UInt64(max(0, limit - bytes)) else {
            throw SSHFailure("scp transfer exceeds the device byte, entry or directory-depth limit")
        }
        bytes += Int(size)
    }

    private func record(_ value: [String: Any]) throws {
        var data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
        data.append(10)
        emit(data)
    }

    func upload(_ local: URL, to remote: String, recursive: Bool, sftp: SFTPClient, depth: Int = 0) async throws {
        let attributes = try FileManager.default.attributesOfItem(atPath: local.path)
        let type = attributes[.type] as? FileAttributeType
        if type == .typeDirectory {
            guard recursive else { throw SSHFailure("Source is a directory; use scp -r") }
            try admit(depth: depth)
            if let existing = try? await sftp.getAttributes(at: remote) {
                guard existing.isDirectory else { throw SSHFailure("Remote destination is not a directory") }
            } else { try await sftp.createDirectory(atPath: remote) }
            for child in try FileManager.default.contentsOfDirectory(at: local, includingPropertiesForKeys: nil).sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                try await upload(child, to: SSHExecProvider.remoteJoin(remote, child.lastPathComponent), recursive: true, sftp: sftp, depth: depth + 1)
            }
            return
        }
        guard type == .typeRegular else { throw SSHFailure("scp refuses symbolic links and special local files") }
        let size = (attributes[.size] as? NSNumber)?.uint64Value ?? 0
        try admit(size: size, depth: depth)
        let input = try FileHandle(forReadingFrom: local)
        defer { try? input.close() }
        try await sftp.withFile(filePath: remote, flags: [.write, .create, .truncate]) { output in
            var offset: UInt64 = 0
            while offset < size {
                try Task.checkCancellation()
                let chunk = try input.read(upToCount: Int(min(32 * 1024, size - offset))) ?? Data()
                guard !chunk.isEmpty else { throw SSHFailure("Local file changed while uploading") }
                try await output.write(ByteBuffer(bytes: chunk), at: offset)
                offset += UInt64(chunk.count)
            }
        }
        try record(["type": "uploaded", "path": remote, "size": size])
    }

    func download(_ remote: String, relative: String, recursive: Bool, sftp: SFTPClient,
                  depth: Int = 0, knownAttributes: SFTPFileAttributes? = nil) async throws {
        let attributes: SFTPFileAttributes
        if let knownAttributes { attributes = knownAttributes }
        else { attributes = try await sftp.getAttributes(at: remote) }
        let kind = attributes.permissions.map { $0 & 0o170000 }
        if attributes.isDirectory {
            guard recursive else { throw SSHFailure("Source is a directory; use scp -r") }
            try admit(depth: depth)
            try record(["type": "directory", "path": relative, "name": Self.basename(remote)])
            for listing in try await sftp.listDirectory(atPath: remote) {
                for child in listing.components where child.filename != "." && child.filename != ".." {
                    try Self.validateName(child.filename)
                    let childPath = relative.isEmpty ? child.filename : relative + "/" + child.filename
                    try await download(SSHExecProvider.remoteJoin(remote, child.filename), relative: childPath,
                                       recursive: true, sftp: sftp, depth: depth + 1, knownAttributes: child.attributes)
                }
            }
            return
        }
        guard kind == 0o100000 || kind == nil else { throw SSHFailure("scp refuses symbolic links and special remote files") }
        guard let size = attributes.size else { throw SSHFailure("Server did not provide the file size") }
        try admit(size: size, depth: depth)
        try record(["type": "file", "path": relative, "name": Self.basename(remote), "size": size])
        try await sftp.withFile(filePath: remote, flags: .read) { input in
            var offset: UInt64 = 0
            while offset < size {
                try Task.checkCancellation()
                let chunk = try await input.read(from: offset, length: UInt32(min(32 * 1024, size - offset)))
                guard chunk.readableBytes > 0 else { throw SSHFailure("Remote file changed while downloading") }
                try self.record(["type": "data", "data": Data(chunk.readableBytesView).base64EncodedString()])
                offset += UInt64(chunk.readableBytes)
            }
        }
        try record(["type": "end", "path": relative])
    }

    static func validateName(_ name: String) throws {
        guard !name.isEmpty, name != ".", name != "..", !name.contains("/"), !name.contains("\\"), !name.contains("\0") else {
            throw SSHFailure("Unsafe filename from SSH server")
        }
    }

    static func basename(_ path: String) -> String {
        path.split(separator: "/").last.map(String.init) ?? "download"
    }
}

extension SFTPFileAttributes {
    var isDirectory: Bool { permissions.map { $0 & 0o170000 == 0o040000 } ?? false }
}
