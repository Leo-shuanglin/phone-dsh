# Agent collaboration

Applies to the whole repository, including Swift, TypeScript, Kotlin and Rust.
`CLAUDE.md` points to this file so all agents follow the same instructions.

## Read before changing code

1. Read [CONTRIBUTING.md](CONTRIBUTING.md) and the
   [protocol baseline](spec/README.md).
2. Record the working tree, branch, HEAD and task scope. Preserve unrelated work;
   an old checkout, proposal or another agent's candidate is not the current baseline.
3. Use the baseline registry to find the affected contract, both ends of the
   boundary, and its tests. Read those sources before proposing a new abstraction.

## Protocol baseline is a collaboration requirement

- **Contract before implementation.** For a change to a message, public interface,
  persisted format or observable state transition, identify its contract ID and
  record the delta using [the change template](docs/protocol-change-template.md)
  in the task or PR. A local refactor with identical boundary behaviour says
  `Protocol impact: none` and explains why; it needs no separate design document.
- **One authority per boundary.** Amend the owning specification or code contract
  and its fixtures together. Do not copy a schema, invent a second envelope, rename
  a field in one consumer, or weaken validation to make one platform pass.
- **Separate target from implementation.** `[NEW]`, draft and reserved methods are
  not available features. The bridge target and current feature bridges differ;
  see the baseline's open items. Never migrate a caller to a target format without
  its receiver, compatibility decision and integration evidence.
- **Compatibility is boundary-specific.** Follow the owning version/unknown-field
  policy. Do not apply protobuf, JSON-RPC or host-event rules to every phoneDSH
  protocol. No new wire fields, frame types, capabilities or protocol dependencies
  solely to fit a coding style or an agent's convenience.
- **Preserve semantics.** Keep correlation IDs, sequence domains, cancellation,
  deadlines, idempotency, backpressure, durability and security checks explicit.
  Acceptance, durable storage and external execution are different observations.
  See the baseline invariants before changing retry or recovery behaviour.

## Coordinating several agents

The task coordinator acts as contract owner unless the task assigns that role.
This is a review responsibility, not a requirement for an extra human approval.

1. The coordinator fixes a base commit, contract IDs, intended delta, compatibility
   decision, file ownership and acceptance commands before dependent coding starts.
2. The contract owner supplies the normative change and shared positive/negative
   fixtures. Independent work may proceed; dependent producers/consumers use the
   same reviewed contract revision. Record contract changes in the task/PR.
3. Implementation agents own distinct files/worktrees and name both ends of any
   affected boundary. They report a needed contract change to the coordinator;
   they do not silently fork the interface or edit another agent's shared files.
4. The integrator checks the combined diff, both implementations and migration
   order. Spec tests alone do not prove runtime conformance. Resolve a disagreement
   in the owning contract and regression fixtures, then update every affected end.
5. Handoff: base/head, contract IDs, changed files, implemented versus open items,
   exact commands/results, platform limitations and remaining risks. Preserve
   `FAIL`, `SKIP`, `INCOMPLETE`, `NOT_RUN` and `NOT_VERIFIED` as such.

Do not start additional agents unless the user's task or applicable instructions
authorize delegation. These rules describe how to coordinate authorized work.

## Verification and repository boundaries

- Use the baseline's existing gate matrix; `bash scripts/ci/check.sh --list` is
  the current command inventory. Run checks appropriate to the actual diff.
- Behaviour changes need a regression exercising the real owning boundary. Reuse
  shared golden vectors for cross-language codecs, including invalid input and
  the failure/reconnect path that changed. Keep checker mutation tests effective.
- Do not delete fixtures, reduce validation, increase an allowlist/baseline, or
  mark a check skipped just to obtain green output. Explain intentional changes.
- No DSH source patching or vendoring. Preserve the narrowly documented pack-time
  exception in CONTRIBUTING; upstream needs go under `docs/upstream/`.
- Preserve Package.swift/Android module boundaries and the shared Worker source.
  Use `spec/platform` for justified OS differences. Rebuild affected Worker assets
  before claiming an app runs new plugin code.
- Add a new generator, gate, hook, protocol layer or dependency only for a concrete
  gap that existing tools cannot cover. Document its cost and proof of benefit.
- Do not infer device, power-loss, model E2E or deployment success from static
  checks, unit tests or a simulator. Report only what was actually run.
