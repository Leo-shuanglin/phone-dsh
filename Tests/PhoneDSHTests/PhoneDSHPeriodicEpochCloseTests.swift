// SPDX-License-Identifier: MIT
// GAP-12: the D7 periodic epoch close while `Running`.
//
// wal.md §5.2 requires a close every 30-60 s. The iOS host had the constant but
// never called it. These tests drive the real host tick: a clean tick closes an
// epoch (level 2 becomes durable), while an outstanding/held VFS_RESYNC skips
// the tick with the path/mseq in the recorded reason instead of failing.

import Foundation
import XCTest
import PhoneDSHController
import PhoneDSHLog
import PhoneDSHWire
@testable import PhoneDSH

private actor PeriodicOutcomeCollector {
    private(set) var outcomes: [PeriodicEpochCloseOutcome] = []

    func append(_ outcome: PeriodicEpochCloseOutcome) {
        outcomes.append(outcome)
    }
}

final class PhoneDSHPeriodicEpochCloseTests: XCTestCase {
    private let path = "/dsh/workspace/notes.txt"

    private func makeHost(_ fixture: FacadeFixture,
                          worker: MockWorker) throws -> PhoneDSHHost {
        let configuration = try fixture.configuration(helloTimeout: 10)
        return PhoneDSHHost(configuration: configuration,
                            engineFactory: { _ in MockEngine(worker: worker) })
    }

    private func manifest(_ fixture: FacadeFixture) throws -> [String: Any] {
        let url = fixture.workspace.appendingPathComponent("manifest.json")
        guard let object = try JSONSerialization.jsonObject(
            with: Data(contentsOf: url)) as? [String: Any] else {
            throw FacadeTestError.invalidState("manifest is not an object")
        }
        return object
    }

    private func seedOutstandingResync(worker: MockWorker) async throws {
        try await worker.writeFile(path: path, data: Data("hello".utf8))
        try await worker.waitForAck(1)
        try await worker.appendFile(path: path, from: 6, data: Data(" world".utf8))
        try await worker.waitForResync(path: path)
    }

    func testRunningPeriodicTickClosesAnEpoch() async throws {
        let fixture = try FacadeFixture()
        defer { fixture.cleanup() }
        let worker = MockWorker()
        let host = try makeHost(fixture, worker: worker)
        let collector = PeriodicOutcomeCollector()
        await host.setPeriodicEpochCloseIntervalForTesting(0.05)
        await host.setPeriodicEpochCloseObserverForTesting { outcome in
            Task { await collector.append(outcome) }
        }
        try await host.start()
        try await worker.writeFile(path: path, data: Data("durable".utf8))
        try await worker.waitForAck(1)

        try await waitUntil(timeout: 8) {
            await collector.outcomes.contains {
                if case .closed = $0 { return true }
                return false
            }
        }
        let outcomes = await collector.outcomes
        XCTAssertTrue(outcomes.contains {
            if case .closed(let upto) = $0 { return upto >= 1 }
            return false
        }, "the Running tick must close the accepted tail: \(outcomes)")
        let manifest = try manifest(fixture)
        XCTAssertGreaterThanOrEqual(manifest["last_durable_epoch"] as? Int ?? 0, 1)
        let state = await host.state
        XCTAssertEqual(state, .running, "a clean periodic close does not change Running")
        try await host.stop()
    }

    func testHeldResyncSkipsThePeriodicTickAndRecordsTheReason() async throws {
        let fixture = try FacadeFixture()
        defer { fixture.cleanup() }
        let worker = MockWorker()
        let host = try makeHost(fixture, worker: worker)
        try await host.start()
        try await seedOutstandingResync(worker: worker)
        let before = try manifest(fixture)["last_durable_epoch"] as? Int ?? 0

        let skipped = await host.performPeriodicEpochCloseForTesting()
        guard case .skipped(let reason) = skipped else {
            return XCTFail("expected a skipped periodic close, got \(skipped)")
        }
        XCTAssertTrue(reason.contains(path), reason)
        XCTAssertTrue(reason.contains("mseq 2"), reason)
        let after = try manifest(fixture)["last_durable_epoch"] as? Int ?? 0
        XCTAssertEqual(after, before, "a skipped close must not write an epoch")
        let stillPending = await host.diagnostics().materializedUptoMseq
        XCTAssertEqual(stillPending, 1)

        // Once the repair arrives, the next tick closes normally.
        try await worker.writeFile(path: path, data: Data("hello world".utf8))
        try await worker.waitForAck(3)
        let closed = await host.performPeriodicEpochCloseForTesting()
        guard case .closed(let upto) = closed else {
            return XCTFail("expected a closed periodic epoch, got \(closed)")
        }
        XCTAssertGreaterThanOrEqual(upto, 3)
        let durable = try manifest(fixture)["last_durable_epoch"] as? Int ?? 0
        XCTAssertGreaterThanOrEqual(durable, 1)
        try await host.stop()
    }
}
