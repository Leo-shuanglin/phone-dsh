# Android / HarmonyOS local-emulator acceptance

Status: INCOMPLETE. PR #34 contains the first verified native client; it does not certify whole-product 1:1.

Android source baseline is `bd1dea5ee5cc0576eb75fb45d8eb97107c92e275`. The frozen first Harmony commit is `c5aec33824f468f76ac324df5d116fe7a56c564f`. The pre-fix Android reference was rebuilt from that source with the pinned Worker. The post-fix Android APK is rebuilt from this branch, using the same Worker rather than a cached APK.

Both local emulators use a 1080 × 2340 display at 2.625× logical scale (420 dpi). The initial Android capture used 480 dpi and is retained as a pre-calibration artifact, not a same-frame pixel comparison. Harmony DisplayManagerService reports VirtualWidth 411, VirtualHeight 891 and Density 2.625; the corrected paired logical width is approximately 411.43. The Android AVD is `PhoneDSH_Android35_Parity` on port 5580; the existing HarmonyOS API20 phone emulator uses HDC 127.0.0.1:5555. System bars, fonts and OS dialogs must be reported separately from app geometry; they are not silently treated as equal pixels.

## Route coverage

A route is accepted only after both emulators show the matching page and its actions are read back. `Navigation observed` records an actual visit; it leaves content, geometry and behavior open. `PENDING` means no matched run is complete.

| Harmony route | Paired emulator result | Required evidence |
| --- | --- | --- |
| Chat | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| Sessions | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| WorkspaceFolders | Navigation observed on both; Android reports read-only, Harmony CRUD verified separately | Native route screenshot, actual data state, actions and back navigation |
| Files | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| Automation | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| Configuration | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| Settings | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| ModelProvider | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| WebSearch | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Mcp | PENDING | Native route screenshot, actual data state, actions and back navigation |
| McpEditor | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Runtime | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| Egress | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| Workbench | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| Tools | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Export | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| DeviceConnection | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| DeviceSession | BLOCKED on Harmony: no native device connector | Native route screenshot, actual data state, actions and back navigation |
| SessionArtifacts | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| AgentPresets | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| PluginInventory | PENDING | Native route screenshot, actual data state, actions and back navigation |
| PhonePermissions | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| ChatPreferences | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| SettingsDocument | Harmony navigation observed; Android PENDING | Native route screenshot, actual data state, actions and back navigation |
| Shortcuts | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| FontSize | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| ArchivedSessions | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| About | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| Licenses | Navigation observed on both; parity PENDING | Native route screenshot, actual data state, actions and back navigation |
| Preview | PENDING | Native route screenshot, actual data state, actions and back navigation |
| Probes | PENDING | Native route screenshot, actual data state, actions and back navigation |

## Interaction coverage

| Scenario | Current evidence | Paired acceptance |
| --- | --- | --- |
| Model/tool/stop/cold recovery | Prior Harmony real-model proofs in VERIFICATION.md | Android real model reply passed; paired tool/stop/recovery still PENDING |
| Structured questions | Old HAP reproduced first-question-only failure; live model read back first=A1 and second=X2,Y2 after routing fix | Harmony PASS; Android comparison PENDING |
| Rich text code, tables and math | Both rendered RICH8433 code and table cards. Harmony copy feedback, wrap, expand and collapse were observed. A real Harmony reply flowed inline fractions and superscripts with text and rendered a display matrix again after reinstall/relaunch. | Android header regression fixed in Compose; paired final captures, invalid TeX and streaming interaction pending |
| Queue, steer, remove and approvals | Harmony read back three durable items during a live Bash turn, removed the middle item, edited another without changing its ID, and verified file side effects after the queue drained. A busy steer changed the live assistant reply; the turn returned to idle with no queued item. | Harmony queue and steer PASS; Android comparison and approvals PENDING |
| Files, import/export, resource/image viewer | Basic Harmony read/write and binary cold proof already recorded | PENDING complete type/action matrix |
| Settings/provider/automation/folders | Prior Harmony CRUD/readback/restoration proved | PENDING Android matched run |
| Background/Ubuntu/device/hardware | Android/Harmony platform semantics differ | PENDING actual capability/constraint evidence |

No physical Harmony device, real power loss or external device link can be certified by these emulator screenshots. P04 unified bridge remains a separate target contract, not a format to invent during UI work.

## Open review cases

Pending questions across event-stream disconnect/reconnect, timed/continued answers and session switches still need runtime evidence. A captured client ID must not be rewritten speculatively without checking the owning DSH broker semantics. Duplicate/empty question IDs and stale/incomplete answer maps are now rejected atomically by the actual helper tests; explicit per-question skips remain valid.

Calibration correction: the earlier 360dp Android bubble example remains a valid independent rendering regression, but the old Harmony screenshot was not a 360vp viewport. The user-approved shared 80% rule remains unchanged. Two Compose cases reproduce the original Android double inset (256 vs224 at360,192 vs160 at280), and both pass after the caller fix; CJK intrinsic width is checked separately against the maximum. No old image is overwritten.

User geometry decision: shared specification wins on both ends. Android call-site measurement is corrected without changing the public API or token. New actual Compose measurements pass at360dp and280dp; the old implementation fails at224/160. The CJK fixture verifies its intrinsic width never exceeds the cap. Old480dpi screenshots remain archived and are not compared as same-frame captures.

Checkpoint tests: 102 Harmony Node tests pass; 3 real Android Compose bubble tests pass after a reproducible 2-failure old implementation run. The new Harmony code-card control/ink PNG and fixed Android bubble at 420 dpi are under [parity/checkpoint](parity/checkpoint/). The measured solid bubble widths in this specific mixed-word sample were 768px and 780px under the common 780px cap; this is not a pixel-equality result.

Queue checkpoint: API20 with the pinned Worker. The final workbench readback was `EDIT=EDITED8450`, `THIRD=THIRD8450`, `REMOVED_FILE_ABSENT`. The separate steer run observed the Bash tool in progress, followed by the assistant's exact `STEER_APPLIED8492` response and an idle composer. Its first polling window expired before the 45-second tool returned; a later UI readback proved completion. The original queue preference was restored. All 112 Harmony Node tests passed.

Layout checkpoint: 19 Android Compose tests pass. The two new tests reproduce the old failures (152dp instead of the 12dp code action inset; permission summary wider than half the settings row). The Harmony inline Text uses native ImageSpan for TeX and extra leading only between lines. The [native screenshot](parity/checkpoint/harmony-inline-math.png) shows a real restored reply, with an inline fraction, inline superscript, narrow table and display matrix. The 30-line native code fixture expanded and returned to its capped height.

Settings index: [before Harmony](parity/before/harmony-settings-index.png), [after Harmony](parity/checkpoint/harmony-settings-index.png), and [Android reference](parity/checkpoint/android-settings-index.png) are actual 420 dpi captures. A center-column pixel scan measured the first card-to-card gap as 105px before, 194px after and 205px on Android. Settings language changed to English and was restored to Chinese on the new HAP. The top offset, native glyph and OS permission facts still differ. The Android folders page disclosed that this host does not implement create/switch/delete; the Harmony proof in VERIFICATION.md covers its own CRUD. No paired navigation result counts as full parity.
