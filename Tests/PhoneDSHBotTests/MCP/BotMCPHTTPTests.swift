// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class BotMCPHTTPTests: XCTestCase {
    func testVolatileContextUsesPhoneAppMCPNameAndRealDomainBlocks() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try SQLiteDomainStore(fileURL: directory.appendingPathComponent("domain.sqlite"), now: { 1_000 })
        let memory = BotMemory(
            store: SQLiteBotMemoryFactStore(eventLog: store, now: { 1_000 }),
            dataRoot: directory.path, botID: "main", now: { 1_000 })
        let machine = RoutineMachine(store: SQLiteRoutineStore(domainStore: store))
        _ = try await machine.create(
            botId: "main", title: "Morning", prompt: "report", schedule: .interval(everyMinutes: 60), now: 1_000)
        let provider = BotDomainVolatileContextProvider(memory: memory, routines: machine, now: { 1_000 })
        let fixture = try makeBotMCPFixture(volatileProvider: provider)
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-main", kind: .chat)
        let server = BotMCPHTTPServer(handler: BotMCPHandler(runtime: fixture.runtime), token: "test-token-1234567890")
        let url = try await server.start()
        defer { server.stop() }
        let arguments = BotJSONValue.object(["sessionId": .string("gb-main")])
        _ = try await request(url.deletingLastPathComponent().appendingPathComponent("identity/register"), body: .object([
            "runtimeId": .string(BotMCPRuntime.defaultRuntimeID), "sessionId": .string("gb-main"),
            "callId": .string("volatile-http"), "name": .string("BotVolatileContext"),
            "argsDigest": .string(try BotArgumentsDigest.string(for: arguments)),
        ]), token: server.token)
        let response = try await request(url, body: .object([
            "jsonrpc": .string("2.0"), "id": .string("volatile-http"), "method": .string("tools/call"),
            "params": .object(["name": .string("BotVolatileContext"), "arguments": arguments]),
        ]), token: server.token)
        let blocks = try XCTUnwrap(response["result"]?["structuredContent"]?["blocks"])
        XCTAssertTrue(blocks["persona"]?.stringValue?.contains("大肥鱼") == true)
        XCTAssertTrue(blocks["automation"]?.stringValue?.contains("Morning") == true)
        XCTAssertTrue(blocks["memory"]?.stringValue?.contains("记忆") == true)
        XCTAssertEqual(response["result"]?["structuredContent"]?["sessionId"], .string("gb-main"))
    }

    func testRealStreamableHTTPDiscoversOnlyP0AndExecutesClaimedCard() async throws {
        let fixture = try makeBotMCPFixture()
        defer { fixture.cleanup() }
        _ = try await fixture.runtime.issueSession(botId: "main", sessionId: "gb-main", kind: .chat)
        let handler = BotMCPHandler(runtime: fixture.runtime)
        let server = BotMCPHTTPServer(handler: handler, token: "test-token-1234567890")
        let url = try await server.start()
        defer { server.stop() }

        let initialized = try await request(url, body: .object([
            "jsonrpc": .string("2.0"), "id": .number(1), "method": .string("initialize"),
            "params": .object(["protocolVersion": .string("2025-06-18")]),
        ]), token: server.token)
        XCTAssertEqual(initialized["result"]?["protocolVersion"], .string("2025-06-18"))

        let listed = try await request(url, body: .object([
            "jsonrpc": .string("2.0"), "id": .number(2), "method": .string("tools/list"),
        ]), token: server.token)
        let tools = try XCTUnwrap(listed["result"]?["tools"]?.arrayValue)
        XCTAssertEqual(tools.count, 17)
        XCTAssertFalse(tools.contains { $0["name"]?.stringValue == "show_table" })
        XCTAssertTrue(tools.contains { $0["name"]?.stringValue == "BotVolatileContext" })

        let arguments = BotJSONValue.object([
            "question": .string("Continue?"),
            "options": .array([.object(["label": .string("Yes")]), .object(["label": .string("No")])]),
        ])
        let registrationURL = url.deletingLastPathComponent().appendingPathComponent("identity/register")
        let registered = try await request(registrationURL, body: .object([
            "runtimeId": .string(BotMCPRuntime.defaultRuntimeID), "sessionId": .string("gb-main"),
            "callId": .string("http-card"), "name": .string("ask_user"),
            "argsDigest": .string(try BotArgumentsDigest.string(for: arguments)),
        ]), token: server.token)
        XCTAssertEqual(registered["ok"], .bool(true))

        let called = try await request(url, body: .object([
            "jsonrpc": .string("2.0"), "id": .string("http-card"), "method": .string("tools/call"),
            "params": .object(["name": .string("ask_user"), "arguments": arguments]),
        ]), token: server.token)
        XCTAssertEqual(called["result"]?["structuredContent"]?["card"]?["cardType"], .string("question"))

        let denied = try await request(url, body: .object([
            "jsonrpc": .string("2.0"), "id": .number(3), "method": .string("ping"),
        ]), token: "wrong")
        XCTAssertEqual(denied["error"]?["code"], .number(401))
    }

    private func request(_ url: URL, body: BotJSONValue, token: String) async throws -> BotJSONValue {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try body.encoded()
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { return .object(["error": .object(["code": .number(401)])]) }
        XCTAssertEqual(status, 200)
        return try BotJSONValue.parse(data)
    }
}
