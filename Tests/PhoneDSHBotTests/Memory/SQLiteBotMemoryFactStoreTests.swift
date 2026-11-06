// SPDX-License-Identifier: MIT

import XCTest
@testable import PhoneDSHBot

final class SQLiteBotMemoryFactStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("phonedsh-bot-memory-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    func testWrittenFactsRoundTripThroughSQLiteReopen() async throws {
        let file = directory.appendingPathComponent("domain.sqlite")
        let domain = try SQLiteDomainStore(fileURL: file, now: { 100 })
        let store = SQLiteBotMemoryFactStore(eventLog: domain, botID: "main", now: { 101 })
        let first = BotMemoryFact(
            factID: "fact-full",
            text: "完整事实",
            learnedAtMS: 1_760_000_000_000,
            tier: .note,
            scope: .agent,
            key: "full",
            trust: .string("user"),
            sensitivity: .sensitive,
            importance: 0.8,
            lastUsedMS: 123,
            extractor: "manual",
            source: BotMemorySource(kind: .userTurn, sessionID: "session", turnID: "turn", role: "user"),
            expiresAt: 2_000_000_000_000,
            applicableWhen: "工作日",
            notApplicableWhen: "正式場合",
            extractRunID: "run-full",
            archived: true,
            archivedAtMS: 456,
            archiveReason: "test",
            l2: true
        )
        let second = makeMemoryFact("fact-user", "共享事實", scope: .user)
        try await store.append(first)
        try await store.append(second)
        do {
            try await store.append(first)
            XCTFail("duplicate active fact id must fail")
        } catch {
            XCTAssertEqual(error as? BotMemoryFactStoreError, .duplicateFactID(first.factID))
        }
        try await domain.close()

        let reopened = try SQLiteDomainStore(fileURL: file, now: { 200 })
        let replayed = SQLiteBotMemoryFactStore(eventLog: reopened, botID: "main", now: { 201 })
        let all = try await replayed.facts()
        XCTAssertEqual(all, [first, second])
        let user = try await replayed.facts(scope: .user)
        XCTAssertEqual(user, [second])
        let events = try await reopened.events(after: 0, limit: 10)
        XCTAssertEqual(events.map(\.type), ["memory.written", "memory.written"])
        guard case .object(let data) = events[0].data else {
            return XCTFail("memory.written data must be the complete fact object")
        }
        XCTAssertNotNil(data["fact_id"])
        XCTAssertNil(data["fact"])
        XCTAssertEqual(try JSONDecoder().decode(BotMemoryFact.self, from: events[0].data.encoded()), first)
        try await reopened.close()
    }

    func testForgetBatchIsDurableAndContainsOnlyDigest() async throws {
        let file = directory.appendingPathComponent("domain.sqlite")
        let domain = try SQLiteDomainStore(fileURL: file)
        let store = SQLiteBotMemoryFactStore(eventLog: domain, now: { 5_000 })
        let first = makeMemoryFact("forget-a", "刪除甲", learnedAtMS: 1)
        let second = makeMemoryFact("forget-b", "刪除乙", learnedAtMS: 2)
        let remaining = makeMemoryFact("keep-c", "保留丙", learnedAtMS: 3)
        for fact in [first, second, remaining] { try await store.append(fact) }

        let removal = try await store.remove(factIDs: [first.factID, second.factID], scope: .agent, tombstone: nil)
        XCTAssertEqual(removal.removed, [first, second])
        XCTAssertEqual(removal.count, 1)
        try await domain.close()

        let reopened = try SQLiteDomainStore(fileURL: file)
        let replayed = SQLiteBotMemoryFactStore(eventLog: reopened, now: { 5_001 })
        let live = try await replayed.facts()
        XCTAssertEqual(live, [remaining])
        let tombstone = try await replayed.tombstone(factID: first.factID, scope: .agent)
        XCTAssertNil(tombstone)
        let forgotten = try await reopened.events(after: 0, limit: 10).filter { $0.type == "memory.forgotten" }
        XCTAssertEqual(forgotten.count, 2)
        XCTAssertEqual(forgotten[0].batch?.id, forgotten[1].batch?.id)
        XCTAssertEqual(forgotten.map { $0.batch?.total }, [2, 2])
        for event in forgotten {
            guard case .object(let data) = event.data else {
                return XCTFail("memory.forgotten data must be an object")
            }
            XCTAssertEqual(Set(data.keys), ["factId", "at", "reason", "scope", "textDigest"])
            XCTAssertEqual(data["reason"], .string("forget"))
            XCTAssertEqual(data["at"], .number(5_000))
            XCTAssertEqual(data["scope"], .string("agent"))
            XCTAssertEqual(data["textDigest"]?.stringCount, 64)
            let raw = String(decoding: try event.data.encoded(), as: UTF8.self)
            XCTAssertFalse(raw.contains("刪除"))
        }
        try await replayed.append(first)
        let reinserted = try await replayed.facts()
        XCTAssertEqual(reinserted, [remaining, first])
        try await reopened.close()
    }

    func testEraseTombstoneSurvivesReopenAndBlocksResurrection() async throws {
        let file = directory.appendingPathComponent("domain.sqlite")
        let domain = try SQLiteDomainStore(fileURL: file)
        let store = SQLiteBotMemoryFactStore(eventLog: domain, now: { 7_000 })
        let secret = makeMemoryFact("erase-secret", "不能復活的秘密", tier: .profile)
        try await store.append(secret)
        let tombstone = BotMemoryTombstone(factID: secret.factID, erasedAtMS: 7_000)
        _ = try await store.remove(factIDs: [secret.factID], scope: .agent, tombstone: tombstone)
        try await domain.close()

        let reopened = try SQLiteDomainStore(fileURL: file)
        let replayed = SQLiteBotMemoryFactStore(eventLog: reopened, now: { 7_001 })
        let live = try await replayed.facts()
        XCTAssertTrue(live.isEmpty)
        let persisted = try await replayed.tombstone(factID: secret.factID, scope: .agent)
        XCTAssertEqual(persisted, tombstone)
        do {
            try await replayed.append(secret)
            XCTFail("erased fact id must not be resurrected")
        } catch {
            XCTAssertEqual(error as? BotMemoryFactStoreError, .erasedFactID(secret.factID))
        }
        let events = try await reopened.events(after: 0, limit: 10)
        let erased = try XCTUnwrap(events.last { $0.type == "memory.forgotten" })
        guard case .object(let data) = erased.data else {
            return XCTFail("erase data must be an object")
        }
        XCTAssertEqual(data["reason"], .string("erase"))
        XCTAssertEqual(data["factId"], .string(secret.factID))
        XCTAssertEqual(data["at"], .number(7_000))
        XCTAssertEqual(data["textDigest"]?.stringCount, 64)
        XCTAssertFalse(String(decoding: try erased.data.encoded(), as: UTF8.self).contains(secret.text))
        try await reopened.close()
    }

    func testTouchAppendsDurableWrittenEventWithoutChangingInsertionOrder() async throws {
        let file = directory.appendingPathComponent("domain.sqlite")
        let domain = try SQLiteDomainStore(fileURL: file)
        let store = SQLiteBotMemoryFactStore(eventLog: domain)
        let first = makeMemoryFact("touch-first", "第一條", learnedAtMS: 1)
        let second = makeMemoryFact("touch-second", "第二條", learnedAtMS: 2)
        try await store.append(first)
        try await store.append(second)
        try await store.touch(factIDs: [first.factID], lastUsedMS: 9_000)
        var expected = first
        expected.lastUsedMS = 9_000
        let touched = try await store.facts()
        XCTAssertEqual(touched, [expected, second])
        try await domain.close()

        let reopened = try SQLiteDomainStore(fileURL: file)
        let replayed = SQLiteBotMemoryFactStore(eventLog: reopened)
        let persisted = try await replayed.facts()
        XCTAssertEqual(persisted, [expected, second])
        let written = try await reopened.events(after: 0, limit: 10).filter { $0.type == "memory.written" }
        XCTAssertEqual(written.count, 3)
        XCTAssertEqual(try JSONDecoder().decode(BotMemoryFact.self, from: written[2].data.encoded()), expected)
        try await replayed.touch(factIDs: ["missing"], lastUsedMS: 10_000)
        let head = try await reopened.headCommitSeq()
        XCTAssertEqual(head, 3)
        try await reopened.close()
    }

    func testCrashRollsBackMultiDeleteAndTombstoneWithoutGap() async throws {
        let file = directory.appendingPathComponent("domain.sqlite")
        let domain = try SQLiteDomainStore(fileURL: file)
        let store = SQLiteBotMemoryFactStore(eventLog: domain, now: { 10_000 })
        let first = makeMemoryFact("atomic-a", "原子甲", learnedAtMS: 1)
        let second = makeMemoryFact("atomic-b", "原子乙", learnedAtMS: 2)
        let remaining = makeMemoryFact("atomic-c", "原子丙", learnedAtMS: 3)
        for fact in [first, second, remaining] { try await store.append(fact) }
        let tombstone = BotMemoryTombstone(factID: first.factID, erasedAtMS: 10_000)
        await domain.armCrashPoint("abort-before-commit")
        do {
            _ = try await store.remove(factIDs: [first.factID, second.factID], scope: .agent, tombstone: tombstone)
            XCTFail("injected crash must abort the batch")
        } catch {
            XCTAssertEqual(error as? SQLiteDomainStoreError, .simulatedCrash("abort-before-commit"))
        }
        try await domain.close()

        let reopened = try SQLiteDomainStore(fileURL: file)
        let replayed = SQLiteBotMemoryFactStore(eventLog: reopened, now: { 10_001 })
        let afterCrash = try await replayed.facts()
        XCTAssertEqual(afterCrash, [first, second, remaining])
        let missingTombstone = try await replayed.tombstone(factID: first.factID, scope: .agent)
        XCTAssertNil(missingTombstone)
        let headAfterCrash = try await reopened.headCommitSeq()
        XCTAssertEqual(headAfterCrash, 3)

        let removal = try await replayed.remove(
            factIDs: [first.factID, second.factID],
            scope: .agent,
            tombstone: tombstone
        )
        XCTAssertEqual(removal.removed, [first, second])
        XCTAssertEqual(removal.count, 1)
        let committed = try await reopened.events(after: 0, limit: 10)
        XCTAssertEqual(committed.map(\.commitSeq), (1...5).map(Optional.some))
        let forgotten = committed.filter { $0.type == "memory.forgotten" }
        XCTAssertEqual(forgotten.count, 2)
        XCTAssertEqual(forgotten[0].batch?.id, forgotten[1].batch?.id)
        guard case .object(let eraseData) = forgotten[0].data,
              case .object(let forgetData) = forgotten[1].data else {
            return XCTFail("forgotten events must carry objects")
        }
        XCTAssertEqual(eraseData["reason"], .string("erase"))
        XCTAssertEqual(forgetData["reason"], .string("forget"))
        try await reopened.close()

        let finalDomain = try SQLiteDomainStore(fileURL: file)
        let finalStore = SQLiteBotMemoryFactStore(eventLog: finalDomain)
        let finalFacts = try await finalStore.facts()
        XCTAssertEqual(finalFacts, [remaining])
        let finalTombstone = try await finalStore.tombstone(factID: first.factID, scope: .agent)
        XCTAssertEqual(finalTombstone, tombstone)
        try await finalDomain.close()
    }

    func testBotMemoryUsesSQLiteStoreEndToEnd() async throws {
        let file = directory.appendingPathComponent("domain.sqlite")
        let domain = try SQLiteDomainStore(fileURL: file)
        let store = SQLiteBotMemoryFactStore(eventLog: domain, botID: "main")
        let memory = BotMemory(
            store: store,
            dataRoot: directory.path,
            botID: "main",
            now: { MemoryTestFixtures.now }
        )
        let written = try await memory.write(BotMemoryWriteRequest(
            text: "第一行\r\n第二行",
            tier: .profile,
            source: BotMemorySource(kind: .userTurn)
        ))
        XCTAssertTrue(written.ok)
        let recalled = try await memory.recall("第一行", now: 1_800_000_000_000)
        XCTAssertEqual(recalled.count, 1)
        try await domain.close()

        let reopened = try SQLiteDomainStore(fileURL: file)
        let replayed = SQLiteBotMemoryFactStore(eventLog: reopened, botID: "main")
        let restored = BotMemory(
            store: replayed,
            dataRoot: directory.path,
            botID: "main",
            now: { MemoryTestFixtures.now }
        )
        let facts = try await restored.facts()
        XCTAssertEqual(facts.map(\.text), ["第一行；第二行"])
        XCTAssertEqual(facts[0].lastUsedMS, 1_800_000_000_000)
        let prompt = try await restored.promptBlock(scopes: [.agent])
        XCTAssertTrue(prompt.contains("第一行；第二行"))
        let erased = try await restored.erase(factID: try XCTUnwrap(written.factID))
        XCTAssertTrue(erased.erased)
        let afterErase = try await restored.promptBlock(scopes: [.agent])
        XCTAssertFalse(afterErase.contains("第一行；第二行"))
        try await reopened.close()
    }
}

private extension BotJSONValue {
    var stringCount: Int? {
        guard case .string(let value) = self else { return nil }
        return value.count
    }
}
