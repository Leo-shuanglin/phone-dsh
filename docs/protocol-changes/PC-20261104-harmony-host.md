# 原生鸿蒙宿主接入

```yaml
change: PC-20261104-harmony-host
contracts: [P01, P02, P03, P04, P05, P06, P08, P09, P12, P13, P14, P16, P18, P20]
status: frozen
breaking: false
version_decision: Same-HAP Harmony adapters; existing wire, WAL codecs and Worker APIs remain authoritative.
base: bd1dea5ee5cc0576eb75fb45d8eb97107c92e275
contract_revision: docs/design/harmony/ARCHITECTURE.md@image-resource-4
```

## 角色与边界

用户要求 Android 产品壳在原生鸿蒙上对应，并以本地模拟器验收。Codex 是协调者、契约负责人和最终集成验证者。DSH 的隔离目录负责具体实现或只读复核，不能修改共享 Worker、DSH checkout、wire 或 WAL codec。

初始架构冻结在 ARCHITECTURE.md。此修订补全边界登记，并冻结启动异步化和请求取消的集成要求。当前状态为 partial。真实对话、取消、冷启动与渲染进程恢复已经实测，完整 UI、全部队列与错误分支尚未验收，不声明整体 verified。

## 契约 delta

| 边界 | 本次实现及权威 |
| --- | --- |
| P01/P02 | 新增 Harmony Rust 宿主。复用 shell/crates/pdsh-wire、pdsh-wal 的实际编解码器与原有规范，不改 frame/type/flag/schema。只宣称已实现的 ACK level 1；掉电 level 2 未验证。镜像已有目录下的完整写入需要安全创建宿主物化父目录，仍拒绝 symlink 与越界路径。 |
| P03 | 生产 Worker 执行工具，宿主接收和保存已有 journal 记录；恢复语义按现有规范。后台 handoff/drain 尚未实现，不能按支持处理。 |
| P04/P14 | ArkTS 原生 UI 通过同 HAP 内页面适配器调用生产 WorkerTunnel。代码契约为 HostBridge.ets、web/engine.mjs 和 product-worker.mjs；它不是 target 的 unified bridge 1。req/stream 等生产协议帧不改写。pdsh-native-init/result 等仅是配对页面适配器的本地消息，不能发往 P01 channel。 |
| P05 | 同一 native origin 的模型别名映射到配置 HTTPS provider。真实密钥仅在 ArkTS 系统网络请求中使用，AssetStore 保存；Worker、Rust、WAL 不持有真实密钥。外部响应必须使用真实 status，不得猜测 200；RCP 回调时序需要模拟器实测。 |
| P06/P09 | 原生 UI 消费真实 follow snapshot/event，稳定 seq 派生行 ID。未知、读取失败、无数据分别展示；不把未执行写入报告为保存成功。 |
| P08 | 仅登记已测模拟器行为；没有鸿蒙真机、硬件授权、后台常驻或功耗证明。 |
| P12 | 平台私有 C ABI 由 harmony/host-rust/include/pdsh_host.h 定义，不改变 xzshell.h。NAPI start 返回 Promise<string>，installBundle 返回 Promise<void>；字符串归还 pdsh_host_free。阻塞安装和 WAL 恢复在 NAPI worker pool 执行；TSFN 只回到 ArkTS 线程。所有调用者同次迁移。 |
| P13 | 新平台调用既有 phone/* 与 DSH API，参数及结果以对应插件 source contract 为准。Android 只读能力不能靠本地开关伪造写入成功。 |
| P16/P18 | model-launch.json 只传已有 phone-model-config v1 的公开 provider 文档。Rust 解析字段白名单，仅派生已有 PDSH_MODEL_NAME/PDSH_MODEL_PROVIDER_JSON；不接受任意 env。设置支持从单 provider 迁移到 profiles + selectedId，并保留原 AssetStore alias。 |
| P20 | HAP 携带由已固定 Android 产品 Worker 与同 pin WorkerTunnel 组成的运行资源，不修改 DSH source。包身份不能仅用文件长度判断。 |

后续请求取消集成冻结如下。原生 HTTP request id 在进程存活期间唯一，并限制在 JS safe integer 范围，旧启动代响应不能命中新启动代同号请求。宿主关闭、超时、已确认客户端断开时，配对 ArkTS proxy 必须取消相应系统网络请求。若增加 http-cancel 本地回调，生产者、ArkTS 消费者和真实 TCP 取消测试同次更新；不增加 P01 wire frame。

## 相容与迁移

| Producer → Consumer | 决定 |
| --- | --- |
| 既有 Worker → Harmony host | 既有 wire/WAL 版本与能力协商；未知/非法帧按现有规范处理，不放宽 golden。 |
| Harmony UI → 配对页面/NAPI | 同一 HAP 一起构建更新，无独立旧页面部署。不保留旧同步 NAPI API；product 与本地 smoke 调用者同时 await。 |
| Android/iOS → 既有宿主 | 本次不改其生产实现或 ABI。共享 codec 仍运行原有 Rust golden。 |
| 旧 Harmony 设置/会话 → 新版 | 保留目录和 AssetStore alias，单服务迁移为一个 profile。follow snapshot 接纳旧随机 ID 展示缓存，不删除 Worker 会话或 WAL。 |
| 新版 → 旧诊断 HAP | 不支持把完整产品数据降级成旧诊断壳。旧 probe 为独立 bundle，不能作为产品兼容性证据。 |

## 验证与尚未完成

本地证据保存在 .audit/harmony-product。实际已观察到产品模型回复、强制关闭后的上下文续接、文件预览读回，以及独立 native smoke 的 shell/Python/jq 和冷启动首次读回。新合入的 RCP、多 provider 与异步启动必须重跑真实流程；既有通过结果不能替代新包验证。

需要运行 shared Rust golden、宿主测试、对应 ArkTS/纯投影测试、完整 HAP 构建、安装、错误/取消/恢复与页面对照。完整产品 1:1 尚未达到，缺口登记为 GAP-14 和本地 issues.md，不修改已有 golden 或跳过失败场景。

## 修订记录

| revision | 负责人 | 内容 |
| --- | --- | --- |
| architecture-1 | Codex | 两个设计候选、原生宿主与唯一隐藏 Worker、C ABI 及 UI 契约。 |
| async-start-2 | Codex | 据真实 THREAD_BLOCK_6S 栈迁移 NAPI Promise；登记边界、旧数据兼容与请求代际/取消要求。 |

## native-media-artifacts-3 冻结

以下为同一 HAP 内新增的配对平台接口，不调用 target 的 Office/render-media IR，也不改现有 channel 或 WAL 格式。

- Native `renderMath(latex: string, optionsJson: string): Promise<string>` 配对 C ABI `pdsh_math_render_svg`。options 只含 fontSize、color、displayMode；TeX 最多 8192 UTF-8 字节，字号 8..96。返回 `{svg,width,height,baseline,error?}`，结果上限 2 MiB。复用与 Android 相同版本的 RaTeX 0.1.15，SVG 必须内嵌字形，不依赖浏览器字体/网络或执行 SVG 脚本。解析失败保留源文并展示失败，不伪造公式。UI 必须处理主题/正文变化后的过期异步结果。
- Native `workspaceChanges(fromMseq: string, toMseq: string): Promise<string>` 配对 C ABI `pdsh_host_workspace_changes`。参数是十进制非负整数，范围为 `(fromMseq,toMseq]`，必须在当前已确认 mseq 内。只读取已有 WAL 记录和物化文件，返回该区间中 `/dsh/workspace/` 的真实写入路径与当前 size/mseq，不读出文件内容，不修改 WAL。不存在/已移除文件不能被 UI 声明为可打开产物。未知或失败范围不能变成空列表成功。
- 同 HAP 的 NAPI 模块通过 `native_async.h` 的 QueueStringJob 调用上述两个字符串 C ABI，阻塞工作只在 NAPI worker pool。字符串统一由 pdsh_host_free 释放。ArkTS 声明、Rust/C、NAPI 注册、UI 消费者同次更新。
- 产物所有权来自当前会话首个已提交轮次前的 mseq，到最后一个排队轮次结束后的 mseq 区间。合并排队组不能冒充单轮精确归属。每个 session 可保存已测路径；旧 metadata 缺字段时保留 Unknown。崩溃期间缺失的测量范围不能推断为没有产物。

| revision | 负责人 | 内容 |
| --- | --- | --- |
| native-media-artifacts-3 | Codex | 冻结原生数学排版与基于现有 mseq 的产物测量；分别由隔离实现更新完整 producer/consumer。 |

产物 mseq 水位补充：新增 Native `workspaceWatermark(): string` 配对 `pdsh_host_workspace_watermark(void)`，返回 `{ackedMseq: "<u64>"}` 或 error。水位来自 Host 缓存的已成功提交 ACK 原子值，在 Store 成功提交后更新、恢复后初始化；同步 UI 读取不得等待文件系统或 Store 写锁。调用方只在 native/Worker 已就绪时读，启动或恢复期间不读。变更仍在 native-media-artifacts-3 的同次 HAP 内配对完成。

数学分配上限补充：单个排版结果每边最多 8192 逻辑像素，总面积最多 4,194,304 像素。超出范围返回 error，保留 TeX，不分配巨幅 PixelMap。ImageSource/PixelMap 在替换和组件退出时释放；过期异步结果不能发布。

## image-resource-4 冻结

同一 HAP 的页面适配器新增 `$openBinaryRead({path})`、`$readBinaryChunk({id,offset})`、`$closeBinaryRead({id})`。打开时冻结 Worker VFS 的字节，最多 16 MiB；最多 4 个活动快照、60 秒 TTL，每块最多 256 KiB，关闭幂等。回复只含随机句柄、总大小、mime、offset 与 base64 数据。路径仍受既有 Worker 写/读命名空间约束，绝不接触宿主 AssetStore 或宿主私有路径。不改生产 Worker/DSH source 或 channel frame。

ArkTS 通过配对 HostBridge 和 ShellActions 的类型化 image loader/资源打开动作消费字节，资源对象留在 UI 组件，不塞入 ShellSnapshot。本地路径和 dsh-resource/file/sandbox 链接按 Android 预览解析器的安全根与会话规则解析，未知会话与越界明确拒绝。远程图片沿用 Android 的凭据为空、最多 8 MiB、逐跳校验且最多三次重定向、解码边长最多 2048 的策略；平台拒绝明文 HTTP 时显示失败，不放宽系统安全配置。不得调用模型代理或借用模型密钥加载图片。显示、全屏查看、失败、重试与过期结果需实测。

产物测量起点保存在宿主私有 SessionRecord.artifactsFromMseq，并在冷启动恢复时读取。该字段不是 Worker schema 或 wire 字段；后续无写入回合不能清空此前会话产物。旧会话缺字段仍为未测量，不推断历史归属。

## android-parity-1 冻结

Base/head 为 `c5aec33824f468f76ac324df5d116fe7a56c564f`。Protocol impact: P09。此修订只对齐现有 Android 的富文本可见行为，不修改共享 Markdown schema、wire、WAL、Worker API、NAPI 或持久化格式。

当前鸿蒙代码块无语法色，窄表格始终显示等宽网格。预期行为以同一基线的 Android `CodeHighlighter.kt`、`CodeBlock.kt` 和 `MarkdownBody.kt` 为准。代码高亮按字符串、注释、数字、关键词顺序覆盖，保留原始文本及 UTF-16 偏移。内模块类型冻结为 `CodeInk = 'base' | 'string' | 'comment' | 'number' | 'keyword'`，`CodeRun { text: string; ink: CodeInk }`，入口为 `highlightCode(source: string): CodeRun[]`。调用者只用现有主题色，不增加依赖或颜色权威。

表格按真实可用宽度与 Android 最小列宽阈值选择网格或逐行卡片，保持 header/value、对齐、compact 模式和资源链接语义。空文本、未识别 token、嵌套注释/字符串覆盖、尾随换行及窄屏多列表格是验收输入；代码复制结果必须逐字符一致。正反用例由真实高亮函数和现有 Markdown parser 驱动，不放宽任何规范验证。

实现者独占 `ui/MarkdownBody.ets`、`model/CodeHighlight.ets` 与 `tests/code-highlight.test.mjs`。协调者负责合并、ArkTS 构建、Android/Harmony 模拟器对照及契约记录。新旧 HAP 都读取原 Worker Markdown 文本；无磁盘迁移，无回滚数据变更，版本保持同 HAP 配对接口。

验收命令为 `node --test harmony/tests/*.test.mjs` 和 `devecocli build`，再以匹配的宽度、字体大小和 light/dark 状态截取两个平台的真实显示结果。未完成模拟器对照前不得升级成整体 1:1 通过。

## android-parity-2 冻结

Base 为 `c5aec33824f468f76ac324df5d116fe7a56c564f`。Protocol impact: P06; P09; P14: none — `$events/result` 的既有 DSH envelope 和字段完全不变。本修订修复多题请求只显示首题、将同一答案写到全部题目的可见错误。

权威是固定 DSH 的 `user-questions/src/types.ts`，与 Android `ChatSessionSnapshot.questionOutcome`、`ChatPage.QuestionTakeover`。ShellSnapshot 的 PendingRequest 新增 data-only `questions`，每题保留 `id/question/detail/header/options[].label+description/multiSelect`。保留现有 `answerRequest(id, answer: string)`；问题回答按 Android 已用的 JSON 对象 `{questionId: [wireLabels, customText]}` 编码，每个问题分别映射到既有 `{kind:'result',value:{answers:[{id,selected,custom?}]}}`，不新增 Worker 字段或第二种 answer RPC。

原生 UI 按题推进，支持上一题、下一题、跳过、单选、多选、自定义文本和 Android 的推荐选项。单选自定义文本替代选项，多选可以同时保留自定义文本。提交前逐题检查，不用第一题答案填充其他题，不生成虚构“回答”选项。拒绝或传输失败保留各题草稿；同一 eventId 的提交期间禁止重复请求。

新增本地 `dismissPending(id)` 只隐藏卡片，不能发送允许、拒绝或 `$events/result`。问题占用 composer seat，权限审批留在卡片区域。切换会话/停引擎清理可见的旧 pending，底层取消仅按既有事件或 runtime 生命周期处理。此处不宣称 timed/continued 问题已完成，相关能力必须另按实际 projection/RPC 验收。

同 HAP 的 UI/host 同次升级，不混用新旧接口。持久化格式、旧会话数据和回滚均不受影响。实现者独占 `model/Contracts.ets`、`model/QuestionAnswers.ets`、`host/HostService.ets`、`ui/ChatView.ets` 与对应纯测试。不得修改模型代理、VFS、wire、WAL、其他 UI、规范或 DSH。协调者执行编译和真实 ask_user_question 多题回合验收，保留未测试项。

### android-parity-2b 分发修正

Protocol impact: P14; P04; P06: none — 不改 DSH 事件或答案内容。真实模拟器回合确认 `$events/result` 被 `method.startsWith('$')` 误分发为本地 VFS 命令，Worker 返回 `Unsupported native Worker method $events/result`，导致答案无法送达。

页面适配器只将 product-worker.mjs 实际提供的八个私有命令 `$diagnose/$writeFile/$readFile/$listFiles/$openBinaryRead/$readBinaryChunk/$closeBinaryRead/$command` 送入本地消息通道；既有 `$follow/$events/$close` 保持其专用流处理。其他 DSH 方法按既有 RPC envelope 经 WorkerTunnel 传递，包括 `$events/result`。不新增方法、消息字段或错误语义。producer/consumer 仍在同 HAP 配对，旧磁盘数据不变。

回归必须执行真实 engine.mjs 的 dispatcher，分别验证 `$events/result` 的 `/api/$events/result` 路由、普通 DSH RPC、全部真实本地命令，以及非法路径拒绝。不得仅比较源码字符串或手写第二个 dispatcher。

### android-parity-2c 回答完整性

独立复核发现重复/空题目 ID 以及不匹配的 answer map 仍可能产生错题或空答案。当前同 HAP 私有 UI 编码修订为整组校验，ID 必须是非空且互异的字符串；非法组显示请求错误，不生成部分问题或发送结果。有效 JSON answer map 必须覆盖当前整组题目，每题值为字符串数组或既有单字符串形式；显式跳过用该题的空数组表达，不能用缺失字段代替跳过。未知/缺失 ID、损坏的组 JSON 和非字符串选择拒绝提交并保留当前卡片。

合法单题保留不歧义的原始字符串兼容路径；多题不支持广播标量。JSON object/array 外观的非法编码不能当作原始用户回答发送。UI 生成 JSON 字典时必须正确支持任意合法 ID，包括 `__proto__`，不依赖 JavaScript 对象原型赋值。此校验只作用于已有配对 UI/host 接缝，不修改 DSH types、Worker schema 或协议错误代码。新旧 HAP 不混用接口，持久化格式不变。

## android-parity-3 代码卡片冻结

Protocol impact: P09。真实配对截图确认鸿蒙代码卡片缺少 Android 既有的分区头部、换行开关、复制图标、边框和长内容展开功能，短行还被水平 Scroll 居中。权威为同一基线的 `CodeBlock.kt` 与现有 UI tokens，保持 Android 源字体 13、默认行高 `navSubtitle(14.5)+codeLineSpacing(5)`、头部高 34、圆角 12、外边距 2、内边距 12、控制框 28、复制反馈 1000ms、长内容阈值 24 行或 2400 字符、折叠上限 300、展开行高 32。

以既有组件结构移植，不另造渲染协议。内部 ArkUI `NativeCodeBlock` 只接收 language/body/dark，复用已测 CodeHighlight，保留原始字符串复制。Code banner 使用现有 `colors.sessionCodeBanner`，边框使用 `colors.sessionBorderL2`，不新增色值权威。换行默认开启，关闭后水平滚动且短内容左对齐；长内容折叠、展开、收起和暗色均须在模拟器实测。普通用户字体角色设置及 Worker/NAPI/持久化均不变。

实现者只写新的 `ui/NativeCodeBlock.ets`。协调者负责 Palette 中既有 token 的绑定、MarkdownBody 接入、语言字典、构建和两端 UI 验收。导出结构与唯一消费者同 HAP 更新，旧文件无需迁移。此修订不允许为了对照而改 Android 基线或隐藏像素差异。

## android-parity-4 气泡宽度裁决

Protocol impact: P09。用户明确选择按共享规范修正两端，并保留旧 Android 截图。规范 `geometry.bubbleMaxWidthRatio` 的 0.8 不变，360 逻辑宽度、20 两侧阅读边距时最大气泡为 `(360 - 40) * 0.8 = 256`。真实 Android 截图出现约 224，是 ChatRows 在扣掉边距后又把内容列宽交给仍会扣边距的 ChatBubble，重复扣除了一次。鸿蒙现有内容列 80% 不应被缩窄去复制这个错误。

修复 Android 私有 ChatRowItem 的测量归属，在外层水平阅读边距之前获取真实行容器宽度，再传给既有 ChatBubble。不得改公共 ChatBubble API、ChatMetrics 公式、共享 token、golden 或主题来压平差异；不得改为窗口常量，也不能用补偿常数掩盖调用者错误。窄容器和正常容器都必须执行真实 Compose 测量回归。鸿蒙端按同一公式在模拟器读回实际尺寸。

修正前图像固定保留在 `docs/design/harmony/parity/before/`，后续不覆盖它们。修正后 Android APK 另记源 revision 与哈希，不冒充原 bd1dea5 基线。实现者独占 Android `ChatRows.kt` 和新的 `ChatUserBubbleWidthTest.kt`，协调者先跑新增测试在旧实现上失败，再合并修正重跑并抓取两端图像。无网络、持久化或 Native ABI 变更。

## android-parity-5 队列冻结

Protocol impact: P06; P09; P14; P15: none — 不更改 mux frame 或 DSH RPC envelope。权威为固定 DSH `session/updateQueue` 与 `session/projections`，以及 Android `ChatQueueActions`、`DshChatQueueTransport`、`ChatSessionSnapshot`。鸿蒙当前只由本地 pendingTurns 推算排队数，无法编辑/移除真实待执行消息，且把 steer 当新 turn 计数。

只读取已实现的 `session/projections {request:{sessionId}}` 中 `kind:'sequenced', values.inbox['next-turn']`。只认 kebab-case 字段。未知、缺字段、失败、`migration-required` 都不能当空队列。实际 item.id、content text parts、source.kind='user' 的 rpcId 是身份来源。预览保留文本，混合非文本内容不可编辑，实际 durable 行可移除；Android 当前 durable next-turn 的 canSteer=false 不得伪造可引导。已有忙时输入的 `session/prompt mode:'steer'` 继续可用。

同 HAP ShellSnapshot 用 data-only `queueItems: QueueItem[] | null`（null 未知、[] 已读空）、`localQueueItems: QueueItem[]`、`queueFailure` 取代展示用 queueCount。QueueItem 区分 local/durable，含 item id、拥有该行的 workerSessionId、promptRequestId、text、lane/state、canEdit/canRemove/canSteer、busy。暂态 echo 从实际提交 requestId 关联，按 durable source.rpcId 或已接纳的 user/message 退役，不能按文本相等。首条普通输入保持现有方式；忙时排队/引导不提前伪造进入 transcript。已有 send action 不分裂出第二套协议。

ShellActions 新增 `updateQueueItem(workerSessionId, itemId, action, replacement?)`，action 为 edit/remove/steer。只操作当前会话真实可编辑的 durable id，记录单行提交中，`session/updateQueue {request:{sessionId,itemId,action:{kind,content?}}}`；edit 的 content 是既有 `{type:'text',text}` 数组。accepted 不表示成功或执行。再次读 sequenced projection 证实 edit 内容、remove 缺失或真实 steer 退出 next-turn 后才发布成功；读取失败不得报成功。过期会话/运行时响应不落在新页，未知状态不显示 0。不得混用 UI 操作 id、prompt requestId、外层 RPC id 或 WAL mseq。

pendingTurns 可保留为私有回合组与产物范围记账，不能作为队列展示；忙时 steer 不增加预期 turn，成功移除自己提交的待执行项只按匹配的 requestId 退役一次。保持真实 turn/start、turn/end 与取消事件语义，不猜测旧进程仍活着；已有会话切换/停止时清除可见队列并重新读取。旧磁盘数据不变，同 HAP 两端同时升级。

实现者独占 `model/QueueModel.ets`、`model/Contracts.ets`、`host/HostService.ets`、`ui/ChatView.ets`、`tests/queue-model.test.mjs`。保留现有多题、locale、RichText、RCP、WAL/安全防线。协调者运行现有 Worker 实测：长 bash 回合中排入三条、按 durable id 移除中间项、编辑另一个、观察实际工具/消息记录与最终空队列；验证忙时 steer 不产生幽灵运行态。不可仅凭模型复述或前端计数算通过。

## android-parity-6 控件布局修复

Protocol impact: P09。校准后截图确认 Android CodeBlock 对语言标签使用 `weight(fill=false)` 又加一个带权重 Spacer，导致换行/复制控件停在中部。共享参考 `Components.swift` 的 `DSSCodeBlock` 用一个 Spacer 将控件推向尾部。修正 Android 权重归属，保留语言文本截断、按钮尺寸和右内距 12。鸿蒙端保持尾部对齐；原始 Android 截图保留。

英文设置长权限摘要曾占尽横向空间，使标题按单字换行并产生巨大空白。两端的设置导航行应保留可见标题，右侧值限内行宽的一半，单行省略，不改实际值或无障碍文本。此处是本地 UI 测量约束，不增加共享 token、持久化字段或远端协议。实际 Compose 回归须覆盖尾部复制控件和长值标题；最终验收另用同密度模拟器截图。

实现者独占 Android `CodeBlock.kt`、`SettingsPage.kt` 和对应渲染测试，以及 Harmony `ShellSupport.ets` 的 NavRow 值约束。协调者合并、构建及原图对照。SDK SVG `fillColor` 实测破坏 `fill=none` 的线框，代码卡片改用既有图标生成器从 `colors.sessionSecondary` 派生的同路径 ink 变体，旧默认图标不改。

## android-parity-7 行内排版冻结

Protocol impact: P09; P12: none — 既有 renderMath 输入、SVG/width/height/baseline 输出与 native ABI 不变。原 Flex 混排把文本片段和公式当独立块，不能完成 Android 的单段落换行。API20 实测探针比较了 SVG data URI 和 ImageKit PixelMap 的 ImageSpan：前者 onError 且蓝色图像像素为 0，后者实际渲染出 3312 绿色像素。采用同一 Text 内 Span + ImageSpan 的 PixelMap 路径。

`NativeInlineText` 只接收 `inlines/fontSize/codeFontSize/lineHeight/baseWeight/align/color/dark/onOpenTarget`。继承现有 Markdown 解析与链接安全策略。所有片段在同一原生 Text 排版；公式以 RaTeX 尺寸为 vp，ImageSpan baselineOffset 为 `baseline - height`。按实际 fp→vp 和显示密度渲染；ImageSource 始终释放，PixelMap 属于组件，替换、失效与销毁释放，过期异步结果不得发布。使用既有 SVG 缓存与 8192 边、4,194,304 像素解码上限。失败保留原 TeX。无网络加载、无 WebView 渲染、无 Snapshot 中的资源句柄。

校准截图同一窄表卡片背景高度为 Harmony 602px / Android 510px。差异来自首尾固定行高；保持角色行高在行间，避免给单行标签/值强加整段 leading。API20 使用实测默认字体自然高度计算间距，`lineSpacing(...,{onlyBetweenLines:true})`；不增加手调偏移。正文角色行高沿 Android `UiType.applyPreferences` 的 16/24 比例和下限，标题使用既有固定字号，行内代码 normal 为 code role+1、compact 为13。系统字形仍按各平台真实字体。原消息复制完整源码；选区 TeX 复制与长公式还要单独验收，不能推断通过。

实现者独占 `ui/NativeInlineText.ets`、`ui/MathFormula.ets` 中现有解码器复用；协调者负责 MarkdownBody/表格消费、FontRole 样式和真实设备验证。有效分式/矩阵、无效 TeX、长段落跨行、链接、放大字体、重排后旧结果清理是验收用例。同 HAP 同次升级，不改 Worker、schema 或旧数据。

## android-parity-8 Android 排队后的会话监听

Base: `42d4dbf748e73a52877b9d82df15c8f521c7d932`. Protocol impact: P06; P09; P14: none — 保持现有 mux 与 DSH RPC 形状；P03: none — 只按现有格式持久化真实事件，无新日志字段或兼容分支。

在 Android API35 实际排入三条后，第一条被 Worker 取走，首轮结尾后界面显示空闲，余下两条留在旧队列投影。Android `ChatTurnRunner.oneTurn` 收到首个 `turn/end` 就关闭 mux，而固定 Worker `ReactLoopAgent.turn` 会继续读取 inbox。相同 follow 应接收各排队回合的原始事件，不重发 prompt。

以固定 DSH `session/projections` 的 `kind:sequenced`、`asOfSeq`、`values.turnBoundary.{openTurnStartSeq,lastTurn}` 和 `values.inbox['next-turn'/'next-step']` 为权威。只在终点已被该投影覆盖、最后 turn 等于所见终点、没有打开的 turn 且两个 lane 已读空时关闭。若 Worker 领先或有待处理输入，继续同一订阅；未知、过期、缺字段或迁移态不是空。保留正常长工具的静默窗口；回合之间有独立的有限等待，无法确认则走已有失败通道。取消仍中止订阅和通知同一 Worker。最终关闭和本地忙时提交同在 UI dispatcher，存在未返回的 busy prompt 时不得关闭；关闭后输入留在草稿。

用户排队消息以真实 `user/message` 及 seq 投影。按 Worker `source.rpcId` 去掉已有首条乐观消息的同一回执；禁止按文本去重。保留现有消息大小限制和已支持文本部件。

旧/新 App 都与同一固定 Worker 互通，Worker/schema/WAL 和旧数据不改；旧 App 的只读历史依然可读。旧 App 仍有首轮关闭缺陷。实现范围为 Android `ChatTurnRunner.kt`、`ProbeEngineViewModel.kt`、`ChatSurface.kt` 及其现有 JVM 测试。验证以真实 HostChannel 的两个有序 turn、非空及过期投影和实际模拟器队列为准；鸿蒙继续使用当前实现，不改其 Worker 端。
