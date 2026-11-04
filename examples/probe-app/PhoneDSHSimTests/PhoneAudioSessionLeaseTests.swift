// SPDX-License-Identifier: MIT

import ProbeKit
import XCTest

@MainActor
final class ProbeScenePhaseForwardingTests: XCTestCase {
    func testDeliverInvokesChatAndMatrixOnce() async {
        let log = PhaseLog()
        await ProbeScenePhaseForwarding.deliver(
            "background",
            chat: { phase in log.add("chat:\(phase)") },
            backgroundMatrix: { phase in log.add("matrix:\(phase)") })
        XCTAssertEqual(log.snapshot.filter { $0.hasPrefix("chat:") }, ["chat:background"])
        XCTAssertEqual(log.snapshot.filter { $0.hasPrefix("matrix:") }, ["matrix:background"])
    }

    func testDeliverForwardsEachPhase() async {
        let log = PhaseLog()
        for phase in ["inactive", "background", "active"] {
            await ProbeScenePhaseForwarding.deliver(
                phase,
                chat: { log.add($0) },
                backgroundMatrix: { _ in })
        }
        XCTAssertEqual(log.snapshot, ["inactive", "background", "active"])
    }
}

@MainActor
final class PhoneAudioSessionLeaseTests: XCTestCase {
    override func setUp() {
        super.setUp()
        PhoneAudioSessionLease.endKeepAlive()
    }

    override func tearDown() {
        PhoneAudioSessionLease.endKeepAlive()
        super.tearDown()
    }

    func testReleaseDeactivatesWhenKeepAliveIsIdle() {
        let deactivated = Counter()
        PhoneAudioSessionLease.releaseMicrophoneUser { deactivated.increment() }
        XCTAssertEqual(deactivated.value, 1)
        XCTAssertFalse(PhoneAudioSessionLease.isKeepAliveHeld)
    }

    func testReleaseReclaimsWhileKeepAliveHoldsTheSession() {
        let deactivated = Counter()
        let reclaimed = Counter()
        PhoneAudioSessionLease.beginKeepAlive { reclaimed.increment() }
        PhoneAudioSessionLease.releaseMicrophoneUser { deactivated.increment() }
        XCTAssertEqual(deactivated.value, 0)
        XCTAssertEqual(reclaimed.value, 1)
        XCTAssertTrue(PhoneAudioSessionLease.isKeepAliveHeld)
    }

    func testEndingKeepAliveRestoresDeactivation() {
        let deactivated = Counter()
        PhoneAudioSessionLease.beginKeepAlive {}
        PhoneAudioSessionLease.endKeepAlive()
        PhoneAudioSessionLease.releaseMicrophoneUser { deactivated.increment() }
        XCTAssertEqual(deactivated.value, 1)
        XCTAssertFalse(PhoneAudioSessionLease.isKeepAliveHeld)
    }
}

private final class PhaseLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String] = []

    func add(_ item: String) {
        lock.lock()
        items.append(item)
        lock.unlock()
    }

    var snapshot: [String] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var stored = 0

    func increment() {
        lock.lock()
        stored += 1
        lock.unlock()
    }

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
}
