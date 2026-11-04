// SPDX-License-Identifier: MIT
// CCR-2 drain perturbation fixtures (budgets.md §1/§2, wal.md §5.1/§5.2).
//
// Both vectors are read from `spec/v1/golden/perturbations/` and driven through
// the real `PhoneDSHHost`/`HostStateMachine` drain: a connected Worker answers
// an outstanding VFS_RESYNC and the host parks with a closed epoch, or it does
// not and the deadline records the mismatch path/mseq and enters Failed. A
// disconnected Worker is not waited for.

import Foundation
import XCTest
import PhoneDSHController
import PhoneDSHLog
import PhoneDSHWire
@testable import PhoneDSH

final class PhoneDSHResyncDrainTests: XCTestCase {
    private let path = "/dsh/workspace/notes.txt"

    // MARK: - Fixtures

    private func loadFixture(_ id: String) throws -> [String: Any] {
        let url = repoRoot()
            .appendingPathComponent("spec/v1/golden/perturbations/\(id).json")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw FacadeTestError.invalidState("missing perturbation fixture \(id)")
        }
        let data = try Data(contentsOf: url)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw FacadeTestError.invalidState("fixture \(id) is not an object")
        }
        return object
    }

    private func expectation(_ fixture: [String: Any]) throws -> [String: Any] {
        guard let expected = fixture["expected"] as? [String: Any] else {
            throw FacadeTestError.invalidState("fixture has no expected")
        }
        return expected
    }

    // MARK: - Host helpers

    private func makeHost(
        _ fixture: FacadeFixture,
        worker: MockWorker,
        drainDeadline: TimeInterval
    ) throws -> PhoneDSHHost {
        let configuration = try fixture.configuration(helloTimeout: 10,
                                                      drainDeadline: drainDeadline)
        return PhoneDSHHost(configuration: configuration,
                            engineFactory: { _ in MockEngine(worker: worker) })
    }

    private func seedOutstandingResync(worker: MockWorker) async throws {
        try await worker.writeFile(path: path, data: Data("hello".utf8))
        try await worker.waitForAck(1)
        try await worker.appendFile(path: path, from: 6, data: Data(" world".utf8))
        try await worker.waitForResync(path: path)
    }

    private func waitForFacadeState(_ host: PhoneDSHHost,
                                    _ wanted: PhoneDSHState,
                                    timeout: TimeInterval = 8) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await host.state == wanted { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        throw FacadeTestError.timeout
    }

    private func waitForHostState(_ host: PhoneDSHHost,
                                  _ wanted: HostState,
                                  timeout: TimeInterval = 8) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await host.diagnostics().hostState == wanted { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        throw FacadeTestError.timeout
    }

    private func manifest(_ fixture: FacadeFixture) throws -> [String: Any] {
        let url = fixture.workspace.appendingPathComponent("manifest.json")
        guard let object = try JSONSerialization.jsonObject(
            with: Data(contentsOf: url)) as? [String: Any] else {
            throw FacadeTestError.invalidState("manifest is not an object")
        }
        return object
    }

    private func hasEpochClose(_ fixture: FacadeFixture) throws -> Bool {
        let scan = try WALReader.scan(
            directory: fixture.workspace.appendingPathComponent("wal", isDirectory: true))
        return scan.segments.contains(where: \.hasEpochClose)
    }

    private func materializedText(_ fixture: FacadeFixture) throws -> String {
        try String(contentsOf: fixture.materializedFile("workspace/notes.txt"),
                   encoding: .utf8)
    }

    // MARK: - drain-outstanding-resync-answered

    func testDrainWaitsForOutstandingResyncAndParks() async throws {
        let fixture = try loadFixture("drain-outstanding-resync-answered")
        let expected = try expectation(fixture)
        let facade = try FacadeFixture()
        defer { facade.cleanup() }
        let worker = MockWorker()
        let host = try makeHost(facade, worker: worker, drainDeadline: 20)
        try await host.start()
        try await seedOutstandingResync(worker: worker)

        let park = Task { try await host.park() }
        try await waitForFacadeState(host, .draining)
        // The drain must still be waiting: the repair arrives afterwards.
        try await Task.sleep(nanoseconds: 100_000_000)
        let draining = await host.state
        XCTAssertEqual(draining, .draining,
                       "the drain parked before the outstanding VFS_RESYNC was answered")
        try await worker.writeFile(path: path, data: Data("hello world".utf8))
        try await park.value

        let finalState = await host.state
        XCTAssertEqual(finalState, .stopped)
        XCTAssertEqual(try expected["state"] as? String, "Parked")
        let finalDiagnostics = await host.diagnostics()
        XCTAssertEqual(finalDiagnostics.hostState, .parked)
        XCTAssertEqual(try materializedText(facade), "hello world")
        // level_2 / closed_epoch: the drain closed an epoch after the repair.
        XCTAssertTrue(try hasEpochClose(facade))
        let manifest = try manifest(facade)
        let durable = manifest["last_durable_epoch"] as? Int ?? 0
        XCTAssertGreaterThanOrEqual(durable, 1)
        XCTAssertEqual(expected["durability_level"] as? String, "level_2")
        XCTAssertEqual(expected["wal_tail"] as? String, "closed_epoch")
        try await host.stop()
    }

    // MARK: - drain-outstanding-resync-deadline-failed

    func testDrainDeadlineWithoutAResyncAnswerFailsWithPathAndMseq() async throws {
        let fixture = try loadFixture("drain-outstanding-resync-deadline-failed")
        let expected = try expectation(fixture)
        let detail = try XCTUnwrap(expected["failure_detail"] as? [String: Any])
        let expectedPath = try XCTUnwrap(detail["path"] as? String)
        let expectedMseq = try XCTUnwrap(detail["mseq"] as? Int)
        let facade = try FacadeFixture()
        defer { facade.cleanup() }
        let worker = MockWorker()
        let host = try makeHost(facade, worker: worker, drainDeadline: 0.75)
        try await host.start()
        try await seedOutstandingResync(worker: worker)

        do {
            try await host.park()
            XCTFail("a drain deadline with an unrepaired mismatch must not park")
        } catch {
            // The terminal failure is asserted through the state and detail.
        }
        try await waitForHostState(host, .failed)
        let facadeState = await host.state
        XCTAssertEqual(facadeState, .failed)
        let diagnostics = await host.diagnostics()
        let failure = try XCTUnwrap(diagnostics.lastError)
        XCTAssertTrue(failure.contains(expectedPath),
                      "failure detail must name the path: \(failure)")
        XCTAssertTrue(failure.contains("mseq \(expectedMseq)"),
                      "failure detail must name the mseq: \(failure)")
        XCTAssertEqual(try expected["state"] as? String, "Failed")
        // level_1 / intact_open_epoch: no epoch-close record is invented.
        XCTAssertFalse(try hasEpochClose(facade))
        let manifest = try manifest(facade)
        XCTAssertEqual(manifest["last_durable_epoch"] as? Int ?? 0, 0)
        XCTAssertEqual(expected["durability_level"] as? String, "level_1")
        XCTAssertEqual(expected["wal_tail"] as? String, "intact_open_epoch")
        // The failed host's stop attempts the terminal seal and re-reports the
        // same unrepaired mismatch; the state assertion above already holds.
        _ = try? await host.stop()
    }

    // MARK: - disconnected Worker is not waited for

    func testDisconnectedWorkerIsNotWaitedFor() async throws {
        let facade = try FacadeFixture()
        defer { facade.cleanup() }
        let worker = MockWorker()
        // Long deadline: waiting for the disconnected Worker would take it.
        let host = try makeHost(facade, worker: worker, drainDeadline: 5)
        try await host.start()
        try await seedOutstandingResync(worker: worker)
        await worker.disconnect()
        try await Task.sleep(nanoseconds: 250_000_000)

        let started = Date()
        do {
            try await host.park()
            XCTFail("an unrepaired mismatch must fail the drain")
        } catch {}
        let elapsed = Date().timeIntervalSince(started)
        XCTAssertLessThan(elapsed, 3,
                          "the drain must not spend the deadline on a disconnected Worker")
        try await waitForHostState(host, .failed)
        let diagnostics = await host.diagnostics()
        let failure = try XCTUnwrap(diagnostics.lastError)
        XCTAssertTrue(failure.contains(path), failure)
        XCTAssertTrue(failure.contains("mseq 2"), failure)
        _ = try? await host.stop()
    }
}
