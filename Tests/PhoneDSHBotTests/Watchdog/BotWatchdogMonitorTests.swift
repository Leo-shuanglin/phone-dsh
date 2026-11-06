// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class BotWatchdogMonitorTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("phonedsh-bot-watchdog-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    func testFiveBaseChecksEmitOnlyOncePerUnitAndNeverEndUnit() async throws {
        let store = try SQLiteDomainStore(fileURL: directory.appendingPathComponent("domain.sqlite"), now: { 100 })
        let config = BotWatchdogConfig(
            heartbeatTimeoutMs: 10, turnDeadlineMs: 10,
            repeatedToolCalls: 2, repeatedToolErrors: 2,
            maxUnitTokens: 10, maxUnitCostUsd: 1, tickMs: 0)
        let monitor = BotWatchdogMonitor(eventLog: store, config: config, now: { 100 })
        await assertEqualAsync(await monitor.startUnit(kind: .turn, id: "turn-1", botId: "main", at: 100), [])

        var flags = await monitor.observeToolCall(kind: .turn, id: "turn-1", name: "read", argumentsDigest: "digest", callId: "call-1")
        XCTAssertEqual(flags.map(\.check), [])
        flags = await monitor.observeToolCall(kind: .turn, id: "turn-1", name: "read", argumentsDigest: "digest", callId: "call-2")
        XCTAssertEqual(flags.map(\.check), [.repeatedToolSignature])
        await assertEqualAsync(await monitor.observeToolCall(kind: .turn, id: "turn-1", name: "read", argumentsDigest: "digest"), [])

        _ = await monitor.observeToolResult(kind: .turn, id: "turn-1", status: "error", error: "same", callId: "call-1")
        flags = await monitor.observeToolResult(kind: .turn, id: "turn-1", status: "error", error: "same", callId: "call-2")
        XCTAssertEqual(flags.map(\.check), [.repeatedToolError])
        await assertEqualAsync(await monitor.observeToolResult(kind: .turn, id: "turn-1", status: "error", error: "same"), [])

        flags = await monitor.observeUsage(kind: .turn, id: "turn-1", tokens: 11, costUsd: 2)
        XCTAssertEqual(flags.map(\.check), [.quota])
        await assertEqualAsync(await monitor.observeUsage(kind: .turn, id: "turn-1", tokens: 100, costUsd: 100), [])

        flags = await monitor.tick(at: 111)
        XCTAssertEqual(Set(flags.map(\.check)), [.deadline, .heartbeat])
        await assertEqualAsync(await monitor.tick(at: 112), [])
        let observedUnit = await monitor.unit(kind: .turn, id: "turn-1")
        let unit = try XCTUnwrap(observedUnit)
        XCTAssertEqual(unit.tokens, 111)
        XCTAssertEqual(unit.costUsd, 102)
        let events = try await store.events(after: 0, limit: 20)
        XCTAssertEqual(events.count, 5)
        XCTAssertTrue(events.allSatisfy { $0.type == "watchdog.flagged" && $0.origin == .local(source: "watchdog") })
        XCTAssertEqual(Set(events.map(\.eventId)).count, 5)
        try await store.close()
    }

    func testThreeMobileEventsEmitOnceAndStayDurableAcrossReopen() async throws {
        let file = directory.appendingPathComponent("domain.sqlite")
        let store = try SQLiteDomainStore(fileURL: file, now: { 1 })
        let config = BotWatchdogConfig(heartbeatTimeoutMs: 0, turnDeadlineMs: 0, tickMs: 0)
        let monitor = BotWatchdogMonitor(eventLog: store, config: config, now: { 1 })
        for event in BotMobileWatchdogEvent.allCases {
            var flags = await monitor.observeMobile(event, kind: .turn, id: "turn-mobile", at: 1)
            XCTAssertEqual(flags.map(\.check), [event.check])
            flags = await monitor.observeMobile(event, kind: .turn, id: "turn-mobile", at: 2)
            XCTAssertEqual(flags, [])
        }
        await assertEqualAsync(try await store.headCommitSeq(), 3)
        try await store.close()

        let reopened = try SQLiteDomainStore(fileURL: file, now: { 2 })
        let reopenedMonitor = BotWatchdogMonitor(eventLog: reopened, config: config, now: { 2 })
        _ = await reopenedMonitor.observeMobile(.thermalPressure, kind: .turn, id: "turn-mobile", at: 2)
        await assertEqualAsync(try await reopened.headCommitSeq(), 3)
        let events = try await reopened.events(after: 0, limit: 10)
        XCTAssertEqual(events.map(\.type), Array(repeating: "watchdog.flagged", count: 3))
        let checks = events.map { $0.data.objectValue?["check"] }
        XCTAssertEqual(checks, [.string("webcontent_terminated"), .string("thermal_pressure"), .string("battery_pressure")])
        try await reopened.close()
    }
}

private extension BotJSONValue {
    var objectValue: [String: BotJSONValue]? {
        if case let .object(value) = self { return value }
        return nil
    }
}
