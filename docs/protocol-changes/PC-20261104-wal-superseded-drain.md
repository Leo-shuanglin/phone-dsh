<!-- SPDX-License-Identifier: MIT -->
# PC-20261104-wal-superseded-drain — CCR-1 / CCR-2 contract freeze

Status: **frozen**. Contract revision: `<freeze-sha>` (the main session fills the commit SHA after
review and commit). This record is the §3.1 contract freeze for the P02/P03/P01/P22 delta; no
implementation in this worktree is part of it.

## 0. Summary

```yaml
change: PC-20261104-wal-superseded-drain
contracts: [P01, P02, P03, P22]
status: frozen
breaking: false
version_decision: no proto/version bump; no wire field, frame, required member, manifest field or on-disk format changes; conservative settlement and the drain order are host-side rules, and the VFS_CONFLICT condition is the existing code with a host-side ignored case. Round 2's only schema addition is the optional replay-vector `expected.watermark_steps`, a spec fixture member, not a runtime format
base: 451429ac
contract_revision: <freeze-sha>
```

## 1. Identity, trigger and roles

- **Trigger → current → expected.**
  - **CCR-1 (P02).** Android device evidence (`durable_mseq=872`, `next_mseq=101297`) showed a
    boot replay that lands on a materialized copy newer than the manifest watermark: an appended
    record `m` whose path was later completely rewritten/truncated/removed in the same retained tail
    is judged a base mismatch. D9 currently has only apply / skip / mismatch; the repair write does
    not contain `m`'s bytes, so `settle` re-mismatches forever and a remove becomes a terminal
    `VFS_CONFLICT`. Expected: the fourth verdict **superseded** settles `m` without applying it when
    a later retained complete write of the path (a repair write is one) or a remove of the path or an
    ancestor covers it; only covered bytes are let go, and an uncovered mismatch still stays held and
    Failed. Round 2 makes the settlement conservative: `m` counts toward the contiguous watermark
    only once its covering record has been applied and is itself inside the same prefix; before then
    it blocks the watermark like a held record, but without Failed, resync or a terminal conflict.
  - **CCR-2 (P03 + P02).** (1) `budgets.md` said "wait for in-flight work" but not whether a
    connected Worker's outstanding VFS_RESYNC is in-flight; Android closed the epoch immediately and
    turned a repairable mismatch into Failed. Expected: Draining waits inside the 20 s deadline for
    issued VFS_RESYNC answers while the Worker is connected, settles them, then closes; an unrepaired
    mismatch at the deadline is Failed per wal.md §5.2 with path/mseq; a disconnected Worker is not
    waited for. (2) wal.md §5.1 said a boot "performs the full-tree reconcile of §7.2", while §7.2
    says reconcile's inputs are never the WAL alone; expected: a boot resync first runs the §7.2
    materialized-copy invariant, and when retained mutations start at mseq 1 with no hole a
    divergence rebuilds from the WAL, otherwise the repair is requested after HELLO. (3)
    Failed→Recovering said "re-run recovery" without an order; expected: recover → overlay pack →
    start Worker → HELLO → VFS_RESYNC → settle, and that row does **not** close an epoch.
  - **iOS gaps (registered, not a contract change).** `wal.md` §5.2's 30–60 s periodic close is not
    called on iOS; `WorkspaceLogService.pendingResyncPaths()` has no production caller, so a
    recovery resync is not re-requested after HELLO. Registered as baseline GAP-12/GAP-13 and
    assigned to the iOS implementer in this change.
- **Coordinator / contract owner:** this session (spec/ and this record only). **Implementers:**
  iOS, Android, Rust (paths in §4). **Reviewer/integrator:** the main session.
- **Owning spec / schema / shared fixtures:** [wal.md](../../spec/v1/wal.md) §5.1, §6.2, §7, §7.1,
  §7.2, §10; [budgets.md](../../spec/v1/budgets.md) §1–§2; [channel.md](../../spec/v1/channel.md)
  §8, §9, §10; [exec.md](../../spec/v1/exec.md) §2.1;
  [manifest.schema.json](../../spec/v1/schema/manifest.schema.json) unchanged; new
  [wal-replay.schema.json](../../spec/v1/schema/wal-replay.schema.json), whose optional
  `expected.watermark_steps` member is added in round 2; replay vectors under
  [golden/wal/replay](../../spec/v1/golden/wal/replay/); the optional `expected.failure_detail` member in
  [perturbation.schema.json](../../spec/v1/schema/perturbation.schema.json) and its two drain fixtures.
