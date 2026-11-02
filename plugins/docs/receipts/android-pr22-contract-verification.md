# Android PR22 — plugin workspace contract verification (r-plugins)

SPDX-License-Identifier: MIT

Evidence-only receipt. This run verified the pinned plugin workspace; it did **not**
change plugin runtime logic, dependencies, lockfiles, tests or gates.
No plugin contract defect was found, so the only committed change is this receipt.

## Candidate binding

| Item | Value |
|---|---|
| Tested candidate `HEAD` | `346676caa8b15cc2a458c32e6a8329d2770a8ea9` ("Integrate Fleet candidate") |
| Frozen campaign baseline | `714348ed33bbb8ae2adac71659b7d662670969e9` |
| `HEAD:plugins` subtree | `0407fa842599230275831bc7685fe7b19d908392` |
| `714348e:plugins` subtree | `0407fa842599230275831bc7685fe7b19d908392` |
| Plugins files changed baseline..candidate | **0** (`git diff --name-only` empty) |
| Worktree before commit | clean (no `node_modules` or test caches staged) |

The plugins tree is byte-identical to the frozen baseline: the shared
shell-ext/mux/main integration did not touch `plugins/`. Per the task rule
("if a plugin was not touched by the merge, leave its logic unchanged and say
so"), no plugin source was edited.

## Exact commands, exits and counts

All commands were run from `plugins/`.

| # | Command | Exit | Result |
|---|---|---|---|
| 1 | `npm ci` | **0** | 717 packages installed from the committed `package-lock.json`; postinstall re-applied the just-bash terminal-state seam |
| 2 | `npm run typecheck` | **0** | `pretypecheck` (ensure-deps + patch-just-bash + wasi dev deps) then `tsc -p tsconfig.json`; no diagnostics |
| 3 | `node scripts/run-tests.mjs "tests/shell-ext-contract.test.ts" "tests/shell-ext-bridge.test.ts" "tests/shell-ext.test.ts"` | **0** | **31/31 pass**, 0 fail, 0 cancelled, 0 skipped |
| 4 (supplementary) | `node scripts/run-tests.mjs "worker-entry/src/mux-bridge.test.ts" "worker-entry/src/mux-projection.test.ts"` | **0** | **24/24 pass**, 0 fail, 0 cancelled, 0 skipped |

Targeted slice breakdown (31 total):
`tests/shell-ext-contract.test.ts` = 11, `tests/shell-ext-bridge.test.ts` = 9,
`tests/shell-ext.test.ts` = 11.

## Coverage exercised by the executed tests

- **Command routing** — advertised accelerators forward `argv`, cwd,
  `requires_mseq` and the D10 `idem_key`; an unadvertised `rg` keeps running
  Worker-local; an unknown program is `127` plus the capability-manifest hint;
  a shell-exported identity/session cannot shadow the trusted envelope.
- **Caps** — host accelerator allow-list (`advertisedAccelerators`), the
  `pdsh-capabilities/1` manifest referent, and a refused 15 MiB write reported
  as `reason=limit-refused`.
- **Denial** — execution-surface read refusals render the contract `EACCES`
  sentence through the shared denial layer; filesystem/symlink cases fail closed
  instead of escaping or lying.
- **Output budgets** — truncation keeps head and tail with the exact
  dropped/total byte counts; stdout and stderr are bounded at `maxOutputBytes`;
  a cut stream exits non-zero with `reason=output-truncated`.

The supplementary mux slice covers the `/api/remote.mux` send/receive/close
bridge and the projection seam.

## Sources reviewed

- `plugins/package.json` and `plugins/package-lock.json` (lockfile pins unchanged:
  `just-bash 3.6.0`, `@deepseek-ai/dsh 0.2.1-rc.2`, `typescript 5.9.3`; 16
  declared workspaces, 15 present in the lock root plus the root package).
- `shell-ext` contract/bridge/shell/output/capabilities/exec-denial sources,
  `channel/src` frame/WAL/hello sources, and `worker-entry/src` mux sources.

## Fixture and safety boundaries

- `NO_PROXY` appears once, only inside the labelled local fake-transport fixture
  `tests/wasi-python-network.test.ts:84`; it is **not** used for app networking.
- No real model/provider calls, billing, messaging, secrets or credentials.
- No admin/service changes; no host/kernel changes; no git push, merge, PR or
  deploy; `.github` untouched.
- No dependency added or upgraded, no package-manager switch, no generated
  `node_modules` committed.

## Environment

- Date (UTC): `2026-11-02T20:14:24Z`
- Node `v22.22.3`, npm `10.9.8`
- `HOME=/work/home`, `XDG_CACHE_HOME=/work/cache`
- Host/toolchain: Linux container; Android SDK/emulator not used.

## NOT_RUN

- Full workspace `npm test` and the contract/compat gates — outside this run's
  required slice; the final `h-verify` lane owns the full plugin suite.
- Device/emulator, APK, and installed-App checks — not applicable to a plugin
  workspace verification; no Linux result is claimed for them.
