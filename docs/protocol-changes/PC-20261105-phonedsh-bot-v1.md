<!-- SPDX-License-Identifier: MIT -->

# phoneDSH Bot v1 契约冻结

```yaml
change: PC-20261105-phonedsh-bot-v1
contracts: [P23, P24, P25, P12, P09, P13]
status: frozen
breaking: false
version_decision: Bot schemaVersion=1；Swift 宿主与 Worker 同一 App bundle 配对升级；不改变 P01/P15 wire 或生命周期版本。
base: 07585efb2a71daa2e05f55c83abcfb4a2cb39f4d
contract_revision: 3c7a6efaab5d95b71cbceb7b69d900f5e8665552
```

Protocol impact: P23; P24; P25; P12; P09; P13。

## 1. 识别与角色

- 具体 trigger → 现况 → 期望：原 DSH 工作台没有统一 Bot 事件、工具、行和常驻宿主边界；PR29 的自建 Swift 主循环、Mandate/Ticket 不合并且不得恢复。现在以 dsh-bot `bf4075b0a2e8092b04b726c59a7b085d5a37e6f6` 为唯一上游，WKWebView Worker 中的 DSH session 作为大脑，Swift 宿主承担确定性常驻能力。
- 协调者兼 contract owner / reviewer-integrator：本 session 主 Agent。只有该角色修改 `spec/`、本变更单和共享 fixture，并审查同一集成 head 的合并后 diff。
- 实现者：WP1–WP12 各自隔离 worktree/分支；只在下列独占路径工作。WP7、WP10、WP11、WP12 按依赖排队，不提前开工。
- Owning spec：`spec/README.md` P23–P25、`spec/bot/v1/README.md`、同目录 schema/golden、`spec/ui/v1/pages/bot-{chat,info}.md`；P12 仍由 `Package.swift`/native ABI 规则所有，P09 仍由 `spec/ui/v1` 所有。
- 受影响 producer/consumer：dsh-bot 只读规格与向量导出器；TypeScript `plugins/bot-runtime`、既有 `phone-mcp`/profile；Swift `PhoneDSHBot`、ProbeKit App 胶水、SwiftUI Bot 页面；iOS 通知与后台能力。P01 Rust/Kotlin wire、P15 控制端点和现有 DSH 多 session 工作台不改。Android/HarmonyOS 不在本轮 Bot 实现范围。
- 上游固定：dsh-bot #23 `1730bf08d2dc29fc8c08f3de90ed70ba9d734d1e`、#24 `28437f5d975230766f3f40463fa090d643f63e7c` 已包含；phone-dsh DSH pin 为 `7be5505b0be06009d74891c869063c9cf19a0df0`。
- PR29 只能从 `61c99054` 用 `git show` 移植 SQLiteDomainStore、loopback/App MCP、通知发送；禁止带入 NativeMainAgentAdapter、NativeToolLoop 主循环、Mandate、Ticket、MandateTokenStore。

## 2. 契约 delta

