# phoneDSH 協議基線與變更協作

本頁是所有 coding agent 的協議入口。它登記權威來源、責任邊界和變更流程；各協議的
欄位、位元組與狀態機仍由下表的 owning contract 定義。`AGENTS.md` 是協作指令唯一來源，
`CLAUDE.md` 連到同一檔案。

初次盤點：2026-11-03，public main `fe33f75f46e970c051668027d85e9748b8f591ce`。
這是可追溯的起點，並非要求後續工作永遠留在此 commit；每次任務另記 base/head。

## 1. 權威與實作的關係

- **規範**：下表登記的 normative contract 決定語義，schema 決定形狀，golden 決定具體
  編碼與邊界案例。三者衝突就是缺陷，需要同一變更修正並說明相容性，不能自行挑一份。
- **實作**：Swift 是參考實作，Kotlin 和 TypeScript 實作同一邊界時共同遵守規範。
  實作與規範不一致時登記差異，不能把一端現狀自動升格為跨端標準。
- **尚無語言無關規範的內部邊界**：下表明列 owning code contract；先保留現有形狀，
  變更時補足兩端與測試。只有出現獨立版本或多實作需求時才抽出 schema／生成器。
- **提案與預留**：`docs/design`、`[NEW]`、reserved、draft 不代表已實作或可呼叫。
  同一文檔中也要逐條讀狀態。測試的證據範圍不能大於它真正執行的範圍。

協議不限於網路封包。跨模組公開 API、Worker 訊息、持久化檔案、事件序列及副作用的
重試／取消／恢復承諾都屬於契約。函式內部的無行為變動重構不需要另寫協議。

## 2. 協議登記表

ID 是 review 與任務交接的索引，**不是新增的 wire 欄位或能力名稱**。Owner 是任務角色，
不是預設指定某位同事；協調者可兼任，實作 agent 不得單方面重定義共同邊界。

