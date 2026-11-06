// SPDX-License-Identifier: MIT

import Foundation
import PhoneDSHAgentCore
@testable import PhoneDSHBot

struct BotScriptedEvent {
    let type: String
    let data: BotJSONValue
    let time: Int64
    let stream: Bool

    init(_ type: String, _ data: BotJSONValue = .object([:]), time: Int64 = 1_000, stream: Bool = false) {
        self.type = type
        self.data = data
        self.time = time
        self.stream = stream
    }

    static func turn(_ number: Int = 1, time: Int64 = 1_000) -> Self {
        Self("turn/start", .object(["turn": .number(Double(number))]), time: time)
    }

    static func assistant(_ text: String, time: Int64 = 1_010) -> Self {
        Self("assistant/message", .object([
            "message": .object(["content": .array([.object(["type": .string("text"), "text": .string(text)])])]),
            "usage": .object([
                "inputTokens": .number(10), "outputTokens": .number(5),
                "cacheReadTokens": .number(4), "cacheWriteTokens": .number(2),
            ]),
        ]), time: time)
    }

    static func delta(_ text: String, time: Int64 = 1_005) -> Self {
        Self("assistant-stream", .object([
            "type": .string("chunk"),
            "chunk": .object(["type": .string("text-delta"), "text": .string(text)]),
        ]), time: time, stream: true)
    }

    static func toolCall(_ name: String, id: String = "call-1", time: Int64 = 1_002) -> Self {
        Self("tool/call", .object([
            "callId": .string(id), "name": .string(name), "arguments": .string("{}"),
        ]), time: time)
    }

    static func toolResult(id: String = "call-1", failed: Bool = false, time: Int64 = 1_003) -> Self {
        Self("tool/result", .object([
            "message": .object([
                "toolCallId": .string(id), "isError": .bool(failed),
                "content": .array([.object(["type": .string("text"), "text": .string("ok")])]),
            ]),
        ]), time: time)
    }

    static func end(_ reason: String = "completed", turn: Int = 1, time: Int64 = 1_020) -> Self {
        Self("turn/end", .object([
            "turn": .number(Double(turn)), "reason": .object(["kind": .string(reason)]),
        ]), time: time)
    }
}

final class BotScriptedRemoteTransport: DSHRemoteTransport, @unchecked Sendable {
    private var scripts: [String: [[BotScriptedEvent]]]
    private let mainId: String
    private(set) var prompts: [String: (sessionId: String, mode: String, text: String)] = [:]
    private(set) var cancellations: [String] = []

    init(mainId: String = "gb-main", scripts: [String: [[BotScriptedEvent]]]) {
        self.mainId = mainId
        self.scripts = scripts
    }

    func queue(sessionId: String, events: [BotScriptedEvent]) {
        scripts[sessionId, default: []].append(events)
    }

    func call(_ endpoint: String, args: BotJSONValue) async throws -> BotJSONValue {
        switch endpoint {
        case "session/create": return .object(["sessionId": .string(mainId)])
        case "session/page": return .object([:])
        case "session/prompt":
            let request = args["request"] ?? .null
            let sessionId = request["sessionId"]?.stringValue ?? ""
            let mode = request["mode"]?.stringValue ?? ""
            let text = (request["content"]?.arrayValue ?? []).compactMap {
                $0["text"]?.stringValue
            }.joined()
            prompts[sessionId] = (sessionId, mode, text)
            return .object([:])
        case "session/cancel":
            let sessionId = args["request"]?["sessionId"]?.stringValue ?? ""
            cancellations.append(sessionId)
            return .object([:])
        default: return .object([:])
        }
    }

    func openMux() async throws -> any DSHMuxConnection {
        BotScriptedMux(eventsForSession: { [weak self] sessionId in
            guard let self, let events = self.scripts[sessionId], !events.isEmpty else { return [] }
            return self.scripts[sessionId]!.removeFirst()
        })
    }

    func authenticationFailed() async {}
}

private actor BotScriptedMux: DSHMuxConnection {
    private let eventsForSession: (String) -> [BotScriptedEvent]
    private var events: [BotScriptedEvent] = []
    private var snapshotReturned = false
    private var index = 0

    init(eventsForSession: @escaping (String) -> [BotScriptedEvent]) {
        self.eventsForSession = eventsForSession
    }

    func send(_ frame: BotJSONValue) async throws {
        guard frame["endpoint"]?.stringValue == "session/follow" else { return }
        let address = frame["payload"]?["args"]?["request"]?["address"]
        let sessionId = address?["sessionId"]?.stringValue ?? ""
        events = eventsForSession(sessionId)
    }

    func receive() async throws -> BotJSONValue? {
        if !snapshotReturned {
            snapshotReturned = true
            return .object([
                "type": .string("item"), "streamId": .string("follow"),
                "value": .object(["type": .string("snapshot"), "cursor": .number(0), "records": .array([])]),
            ])
        }
        guard index < events.count else { return nil }
        let event = events[index]
        index += 1
        if event.stream {
            return .object([
                "type": .string("item"), "streamId": .string("follow"),
                "value": .object([
                    "type": .string("assistant-stream"), "time": .number(Double(event.time)),
                    "frame": event.data,
                ]),
            ])
        }
        return .object([
            "type": .string("item"), "streamId": .string("follow"),
            "value": .object([
                "type": .string("event"),
                "event": .object([
                    "type": .string(event.type), "seq": .number(Double(index)),
                    "time": .number(Double(event.time)), "data": event.data,
                ]),
            ]),
        ])
    }

    func close() async {}
}
