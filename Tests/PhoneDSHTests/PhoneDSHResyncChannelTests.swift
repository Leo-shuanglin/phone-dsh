// SPDX-License-Identifier: MIT
// P01 channel-side resync wiring: GAP-13 (re-request after HELLO) and the
// superseded VFS_CONFLICT case (channel.md §9 step 6, §10; wal.md §5.1/§6.2).
//
// The tests drive the real ChannelServer: it receives a worker HELLO over the
// ordered frame decoder, asks the workspace-log seam for pending paths, and
// sends the real 0x13 frames. A VFS_CONFLICT naming a VFS_RESYNC whose held
// record was superseded must be ignored rather than tearing the channel down.

import Foundation
import XCTest
import PhoneDSHController
import PhoneDSHLog
import PhoneDSHLoopback
import PhoneDSHWire

private actor ScriptedSocket: ChannelSocket {
    private var inbound: [Data] = []
    private var receiveWaiters: [CheckedContinuation<Data?, Never>] = []
    private var inboundSeq: UInt64 = 0
    private var decoder = FrameDecoder()
    private var outboundFrames: [Frame] = []
    private(set) var closed = false

    func push(_ type: FrameType, payload: Data) throws {
        inboundSeq += 1
        inbound.append(try FrameEncoder.encode(type: type, seq: inboundSeq, payload: payload))
        resumeWaiters()
    }

    private func resumeWaiters() {
        let waiters = receiveWaiters
        receiveWaiters.removeAll()
        for waiter in waiters {
            if inbound.isEmpty {
                if closed { waiter.resume(returning: nil) }
                else { receiveWaiters.append(waiter) }
            } else {
                waiter.resume(returning: inbound.removeFirst())
            }
        }
    }

    func receiveChannelMessage() async throws -> Data? {
        if !inbound.isEmpty { return inbound.removeFirst() }
        if closed { return nil }
        return await withCheckedContinuation { receiveWaiters.append($0) }
    }

    func sendChannelMessage(_ data: Data) async throws {
        for frame in try decoder.feed(data) {
            outboundFrames.append(frame)
        }
    }

    func closeChannel(code: Int) async {
        guard !closed else { return }
        closed = true
        resumeWaiters()
    }

    func frames() -> [Frame] { outboundFrames }

    func firstFrame(_ type: FrameType) -> Frame? {
        outboundFrames.first { $0.type == type }
    }
}

/// Workspace-log seam with a caller-controlled pending repair set.
private actor PendingResyncLog: LoopbackWorkspaceLog, LoopbackResyncStatusProviding {
    private var pending: [String]
    private var nextMseq: UInt64 = 1

    init(pending: [String]) {
        self.pending = pending
    }

    func setPending(_ paths: [String]) {
        pending = paths
    }

    func pendingResyncPaths() async -> [String] { pending }

    /// A complete write (`op = 1`, `appended` clear) replaces the file and
    /// settles every held append it covers (wal.md §5.1/§6.2); this log then
    /// no longer owes a resync for that path.
    func append(_ mutation: VfsMutationBody) async throws -> UInt64 {
        if mutation.op == .write, !mutation.mflags.contains(.appended) {
            pending.removeAll { $0 == mutation.path }
        }
        defer { nextMseq += 1 }
        return nextMseq
    }

    nonisolated func acks() -> AsyncStream<LoopbackWorkspaceAck> {
        AsyncStream { $0.finish() }
    }
}