| ID／責任角色 | Owning contract | 生產端／消費端與既有證據 |
| --- | --- | --- |
| P01 channel／wire owner | [v1/channel.md](v1/channel.md)、[v1/schema](v1/schema)、[v1/golden/frames](v1/golden/frames) | [Swift wire](../Sources/PhoneDSHWire/) ↔ [TS channel](../plugins/channel/src/)；[Kotlin wire](../android/core/src/main/kotlin/com/phonedsh/wire/) + Rust `shell/`；[Swift goldens](../Tests/PhoneDSHWireTests/GoldenFrameTests.swift)、[TS goldens](../plugins/channel/src/golden-frames.test.ts)、[Kotlin goldens](../android/core/src/test/kotlin/com/phonedsh/wire/WireGoldenFramesTest.kt) 共用 frame goldens |
| P02 VFS／WAL／log owner | [v1/wal.md](v1/wal.md)、[v1/golden/wal](v1/golden/wal) | `Sources/PhoneDSHLog/`、`Sources/PhoneDSHWire/WAL.swift` ↔ `plugins/channel/src/wal.ts`；Swift `WALTests`、`PhoneDSHLogTests`，Kotlin `NativeWalGoldenTest` |
| P03 exec／journal／controller owner | [v1/exec.md](v1/exec.md)、[v1/handoff.md](v1/handoff.md)、[v1/budgets.md](v1/budgets.md) | `Sources/PhoneDSHController/`、`Sources/PhoneDSH/` ↔ `plugins/exec-bridge/`、`tool-journal/`、`lifecycle-gate/`；`StepMachineJournalContractTests`、`SideEffectLedgerTests`、`PhoneDSHDrainRecoveryContractTests`、`plugins/tests/journal-sync.test.ts` |
| P04 page bridge／engine owner | [v1/bridge.md](v1/bridge.md)、[bridge schema](v1/schema/bridge-message.schema.json)、[goldens](v1/golden/bridge) | **規範含未實作目標**；Swift `WebEngineNativePreview.swift` ↔ `plugins/phone-native-preview/src/protocol.ts` 仍是 feature bridge；directory import／console 同樣需查實作，見 §5 |
| P05 HTTP／MCP gateway／security owner | [v1/gateway-security.md](v1/gateway-security.md)、[v1/exec.md](v1/exec.md) | `Sources/PhoneDSHGateway/`、`Sources/PhoneDSHLoopback/` ↔ `plugins/llm-bridge/`、`phone-mcp/`；`Tests/PhoneDSHGatewayTests/`、`plugins/tests/phone-mcp.test.ts`；`phone-mcp/src/protocol.ts` 擁有本地設定與 Remote payload 型別，不代表完整外部 MCP 規範 |
| P06 harness／host contract owner | [host/v1/README.md](host/v1/README.md)、[event crosswalk](host/v1/event-kinds.md)、[host goldens](host/v1/golden) | [AgentHarness](../Sources/PhoneDSHAgentCore/AgentHarness.swift)、[DSHRemoteHarness](../Sources/PhoneDSHAgentCore/DSH/DSHRemoteHarness.swift)、[DSHEventMapper](../Sources/PhoneDSHAgentCore/DSH/DSHEventMapper.swift) ↔ [Kotlin AgentHarness](../android/core/src/main/kotlin/com/phonedsh/agentcore/AgentHarness.kt)／UI 消費者；[AgentCore tests](../Tests/PhoneDSHAgentCoreTests/) |
| P07 device／connector owner | [host/v1/README.md](host/v1/README.md) 的 envelope／caps／receipt schema；[adapter draft](../docs/design/device-adapter-contract-draft.md) | 固定的是 host 映射形狀；第三方設備傳輸協議及端到端執行證據不由 schema 自動保證。新增 adapter 需另列實際 sender／receiver／receipt 測試 |
| P08 OS differences／platform owner | [platform/README.md](platform/README.md)、[whitelist](platform/whitelist.json) | Swift OS API ↔ Android `:platform`／`:engine`／`:net`；只登記有根據的機制差異與證據等級，不以 whitelist 代替 conformance |
| P09 UI projection／UI owner | [ui/v1/README.md](ui/v1/README.md)、[tokens](ui/v1/tokens.json)、[pages](ui/v1/pages)、[v1/trajectory.md](v1/trajectory.md) | 原生／Web UI 的事件、狀態、互動與平台差異；`ui_check.py`、`page_structure_check.py`、`trajectory_norm.py` 和平台投影測試，不能用更改 tokens 掩蓋事件語義差異 |
| P10 workflow isolate／Worker owner | **code contract** [protocol.ts](../plugins/workflow-worker/src/protocol.ts) | parent ↔ isolate 的 structured-clone 訊息；[workflow tests](../plugins/tests/workflow-worker.test.ts)。不傳 Agent、token、VFS handle 或 function；沒有獨立 wire version，不冒稱 proto 1 |
| P11 WASI VFS proxy／toolbox owner | **code contract** [vfs-protocol.ts](../plugins/wasi-toolbox/src/vfs-protocol.ts)、[types.ts](../plugins/wasi-toolbox/src/types.ts) | async postMessage／SAB 共用 codec；u32 JSON 長度為 **little-endian**，與 P01 的 network byte order 不同；[proxy tests](../plugins/wasi-toolbox/src/vfs-proxy.test.ts)、[SAB tests](../plugins/wasi-toolbox/src/vfs-sab.test.ts)。不能套用 P01 frame header |
| P12 Rust native bridge／shell owner | **code contract** [shell README](../shell/README.md)、[Swift FFI](../Sources/PhoneDSHShell/)、[Kotlin JNI](../android/shell/src/main/kotlin/com/phonedsh/shell/) | Swift／Kotlin ↔ Rust；實際 binary artifact、ABI、平台及錯誤路徑須一起核對。以 [Package.swift](../Package.swift)、Android build wiring 和相應 shell tests 判斷可用性，不能從 source 存在推斷已連結 |
| P13 plugin Remote／plugin owner | **code contracts** [phone-shell](../plugins/phone-shell/src/contract.ts)、[feature-admin](../plugins/phone-feature-admin/src/contracts.ts)、[MCP](../plugins/phone-mcp/src/protocol.ts)、[directory import](../plugins/phone-directory-import/src/types.ts) | Worker host ↔ Web client／Remote caller；[shell tests](../plugins/tests/phone-shell.test.ts)、[admin tests](../plugins/tests/phone-feature-admin.test.ts)、[MCP tests](../plugins/tests/phone-mcp.test.ts)、[import tests](../plugins/tests/phone-directory-import.test.ts)。本地 config／Remote API 版本與外部 MCP、P01 channel 版本分開 |

這是本輪已盤點的主要跨邊界索引，不宣稱涵蓋每個 plugin Remote endpoint。新增或發現
尚未登記的公共邊界時，補上 contract、兩端、版本／錯誤／證據，再分派實作。

## 3. 不同邊界的相容性

