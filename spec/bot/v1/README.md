<!-- SPDX-License-Identifier: MIT -->

# phoneDSH Bot v1 契约

状态：`target`。本目录冻结 P23 BotEventLog、P24 BotToolSurface、P25 BotRows 的共享语义；静态 schema/golden 通过不等于 Swift、Worker、模型或真机已经实现。

唯一上游规格是 dsh-bot `bf4075b0a2e8092b04b726c59a7b085d5a37e6f6`，由 `dshbot-pin.json` 固定。除 Mac 专属和手机平台限制外，各子系统复用其语义。不得恢复 PR29 的 NativeToolLoop 主循环、Mandate、Ticket 或 MandateTokenStore。

## 1. 架构与存储

- 主 Bot 是 WKWebView Worker 中的一个 DSH session。每个 Bot 只有一条连续主对话；routine 与 Task 使用各自的后台 session。
- Swift 宿主常驻负责事件、授权、记忆、routine 调度、通知、账本和 Watchdog。WebContent 终止不能丢失 durable 数据。
- Bot 事件写 SQLite，不使用 JSONL 作为权威存储。`commitSeq` 由 SQLite 在同一事务中分配，单调且无缺口；`eventId` 去重。P03 journal 是另一序号域，不得混用。
- effects outbox 至少一次投递，包含 lease、去重、dead-letter 和 needs-reconciliation。接受、持久化和外部副作用完成是不同事实。
- 第一版不做回退引擎、回滚、import、多 Bot、Team、Box、CloudAgent、GenerateImage 或 macOS 本机工具。

## 2. 工具面

`tool-catalog.schema.json` 与 `golden/tool-catalog.json` 将工具分为两组：

- `p0`：第一版 App MCP 和 persona 可以暴露的全部工具。
- `p1`：`show_table`、`show_callout`、`show_info`、`show_payload`、`show_library`。本修订只冻结 schema 和 payload，标为 `target`；第一版不得注册到 App MCP，也不得写进 persona。

`canvas`、`product`、`gift` 不在手机卡片词汇中。未知工具、未知 `cardType`、缺少渲染所需数据的卡片一律 fail-closed；工具返回失败，不生成半空卡片。

`card-payload.schema.json` 的字段来自 dsh-bot `cardPayloads.cjs` 的白名单。`golden/card-payload.json` 必须由真实 builder/normalizer 导出。链接只接受 HTTP/HTTPS；卡片数据必须自带，不能假设存在服务端 callback 补全。

### 2.1 volatile 上下文快照（WP2 CCR）

宿主通过既有 `phone` App MCP 提供 `BotVolatileContext`，Worker 中的发现名为 `mcp__phone__BotVolatileContext`。不新增环境变量、ordered-channel Remote 或第二种传输。WP2 是 consumer，WP7 是 Swift producer。

- 请求只含当前 `sessionId`（1–200 字符），不允许读取其他 session。宿主从已认领的 principal 推导 Bot 和授权范围，不能接受调用方自报的 botID 扩权。
- 成功结果的 `structuredContent` 符合 `volatile-context.schema.json`：`schemaVersion=1`、同一 `sessionId`、进程内单调递增的 `revision`，以及可选 `blocks.variant/persona/memory/automation`。块缺失或为空表示没有该类内容，不是成功伪造数据。
- variant/persona 各最多 12,000 字符，memory/automation 各最多 4,000 字符；总文本最多 24,000 字符。不得包含模型密钥、Bearer token 或无关 session 的内容。
- 传输失败、标准 MCP `isError`、超时、非法响应或 session 不符都不得携带部分快照。consumer 必须丢弃本次结果并使用空快照，不能沿用可能陈旧的记忆；`AbortSignal` 只用于本地忽略迟到结果，网络超时仍由既有 App MCP mount 管理。
- consumer 的本地 revision 防止旧请求覆盖新请求；producer revision 用于诊断，不作为跨进程持久游标。不得将静态工具发现当成宿主数据已接通。

## 3. 记忆

facts 是 SQLite 唯一权威。写入归一为单行；注入块逐字对齐；召回使用 CJK 分词、BM25、时近度和重要度；非 `user-turn` 不得写 `scope:user` 或 `profile`，来源只降級；forget/erase 与上游一致。

