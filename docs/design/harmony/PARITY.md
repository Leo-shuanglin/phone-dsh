# Android / HarmonyOS local-emulator acceptance

Status: INCOMPLETE. PR #34 contains the first verified native client; it does not certify whole-product 1:1.

Android source baseline is `bd1dea5ee5cc0576eb75fb45d8eb97107c92e275`. The frozen first Harmony commit is `c5aec33824f468f76ac324df5d116fe7a56c564f`. Android is rebuilt from this checkout with the same unmodified Android source and the pinned Worker bundle, rather than reusing a cached APK with a different hash.

Both local emulators use a 1080 × 2340 display at 2.625× logical scale (420 dpi). The initial Android capture used 480 dpi and is retained as a pre-calibration artifact, not a same-frame pixel comparison. Harmony DisplayManagerService reports VirtualWidth 411, VirtualHeight 891 and Density 2.625; the corrected paired logical width is approximately 411.43. The Android AVD is `PhoneDSH_Android35_Parity` on port 5580; the existing HarmonyOS API20 phone emulator uses HDC 127.0.0.1:5555. System bars, fonts and OS dialogs must be reported separately from app geometry; they are not silently treated as equal pixels.

## Route coverage

A route is not accepted merely because its component exists. Each row needs corresponding Android/Harmony screenshots and interaction readback. `PENDING` means no matched run is complete.

| Harmony route | Paired emulator result | Required evidence |
| --- | --- | --- |
| Chat | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Sessions | PENDING | Native route screenshot, actual data state, actions and back navigation |
| WorkspaceFolders | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Files | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Automation | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Configuration | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Settings | PENDING | Native route screenshot, actual data state, actions and back navigation |
| ModelProvider | PENDING | Native route screenshot, actual data state, actions and back navigation |
| WebSearch | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Mcp | PENDING | Native route screenshot, actual data state, actions and back navigation |
| McpEditor | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Runtime | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Egress | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Workbench | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Tools | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Export | PENDING | Native route screenshot, actual data state, actions and back navigation |
| DeviceConnection | PENDING | Native route screenshot, actual data state, actions and back navigation |
| DeviceSession | PENDING | Native route screenshot, actual data state, actions and back navigation |
| SessionArtifacts | PENDING | Native route screenshot, actual data state, actions and back navigation |
| AgentPresets | PENDING | Native route screenshot, actual data state, actions and back navigation |
| PluginInventory | PENDING | Native route screenshot, actual data state, actions and back navigation |
| PhonePermissions | PENDING | Native route screenshot, actual data state, actions and back navigation |
| ChatPreferences | PENDING | Native route screenshot, actual data state, actions and back navigation |
| SettingsDocument | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Shortcuts | PENDING | Native route screenshot, actual data state, actions and back navigation |
| FontSize | PENDING | Native route screenshot, actual data state, actions and back navigation |
| ArchivedSessions | PENDING | Native route screenshot, actual data state, actions and back navigation |
| About | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Licenses | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Preview | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Probes | PENDING | Native route screenshot, actual data state, actions and back navigation |

## Interaction coverage

| Scenario | Current evidence | Paired acceptance |
| --- | --- | --- |
| Model/tool/stop/cold recovery | Prior Harmony real-model proofs in VERIFICATION.md | Android real model reply passed; paired tool/stop/recovery still PENDING |
| Structured questions | Old HAP reproduced first-question-only failure; live model read back first=A1 and second=X2,Y2 after routing fix | Harmony PASS; Android comparison PENDING |
| Rich text code and tables | Both emulators rendered syntax-highlighted RICH8433 and four narrow-table cards. Harmony copy feedback and wrap toggle observed. | Nonzero: table leading/Android header placement; long block, dark and streaming interaction pending |
| Queue, steer, remove and approvals | Existing Android owning flow and Harmony implementation under audit | PENDING |
| Files, import/export, resource/image viewer | Basic Harmony read/write and binary cold proof already recorded | PENDING complete type/action matrix |
| Settings/provider/automation/folders | Prior Harmony CRUD/readback/restoration proved | PENDING Android matched run |
| Background/Ubuntu/device/hardware | Android/Harmony platform semantics differ | PENDING actual capability/constraint evidence |

No physical Harmony device, real power loss or external device link can be certified by these emulator screenshots. P04 unified bridge remains a separate target contract, not a format to invent during UI work.

## Open review cases

Pending questions across event-stream disconnect/reconnect, timed/continued answers and session switches still need runtime evidence. A captured client ID must not be rewritten speculatively without checking the owning DSH broker semantics. Duplicate/empty question IDs and stale/incomplete answer maps are now rejected atomically by the actual helper tests; explicit per-question skips remain valid.

Calibration correction: the earlier 360dp Android bubble example remains a valid independent rendering regression, but the old Harmony screenshot was not a 360vp viewport. The user-approved shared 80% rule remains unchanged. Two Compose cases reproduce the original Android double inset (256 vs224 at360,192 vs160 at280), and both pass after the caller fix; CJK intrinsic width is checked separately against the maximum. No old image is overwritten.

User geometry decision: shared specification wins on both ends. Android call-site measurement is corrected without changing the public API or token. New actual Compose measurements pass at360dp and280dp; the old implementation fails at224/160. The CJK fixture verifies its intrinsic width never exceeds the cap. Old480dpi screenshots remain archived and are not compared as same-frame captures.

Checkpoint tests: 102 Harmony Node tests pass; 3 real Android Compose bubble tests pass after a reproducible 2-failure old implementation run. The new Harmony code-card control/ink PNG and fixed Android bubble at 420 dpi are under [parity/checkpoint](parity/checkpoint/). The measured solid bubble widths in this specific mixed-word sample were 768px and 780px under the common 780px cap; this is not a pixel-equality result.