| 项目 | Before → After / 依据条款 |
| --- | --- |
| 协议登记 | 无 Bot owner → P23 BotEventLog/effects、P24 BotToolSurface/memory/wake/observability、P25 BotRows/UserAction；触发路径和 registry 同步生成。 |
| fields / encoding / limits | 新增 v1 JSON schema 与 18 组 golden。事件 `schemaVersion=1`、SQLite 分配 `commitSeq`；工具分 P0/P1；行使用 `botID/seq/updatedSeq/cursor`；LLM 账本记录四类 token、TTFT 和耗时；具体类型、必填、上限以 `spec/bot/v1/*.schema.json` 为唯一权威。 |
| 卡片与工具面 | P0 包含 ask/steps/task/attachment/document/progress/checklist/link、记忆、Task、routine、审批和 SendToUser；P1 table/callout/info/payload/library 仅为 target，不进第一版 persona/App MCP。canvas/product/gift、Mac/Box/Team/CloudAgent/GenerateImage 不开放。 |
| capability / version / unknown | 宿主与 Worker 同 bundle 配对。未知 cardType、数据不足卡片 fail-closed；未知工具拒绝；不新增 `PDSH_*` env。未知 context profile 回退 default。 |
| correlation / sequence / ordering | `commitSeq` 单调无缺口、`eventId` 去重；`updatedSeq` 由投影器分配；P03 journal、P01 seq、WAL mseq 不与 Bot 序号互换。progress 以 cardKey 原地更新。 |
| error / retry / cancel / late response | ActionResult 分 accepted/duplicate/stale/rejected；取消只停当前 DSH 回合并丢弃迟到输出，不重启 runtime；effects 不能因取消推断外部副作用未发生。 |
| idempotency / ACK / durability / recovery | effects outbox 至少一次、lease、去重、dead-letter、needs-reconciliation；接受不等于 durable，更不等于外部执行完成。SQLite 是唯一 Bot 事件权威，不用 JSONL。 |
| origin / auth / scope / redaction | 身份登记、精确认领、Grant、执行点再授权和 deny 优先复用 dsh-bot；token 只存摘要、审计只存参数摘要。UI 动作由宿主盖 owner_direct，不能自行声明信任。模型 key 仍由 phoneDSH 宿主持有。 |
| session / streaming | 每个 Bot 一条连续主对话；Task/routine 后台 session 只投影卡片。运行中 steering 不排队，用户消息优先；流式增量只在内存，最多 10 次/秒；question/steps 后宿主抑制模型收尾。 |
| 记忆 / 主动性 | facts、注入、召回、来源边界、forget/erase 与上游一致。自动抽取、MemoryPolicy 决策、L2 反思、衰减不实现；相应 facts 字段保留，memory-policy golden 不代表功能开放。 |
| 观测 | Watchdog 只观察，每项每单元只发一次 watchdog.flagged；新增 WebContent 终止、温度、电量。只有 front-turn/worker-turn 可用大模型；上下文 profile 逐值复用；第一版只做 contextLint。 |
| P09 页面 | 新增 BotChat/BotInfo target 页面规格；结构/交互照 DSH Bot，视觉只用 phoneDSH tokens；卡片全宽、44pt 命中区、hover 改长按、深浅色同角色。既有页面仅同步生成器和真实源码 provenance，不改变行为。 |
| P12 模块 | 新增 `PhoneDSHBot`（依赖 `PhoneDSHAgentCore`）及测试 target；仅公共类型和 fail-closed 存储接口，未提前实现 WP3。 |

自检：没有第二套事件 envelope、工具别名或信任来源；没有修改既有 P01/P02/P03/P04/P15 消息；没有复用已删除协议编号；新协议均为 v1。基线 UI 的 token/页面 provenance 由真实源码和标准提取器同步，未增加页面白名单。

## 3. 兼容性与发布

| Producer → Consumer | 支持 / 拒绝方式及对应证据 |
| --- | --- |
| old → new | 既有 DSH Worker/宿主协议不变；新版继续读既有会话。此前没有已发布的 P23–P25 producer，不存在旧 Bot v0 兼容承诺。 |
| new → old | 旧 App 没有 Bot MCP、行消费者和页面，不宣称能消费新 Bot 数据；不得把新 Worker 与旧宿主混部。静态兼容不表示旧 App 可运行 Bot。 |
| new → new | 同一 App bundle 配对；真实 codec/状态机必须读取共享有效/无效 golden。当前只有 Python schema 与 Swift 骨架证据，WP 实现证据尚未产生。 |
| persisted old data → new / rollback | PR29 MemoryStore 无线上数据，不迁移。新 Bot SQLite 从 v1 开始；旧版无 Bot 入口，回滚时保留新库且不自动删除/降级，旧版不会读取它。 |

- 版本 / adapter：Bot `schemaVersion=1`；宿主与 Worker 同 bundle 原子升级，不新增 wire major 或私有版本字段。
- 发布顺序：先契约与本冻结记录，再 WP 实现；每包 PR 回合 `feat/phonedsh-bot`，最终总 PR 到 main，不自动合并。产物顺序为 Swift 协议/宿主、插件与 Worker image、App UI 接线、真实场景验收。
- target 保留项：P1 卡片、WP1 常驻结论、WP10 产品化、真实模型/真机/外部账号证据均不得由静态检查升级为 PASS。
- P01 HELLO 或 P15 P6 若需修改，必须先提交 CCR，说明影响范围和兼容性，并经用户确认；实现者不得本地放宽。

