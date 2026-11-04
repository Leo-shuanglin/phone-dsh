// SPDX-License-Identifier: MIT
// CCR-2 Failed -> Recovering order (budgets.md §2):
// recover -> pack overlay -> start Worker -> HELLO -> VFS_RESYNC -> settle.
// This row does not close an epoch; the next close is a periodic close or a
// drain. The test fails the live host on an unrepaired base mismatch, resets it,
// and proves the new Worker HELLO precedes the re-requested VFS_RESYNC and that
// the reset wrote no epoch-close record.

import Foundation
import XCTest
import PhoneDSHController
import PhoneDSHLog
import PhoneDSHWire
@testable import PhoneDSH

final class PhoneDSHFailedResetResyncTests: XCTestCase {
    private let path = "/dsh/workspace/notes.txt"

    private func hasEpochClose(_ fixture: FacadeFixture) throws -> Bool {
        let scan = try WALReader.scan(
            directory: fixture.workspace.appendingPathComponent("wal", isDirectory: true))
        return scan.segments.contains(where: \.hasEpochClose)
    }

    private func waitForTimelineResyncAfterHello(_ worker: MockWorker,
                                                 after index: Int,
                                                 timeout: TimeInterval = 10) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let events = await worker.timelineEvents()
            if let hello = events.indices.dropFirst(index).last(where: { events[$0] == "hello" }),
               let resync = events.indices.dropFirst(index).last(where: { events[$0] == "resync" }),
               resync > hello {
                return
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        throw FacadeTestError.timeout
    }

    func testFailedResetPacksHelloResyncsAndDoesNotCloseAnEpoch() async throws {
        let fixture = try FacadeFixture()
        defer { fixture.cleanup() }
        let worker = MockWorker()
        let configuration = try fixture.configuration(helloTimeout: 10,
                                                      drainDeadline: 0.75)
        let host = PhoneDSHHost(configuration: configuration,
                                engineFactory: { _ in MockEngine(worker: worker) })
        try await host.start()

        try await worker.writeFile(path: path, data: Data("hello".utf8))
        try await worker.waitForAck(1)
        try await worker.appendFile(path: path, from: 6, data: Data(" world".utf8))
        try await worker.waitForResync(path: path)
        // The deadline fails the live host, keeping the runtime (and therefore
        // the pending repair) for the explicit reset.
        _ = try? await host.park()
        try await waitUntil(timeout: 8) {
            let state = await host.state
            return state == .failed
        }
        let bootsBeforeReset = await host.diagnostics().workerHelloCount
        let timelineBeforeReset = await worker.timelineEvents().count
        XCTAssertFalse(try hasEpochClose(fixture),
                       "the Failed drain must not invent an epoch close")

        let reset = Task { try await host.reset() }
        try await waitForTimelineResyncAfterHello(
            worker, after: timelineBeforeReset)
        // The re-request targets the still-held path; then the Worker answers
        // with the complete write at the next normal mseq (3) and it settles.
        let requested = await worker.resyncPaths()
        XCTAssertTrue(requested.contains(path))
        try await worker.writeFile(path: path, data: Data("hello world".utf8))
        do {
            try await worker.waitForAck(3)
        } catch {
            let timeline = await worker.timelineEvents()
            let hostError = await worker.hostErrorText()
            XCTFail("repair was not acknowledged: timeline=\(timeline) hostError=\(String(describing: hostError))")
            throw error
        }
        try await reset.value
        try await waitUntil(timeout: 8) {
            let state = await host.state
            return state == .running
        }
        let diagnostics = await host.diagnostics()
        XCTAssertGreaterThan(diagnostics.workerHelloCount, bootsBeforeReset)
        XCTAssertEqual(diagnostics.materializedUptoMseq, 3)
        XCTAssertEqual(try String(contentsOf: fixture.materializedFile("workspace/notes.txt"),
                                  encoding: .utf8), "hello world")
        XCTAssertFalse(try hasEpochClose(fixture),
                       "Failed -> Recovering does not close an epoch")
        try await host.stop()
    }
}