第一版不实现自动抽取、MemoryPolicy 决策、L2 反思和衰減。facts 中对应字段保留供以后启用；`memory-policy` 向量仅作上游兼容证据，不表示该功能已开放。

## 4. 唤醒与主动提醒

routine、cron、run-attempt、安静时段、silent/held/interrupt、held inbox、隐藏唤醒和 `<automation_status>` 与上游一致。安静时段延后而不是丢弃；urgent 必须有理由；WebContent 不可用时常驻宿主排队，恢复后依次运行且错过的只补一次。

主动提醒规则链只用于 interrupt。iOS 通知深链为 `phonedsh://bot/<botID>/row/<rowID>`。推送中继不在本轮，只保留 WakeSignalSource 接口。

## 5. 行与交互

Row、RowBatch、UserAction 和 ActionResult 以对应 schema 为准。`body.type` 为 `text`、`widget`、`attachment`、`approval`、`automation`、`notice`、`deleted`；`widget.cardType` 为 `question`、`steps`、`task`、`document`、`progress`、`checklist`、`link`、`table`、`callout`、`info`、`payload`、`library`。attachment、approval、automation 和 notice 使用独立 body 类型，不伪装成 widget。

- UI 只读行、只发用户动作；宿主盖 `owner_direct`，UI 不得自行设置信任级别。
- `watch(after:)` 增量补齐，游标单调；墓碑重建不复活；nonce 幂等。
- progress 使用相同 `cardKey` 原地更新同一行。
- 流式文字只存在内存覆盖层，不写 durable 事件，手机每秒最多推送 10 次。
- 运行中输入通过 steering 并入当前回合，不排队；停止只取消当前回合并丢弃迟到输出，不重启 runtime。
- 隐藏回合只显示卡片；question/steps 成功后宿主抑制模型收尾文字。
- 第一版不提供新建对话、对话列表、回滚或删除单条。现有 DSH 多 session 工作台保留为独立高级入口。

## 6. 观测与成本

- 每个模型 step 追加一条 LLM 账本，字段以 `llm-ledger.schema.json` 为准；四类 token 分开记录，快取命中率按上游公式计算，TTFT 不可得时省略。
- 只有 `front-turn` 和 `worker-turn` 允许大模型；其他 purpose 必须走小模型策略。模型密钥仍由 phoneDSH 宿主持有。
- Watchdog 只观察，不自动 nudge/stop。deadline、heartbeat、重复工具签名、重复工具错误、token/成本配额，以及手机新增的 WebContent 终止、温度、电量事件，每项每个工作单元只发一次 `watchdog.flagged`。
- compaction、pruner、spill 的 default/conservative 参数逐值复用 `CONTEXT_PROFILES`。未知 profile 回退 default，不新增环境变量。
- contextLint 对最近 session 做确定性检查；冻结重放和 rubric 不在第一版。

## 7. 大肥鱼与表现状态

素材来自上游 `public/assets/fish`，使用 512×512 RGBA PNG 和 manifest，不使用占位图。`fish-director.schema.json` 冻结真实事件到表现状态的映射、优先级、one-shot TTL、去重和生命周期恢复；Swift 只实现确定性状态机，不引入 JavaScript/Canvas runtime。

头像只能消费当前 Bot 的真实 BotEvent。审批和 Watchdog 进入 alerting，成功进入 celebrate，失败或拒绝进入 sad，回合结束恢复 idle。皇冠身份由真实 principal 决定，不能根据名称、颜色或发型推断。任何 UI 都不能自行申报运行状态。

## 8. 一致性向量

从仓库根运行：

```bash
node spec/tools/bot/export-vectors.mjs \
  --checkout /tmp/phonedsh-bot-spec \
  --sha bf4075b0a2e8092b04b726c59a7b085d5a37e6f6 \
  --out spec/bot/v1
python3 spec/bot/v1/tools/validate_vectors.py
```

导出器只读 dsh-bot，临时 fixture 写系统临时目录。所有实现必须让真实 codec/状态机读取这些 golden；Python 校验器通过不能替代运行时 conformance。