| 邊界 | 已有規則；變更時必須保持的差別 |
| --- | --- |
| P01 `proto: 1` | HELLO 協商版本與能力，缺少能力不可使用；不同 proto 拒絕。新增 frame type、flag、required member 或改既有語義需新 major，依 v1 README §2。合法 JSON 的 decoder 不可要求 canonical 排版；encoder 仍遵循 schema 與 canonical JSON |
| P04 `bridge 1` target | schema 關閉未知欄位，`requestId`／method／terminal 規則由 bridge.md 定義。不得套用 P06 的忽略未知欄位策略；也不得把這個 target 的 `v:1` 當成現有 preview feature 的 `v:1`，兩者不是同一格式 |
| P06／P07 host contract 1 | decoder 的未知 member／event／capability 處理依 host README §2；事件種類可加，但 required member、移除／改名／改語義及縮小有效範圍需 major。未知 durable event 仍消耗 cursor；best-effort 沒有 durable seq |
| P02 persistent data | 版本、checksum、順序、原子提交與恢復規則依 wal.md。讀寫兩端、舊資料、降級／回滾路徑一起評估；不能只更新 Swift Codable 型別 |
| P10／P11／P12 code contracts | 同次建置配對不代表可以讓舊 bundle 或 native artifact 消費新格式。若兩端不能原子更新，先設計版本辨識或 adapter；沒有現成 negotiation 就如實記錄，不偷偷新增自訂版本欄位 |

相容性至少檢查 old producer→new consumer、new producer→old consumer。只要有不合法的
組合，就記錄拒絕方式、部署順序與回滾限制；不適用時說明理由。未知欄位／方法、缺省值、
null、整數上限、浮點、字元編碼及 max payload 都是相容性的一部分。

## 4. 必守語義

1. **序號有各自生命週期**：channel `seq` 每連線每方向；VFS `mseq` 持久；journal
   `jseq` 每 session；harness durable event `seq` 是 cursor，容許 gap。不可互換或用差值猜遺失數。
2. **確認要對應承諾**：WAL L1 承諾與 L2 epoch durability 分開；HTTP／bridge／MQTT 的
   接受回覆不是外部動作已完成。設備執行需對應 command 的 applied／failed receipt。
3. **取消與超時不證明副作用沒有發生**：保留同一操作的 idempotency／fence／結果查詢語義，
   不能以新 ID 盲重送。現有 `SideEffectLedger` 是 exec／MCP replay 機制，不能推論成任意
   遠端 API 的 exactly-once 或 generic unknown-result reconciliation。
4. **恢復需重新確認權威**：HELLO、incarnation、fence generation、lease、WAL／journal
   watermark 都有 owning rule。舊 Worker 的 ready／ACK 不可自動當成新 Worker 的授權。
5. **有界與隔離**：保留 payload／buffer／queue／timeout／budget 上限與可觀察錯誤；token
   留在 host 的既定 credential 機制；origin、main-frame、workspace path、capability 檢查
   不得為方便測試而放寬。通知可以 best-effort，durable event 不得冒充通知丟棄。
6. **數值不可默默失真**：binary u64、JSON number、Swift Int／Double、JS number／bigint
   的有效域不同。使用 owning codec，保留合法浮點；不可經 `String`／`Int` 任意轉換。
   全 u64 JSON 相容性仍有 §5 的缺口，不可聲稱已跨端解決。

## 5. 已知差異：維護債，不是實作許可

以下是固定盤點 commit 的 source review。尚未以真機／完整 runtime 證明的部分維持未知；
本輪標準化沒有改訊息格式或宣告它們已修復。後續修正要附對應 contract delta 與 regression。

| ID／責任 | 觀測 | 下一步與關閉條件 |
| --- | --- | --- |
| GAP-01／P04 engine owner | bridge.md §11–12 已把 unified name／envelope 等標為 `[NEW]`；preview 的 TS `NativePreviewMessage` 與 Swift `WebEngineNativePreviewRequest.parse` 實際是 `{v,id,sessionId,path}`，回覆 `{id,ok,code,message}`，不是 `{v,kind,requestId,method,...}`。現有 feature tests 不等於 unified bridge goldens 的 runtime 測試 | 遷移需同時處理 caller、receiver、shim、錯誤／取消／timeout、平台 scope 與舊 bundle 策略；兩端 runtime conformance 通過才可把對應條款改為 implemented。現階段保持 legacy 形狀 |
| GAP-02／P01 wire owner | [hello schema](v1/schema/hello.schema.json) 表達 u64 整數；[TS host-hello.ts](../plugins/channel/src/host-hello.ts) 的 `requireU64Integer` 要求 safe integer；[Kotlin HostHello](../android/dsh/src/main/kotlin/com/phonedsh/dsh/host/HostHello.kt) 水位以 signed Long 表示。schema 合法域不等於各語言 JSON 數值域 | 增加 2^53 附近及 u64 邊界的共同向量，選定相容編碼／有效域與版本方案，讓 Swift／TS／Kotlin 真正讀同一批資料。不得只把 JSON 改字串或把 schema 上限縮小 |
| GAP-03／P01 wire owner | [Swift HelloPayload.validate](../Sources/PhoneDSHWire/JSONPayloads.swift) 要求 `next_mseq == acked_mseq + 1`；TS `parseHostHello` 及 Kotlin `MseqWatermark.satisfiesHelloRules` 只檢查 `next > acked` 等條件。channel.md §4.1 和 wal.md §7.2 的 effective tail 公式要求一起核對 | 用共同 negative HELLO 向量及 host watermark 建構測試驗證拒絕範圍，保留 D17 不重用 mseq，修復不一致。正常 golden 通過不能證明完整驗證域一致 |
| GAP-04／P01 wire owner | Swift `HelloPayload.validate` 拒絕重複或不合法 capability；TS `parseHostHello` 僅確認 string array 和 required capabilities，與 schema 的 pattern／uniqueItems 範圍不一致 | 把重複／不合法 capability 加入共用 negative fixtures，核對每端實際 parser。保留缺少能力不可使用的規則，不用放寬 schema 消除差異 |

