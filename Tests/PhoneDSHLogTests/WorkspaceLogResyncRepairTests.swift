// SPDX-License-Identifier: MIT
// CCR-2 boot-resync repair through the real WorkspaceLog: recovery leaves a
// retained tail whose appended base does not match the materialized copy, the
// host keeps the path/mseq for the post-HELLO VFS_RESYNC, and the Worker's
// complete write settles it against the on-disk retained sequence (wal.md
// §5.1, §10; channel.md §9 step 6). GAP-13's channel-side re-request is tested
// separately; this pins the log state it reads.

import Foundation
import XCTest
@testable import PhoneDSHLog
import PhoneDSHWire

final class WorkspaceLogResyncRepairTests: XCTestCase {
    private let path = "/dsh/workspace/notes.txt"

    private func fixtureRoot(_ test: XCTestCase) throws -> (URL, WorkspacePaths) {
        let root = LogTestSupport.root(for: test)
        let paths = WorkspacePaths(root: root)
        try paths.createDirectories()
        let target = paths.materializedDirectory
            .appendingPathComponent("workspace/notes.txt")
        try FileManager.default.createDirectory(
            at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("HELLO".utf8).write(to: target)
        return (root, paths)
    }

    private func seedRetainedTail(root: URL,
                                  paths: WorkspacePaths,
                                  bootID: Data) async throws {
        let bootHex = bootID.map { String(format: "%02x", $0) }.joined()
        let store = ManifestStore(paths: paths)
        // The bootstrap checkpoint lets WALWriter allocate mseq 5; the real
        // checkpoint (watermark 4) is written after the segments exist.
        try store.write(Manifest(bootID: bootHex, materializedUptoMseq: 4,
                                 lastMseq: 4, lastDurableEpoch: 0, segments: []))
        do {
            let writer = try WALWriter(
                configuration: LogTestSupport.configuration(root: root),
                bootID: bootID)
            _ = try await writer.append(LogTestSupport.append(path, from: 6, data: " world"))
            try await writer.flush()
        }
        let scan = try WALReader.scan(directory: paths.walDirectory)
        try store.write(Manifest(
            bootID: bootHex,
            materializedUptoMseq: 4,
            lastMseq: max(4, scan.lastMutationMseq),
            lastDurableEpoch: 0,
            segments: WALSegmentLifecycle.manifestSegments(scan.segments.map {
                WALSegmentLifecycleRecord(segment: $0)
            })))
    }

    func testRecoveryResyncIsRepairedByTheWorkersCompleteWrite() async throws {
        let (root, paths) = try fixtureRoot(self)
        let bootID = LogTestSupport.bootID()
        try await seedRetainedTail(root: root, paths: paths, bootID: bootID)
        let log = try WorkspaceLog(
            configuration: LogTestSupport.configuration(root: root),
            bootID: bootID)

        let pending = await log.pendingResyncPaths()
        XCTAssertEqual(pending, [path])
        let holds = await log.pendingResyncHolds()
        XCTAssertEqual(holds, [MaterializationHold(path: path, mseq: 5)])
        let positions = await log.positions()
        XCTAssertEqual(positions.ackedMseq, 5)

        // The Worker answers the VFS_RESYNC with a complete write at mseq 6.
        let repair = LogTestSupport.write(path, data: "fixed")
        let mseq = try await log.append(repair)
        XCTAssertEqual(mseq, 6)
        let watermark = try await log.materialize()
        XCTAssertEqual(watermark, 6)
        let after = await log.pendingResyncPaths()
        XCTAssertTrue(after.isEmpty)
        XCTAssertEqual(try String(contentsOf: paths.materializedDirectory
            .appendingPathComponent("workspace/notes.txt"), encoding: .utf8), "fixed")

        // The epoch close now succeeds (level 2) and the manifest names 6.
        let ack = try await log.closeEpoch()
        XCTAssertEqual(ack.level, .epochFsync)
        XCTAssertEqual(ack.uptoMseq, 6)
        let manifest = try ManifestStore(paths: paths).read()
        XCTAssertEqual(manifest?.materializedUptoMseq, 6)
        XCTAssertGreaterThanOrEqual(manifest?.lastDurableEpoch ?? 0, 1)
    }

    func testRecoveryResyncRebuildsWhenTheRetainedHistoryStartsAtOne() async throws {
        let (root, paths) = try fixtureRoot(self)
        let bootID = LogTestSupport.bootID()
        let bootHex = bootID.map { String(format: "%02x", $0) }.joined()
        let store = ManifestStore(paths: paths)
        // The copy is ahead of the WAL prefix: a clean rebuild from mseq 1.
        try store.write(Manifest(bootID: bootHex, materializedUptoMseq: 0,
                                 lastMseq: 0, lastDurableEpoch: 0, segments: []))
        do {
            let writer = try WALWriter(
                configuration: LogTestSupport.configuration(root: root),
                bootID: bootID)
            _ = try await writer.append(LogTestSupport.write(path, data: "hello"))
            _ = try await writer.append(LogTestSupport.append(path, from: 5, data: " world"))
            _ = try await writer.append(LogTestSupport.append(path, from: 11, data: "!"))
            try await writer.flush()
        }
        try Data("HELLO WORLD!".utf8).write(to: paths.materializedDirectory
            .appendingPathComponent("workspace/notes.txt"))
        let scan = try WALReader.scan(directory: paths.walDirectory)
        try store.write(Manifest(
            bootID: bootHex,
            materializedUptoMseq: 1,
            lastMseq: scan.lastMutationMseq,
            lastDurableEpoch: 0,
            segments: WALSegmentLifecycle.manifestSegments(scan.segments.map {
                WALSegmentLifecycleRecord(segment: $0)
            })))

        let log = try WorkspaceLog(
            configuration: LogTestSupport.configuration(root: root),
            bootID: bootID)
        let report = try await log.recover()
        XCTAssertTrue(report.rebuiltFromWal)
        XCTAssertTrue(report.needsResync.isEmpty)
        XCTAssertEqual(report.materializedUptoMseq, 3)
        XCTAssertEqual(try String(contentsOf: paths.materializedDirectory
            .appendingPathComponent("workspace/notes.txt"), encoding: .utf8),
                       "hello world!")
    }
}