## 4. 分工与验收命令

共同规则：每包从冻结修订开 `bot/wp<N>-*` 分支；新文件 SPDX MIT；PR 写 Protocol impact 和 AGENTS §3.5 证据；真实 encoder/decoder 读取共享 golden。下列测试子目录按包独占，根目录 WP0 测试不得改动。WP2/WP7 对相同插件/profile 的修改串行，WP12 使用独立 profile 文件避免重叠。

| 包 | 独占路径 | 验收命令与门槛 |
| --- | --- | --- |
| WP0 | `spec/**`、`Package.swift`、`scripts/ci/modules.py`、`docs/protocol-changes/PC-20261105-phonedsh-bot-v1.md`、Swift 根骨架与根测试 | `python3 spec/bot/v1/tools/validate_vectors.py`；`swift test --filter PhoneDSHBotTests`；`bash scripts/ci/check.sh --only phone-spec,phone-p0-conformance,phone-cross-tree,phone-modules,phone-license,phone-hygiene`；两次导出 diff 一致。 |
| WP1 | `examples/probe-app/{ProbeKit,PhoneDSHProbe}/BotResidencySpike/**`、`scripts/ci/bot-residency/**` | `bash scripts/ci/bot-residency/run-device.sh --duration-seconds 7200 --events 10 --kill-webcontent 3`；真机不丢不重、恢复后 30 秒内交付。无真机为 NOT_RUN；P01/P15 问题只交 CCR。 |
| WP2 | `plugins/bot-runtime/**`、`plugins/profile/phone.worker.cordis.patch.yml`、`plugins/tests/bot-runtime-*` | `bash scripts/ci/check.sh --only phone-plugins,phone-image,phone-bundle-fresh,phone-no-dsh-patch,phone-compat,phone-license`；另交全部挂载点清单和模拟器证据，真机可选。 |
| WP3 | `Sources/PhoneDSHBot/{Store,Journal}/**`、`Tests/PhoneDSHBotTests/{Store,Journal}/**` | `swift test --filter PhoneDSHBotTests`；`python3 spec/bot/v1/tools/validate_vectors.py`；真实 codec golden、崩溃点重开无缺口/重复。 |
| WP4 | `Sources/PhoneDSHBot/{Authz,Gateway}/**`、`Tests/PhoneDSHBotTests/{Authz,Gateway}/**` | `swift test --filter PhoneDSHBotTests`；toolEffects/approvalPatterns 全向量、到期/撤销/deny/once 测试，无法归属调用拒绝；报告 JSC 或 Swift 选择。 |
| WP5 | `Sources/PhoneDSHBot/Memory/**`、`Tests/PhoneDSHBotTests/Memory/**` | `swift test --filter PhoneDSHBotTests`；注入逐字、召回、来源边界、forget/erase 测试。 |
| WP6 | `Sources/PhoneDSHBot/{Wake,Routines,Proactive}/**`、`Tests/PhoneDSHBotTests/{Wake,Routines,Proactive}/**`、`examples/probe-app/ProbeKit/Bot/Notifications/**` | `swift test --filter PhoneDSHBotTests`；唤醒/安静时段/三档向量、WebContent 不可用排队和恢复交付测试。 |
| WP7（等 WP2–WP6） | `Sources/PhoneDSHBot/MCP/**`、`Tests/PhoneDSHBotTests/MCP/**`、`plugins/bot-runtime/**`、`plugins/profile/phone.worker.cordis.patch.yml`、接线所需 `plugins/phone-mcp/**`、`plugins/tests/bot-*`、`scripts/ci/bot-e2e/**` | 同 WP2 gate；`swift test --filter PhoneDSHBotTests`；`bash scripts/ci/bot-e2e/run.sh --simulator --scenario conversation-card-approval-routine-held`；persona 检查只含 P0。 |
| WP8 | `Sources/PhoneDSHBot/Rows/**`、`Tests/PhoneDSHBotTests/Rows/**` | `swift test --filter PhoneDSHBotTests`；投影向量、删缓存重建一致、断线补齐一致、nonce 仅一次、progress 同 cardKey 更新。 |
| WP9 | `examples/probe-app/PhoneDSHProbe/Bot/**`、`scripts/ci/bot-ui/**`；不得修改 `spec/` | `bash scripts/ci/check.sh --only phone-xcode-resolve,phone-p0-conformance`；`bash scripts/ci/bot-ui/capture.sh --appearance light,dark`；P0 每卡截图与交互清单，WP8 后接真实行。 |
| WP10（等 WP1） | WP1 结论指定的 `Sources/PhoneDSH/Background/**`、`Sources/PhoneDSH/PhoneDSHLifecycle.swift`、`examples/probe-app/ProbeKit/PhoneAudioSessionLease.swift`；路径调整先由 owner 公告 | 重跑 WP1 真机命令并达标；P01/P15 先 CCR。WP1 不通过则停止等待用户决策。 |
| WP11（等 WP4/WP6/WP7） | `examples/probe-app/ProbeKit/Bot/Providers/**`、`Sources/PhoneDSHBot/Providers/**`、`Tests/PhoneDSHBotTests/Providers/**` | `swift test --filter PhoneDSHBotTests`；每个 provider 工具/事件、断开重连不丢不重测试。 |
| WP12（等 WP3） | `Sources/PhoneDSHBot/{Ledger,Watchdog}/**`、`Tests/PhoneDSHBotTests/{Ledger,Watchdog}/**`、独立 `plugins/profile/bot-context.cordis.patch.yml` | `swift test --filter PhoneDSHBotTests`；purpose/Watchdog/contextLint 向量与单测。WP7 后追加 `bash scripts/ci/bot-e2e/run.sh --simulator --scenario ledger` 验证真实回合账本，未跑前不得标完整 verified。 |

