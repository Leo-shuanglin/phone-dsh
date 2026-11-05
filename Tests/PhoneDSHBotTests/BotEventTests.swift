// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class BotEventTests: XCTestCase {
    func testEventEnvelopeRoundTrip() throws {
        let event = BotEvent(type: "routine.run_started", eventId: "evt-1", commitSeq: 1, time: 1_760_000_000_000,
                             origin: .local(source: "scheduler"), traceId: "trace-1",
                             principal: BotPrincipal(botId: "main", sessionId: "gb-main--routine-daily", kind: "routine"),
                             causationId: nil, correlationId: "daily", runId: "run-1", attempt: 1,
                             data: .object(["routineId": .string("daily")]))
        let data = try JSONEncoder().encode(event)
        XCTAssertEqual(try JSONDecoder().decode(BotEvent.self, from: data), event)
    }

    func testUnimplementedStoreFailsClosed() async {
        let store = UnimplementedBotEventLog()
        let event = BotEvent(type: "ui.notice", eventId: "evt-2", time: 1,
                             origin: .local(source: "user"), traceId: "trace-2",
                             principal: BotPrincipal(botId: "main", sessionId: nil, kind: "ipc"),
                             causationId: nil, correlationId: nil, data: .object([:]))
        do {
            _ = try await store.append(event)
            XCTFail("unimplemented store must not accept writes")
        } catch {
            XCTAssertEqual(error as? BotEventStoreError, .unimplemented)
        }
    }
}
