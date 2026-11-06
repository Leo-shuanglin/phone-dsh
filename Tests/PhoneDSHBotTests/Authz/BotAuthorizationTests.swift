// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

private func assertTrue(_ expression: @autoclosure () async -> Bool) async {
    let value = await expression()
    XCTAssertTrue(value)
}

private func assertFalse(_ expression: @autoclosure () async -> Bool) async {
    let value = await expression()
    XCTAssertFalse(value)
}

private func assertEqual<T: Equatable>(_ lhs: @autoclosure () async -> T,
                                       _ rhs: @autoclosure () async -> T) async {
    let left = await lhs()
    let right = await rhs()
    XCTAssertEqual(left, right)
}

private actor AuditSink: BotAuditRecording {
    private(set) var records: [BotAuditRecord] = []

    func append(_ record: BotAuditRecord) -> Bool {
        records.append(record)
        return true
    }
}

final class BotAuthorizationTests: XCTestCase {
    private let effect = BotToolEffect(tool: "memory", operation: "memory.forget",
                                       resource: "memory:user&fact=shared", mutability: .destructive,
                                       policy: .ask, label: "secret label", detail: "secret detail")
    private let principal = BotExecutionPrincipal(botId: "main", sessionId: "s1")

    func testUnattributedEmptyAndMalformedCallsFailClosed() async throws {
        let store = BotGrantStore(now: { 1_000 })
        let authorizer = BotExecutionAuthorizer(grants: store)
        await assertFalse(await authorizer.authorizeExecution(principal: nil, effects: [effect]).allowed)
        await assertFalse(await authorizer.authorizeExecution(
            principal: BotExecutionPrincipal(botId: nil), effects: [effect]).allowed)
        await assertFalse(await authorizer.authorizeExecution(
            principal: BotExecutionPrincipal(botId: "main", certain: false), effects: [effect]).allowed)
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: []).allowed)
        let malformed = BotToolEffect(tool: "", operation: "x", resource: "secret",
                                      mutability: .write, policy: .ask)
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: [malformed]).allowed)
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: [effect],
                                                           argsDigest: "not-a-digest").allowed)
    }

    func testOnceGrantIsConsumedAndCannotBeReused() async throws {
        let clock = BotTestClock(10_000)
        let store = BotGrantStore(now: { clock.now() })
        let authorizer = BotExecutionAuthorizer(grants: store)
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: [effect]).allowed)
        let grant = try await store.add(BotGrant(botId: "main", tool: effect.tool,
                                                operation: effect.operation, resource: effect.resource,
                                                scope: .once, decision: .allow))
        XCTAssertEqual(grant.expiresAt, 130_000)
        await assertTrue(await authorizer.authorizeExecution(principal: principal, effects: [effect]).allowed)
        await assertEqual(await store.list().count, 0)
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: [effect]).allowed)
    }

    func testConcurrentOnceAuthorizationHasOneWinner() async throws {
        let store = BotGrantStore(now: { 1_000 })
        _ = try await store.add(BotGrant(botId: "main", tool: effect.tool,
                                        operation: effect.operation, resource: effect.resource,
                                        scope: .once, decision: .allow))
        let authorizer = BotExecutionAuthorizer(grants: store)
        async let first = authorizer.authorizeExecution(principal: principal, effects: [effect])
        async let second = authorizer.authorizeExecution(principal: principal, effects: [effect])
        let results = await [first, second]
        XCTAssertEqual(results.filter(\.allowed).count, 1)
        XCTAssertEqual(results.filter { !$0.allowed }.count, 1)
        await assertEqual(await store.list().count, 0)
    }

    func testGrantExpiryRevocationAndSessionIsolation() async throws {
        let clock = BotTestClock(1_000)
        let store = BotGrantStore(now: { clock.now() })
        let authorizer = BotExecutionAuthorizer(grants: store)
        let expiring = try await store.add(BotGrant(botId: "main", tool: effect.tool,
                                                   operation: effect.operation, resource: effect.resource,
                                                   scope: .once, decision: .allow))
        clock.advance(120_000)
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: [effect]).allowed)
        await assertEqual(await store.list().count, 0)

        let revoked = try await store.add(BotGrant(id: expiring.id, botId: "main", tool: effect.tool,
                                                  operation: effect.operation, resource: effect.resource,
                                                  scope: .until, decision: .allow))
        await assertTrue(await store.revoke(id: revoked.id))
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: [effect]).allowed)

        _ = try await store.add(BotGrant(botId: "main", tool: effect.tool,
                                        operation: effect.operation, resource: effect.resource,
                                        scope: .session, sessionId: "s1", decision: .allow))
        await assertTrue(await authorizer.authorizeExecution(principal: principal, effects: [effect]).allowed)
        let otherSession = BotExecutionPrincipal(botId: "main", sessionId: "s2")
        await assertFalse(await authorizer.authorizeExecution(principal: otherSession, effects: [effect]).allowed)
        await assertEqual(await store.revokeSession("s1", botId: "main"), 1)
        await assertFalse(await authorizer.authorizeExecution(principal: principal, effects: [effect]).allowed)
    }

    func testDenyWinsOverAutoAndAllowAndBotsAreIsolated() async throws {
        let store = BotGrantStore(now: { 1_000 })
        let auto = BotToolEffect(tool: effect.tool, operation: effect.operation,
                                 resource: effect.resource, mutability: .write, policy: .auto)
        _ = try await store.add(BotGrant(botId: "main", tool: auto.tool, operation: auto.operation,
                                        resource: auto.resource, scope: .until, decision: .allow))
        _ = try await store.add(BotGrant(botId: "main", tool: auto.tool, operation: auto.operation,
                                        resource: auto.resource, scope: .until, decision: .deny))
        let denied = await BotExecutionAuthorizer(grants: store)
            .authorizeExecution(principal: principal, effects: [auto])
        XCTAssertFalse(denied.allowed)
        XCTAssertTrue(denied.denied)
        XCTAssertEqual(denied.reason, "standing-deny")

        let isolated = BotGrantStore(now: { 1_000 })
        _ = try await isolated.add(BotGrant(botId: "other", tool: effect.tool,
                                           operation: effect.operation, resource: effect.resource,
                                           scope: .until, decision: .allow))
        await assertFalse(await BotExecutionAuthorizer(grants: isolated)
            .authorizeExecution(principal: principal, effects: [effect]).allowed)
    }

    func testFailedMultiEffectDecisionDoesNotConsumeOnce() async throws {
        let store = BotGrantStore(now: { 1_000 })
        let second = BotToolEffect(tool: "local", operation: "write", resource: "path=/tmp/report",
                                   mutability: .write, policy: .ask)
        _ = try await store.add(BotGrant(id: "once-1", botId: "main", tool: effect.tool,
                                        operation: effect.operation, resource: effect.resource,
                                        scope: .once, decision: .allow))
        let authorizer = BotExecutionAuthorizer(grants: store)
        await assertFalse(await authorizer.authorizeExecution(principal: principal,
                                                           effects: [effect, second]).allowed)
        await assertEqual(await store.list().map(\.id), ["once-1"])
        _ = try await store.add(BotGrant(id: "once-2", botId: "main", tool: second.tool,
                                        operation: second.operation, resource: second.resource,
                                        scope: .once, decision: .allow))
        await assertTrue(await authorizer.authorizeExecution(principal: principal,
                                                          effects: [effect, second]).allowed)
        await assertEqual(await store.list().count, 0)
    }

    func testAuditNeverContainsArgumentResourceOrDisplaySecrets() async throws {
        let sink = AuditSink()
        let store = BotGrantStore(now: { 1_000 }, audit: sink)
        let secret = "super-secret-value-that-must-not-leak"
        let auto = BotToolEffect(tool: "memory", operation: "memory.write",
                                 resource: "memory:agent&fact=\(secret)", mutability: .write,
                                 policy: .auto, label: secret, detail: secret)
        let digest = try BotArgumentsDigest.string(for: .object(["fact": .string(secret)]))
        let result = await BotExecutionAuthorizer(grants: store).authorizeExecution(
            principal: principal, effects: [auto], source: "memory", argsDigest: digest)
        XCTAssertTrue(result.allowed)
        let records = await sink.records
        XCTAssertEqual(records.count, 1)
        let encoded = String(data: try JSONEncoder().encode(records), encoding: .utf8)!
        XCTAssertFalse(encoded.contains(secret))
        XCTAssertFalse(encoded.contains("memory:agent&fact="))
        XCTAssertTrue(encoded.contains(digest))
        XCTAssertEqual(records[0].effects[0].argsDigest, digest)
        XCTAssertEqual(records[0].effects[0].resourceDigest,
                       BotArgumentsDigest.digest(ofUTF8: auto.resource))
    }

    func testFileAuditIsPrivateAndDigestOnly() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("phonedsh-wp4-audit-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let log = BotAuditLog(dataRoot: root)
        let store = BotGrantStore(now: { 1_000 }, audit: log)
        let secret = "file-audit-secret"
        let auto = BotToolEffect(tool: "memory", operation: "memory.write",
                                 resource: "fact=\(secret)", mutability: .write, policy: .auto,
                                 label: secret, detail: secret)
        await assertTrue(await BotExecutionAuthorizer(grants: store)
            .authorizeExecution(principal: principal, effects: [auto]).allowed)
        let directory = root.appendingPathComponent("authz")
        let files = try FileManager.default.contentsOfDirectory(at: directory,
                                                                includingPropertiesForKeys: nil)
        XCTAssertEqual(files.count, 1)
        let text = try String(contentsOf: try XCTUnwrap(files.first), encoding: .utf8)
        XCTAssertFalse(text.contains(secret))
        XCTAssertFalse(text.contains(auto.resource))
        let fileMode = try FileManager.default.attributesOfItem(atPath: files[0].path)[.posixPermissions] as? NSNumber
        let directoryMode = try FileManager.default.attributesOfItem(atPath: directory.path)[.posixPermissions] as? NSNumber
        XCTAssertEqual(fileMode?.intValue, 0o600)
        XCTAssertEqual(directoryMode?.intValue, 0o700)
    }
}