共享 fixture：`spec/bot/v1/golden/`（18 组，含有效/无效、取消/恢复相关输入）与 `spec/ui/v1/golden/`。导出器：`spec/tools/bot/export-vectors.mjs`，只读 `/tmp/phonedsh-bot-spec`，临时输入写系统临时目录，不修改 dsh-bot。

统一导出命令：

```bash
node spec/tools/bot/export-vectors.mjs \
  --checkout /tmp/phonedsh-bot-spec \
  --sha bf4075b0a2e8092b04b726c59a7b085d5a37e6f6 \
  --out spec/bot/v1
python3 spec/bot/v1/tools/validate_vectors.py
```

## 5. 当前证据与未关闭风险

以下均为本机实际结果，时间环境为 macOS、本地 Swift/Node/Python；模拟器、真机、真实模型和外部账号均未运行。

| 命令 | 结果 |
| --- | --- |
| 两次导出至独立临时目录并 `diff -qr`，再与提交的 golden/pin/manifest 比较 | PASS，20 组向量逐字节一致 |
| `python3 spec/bot/v1/tools/validate_vectors.py` | PASS，schema、正例、反例 |
| `swift test --filter PhoneDSHBotTests` | PASS，127 tests / 0 failures（含 FishDirector 对拍；WP7 真实 E2E 尚未完成） |
| 完整 WP0 命令中的 `phone-spec` | PASS，195 tests |
| 同一命令中的 `phone-cross-tree` | PASS，8/8 invariants、registry 25 contracts、84 tests |
| 同一命令中的 `phone-modules` | PASS |
| 同一命令中的 `phone-license` | PASS；补齐了 5 个基线文件 SPDX 位置/头部 |
| 同一命令中的 `phone-p0-conformance` | PASS，page-structure 31 known / 0 new / 0 stale、105 tests。已按真实源码和标准提取器同步旧契约；31 项既有 iOS/Android 差异仍明确归 Lane F，未宣称两端一致 |
| 同一命令中的 `phone-hygiene` | PASS。经用户授权，只为已核实的合成标识、保留测试邮箱和公开第三方主机添加精确匹配规则；未跳过扫描 |

完整 WP0 命令最终结果：六项全部 PASS，`== CI GATE: PASS`。契约 frozen，可以按依赖并行启动 WP1、WP2、WP3、WP4、WP5、WP6、WP8、WP9；WP12 等 WP3，其他包继续按依赖排队。

