// SPDX-License-Identifier: MIT
import Citadel
import Foundation
import NIO
import PhoneDSHAgentCore

/// SSH and modern scp (SFTP over SSH). Uploads read the acknowledged native
/// mounts; downloads emit records for the Worker to commit through its VFS.
public final class SSHExecProvider: ExecProvider, @unchecked Sendable {
    public typealias Resolver = @Sendable (String) async throws -> SSHConnection
    public typealias ConnectionList = @Sendable () async -> String
    private let resolve: Resolver
    private let list: ConnectionList
    private let fallback: any ExecProvider
    private let mounts: [String: URL]
    private let byteLimit: Int
    private let lock = NSLock()
    private var operations: [String: SSHOperation] = [:]

    public init(workspace: URL, home: URL, fallback: any ExecProvider,
                byteLimit: Int = 32 * 1024 * 1024,
                connections: @escaping ConnectionList,
                resolve: @escaping Resolver) {
        self.mounts = ["/dsh/workspace": workspace, "/dsh/home": home]
        self.fallback = fallback; self.byteLimit = byteLimit
        self.list = connections; self.resolve = resolve
    }

    public var capabilities: ExecCapabilities {
        .init(environment: "native SSH and local accelerators", network: true,
              persistentWorkspace: true, tiers: [.interactive, .session])
    }

    public func run(_ request: ExecRequest) -> AsyncThrowingStream<ExecEvent, Error> {
        guard ["ssh", "scp"].contains(request.argv.first ?? "") else { return fallback.run(request) }
        return AsyncThrowingStream { continuation in
            let operation = SSHOperation()
            let admitted = lock.withLock { () -> Bool in
                guard operations[request.execID] == nil else { return false }
                operations[request.execID] = operation
                return true
            }
            guard admitted else {
                continuation.finish(throwing: SSHFailure("SSH execution already active; do not retry remote side effects"))
                return
            }
            continuation.onTermination = { termination in
                if case .cancelled = termination { Task { await operation.cancel() } }
            }
            let task = Task {
                let remaining = max(0, (request.deadline ?? Date().addingTimeInterval(120)).timeIntervalSinceNow)
                let timer = Task {
                    do { try await Task.sleep(for: .seconds(remaining)) }
                    catch { return }
                    await operation.cancel(timeout: true)
                }
                defer {
                    timer.cancel()
                    _ = self.lock.withLock { self.operations.removeValue(forKey: request.execID) }
                    continuation.finish()
                }
                var code: Int32 = 0
                var truncated = false
                do {
                    try Task.checkCancellation()
                    if request.argv == ["ssh", "--help"] {
                        continuation.yield(.stdout(seq: 0, data: Data("Usage: ssh [-p port] [-l user] connection command; ssh --list. Non-interactive, no stdin/forwarding. Configure connections in Settings → SSH.\n".utf8)))
                    } else if request.argv == ["ssh", "--list"] {
                        continuation.yield(.stdout(seq: 0, data: Data((await self.list() + "\n").utf8)))
                    } else {
                        let invocation = try SSHInvocation(request.argv)
                        let connection = try await self.resolve(invocation.destination)
                        if let port = invocation.port, port != connection.port { throw SSHFailure("Port differs from saved SSH connection") }
                        if let user = invocation.user, user != connection.username { throw SSHFailure("User differs from saved SSH connection") }
                        let client = try await connection.connect(timeout: remaining)
                        operation.setClient(client)
                        try Task.checkCancellation()
                        switch invocation.operation {
                        case .command(let command):
                            if request.standardInput?.isEmpty == false && !invocation.ignoreStandardInput {
                                throw SSHFailure("SSH stdin piping is not supported; use scp to upload input files")
                            }
                            var outputBytes = 0
                            for try await output in try await client.executeCommandStream(command) {
                                try Task.checkCancellation()
                                let count: Int
                                switch output { case .stdout(let bytes), .stderr(let bytes): count = bytes.readableBytes }
                                outputBytes += count
                                if outputBytes > self.byteLimit {
                                    truncated = true
                                    throw SSHFailure("SSH output limit exceeded; redirect large output to a file and use scp")
                                }
                                switch output {
                                case .stdout(let bytes): continuation.yield(.stdout(seq: 0, data: Data(bytes.readableBytesView)))
                                case .stderr(let bytes): continuation.yield(.stderr(seq: 0, data: Data(bytes.readableBytesView)))
                                }
                            }
                        case .upload(let local, let remote, let recursive):
                            let source = try Self.resolveLocalPath(local, mounts: self.mounts)
                            let transfer = SSHFileTransfer(limit: self.byteLimit, emit: { record in
                                continuation.yield(.stdout(seq: 0, data: record))
                            })
                            try await client.withSFTP { sftp in
                                var target = remote
                                if let attributes = try? await sftp.getAttributes(at: remote), attributes.isDirectory {
                                    target = Self.remoteJoin(remote, source.lastPathComponent)
                                }
                                try await transfer.upload(source, to: target, recursive: recursive, sftp: sftp)
                            }
                        case .download(let remote, let recursive):
                            let transfer = SSHFileTransfer(limit: self.byteLimit, emit: { record in
                                continuation.yield(.stdout(seq: 0, data: record))
                            })
                            try await client.withSFTP { sftp in
                                let basename = SSHFileTransfer.basename(remote)
                                let source = [".", ".."].contains(basename)
                                    ? try await sftp.getRealPath(atPath: remote) : remote
                                try await transfer.download(source, relative: "", recursive: recursive, sftp: sftp)
                            }
                        }
                    }
                } catch let error as SSHClient.CommandFailed {
                    code = Int32(clamping: error.exitCode)
                } catch {
                    code = 1
                    if !operation.cancelled {
                        // NIO/Citadel errors often have no LocalizedError
                        // conformance: Foundation then hides the useful enum
                        // case behind an opaque numeric error code.
                        let detail = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
                        continuation.yield(.stderr(seq: 0, data: Data("\(request.argv[0]): \(detail)\n".utf8)))
                    }
                }
                await operation.close()
                if operation.cancelled { code = operation.timedOut ? 124 : 130 }
                continuation.yield(.exit(code: code, signal: operation.cancelled ? 15 : nil, truncated: truncated))
            }
            operation.setTask(task)
        }
    }

