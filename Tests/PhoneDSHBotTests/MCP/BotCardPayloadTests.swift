// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class BotCardPayloadTests: XCTestCase {
    private struct Vector: Decodable {
        let valid: [Call]
        let invalid: [Call]
    }
    private struct Call: Decodable {
        let tool: String
        let args: BotJSONValue
        let result: BotJSONValue
    }

    func testP0CardsMatchSharedGolden() throws {
        let vector = try golden()
        for call in vector.valid where call.tool.hasPrefix("show_") || call.tool == "ask_user" {
            if BotToolCatalog.p1TargetNames.contains(call.tool) {
                XCTAssertThrowsError(try BotCardPayloads.build(tool: call.tool, arguments: call.args))
                continue
            }
            XCTAssertEqual(try BotCardPayloads.build(tool: call.tool, arguments: call.args), call.result, call.tool)
        }
        for call in vector.invalid {
            XCTAssertThrowsError(try BotCardPayloads.build(tool: call.tool, arguments: call.args)) { error in
                guard case BotCardPayloadError.invalid(let message) = error else {
                    return XCTFail("unexpected error: \(error)")
                }
                XCTAssertEqual(message, call.result["error"]?.stringValue)
            }
        }
    }

    func testProgressUsesStableCardKeyAndValidatesRecommendations() throws {
        let first = try BotCardPayloads.build(tool: "show_progress", arguments: .object([
            "title": .string("Generate"), "state": .string("running"),
        ]))
        let second = try BotCardPayloads.build(tool: "show_progress", arguments: .object([
            "title": .string("Generate"), "state": .string("done"),
        ]))
        XCTAssertEqual(first["card"]?["cardKey"], second["card"]?["cardKey"])
        XCTAssertEqual(second["card"]?["percent"], .number(100))
        XCTAssertThrowsError(try BotCardPayloads.build(tool: "ask_user", arguments: .object([
            "question": .string("Choose"),
            "options": .array([
                .object(["label": .string("A"), "recommended": .bool(true)]),
                .object(["label": .string("B"), "style": .string("primary")]),
            ]),
        ])))
        XCTAssertThrowsError(try BotCardPayloads.build(tool: "show_next_steps", arguments: .object([
            "steps": .array([
                .object(["label": .string("A"), "style": .string("danger"), "recommended": .bool(true)]),
                .string("B"),
            ]),
        ])))
    }

    func testCatalogRegistersExactlyP0AndVolatileContext() {
        XCTAssertEqual(BotToolCatalog.definitions.map(\.name), BotToolCatalog.p0Names)
        XCTAssertEqual(BotToolCatalog.allDefinitions.count, 17)
        for name in BotToolCatalog.p1TargetNames + ["show_canvas", "show_product", "show_gift", "GenerateImage"] {
            XCTAssertFalse(BotToolCatalog.isRegistered(name))
        }
        XCTAssertEqual(BotToolCatalog.volatileContextDefinition.name, "BotVolatileContext")
    }

    private func golden() throws -> Vector {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        return try JSONDecoder().decode(Vector.self,
            from: Data(contentsOf: root.appendingPathComponent("spec/bot/v1/golden/card-payload.json")))
    }
}