- 真机、WebContent 2 小时 spike、模型 E2E、通知实投、外部账号：NOT_RUN。
- WP1 不通过时停止，由用户决定是否采用 Swift LLM 循环退路；不得暗中更换架构。
- 鱼素材与 FishDirector 已获用户授权并完成契约和纯 Swift 对拍；生产 UI 的资源打包与真实事件状态接线由 WP9 验收，未运行前不得标 PASS。
- Gmail/GitHub、污染隔离、推送中继、Space UI、多 Bot/Team、回滚/import 不在本轮。

## 6. 修订记录

| revision | 由谁 | 原因（含 CCR） | 通知端 |
| --- | --- | --- | --- |
| `b5192c4a3eedd94ac4878bc0270684f7854ac273` | contract owner / 主 Agent | 初始冻结；纳入连续单 session、P0/P1 卡片、复用总表、WP12 观测与成本；同步最新 origin/main `07585efb`。无 CCR | 所有 WP |
| `65e062856c7e87f2f2b649b96383ae84259b2d65` | contract owner / 主 Agent | WP3 CCR-1：BotEventOrigin 改为冻结的 kind=dsh/local 显式 Codable，拒绝未知 kind/member。CCR-2：BotEffect 增加 kind/target/payloadDigest/retryPolicy/maxAttempts，保留源兼容默认值并支持 network-idempotent 五次尝试。schema/golden 不变，真实 Codable 回归通过 | WP3 重新分派；WP4/WP5/WP6/WP8 集成时同步该修订 |
| `784cd28e4be998477f4ab1d25bb7967d2cfa0a49` | contract owner / 主 Agent | WP2 CCR-2：冻结现有 phone App MCP 的 BotVolatileContext 请求/响应、发现名、scope、大小、迟到结果和错误语义；WP2 consumer、WP7 Swift producer。CCR-1 workspace/image 收录由协调者处理，不属于协议变更 | WP2 重新分派；WP7 按此实现 producer |
| `0074475d322395f6cdb06caa42ea391a214bd0da` | contract owner / 主 Agent | 上游基线升级为 DSH Bot bf4075b；golden 除来源 SHA 无语义差异。补充 Bot 色彩、composer 活动行、简洁回复/next-step、真实鱼素材与 FishDirector 状态映射。用户已明确头像资产授权 | WP7/WP9 按新基线接线；其余 WP 无需变更 |
| `3c7a6efaab5d95b71cbceb7b69d900f5e8665552` | contract owner / 主 Agent | 新增 FishDirector schema/golden 与纯 Swift 对拍实现；纳入真实 512px 头像资源及 BotFishAvatar。20 组向量、127 个 Bot 测试通过 | WP9 直接消费公共状态机和资产；WP7 提供真实事件 |

## 7. WP7 CCR：durable user-action transaction

接受 `Sources/PhoneDSHBot/MCP/CONTRACT_CHANGE_REQUEST.md` 报告的“handler 已执行、nonce 未持久化”崩溃窗口。P24/P25 的 JSON wire、`BotUserAction`、`BotActionResult` 和既有 golden 不变，不增加第二套 action envelope 或 result 别名。