- **Affected producers/consumers (registry):** P02 Swift `PhoneDSHLog` + `PhoneDSHWire/WAL.swift`,
  Kotlin `android/core/**/log` + `android/dsh/**/host`, Rust `pdsh-wal` (+ the JNI export);
  P03 Swift controller/host + Kotlin `android/controller` and `android/dsh/**/host`, including the
  `requires_mseq` readiness fence that must also wait for a settled append's cover (exec.md §2.1);
  P01 the channel
  host side (`VFS_RESYNC`, HELLO re-request, `VFS_CONFLICT`) and the Worker resync handler's outbound
  ordering; P22 the perturbation fixture/schema family. The TS `plugins/channel/src/wal.ts` codec is
  a byte-codec counterpart only and has no production consumer; it is not changed.

## 2. Contract delta

| Item | Before → After / clause |
| --- | --- |
| D9 verdicts | apply / skip / mismatch → **superseded** in addition, checked before the base check. Later retained complete write of P (`op=1`, appended clear), remove of P, or remove of an ancestor covers `m`; intervening appends must depend on `m`'s base only when covered by the same record. wal.md §6.2. |
| Settlement of held appends | §5.1 only named the repair write → a later complete write/remove of P (or ancestor) arriving after the mismatch also settles covered held appends; the repair write is such a complete write. Only covered bytes are settled; an uncovered mismatch stays held and fails at the next epoch close. A superseded record counts as settled only once that cover is applied. |
| Watermark over settled records | the weaker draft let the watermark pass a superseded record immediately → a settled record holds the contiguous watermark below it until its covering record is applied **and inside the same prefix**; before then it is not a failure, a resync or a terminal conflict. Invariant: every manifest watermark that is at least a settled record's `mseq` also has the cover's `mseq` at or below the watermark. wal.md §5.1, §6.2, §7, §7.1, §10; channel.md §8; exec.md §2.1. |
| VFS_CONFLICT | always terminal → ignored when the held record is superseded; terminal otherwise. channel.md §10, wal.md §5.1. |
| Boot resync order | "replay reaches it and requests the repair" → first the §7.2 invariant: retained mseq-1/no-hole divergence rebuilds from the WAL; otherwise request after HELLO (§10 step 4). The mseq-1 rebuild is explicitly not staged reconcile and may use the WAL alone. wal.md §5.1, §7.2. |
| Draining | "wait for in-flight work" → inside the 20 s deadline, include issued VFS_RESYNC answers when the Worker is connected; deadline with an unrepaired mismatch → Failed with path/mseq; disconnected Worker is not waited for. budgets.md §1, §2. |
| Failed→Recovering | "re-run recovery" → recover, pack overlay, start Worker, HELLO, VFS_RESYNC, settle; **no** epoch close in this row; the next close is a periodic close or a drain. budgets.md §2. |
| Capability/version/unknown field | unchanged. No new wire type, field, capability, error code or manifest member. `proto 1`, WAL format 1 and the manifest schema are unchanged. |
| Sequence/ordering | `mseq` semantics are unchanged; the watermark may pass an applied or idempotently skipped record, and a settled-superseded record only together with its cover; otherwise it stops at the first unresolved mseq − 1. §7/§7.1 name the settled outcome and the conservative invariant, so "the watermark may pass it" no longer contradicts the manifest field. |
| Error/retry/deadline | no new code; `VFS_CONFLICT` is the existing code with one host-side ignored case; drain keeps the existing 20 s deadline; no retry is added. |
| Idempotency/ACK/durability | D9 apply/skip are unchanged for uncovered records; level-1/level-2 promises and the D18 close order are unchanged. A successful mseq-1 rebuild removes the resync before HELLO. |
| Fixtures/schema | new `wal-replay.schema.json` and 14 vectors, including `settled-watermark-waits-for-cover` with optional `expected.watermark_steps`; `perturbation.schema.json` gains optional `expected.failure_detail` `{path,mseq}`; two drain fixtures. Additive and optional. |

Self-check against baseline §5.1: no second representation of an existing semantic (the verdict is
inside D9, not a new wire value); the added schema members are optional and absent from old
fixtures/readers (both are spec fixture assets: `expected.failure_detail` and
`expected.watermark_steps`); the changed recovery behavior does not tighten a two-end message; no
enum value is added to a wire message; no name/number is deleted or reused.

