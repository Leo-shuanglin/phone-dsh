// SPDX-License-Identifier: MIT

import Foundation
import UIKit
import XCTest
import PhoneDSH
import PhoneDSHAgentCore
import PhoneDSHBot
import PhoneDSHEngine
import PhoneDSHHarness
@testable import BotE2EApp

@MainActor
final class BotCompositionE2ETests: XCTestCase {
    func testProductionCompositionUsesRealHostAndWorker() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("bot-production-composition-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = try SQLiteDomainStore(fileURL: root.appendingPathComponent("domain.sqlite"))
        let routineStore = SQLiteRoutineStore(domainStore: store)
        let environment = BotProductionEnvironment(
            domain: store, botID: "main", sessionID: "main", routineStore: routineStore)
        let launch = try await BotRuntimeComposition.prepare(environment: environment)
        let mock = MockModelUpstream()
        mock.script = .generic(command: "printf BOT_TOOL", toolText: "BOT_TOOL", finalText: "BOT_COMPOSITION_OK")
        let mockURL = try mock.start()
        defer { mock.stop() }
        guard let worker = Bundle.main.url(forResource: "worker", withExtension: nil) else {
            return XCTFail("the real packed Worker is absent")
        }
        let bundle = PhoneDSHBundleLocation(staticDirectory: worker)
        XCTAssertTrue(bundle.exists())
        let mcpPolicy = MCPEgressPolicy()
        try mcpPolicy.grantBotRuntimeEndpoint(
            try XCTUnwrap(launch.composition.server.url), token: launch.composition.server.token)
        let baseCredentials = StaticCredentialProvider([
            CredentialScope(service: "127.0.0.1"): Credential(
                kind: .apiKey(header: "x-api-key"), value: "composition-test-fixture"),
        ])
        var configuration = PhoneDSHConfiguration(
            workspaceRoot: root.appendingPathComponent("workspace", isDirectory: true),
            token: "", origin: URL(string: "http://127.0.0.1:1")!, bundle: bundle,
            allowedModelHosts: [mockURL.absoluteString],
            credentialProvider: PhoneMCPCredentialProvider(policy: mcpPolicy, base: baseCredentials),
            egressAuthorizer: mcpPolicy, drainDeadline: 2, helloTimeout: 60)
        configuration.engine.bootTimeout = 60
        configuration.engine.environment = launch.workerEnvironment.merging([
            "DEEPSEEK_BASE_URL": mockURL.absoluteString,
            "DEEPSEEK_API_KEY": "composition-test-placeholder",
            "DSH_TELEMETRY_DISABLED": "1",
        ]) { _, new in new }
        let app = try XCTUnwrap(UIApplication.shared.delegate as? AppDelegate)
        let engineHolder = BotCompositionEngineHolder()
        let host = PhoneDSHHost(configuration: configuration) { configuration in
            await engineHolder.attach(configuration, to: app.anchor)
        }
        try await launch.composition.start(host: host)
        let mainSessionID = await launch.composition.remote.mainSessionIdentifier()
        let sessionID = try XCTUnwrap(mainSessionID)
        XCTAssertFalse(sessionID.isEmpty)

        let direct = try await launch.composition.remote.sendUserMessage("请给出结果")
        XCTAssertEqual(direct.status, "completed", direct.error ?? "no error")
        XCTAssertTrue(direct.text.contains("BOT_COMPOSITION_OK"),
                      "text=\(direct.text); requests=\(mock.requestCount)")
        let rows = BotProductionRowService(environment: environment)
        let action = BotUserAction(nonce: "composition-chat", botID: "main", kind: .chat, text: "请给出结果")
        let accepted = try await rows.perform(action)
        XCTAssertEqual(accepted, .accepted(eventID: "action:composition-chat"))
        let events = try await BotProductionEventReader.all(domain: store)
        let replies = events.filter { $0.type == "conversation.assistant_message" }
        XCTAssertTrue(replies.contains { $0.data["text"]?.stringValue?.contains("BOT_COMPOSITION_OK") == true })
        XCTAssertTrue(events.contains { $0.type == "ledger.usage_recorded" })

        let unauthorized = try await post(
            url: try XCTUnwrap(launch.composition.server.url), token: "wrong-token",
            body: Data(#"{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"#.utf8))
        XCTAssertEqual(unauthorized.status, 401)
        let list = try await post(
            url: try XCTUnwrap(launch.composition.server.url), token: launch.composition.server.token,
            body: Data(#"{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"#.utf8))
        XCTAssertEqual(list.status, 200)
        XCTAssertNotNil(list.json["result"]?["tools"]?.arrayValue)

        _ = try await launch.composition.runtime.registerIdentity(
            runtimeId: BotMCPRuntime.defaultRuntimeID, sessionId: sessionID,
            callId: "volatile-context", name: BotToolCatalog.volatileContextName, arguments: .object([:]))
        let volatile = await launch.composition.handler.rpc(
            method: "tools/call",
            params: .object([
                "name": .string(BotToolCatalog.volatileContextName), "arguments": .object([:]),
            ]), id: .string("volatile-context"))
        XCTAssertEqual(volatile["result"]?["structuredContent"]?["schemaVersion"]?.intValue, 1)
        XCTAssertTrue(volatile["result"]?["structuredContent"]?["blocks"]?["persona"]?.stringValue?
            .contains("大肥鱼") == true)

        mock.script = .sequence(
            tools: [MockModelToolCall(
                name: "mcp__dshbot__Task",
                arguments: .object(["task_title": .string("真实 Task"), "prompt": .string("完成委派")]))],
            finalText: "TASK_PARENT_DONE")
        let taskAction = BotUserAction(nonce: "composition-task", botID: "main", kind: .chat, text: "委派一个任务")
        let taskResult = try await rows.perform(taskAction)
        XCTAssertEqual(taskResult, .accepted(eventID: "action:composition-task"))
        let taskEvents = try await BotProductionEventReader.all(domain: store)
        let task = try XCTUnwrap(taskEvents.last(where: { $0.type == "task.created" }))
        let taskSessionID = try XCTUnwrap(task.data["sessionId"]?.stringValue)
        XCTAssertNotEqual(taskSessionID, sessionID)
        XCTAssertFalse(taskSessionID.isEmpty)

        mock.script = .generic(command: "printf BOT_TOOL", toolText: "BOT_TOOL", finalText: "BOT_ROUTINE_OK")
        let routine = try await launch.composition.runtime.routineMachine.create(
            botId: "main", title: "生产组合根 routine", prompt: "生成固定结果",
            schedule: .interval(everyMinutes: 1, timezone: "UTC"),
            now: Int64(Date().timeIntervalSince1970 * 1_000) - 120_000, id: "composition-routine")
        XCTAssertTrue(routine.enabled)
        try await launch.composition.scheduler.tick()
        await launch.composition.scheduler.waitForIdle()
        _ = try await launch.composition.outbox.runNext()
        let effects = try await store.effects()
        XCTAssertTrue(effects.contains { $0.effect.kind == BotRoutineOutbox.kind && $0.state == .acked })
        let routineEvents = try await BotProductionEventReader.all(domain: store)
        XCTAssertTrue(routineEvents.contains {
            $0.type == "conversation.automation_run" && $0.data["text"]?.stringValue == "BOT_ROUTINE_OK"
        })
        await launch.composition.scheduler.stop()
        await launch.composition.outbox.stop()

        let requestCount = mock.requestCount
        let duplicate = try await rows.perform(action)
        XCTAssertEqual(duplicate, .duplicate(eventID: "action:composition-chat"))
        XCTAssertEqual(mock.requestCount, requestCount)

        try await engineHolder.engine?.restart(reason: .planned)
        let resumed = try await launch.composition.remote.sendUserMessage("重启后的继续对话")
        XCTAssertEqual(resumed.status, "completed")
        XCTAssertTrue(resumed.text.contains("BOT_ROUTINE_OK"))
        let recoveredDuplicate = try await rows.perform(action)
        XCTAssertEqual(recoveredDuplicate, .duplicate(eventID: "action:composition-chat"))
        await launch.composition.stop()
        try await host.stop()
        try await store.close()
    }

    private func post(url: URL, token: String, body: Data) async throws -> (status: Int, json: BotJSONValue) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (data, response) = try await URLSession.shared.data(for: request)
        return ((response as? HTTPURLResponse)?.statusCode ?? -1,
                (try? BotJSONValue.parse(data)) ?? .null)
    }
}

@MainActor
private final class BotCompositionEngineHolder {
    var engine: WebEngine?

    func attach(_ configuration: WebEngineConfiguration, to anchor: UIView) -> WebEngine {
        let engine = WebEngine(configuration: configuration)
        engine.attach(to: anchor)
        self.engine = engine
        return engine
    }
}