    public func cancel(execID: String) async {
        if let operation = lock.withLock({ operations[execID] }) { await operation.cancel() }
        else { await fallback.cancel(execID: execID) }
    }

    static func resolveLocalPath(_ path: String, mounts: [String: URL]) throws -> URL {
        guard !path.contains("\0"), !path.split(separator: "/").contains("..") else { throw SSHFailure("Invalid local path") }
        for (guest, root) in mounts where path == guest || path.hasPrefix(guest + "/") {
            let base = root.resolvingSymlinksInPath().standardizedFileURL
            var result = base
            for component in path.dropFirst(guest.count).split(separator: "/") where component != "." {
                result.appendPathComponent(String(component))
                // resolvingSymlinksInPath may leave a symlink ancestor unresolved
                // when the final file does not exist. Inspect each component.
                if let attributes = try? FileManager.default.attributesOfItem(atPath: result.path),
                   attributes[.type] as? FileAttributeType == .typeSymbolicLink {
                    throw SSHFailure("scp refuses symbolic links in local paths")
                }
            }
            return result.standardizedFileURL
        }
        throw SSHFailure("scp can only read /dsh/workspace and /dsh/home")
    }

    static func remoteJoin(_ parent: String, _ name: String) -> String {
        parent.hasSuffix("/") ? parent + name : parent + "/" + name
    }
}

private final class SSHOperation: @unchecked Sendable {
    private let lock = NSLock()
    private var client: SSHClient?
    private var task: Task<Void, Never>?
    private var stopped = false
    private var deadline = false
    var cancelled: Bool { lock.withLock { stopped } }
    var timedOut: Bool { lock.withLock { deadline } }
    func setClient(_ value: SSHClient) { lock.withLock { client = value } }
    func setTask(_ value: Task<Void, Never>) {
        let cancel = lock.withLock { task = value; return stopped }
        if cancel { value.cancel() }
    }
    func cancel(timeout: Bool = false) async {
        let current = lock.withLock { stopped = true; deadline = deadline || timeout; return task }
        current?.cancel()
        await close()
    }
    func close() async {
        let current = lock.withLock { let value = client; client = nil; return value }
        try? await current?.close()
    }
}

struct SSHInvocation {
    enum Operation {
        case command(String)
        case upload(String, String, Bool)
        case download(String, Bool)
    }
    var ignoreStandardInput = false
    var destination: String
    var port: Int?
    var user: String?
    var operation: Operation

    init(_ argv: [String]) throws {
        guard argv.allSatisfy({ !$0.contains("\0") }) else { throw SSHFailure("Arguments must be NUL-free") }
        if argv.first == "scp" {
            guard argv.count >= 7, argv[1] == "--pdsh-v1" else { throw SSHFailure("Use scp through the Worker shell") }
            let recursive = argv[3] == "recursive"
            guard recursive || argv[3] == "file" else { throw SSHFailure("Invalid transfer mode") }
            destination = argv[4]
            if argv[5] != "default" {
                guard let parsed = Int(argv[5]), (1...65535).contains(parsed) else { throw SSHFailure("Invalid SSH port") }
                port = parsed
            }
            if argv[2] == "upload", argv.count == 8 { operation = .upload(argv[6], argv[7], recursive) }
            else if argv[2] == "download", argv.count == 7 { operation = .download(argv[6], recursive) }
            else { throw SSHFailure("Invalid transfer request") }
            return
        }
        var index = 1
        while index < argv.count, argv[index].hasPrefix("-") {
            let flag = argv[index]; index += 1
            if flag == "--" { break }
            if flag == "-T" { continue }
            if flag == "-n" { ignoreStandardInput = true; continue }
            guard ["-p", "-l"].contains(flag), index < argv.count else {
                throw SSHFailure("Usage: ssh [-p port] [-l user] connection command; ssh --list. Interactive shells and forwarding are not supported.")
            }
            if flag == "-p" {
                guard let parsed = Int(argv[index]), (1...65535).contains(parsed) else { throw SSHFailure("Invalid SSH port") }
                port = parsed
            } else { user = argv[index] }
            index += 1
        }
        guard index + 1 < argv.count else { throw SSHFailure("Usage: ssh connection command; ssh --list") }
        destination = argv[index]
        operation = .command(argv.dropFirst(index + 1).joined(separator: " "))
    }
}