### 2.1 Round-2 review decisions (contract owner record)

The round-1 freeze reported four semantic points. The main session's contract decisions are:

| # | Point | Decision | Contract text / next step |
| --- | --- | --- | --- |
| 1 | intervening append literal reading: in a linear WAL a cover covers every earlier record of the same path; the boundary the rule protects is an append that still assumes the old base after the cover | **已確認** (confirmed) | wal.md §6.2; `intervening-append-covered-settles` and `dependent-append-not-covered-stays-held` keep the reading. |
| 2 | Worker sends every completed same-path mutation before `VFS_CONFLICT` (channel.md §10) | **已確認** (confirmed) | TS Worker implementer first verifies whether `plugins/worker-entry` and the send sites in `plugins/channel` already send frames in mseq order on the same ordered channel; if they do, add the ordering test only and do not change the implementation. |
| 3 | watermark passing a settled record | **Revised — conservative semantics** (the weaker settlement was not accepted). A superseded record is settled and may be passed only once its covering record has been applied inside the contiguous prefix; before that it holds the watermark like a held record, without Failed or resync. Invariant: any manifest watermark that is at least a settled record's `mseq` has the cover's `mseq` at or below the watermark. | wal.md §5.1, §6.2, §7, §7.1, §10; channel.md §8; exec.md §2.1; replay schema/vector `watermark_steps` and its mutation test; compatibility matrix below. |
| 4 | GAP-12 / GAP-13 | **Maintained** (維持登記); the iOS implementer fixes both this round; not a contract change. | spec/README.md §7; no spec text changes. |

`budgets.md` has no independent watermark-passage wording: its `Draining` row defers to wal.md §5.1
for the VFS_RESYNC settlement, so round 2 leaves that document unchanged.

## 3. Compatibility and release

| Producer → Consumer | Support / refusal, reason, test |
| --- | --- |
| old → new | Wire and on-disk format are unchanged. An old peer (or old host) reading new bytes behaves exactly as before: it may still hold and Fail the superseded sequences the new host settles, and it has no conservative settlement rule, so the watermark it writes may pass a covered append before its cover. That is the defect being fixed, not a new incompatibility. The new host repairs it on the next boot from the retained cover, and no WAL record is rewritten. |
| new → old | The new host emits no new frame, field, error code or manifest member. An old peer sees the same frames. The visible differences are host-side: the new host may ignore a `VFS_CONFLICT` for a superseded path and finish a drain it would previously have failed, and it writes a conservative watermark that can be lower than the old rule would write (a settled record is not counted until its cover is inside the prefix). An old host reading that manifest replays more retained records; its own D9 idempotence and the eventual cover make that safe. Old data remains readable; the optional replay fixture member is not on-disk data. |
| new → new | Both ends settle a covered append only after its cover is applied inside the prefix, keep the contiguous watermark below it until then, rebuild from the WAL on a retained mseq-1 divergence, and park after a connected Worker's resync is answered. The shared replay and drain fixtures are the oracle; the optional `expected.watermark_steps` checkpoints are the manifest assertion. |
| persisted old data → new / rollback | No migration: manifest schema, WAL format 1, segment names and mseq domain are unchanged. A real device WAL written by the old Android/iOS build is read by the new host: superseded appends settle only once the cover is applied inside the prefix, a mseq-1 divergence rebuilds from the WAL, and a retained tail above 1 keeps the old repair/Failed path. An old manifest is not rejected or rewritten for a watermark that the new conservative rule would not have written: the cover above the watermark is retained and replayed, so the copy converges, and the new host writes the conservative value at its next manifest write. Rollback is a binary rollback only; the old binary reads the same files and may take the old mismatch/Failed or weaker-watermark path again. The new host does not rewrite old records or the manifest format. |
| old version reads new data / new version reads real-device old WAL | **Old version reads new data:** format-identical; no new member, so there is nothing to ignore. A new host's manifest watermark never passes a settled record whose cover is above the watermark, so an old reader can replay from it and apply the retained tail; under its own weaker rule it may apply or pass the covered append before the cover and expose the old bytes, which the new host repairs on the next boot. **New version reads real-device old WAL:** same format. The old build had no settled verdict: it either applied the appended record or held it below the watermark. A retained cover above an old watermark is replayed by the new host, and the conservative rule applies from its next manifest write; no old manifest is invalidated or rewritten. The mseq-1 invariant can rebuild the copy, and a retained tail above 1 is not rebuilt from an incomplete root. This is the Android evidence case. |

