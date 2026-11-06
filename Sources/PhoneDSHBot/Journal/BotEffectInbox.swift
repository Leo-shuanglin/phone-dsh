// SPDX-License-Identifier: MIT

import Foundation

public actor SQLiteBotEffectInbox {
    private let store: SQLiteDomainStore
    private let receiver: String
    private let clock: @Sendable () -> Int64

    public init(
        store: SQLiteDomainStore,
        receiver: String,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) }
    ) throws {
        guard receiver.range(of: #"^[A-Za-z0-9._-]+$"#, options: .regularExpression) != nil,
              receiver != ".", receiver != ".." else {
            throw SQLiteDomainStoreError.invalidConfiguration("invalid effect receiver")
        }
        self.store = store
        self.receiver = receiver
        self.clock = now
    }

    public func apply(
        _ lease: BotEffectLease,
        executor: @Sendable (BotEffectLease) async throws -> String?
    ) async throws -> BotEffectInboxResult {
        if let existing = try await store.inboxEntry(receiver: receiver, effectKey: lease.effectKey),
           existing.generation >= lease.generation {
            return BotEffectInboxResult(deduped: true, resultRef: existing.resultRef, entry: existing)
        }
        guard let record = try await store.effect(lease.effectKey) else {
            throw SQLiteDomainStoreError.unknownEffect(lease.effectKey)
        }
        guard record.generation == lease.generation,
              record.attempt == lease.attempt,
              record.leaseOwner == lease.leaseOwner else {
            throw SQLiteDomainStoreError.staleLease(lease.effectKey)
        }
        guard record.state == .leased || record.state == .cancelRequested else {
            throw SQLiteDomainStoreError.invalidTransition("effect is not leased for inbox apply")
        }
        let resultRef = try await executor(lease)
        let entry = try await store.recordInbox(
            receiver: receiver, lease: lease, appliedAt: clock(), resultRef: resultRef)
        return BotEffectInboxResult(deduped: false, resultRef: entry.resultRef, entry: entry)
    }
}