/// Records every ERROR the channel server dispatches to a handler.
///
/// The facade's `PhoneDSHChannelHandler.handleError` is where
/// `PhoneDSHEvent.error` is published (`Sources/PhoneDSH/PhoneDSHChannel.swift`);
/// an ERROR that never reaches the handler therefore never becomes an
/// App-visible error event. This double makes that call observable.
private actor RecordingChannelHandler: ChannelHandling {
    private let workspaceLog: (any LoopbackWorkspaceLog)?
    private(set) var errorPayloads: [ErrorPayload] = []

    init(workspaceLog: (any LoopbackWorkspaceLog)? = nil) {
        self.workspaceLog = workspaceLog
    }

    func handleHello(_ payload: HelloPayload) async throws {}
    func handleVFSMutation(_ body: VfsMutationBody, seq: UInt64) async throws {
        _ = try await workspaceLog?.append(body)
    }
    func handleVFSMutation(_ body: VfsMutationBody, mseq: UInt64, seq: UInt64) async throws {
        _ = try await workspaceLog?.append(body)
    }
    func handleVFSBarrier(_ body: VfsBarrierBody) async throws {}
    func handleExecRequest(_ payload: ExecRequestPayload) async throws {}
    func handleExecControl(_ payload: ExecControlPayload) async throws {}
    func handleJournal(_ body: JournalBody) async throws {}
    func handleAPIResponse(_ frame: ApiResponseFrame) async throws {}
    func handleAPIEnd(_ frame: ApiEndFrame) async throws {}
    func handleAPIAbort(_ frame: ApiAbortFrame) async throws {}
    func handleConsole(_ payload: ConsolePayload) async throws {}

    func handleError(_ payload: ErrorPayload) async throws {
        errorPayloads.append(payload)
    }
}

final class PhoneDSHResyncChannelTests: XCTestCase {
    private let path = "/dsh/workspace/notes.txt"

    private func hostState() -> ChannelHostState {
        ChannelHostState(bootID: String(repeating: "0", count: 32),
                         sessionEpoch: 1,
                         fence: Fence(owner: "phone", generation: 1, walSeq: 0),
                         caps: ["channel/1", "wal/1"],
                         ackedMseq: 0,
                         durableMseq: 0)
    }

    private func workerHello() throws -> Data {
        try CanonicalJSON.encode(HelloPayload(
            role: .worker,
            bootID: String(repeating: "1", count: 32),
            sessionEpoch: 1,
            fence: Fence(owner: "phone", generation: 1, walSeq: 0),
            caps: ["channel/1", "vfs-sink/1", "journal/1", "api/1"],
            lastMseq: 0))
    }

    private func startServer(socket: ScriptedSocket,
                             log: PendingResyncLog,
                             handler: (any ChannelHandling)? = nil) async throws
        -> (ChannelServer, Task<Void, Never>) {
        let server = ChannelServer(socket: socket, state: hostState(), workspaceLog: log)
        if let handler {
            try await server.installHandler(handler)
        } else {
            try await server.installHandler(DefaultChannelHandling(outbound: server))
        }
        try await socket.push(.hello, payload: try workerHello())
        let task = Task { await server.run() }
        return (server, task)
    }

    private func waitForFrame(_ type: FrameType,
                              in socket: ScriptedSocket,
                              timeout: TimeInterval = 5) async throws -> Frame {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let frame = await socket.firstFrame(type) { return frame }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        throw FacadeTestError.timeout
    }

    private func waitForResync(path: String,
                               in socket: ScriptedSocket,
                               timeout: TimeInterval = 5) async throws -> Frame {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let frames = await socket.frames()
            for frame in frames where frame.type == .vfsResync {
                if let body = try? VfsResyncBody.decode(frame.payload), body.path == path {
                    return frame
                }
            }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
        throw FacadeTestError.timeout
    }

    func testHelloReRequestsEveryStillHeldResyncPath() async throws {
        let socket = ScriptedSocket()
        let log = PendingResyncLog(pending: [path])
        let (server, task) = try await startServer(socket: socket, log: log)

        let frame = try await waitForFrame(.vfsResync, in: socket)
        let body = try VfsResyncBody.decode(frame.payload)
        XCTAssertEqual(body.path, path)
        await server.close()
        await task.value
    }

