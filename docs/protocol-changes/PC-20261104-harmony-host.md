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