- **计划接口**：`BotUserActionPlanning.plan(_:trust:) -> BotUserActionPlanOutcome` 替代会产生副作用的旧 handler。outcome 是 `commit(BotUserActionPlan)`、`stale(reason)` 或 `rejected(reason)`；plan 只含一个 durable `BotEvent` 和零至多个 `BotEffect`。planner 只能验证、授权和生成计划，不得调用 DSH prompt、恢复审批 continuation、写卡片、访问网络或文件。
- **事务接口**：`SQLiteDomainStore.performAndRecord(action:plan:) -> BotActionResult` 是唯一生产提交入口。它在同一 `BEGIN IMMEDIATE` 事务内检查 nonce、提交事件、effects/outbox 与 `(botID,nonce)→eventID`；成功返回 accepted，已有 nonce 返回原 eventID 的 duplicate。planner 的 stale/rejected 不消耗 nonce。
- **dispatcher**：`BotUserActionDispatcher` 先查持久 nonce，再只对未消费动作生成计划，最后调用上述单一事务；进程内相同 key 共用同一 Task，已 accepted 的并发重放返回 duplicate。旧的“先 handler.perform、后 nonceStore.record”不得用于生产，也不得通过内存 nonce 或调整记录顺序规避。
- **原子性边界**：崩溃发生在 commit 前，事件、effects 和 nonce 全部回滚；commit 后重放为 duplicate。不得宣称 DSH、网络、文件或其他进程外部副作用 exactly-once；它们由 outbox 的至少一次投递、inbox 去重及 needs-reconciliation 处理。
- **SQLite v2**：只新增 nonce 表和索引，既有 v1 数据原地保留。旧 v1 App 回滚时明确拒绝 v2 库，不自动删库或降级。`user_version=2` 和迁移/崩溃测试属于 P23/P25 实现契约，JSON schema 无需改版本。
- **验证**：真实 SQLite 覆盖 v1→v2、并发 duplicate、事件/effect/nonce 同事务、before-commit/after-handler 等崩溃点重开；拒绝和 stale 后允许修正重试。测试不能用 mock nonce 代替数据库，也不能把普通 reopen duplicate 当成崩溃证据。
- **路径重分配**：一个新的后续工作包拥有 `Sources/PhoneDSHBot/{Store,Journal,Rows}/**` 及对应测试中的 transaction 改动；WP7 只接 `MCP/**` planner/host adapter，WP9 只改 UI caller/fixture，均不得复制 nonce 表或事务。
- **兼容性**：UI、Worker、Swift host 同 App bundle 更新，P25 wire 不变；旧持久 Bot v1 数据可升级，旧 App 不能读取 v2，与上述回滚拒绝规则一致。

验收命令：`swift test --filter PhoneDSHBotTests`；`python3 spec/bot/v1/tools/validate_vectors.py`；`bash scripts/ci/check.sh --only phone-spec,phone-p0-conformance,phone-cross-tree`。冻结提交的 SHA 在推送后通知 WP7/WP9 和 transaction 实现者。

## 8. 集成审查 CCR：Grant 恢复与 session incarnation

核心审查证明仅有内存 Grant 会在重启后丢失 standing deny，进而让 auto 写操作错误放行。冻结以下最小修订，不改变 P24 的工具 schema、Grant 匹配规则、deny 优先或审批结果：

- 在现有 BotEvent/SQLite 中增加 `authz.grant_snapshot` 持久投影，保存完整当前 Grant 集合。其 `canonicalResource` 等匹配字段与上游 `grantEventData` 同义；这是恢复授权所需的状态投影，不是放宽“审计只存摘要”。继续使用 §7 的 SQLite v2，不新增平行数据库、grant wire envelope 或 schema v3。
- 生产启动必须先 replay 最后一份完整快照并验证全部 Grant，再开放执行；恢复失败 fail-closed。add/extend/consume/revoke 持久化成功后才公开新状态；once 不得因 actor 重入或崩溃复活。standing deny、session/until allow 的既有 TTL 和 scope 不变。
- Gateway principal 增加宿主生成的随机 `incarnation`， attribution 注册保存该代次，解析与当前代次不一致即拒绝。重启可重新发行 token，不要求恢复 Bearer token；持久 token 仍只有摘要。旧工具调用不能因 session 重签而继承新身份。
- `BotExecutionPrincipal` 的已验证构造收敛到 PhoneDSHBot 模块，MCP 输入不能自报 `userUI/certain/trust`；Memory 的真实 turn source 由 WP5 修复，不由本修订新增参数。
- WP4 后续修复独占 `Sources/PhoneDSHBot/{Authz,Gateway}/**` 及对应测试；不得改 Store/Journal/Rows，以免与 §7 transaction 工作包冲突。WP7 只能传入已解析的 principal 和宿主重新推导的 effects。
- 验证包括冷重启 standing deny + auto 仍拒绝、session/until 恢复、once 不复活、revoke 不回滚、损坏快照 fail-closed、并发 consume、旧 incarnation/撤销 token 拒绝，以及伪造 userUI 的负向测试。

验收沿用 §7 命令；WP7/WP9 合入时以此修订为准。