- **Version decision:** no bump. No wire frame/type/flag/required member changed; D9 is a host
  materialization rule, and the `VFS_CONFLICT` change is a host-side condition on an existing code.
  WAL format 1 and the manifest schema are unchanged, so the owning version rule (wal.md §1–§7,
  channel.md §2) does not require a new major. The shared fixture schema is a spec asset, not a
  runtime format.
- **Deployment order / rollback:** the host-side code is the only change to the WAL format; producer
  and consumer can be updated independently because the bytes do not change. Update iOS and Android
  hosts first; a new host with an old Worker is wire-compatible (the Worker's repair writes and
  removes are ordinary mutations). The live `VFS_CONFLICT` ordering rule is new in the Worker, so
  rebuild the bundle with the hosts: an old Worker that sends the conflict before the covering
  mutation can still make the new host take the old terminal path. Rolling a host back leaves the
  same WAL and manifest; no cleanup is required. There is no adapter and no dual-write.

## 4. Ownership and evidence

Disjoint paths; the contract owner only touches `spec/**` and this record in the freeze.

| Implementer | Exclusive paths | Exact acceptance command |
| --- | --- | --- |
| iOS | `Sources/PhoneDSHLog/{Materializer,RecoveryManager,MaterializedInvariant,WorkspaceLogService,WorkspaceLog}.swift`; `Sources/PhoneDSH/PhoneDSHHost.swift`; `Sources/PhoneDSHLoopback/ChannelServer.swift`; `Tests/PhoneDSHLogTests/**`; `Tests/PhoneDSHTests/**` | `swift test --filter PhoneDSHLogTests` then `swift test --filter PhoneDSHTests`; `bash scripts/ci/check.sh --only phone-sdk` |
| Android | `android/core/src/main/kotlin/com/phonedsh/log/**`; `android/dsh/src/main/kotlin/com/phonedsh/dsh/host/**`; `android/controller/src/main/**`; tests `android/core/src/test/kotlin/com/phonedsh/log/**`, `android/dsh/src/test/kotlin/com/phonedsh/dsh/host/**`, `android/controller/src/test/kotlin/com/phonedsh/controller/**` | `cd android && ./gradlew :core:test :dsh:testDebugUnitTest :controller:test` |
| TypeScript Worker (P01 order) | `plugins/worker-entry/src/entry.ts` (send every mutation already made on the path before the `VFS_CONFLICT`); new `plugins/tests/worker-entry-resync-order.test.ts` | `cd plugins && npm run typecheck && npm test` (`phone-plugins`, SKIP without node_modules) |
| Rust (third codec implementer) | `shell/crates/pdsh-wal/src/replay.rs` (retained-sequence supersession predicate; `appended_replay` stays the three-way per-record base check), `shell/crates/pdsh-wal/tests/replay.rs`, `shell/crates/pdsh-jni/src/{wal,lib}.rs` (export for Kotlin) | `bash scripts/ci/check.sh --only phone-rust-tests` (runs `cargo test -p pdsh-wire -p pdsh-wal`) and, for the JNI export, `cd android && ./gradlew :core:test` |
| Contract owner | `spec/**`, this record | `bash scripts/ci/check.sh --only phone-spec,phone-p0-conformance,phone-cross-tree,phone-license,phone-gate-contracts` |

**Worker ordering note (review decision 2).** Before changing `plugins/worker-entry/src/entry.ts`,
the TS Worker implementer must first check whether `plugins/worker-entry` and the send sites in
`plugins/channel` already flush frames in mseq order on the same ordered channel. If they do, the
guarantee is already met by construction: add the test that drives the real ordering path and do not
change the implementation. The table's `entry.ts` edit is conditional on that check.

**Rust decision.** `appended_replay` is decided in the Rust kernel
(`shell/crates/pdsh-wal/src/replay.rs`, exported through
`shell/crates/pdsh-jni/src/wal.rs` and called by Kotlin `Materializer`). The fourth verdict needs the
retained record sequence after `m`, so it cannot be the per-record three-way function. The freeze
therefore assigns the retained-sequence predicate to Rust as well, so D9 has one authority for the
Kotlin path; Swift implements the same predicate natively. The contract text names no language. The
retained-sequence predicate must return the verdict together with the covering mseq, because a
`settled-superseded` verdict alone must not advance the watermark; Kotlin and Swift advance it only
when that cover's mseq is inside the contiguous prefix (wal.md §6.2, §7.1).
The Worker ordering rule is a P01 change in `plugins/worker-entry/src/entry.ts`: it adds no frame or
field, only the order in which the Worker flushes an already-made mutation before its existing
`VFS_CONFLICT`.

