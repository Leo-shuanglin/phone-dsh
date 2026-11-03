# PR14 Android / PR22 continuation — owner checkpoint
Date: 2026-11-03. Status: in progress; keep PR14 draft.

## Source binding
- Original PR14: 8c2eeab7b29728ebd0a54b71b5deaab07e5ac6eb.
- Owner integration checkpoint: 2d0ea32250980d4ef467aadfd303c63b8b713c08.
- Current main merged as ancestor: 2695fd21d2d32369c5b3c8b8ae4cc736819b7d74.
- UI authority: iOS PR22 edd687463cd24b9c969914a13fa1da268df016fe plus current-main iOS keyboard/scroll changes. iOS examples/probe-app and Sources remain current-main source; PR23 stays separate.
- No main merge, deployment, or CI workflow restoration.

## Implemented in this checkpoint
- Typed session presentation/actions, independent stop availability, confirmed writes and stale session/root result guards.
- Source-backed composer/header/empty-state geometry, collapse preference binding, extended-grapheme 8000-character limit.
- Native math/images/resources, exact backend Worker session identity and bounded loading; required licenses packaged.
- Actual 320 pt drawer, cutout-safe reachable footer, folder/session actions and confirmed permission-setting callbacks.
- Session metadata-preserving content updates and durable current-session selection across cold launch. Folders are logical groups sharing the physical workspace.
- Current settings grouping, secondary page copy and source-contract extraction fixes. Existing unsupported capabilities remain explicit.

## Evidence
- c175ad51: canonical 12-module JVM run: 2285 tests, 0 failures/errors, 2 existing skips (P4SnapshotRecoveryTest and TrajectoryPythonParityTest). Release App and published-coordinate consumer R8 builds passed.
- b185ee77: six focused Unicode/grapheme tests passed; tooling suite 70 passed; spec tools 165 passed. These are distinct source-bound runs, not a fabricated one-run final count.
- 501eec31: full library Android-test APK and production App debug build passed; 27 real Android instrumentation cases passed on owned API35 ARM64 emulator-5560. Component fixtures cover math, images, resource identity/races, gestures/drawer and transcript tail behavior.
- b185 release consumer: actual minified published-coordinate AAR consumer rendered native math on device. This is a controlled consumer fixture, not a live model conversation.
- 2d0ea322: App JVM suite and full debug APK build passed. Installed production App created a session in task-owned logical folder UI-PR22-QA; force-stop and cold launch retained the folder after fixing the observed regression.
- Source-map unit suite: 6 passed. Provenance and source checks do not establish complete UI or pixel parity.
- Linux Fleet verification at b185/266d6fc: 1087 App/library JVM tests passed under ordinary-owner UID1000. Literal UID0 permission wrapper failed closed; a cross-lane helper repair is undergoing review. Runtime 3286aa5 evidence is retained but not owner-accepted.
- Logs and screenshots are in the owner workspace fleet-review directory; Fleet campaign 12fd46f595df45f68c2531630d2b57ef retains bound Linux artifacts.

## Open / FAIL / NOT_RUN
- Model-provider multi-profile storage/selection, secure editor/source navigation and GetModels UI binding still require implementation and review.
- Configuration namespace loading/schema/CAS/reset callbacks and plugin inventory loading/partial errors still require live host integration. Phone plugin installation/enabling remains unsupported by the iOS source.
- Session metrics/details and subagent catalog/history candidates are under owner review; they are not included in checkpoint 2d0ea322.
- Source page checks: strict FAIL (60), ratchet FAIL (16 new outside 44 existing baseline). Baseline only shrank; no new waivers.
- iOS live Picker/interaction acceptance: NOT_RUN because Mac remains locked; user chose to continue existing verification.
- Physical iPhone/Android, live provider/model requests, complete paired-state pixel acceptance and 16 KB runtime validation: NOT_RUN.
- HTML/3D preview remains the documented process/cookie-isolation limitation.
- PR23 fixes are open separately: https://github.com/launsion-boop/phone-dsh/pull/23.

