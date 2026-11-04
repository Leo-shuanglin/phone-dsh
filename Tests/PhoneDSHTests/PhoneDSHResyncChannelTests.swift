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

    init(pending: [String]) {
        self.pending = pending
    }

    func setPending(_ paths: [String]) {
        pending = paths
    }

    func pendingResyncPaths() async -> [String] { pending }

    func append(_ mutation: VfsMutationBody) async throws -> UInt64 { 1 }

    nonisolated func acks() -> AsyncStream<LoopbackWorkspaceAck> {
        AsyncStream { $0.finish() }
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
                             log: PendingResyncLog) async throws -> (ChannelServer, Task<Void, Never>) {
        let server = ChannelServer(socket: socket, state: hostState(), workspaceLog: log)
        try await server.installHandler(DefaultChannelHandling(outbound: server))
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
        let (server, task) = try await startServer(socket: socket, log: log)

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

        // The same conflict while the held record is still pending is terminal
        // (the Worker closes the channel after its ERROR).
        await log.setPending([path])
        let terminal = try CanonicalJSON.encode(
            ErrorPayload(code: .vfsConflict, message: "still held",
                         refSeq: resync.seq))
        try await socket.push(.error, payload: terminal)
        try await waitUntil(timeout: 5) {
            await socket.closed
        }
        await task.value
    }
}