**Shared fixtures.** `spec/v1/golden/wal/replay/*.json` (the 14 valid/invalid replay vectors,
including `settled-watermark-waits-for-cover` with `expected.watermark_steps`) and
`spec/v1/golden/perturbations/drain-outstanding-resync-{answered,deadline-failed}.json`. Both ends
must feed the real recovery/state-machine code with `copy`, `materialized_upto_mseq` and `records`
(not a hand-written copy), and must preserve the per-step manifest watermark: a
`settled-superseded` verdict does not count until the cover's mseq is inside the same prefix. The
spec checker is **not** runtime conformance.

**Spec-freeze commands and results (this worktree; base `451429ac`):**

- `bash scripts/ci/check.sh --only phone-spec` → **PASS** (88 files, 195 spec-tool tests; the new
  `settled-watermark-waits-for-cover` vector, its two mutation tests and the required-text mutation
  test are included).
- `bash scripts/ci/check.sh --only phone-cross-tree` → **PASS** (8/8 invariants; registry 22 contracts,
  280 references; schema diff 0 breaking).
- `bash scripts/ci/check.sh --only phone-rust-tests` → **PASS** (the Rust codec was not changed; this
  is the extra P01/P02 gate from AGENTS §5).
- `bash scripts/ci/check.sh --only phone-p0-conformance` → **FAIL**, pre-existing at `451429ac`:
  `ui_check` reports stale `ChatPage.swift:4599` (`provenance.colors.sessionOnAccent` expects
  `.white`). A clean `451429ac` checkout reproduces the same single failure; this freeze changes no
  Swift/UI file.
- `bash scripts/ci/check.sh --only phone-license` → **FAIL**, pre-existing at `451429ac`: five
  Android test/tool files lack SPDX headers. The five are unchanged by this freeze; a clean
  `451429ac` checkout reproduces the same five findings, and no changed spec/doc file is reported.
- `bash scripts/ci/check.sh --only phone-gate-contracts` → **FAIL**, pre-existing at `451429ac`:
  `selftest.py::ProductionScanTests.test_production_scan_does_not_descend_into_generated_dirs`
  inherits the pre-existing hygiene hostname findings in unchanged Android files. A clean
  `451429ac` checkout reproduces it; the other gate-contract suites pass.

Implementer commands above are the acceptance commands, not results claimed by this freeze. The
three `FAIL` stages are the same failures on the untouched base; they are not introduced by this
diff. The base comparison ran the same three stages in a clean clone checked out at `451429ac` and
found the same failure findings and the same per-suite test counts.

**Remaining risks / not verified:** no iOS/Android/Rust/Worker implementation was changed; only
the existing Rust gate was run. The device WAL, power-loss, real Worker behavior and UI evidence are
not measured by this freeze. The conservative watermark is checked on the spec reference model and
its fixtures only; the implementers must expose the per-step watermark and drive the 14 vectors
through real code. GAP-12 (iOS periodic close) and GAP-13 (`pendingResyncPaths` unwired) stay
registered and open until the iOS implementer lands the regression and records runtime evidence;
the main session's round-2 decision 4 keeps the registration unchanged. The drain fixtures are
`spec-only` until then.

## 5. Revision log

| revision | by | reason (including CONTRACT CHANGE REQUESTs) | notified ends |
| --- | --- | --- | --- |
| r1 (2026-11-04) | contract owner | initial freeze of CCR-1 and CCR-2 from `fleet-plans/pr14-handover-20261103/wal-resync-rootcause.md` §7.4; no CCR was rejected | iOS, Android, Rust, reviewer |
| r2 (2026-11-04) | contract owner | round-2 review: (1) intervening-append reading **已確認**; (2) Worker sends completed same-path mutations before `VFS_CONFLICT` **已確認**, with the verify-existing-order-first instruction for the TS implementer; (3) the weaker settlement is replaced by the conservative rule (cover applied and inside the watermark; `watermark_steps` fixture/checker/mutation test; `wal.md` cap 398 → 412); (4) GAP-12/GAP-13 **維持登記** for the iOS implementer. No CCR was rejected; no wire/WAL/manifest format change | iOS, Android, Rust, TS Worker, reviewer |
