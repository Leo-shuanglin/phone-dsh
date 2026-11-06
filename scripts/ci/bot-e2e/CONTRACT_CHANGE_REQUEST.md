<!-- SPDX-License-Identifier: MIT -->

# CONTRACT CHANGE REQUEST

Contract: P24/P25 @ 3c7a6efaab5d95b71cbceb7b69d900f5e8665552
Clause: `Sources/PhoneDSHBot/Rows/BotUserAction.swift` `BotUserActionDispatcher.dispatch`; `Sources/PhoneDSHBot/Store/SQLiteDomainStore.swift` schema v1; `spec/bot/v1/README.md` §5 nonce idempotency and WP7 supplemental durable-action requirement

Problem: `BotUserActionDispatcher` performs the action handler before calling `BotUserActionNonceStore.record`. A crash after the handler commits its side effect but before `record` commits leaves no nonce; replaying the action then executes the handler again. The nonce store protocol's atomic `record` only protects duplicate record calls, not the handler-to-record transaction. `SQLiteDomainStore.appendBatch` can atomically commit action and nonce events, but the current dispatcher/handler protocol does not expose a transaction or a plan that can join them. Adding a SQLite nonce table would require changing WP3-owned schema v1; changing dispatch order would require changing WP8-owned code. Both are outside WP7 ownership. Persisting only a deterministic nonce event after the handler would hide, not close, the crash window and is therefore not exactly-once.

Proposal: smallest delta: add an atomic action transaction at the existing SQLite owner. The host handler produces an action event and its effects as a durable plan; `SQLiteDomainStore` commits the action event, effect/outbox records, and `(botID, nonce) -> eventID` in one `BEGIN IMMEDIATE` transaction with a uniqueness constraint. `BotUserActionDispatcher` (or its v2 replacement) calls that single `performAndRecord` method instead of separately invoking handler and nonce store. Existing accepted/duplicate/stale/rejected results stay unchanged. A crash before commit rolls back everything; a crash after commit replays as duplicate and never executes the handler again. Do not claim exactly-once for external side effects outside that transaction; they retain the existing outbox/reconciliation semantics.

Affects: WP8 row/action dispatcher and its tests, WP3 SQLiteDomainStore schema migration and crash tests, WP9 UI action caller, WP7 host adapter. Until the contract owner freezes this delta, WP7 durable nonce/action adaptation is stopped and no substitute implementation is mounted.