只有修正來源、相關端測試及必要的 integration 證據齊備，才在此記錄關閉與修復 commit。
不得為消除紅字把規範改成寬鬆的實作現狀。

## 6. Agent 協作順序

1. **定位**：協調者記 base/head、Pxx、具體 trigger→現況→期望，列出 owning source 與受影響端。
2. **契約 delta**：使用 [變更單](../docs/protocol-change-template.md)，先定 fields／語義／
   failure／compatibility；同步修訂 owning spec/schema/golden，code contract 則改其唯一來源。
3. **固定協作版本**：協調者交付 contract revision、共享 fixture、檔案責任與命令。
   producer／consumer 可並行實作；需要改契約時先回到同一變更單，不能各自補欄位。
4. **驗證整合**：檢查兩端真實 encoder／decoder 或狀態機及受影響失敗路徑，再跑下表。
   review 角色可由 agent 承擔；本流程不額外要求每個小改動都等待人工批准。
5. **交付**：PR 使用 [模板](../.github/PULL_REQUEST_TEMPLATE.md) 記影響與證據；保留未跑項。
   若沒有 protocol impact，記錄理由即可，不建立空白變更單。

## 7. 驗證矩陣：沿用現有工具

| 實際變動 | 命令與證據範圍 |
| --- | --- |
| spec／schema／golden／協議說明 | `bash scripts/ci/check.sh --only phone-spec,phone-p0-conformance,phone-cross-tree`：規範與 fixtures 一致；**不是 runtime conformance** |
| P01／P02 Swift codec | `swift test --filter PhoneDSHWireTests`；依受影響持久化行為加 `PhoneDSHLogTests`。TS 的同一 goldens 測試及 Android/Rust 相應測試也須列在變更證據中 |
| Worker／TS | `cd plugins && npm run typecheck && npm test`；`phone-compat` 確認既定 WebView 語法/API 基線。依賴未安裝不是通過；依既有 README 準備依賴 |
| host／controller／gateway／harness runtime | 對應 Swift test target 與回歸；host path 按 CONTRIBUTING 跑 `bash scripts/ci/smoke.sh`。需要 Worker bundle／image 或模型的項目，分別記錄是否真的具備並執行 |
| Android 實作／native ABI | `phone-android-structure`、`phone-android-tests`；影響產物時加 `phone-android-release`。建置／JVM 不等於 WebView／真機行為 |
| 公倉交付 | CONTRIBUTING 的 hygiene／license／no-dsh-patch 與受影響 gates；完整清單以 `bash scripts/ci/check.sh --list` 為準 |

`phone-p0-conformance` 內含 UI page baseline ratchet，`phone-cross-tree` 有八項跨樹 invariant，
其中 reference 檢查也驗證本頁與 AGENTS 的本地連結，以及 CLAUDE 指向同一份規則；
兩者通過都不代表每個 runtime contract 或每個 UI 頁面已全面對齊。保留既有
PASS／FAIL／SKIP／INCOMPLETE 口徑，未執行項目記 `NOT_RUN`，無測量證據記 `NOT_VERIFIED`。

既有 gate 已覆蓋 schema、golden、跨樹索引與 checker mutation，本輪不新增通用 hook 或
平行的 protocol framework。[研究依據](../docs/research/protocol-baseline-2026-11-03.md) 解釋
借鑑與不採用的做法；它是研究紀錄，不覆寫本頁登記的 owning contracts。