    func testSupersededConflictIsIgnoredAndNonSupersededConflictCloses() async throws {
        let socket = ScriptedSocket()
        let log = PendingResyncLog(pending: [path])
        let handler = RecordingChannelHandler()
        let (server, task) = try await startServer(socket: socket, log: log,
                                                   handler: handler)

        let resync = try await waitForFrame(.vfsResync, in: socket)
        // A later same-path mutation settled the held record: the path is no
        // longer pending when the Worker's conflict arrives.
        await log.setPending([])
        let conflict = try CanonicalJSON.encode(
            ErrorPayload(code: .vfsConflict, message: "superseded",
                         refSeq: resync.seq))
        try await socket.push(.error, payload: conflict)
        try await waitUntil(timeout: 5) {
            let metrics = await server.currentMetrics()
            return metrics.vfsConflictsIgnored == 1
        }
        let closedAfterIgnored = await socket.closed
        XCTAssertFalse(closedAfterIgnored,
                       "a superseded VFS_CONFLICT is not a terminal channel error")
        // The superseded conflict must be decided before the handler runs:
        // `PhoneDSHChannelHandler.handleError` publishes `PhoneDSHEvent.error`,
        // so a handler call here would surface the ignored conflict to the App.
        let errorsAfterIgnored = await handler.errorPayloads
        XCTAssertTrue(errorsAfterIgnored.isEmpty,
                      "a superseded VFS_CONFLICT must not reach handler.handleError "
                        + "(no PhoneDSHEvent.error)")

        // The same conflict while the held record is still pending is terminal
        // (the Worker closes the channel after its ERROR) and the handler sees it.
        await log.setPending([path])
        let terminal = try CanonicalJSON.encode(
            ErrorPayload(code: .vfsConflict, message: "still held",
                         refSeq: resync.seq))
        try await socket.push(.error, payload: terminal)
        try await waitUntil(timeout: 5) {
            await socket.closed
        }
        let terminalErrors = await handler.errorPayloads
        XCTAssertEqual(terminalErrors.map(\.code), [.vfsConflict],
                       "a non-superseded VFS_CONFLICT still reaches the handler")
        XCTAssertEqual(terminalErrors.first?.message, "still held")
        await task.value
    }

    /// A and B are both held when HELLO arrives. A later complete write settles
    /// A, so a VFS_CONFLICT naming A's VFS_RESYNC is superseded and ignored; B
    /// is still held, so its conflict remains terminal. The ref_seq mapping, not
    /// just "anything pending", selects the held record.
    func testSupersededConflictIgnoresOnlyTheSettledPath() async throws {
        let pathA = "/dsh/workspace/held-a.txt"
        let pathB = "/dsh/workspace/held-b.txt"
        let socket = ScriptedSocket()
        let log = PendingResyncLog(pending: [pathA, pathB])
        let handler = RecordingChannelHandler(workspaceLog: log)
        let (server, task) = try await startServer(socket: socket, log: log,
                                                   handler: handler)

        let resyncA = try await waitForResync(path: pathA, in: socket)
        let resyncB = try await waitForResync(path: pathB, in: socket)
        XCTAssertNotEqual(resyncA.seq, resyncB.seq)

        // A later complete write of A (op=1, appended clear) replaces the file
        // and settles its held append; B's held record is untouched.
        let settleA = VfsMutationBody(op: .write, mode: 0o644, path: pathA,
                                      data: Data("settled A".utf8))
        try await socket.push(
            .vfsMutation, payload: try VfsMutationPayload.encode(mseq: 1, body: settleA))
        try await waitUntil(timeout: 5) {
            await log.pendingResyncPaths() == [pathB]
        }
        let conflictA = try CanonicalJSON.encode(
            ErrorPayload(code: .vfsConflict, message: "path A superseded",
                         refSeq: resyncA.seq))
        try await socket.push(.error, payload: conflictA)
        try await waitUntil(timeout: 5) {
            let metrics = await server.currentMetrics()
            return metrics.vfsConflictsIgnored == 1
        }
        let closedAfterA = await socket.closed
        XCTAssertFalse(closedAfterA,
                       "the ignored conflict must leave the connection open for B")
        let errorsAfterA = await handler.errorPayloads
        XCTAssertTrue(errorsAfterA.isEmpty,
                      "the superseded A conflict must not reach handler.handleError")

        // B is still held: its conflict is terminal and reaches the handler.
        let conflictB = try CanonicalJSON.encode(
            ErrorPayload(code: .vfsConflict, message: "path B still held",
                         refSeq: resyncB.seq))
        try await socket.push(.error, payload: conflictB)
        try await waitUntil(timeout: 5) {
            await socket.closed
        }
        let errorsAfterB = await handler.errorPayloads
        XCTAssertEqual(errorsAfterB.map(\.code), [.vfsConflict],
                       "only the still-held B conflict is terminal")
        XCTAssertEqual(errorsAfterB.first?.message, "path B still held")
        await task.value
    }
}
