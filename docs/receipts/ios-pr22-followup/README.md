# PR22 follow-up validation

Base: `2695fd21d2d32369c5b3c8b8ae4cc736819b7d74` (merged PR22 and PR18 fixes).

The Add button previously presented an Edit-titled form and queried a credential
for the empty new-profile id. A single sheet item now carries both the profile
and add/edit mode. Cancel/reopen discards the draft.

The workbench toolbar Picker is now extracted, and segmented choices are compared
as actions. Only the proven false `文件` baseline entry was removed (174→173).
The existing real Android gaps remain visible; strict whole-App parity is not claimed.

Validated on iPhone14ProMax/iOS26.5 simulator:

- Three native `ModelProviderEditorUITests`: PASS (first Add; Edit→Add; Cancel→Add).
- Full UI Python suite: 89 PASS.
- All 33 page contracts up to date; page ratchet: 173 known, zero new/stale.
- Token/source checker: PASS with six existing deferred contrast pairs.
- `git diff --check`: PASS.

Reproduce with `xcodebuild test -project examples/probe-app/PhoneDSHProbe.xcodeproj
-scheme PhoneDSHDeviceUI -destination 'platform=iOS Simulator,id=<simulator-id>'
-only-testing:PhoneDSHDeviceUITests/ModelProviderEditorUITests
-parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO`, after the documented asset
staging and XcodeGen step. Run Python checks from the repository root.

The screenshots compare the title/error state of the production sheet. Before
entered through the product home; after entered through the provider-list preview
route and used its real Add action. No credentials, provider requests or physical
device validation. See `evidence.json` for source/capture hashes and exact scope.

| Before | After |
|---|---|
| ![Before](provider-add-before.png) | ![After](provider-add-after.png) |

## Queue follow-up

Local queued messages can appear before the durable inbox projection returns an
item ID. Delete and Steer previously looked enabled, but their closures returned
without doing anything when that ID was missing. They now share Edit's explicit
unavailable reason until the durable row arrives. Existing durable-row actions
are unchanged.

- Final iOS simulator Debug build: PASS.
- Full UI Python suite: 89 PASS; 33 generated page contracts current.
- Explicit baseline check: 173 known / 0 new / 0 stale; strict parity remains
  FAIL with the same 173 Android differences. No baseline entry was added.
- Token/source check and diff whitespace check: PASS.
- Queue interaction UI tests: NOT_RUN. The three native provider UI tests above
  cover the earlier provider fix at df20c5b; they are not queue coverage.

The two generated ChatPage/runtime contract files and the manual Submit Answers
source anchor were refreshed for the two-line source shift.


## 2026-11-03 — subagent aside availability

Source checkpoint: `a28d4e3f9ac98e24f078b3a328829b75a6bb7c88`. The product catalog previously passed the same active-chat selection callback for both primary open and “在侧边栏打开”; the component's default aside callback could also do nothing. The aside callback is now optional. The chevron and context action render only with a distinct supplied destination; normal session open is preserved. No side-pane architecture was added.

- **PASS:** iOS Simulator Debug build using the existing pack-image-generated Worker input from the same source lineage. Root independently checked both `PhoneDSHProbe` and `PhoneDSHProbe.debug.dylib` with `lipo -archs`: `x86_64 arm64`.
- **PASS:** full UI Python suite, 89 tests, after correcting the shifted manual `提交回答` source anchor.
- **PASS:** extraction check, 33 current contracts; token/source check (the same six AA pairs remain deferred); baseline ratchet 173 known, 0 new, 0 stale, one existing platform whitelist. This is the Android snapshot in this iOS follow-up branch, not PR14's later integration tree.
- **PASS:** final diff whitespace check. Regenerated `chat.json` and `runtime-details.json` only change source line/provenance/id/span metadata. No baseline or waiver entries were added.
- **NOT_RUN:** aside interaction/SwiftUI view tests and physical-device checks. The existing simulator test target does not import this view; no replacement test architecture was added. Prior provider UI checks and queue-source results above retain their original source bindings.

The latest metadata/receipt commit leaves the product Swift source byte-identical to the checkpoint above.
