# Android PDSH — 对齐 iOS PR15 基线

> **基线 SHA**：iOS `8dd17496`（`#15: direct native preview with paging and honest states`）
> 前一个基线是 `86a39aac`（`#13` 之后一个收紧 composer 底距的提交）。
> **本轮只换 iOS 源码的那 8 个文件，`android/` 不受影响。**

---

## 一句话

**Android 侧 12 个模块、1746 个 JVM 测试、13/13 CI 门禁全绿；页面结构契约 51 条分歧降到 0。**

但这一轮最重要的产出不是「做了多少页面」，而是**证明了那 51 条里 40 条从来不是问题**——它们是契约提取器、结构检查器、门禁自己的错误。如果没人查，最省事的做法是给每个页面补一堆假控件把 51 变成 0：**门禁会绿，产品会变差，而且没人知道。**

---

## 一、那 40 条是怎么消失的

`phone-p0-conformance` 的判据是「iOS 契约里的 section/action 标签，Android 页面上有没有」。它报 51 条缺失。逐条查下去，**有六类假话**：

| 假话 | 后果 |
|---|---|
| `android_mapping()` 只匹配 `^public fun` | 7 个页面的 `android.file` 落到「注释里提过」的猜测分支。**其中一个让 4 份契约比同一个文件**——`native-agent-presets` 拿 `SettingsPage.kt` 去比，报 6 条「缺控件」，而它的实现在另一条车道里本来是完整的 |
| `mask_kotlin()` 没有字符字面量规则 | `'"'` 在 Kotlin 里是字符字面量，掩码器把它当成字符串开头，**吞掉文件剩下全部**。于是 `SessionArtifactsPage.kt` 里明明写着 `fun SessionArtifactsPage(`，却被报「函数丢了」 |
| `*SectionLabel` 只读 `text =` | 所有 `SettingsSectionLabel(title = …)` 的标题**渲染正确却不可见**，报 4 条缺失 |
| 模板标签只在 Android 侧过滤 | `删除 %@` 在 Android 侧被 `is_template_label` 滤掉，契约侧不过滤 → **数学上不可满足** |
| 靠命名识别 section | `ShellSection(title = …)` 实现完全正确，但检查器只认 `*SectionLabel` / `*SectionHeader` / `*SectionView` 三种名字 |
| picker 的 `items` 被当成页面动作 | 一个下拉框的选项 `文件` / `命令` 被报成两个「凭空多出的动作」 |

修复方向是**让工具如实描述现实**，不是让页面去适应工具：

```diff
- if re.search(rf"^public fun {screen_name}\s*\(", text, re.M):
+ # Visibility is not the extractor's business: a page composable may be
+ # `internal` and it is still the Android screen for this contract.
+ if re.search(rf"^(?:public|internal) fun {screen_name}\s*\(", text, re.M):
```

```diff
- if value is None and dynamic is None and callee in CHIP_CONTROLS:
+ # `dynamic` does not disqualify the caption: the dynamic text is the *value*
+ # (the selected segment), while the caption beside it is the label.
+ if value is None and callee in CHIP_CONTROLS:
```

**两次我试过放宽检查器，两次都引入更多假阳性**（`SettingsFormRow` 连 `onClick` 都带上仍误报 10 条，因为它是 Settings 家族的**通用展示行**）。所以那两次回退了，并在代码里写明为什么不该再试。

---

## 二、三条不是「分歧」而是「口径」的

### `copy-forced` —— 新增的 divergence class

iOS 的插件面写着 `This iPhone Worker has no pnpm…`。契约要求 Android 页面也出现这句话。

**但这是 Android 应用。照抄就是在屏幕上陈述一个假事实。**

现有三个 class 用不了：`os-forced` 被 `platform_check.check_mechanism()` 强制要求一个具体的 `android_mechanism`，而文案差异**两端都没有可替换的 API**——填一个 Kotlin 常量能机械地过，但语义是谎。

所以新增第四类：

```python
DIVERGENCE_CLASSES = ("os-forced", "hardware-absent", "policy-forbidden", "copy-forced")
```

> `copy-forced`：措辞强制。两端描述的是同一件事，但照抄另一端的说法就会在屏幕上陈述一个假事实。它没有可命名的 Android 机制，因此不属于 `os-forced`——把文案差异登记成机制差异会让 `android_mechanism` 变成检查器抓不到的谎。

**这是加一条正确的规则，不是放宽检查。** 语义写在 schema 的 `x-class-definitions` 里，enum 和定义不会漂移。

### `§6.3-5` 在仓库里没有家

`grep -rn "6\.3-5" spec/` 只有 **1 处，而且是引用不是定义**。代码反复引用「方案 §6.3」，而那份方案的正文**不在树里**——读不到它的范围、理由、修改流程。

三条独立车道（L28 / L29 / L33）先后撞上它，每条都重新推导一次。

**补上了**：`docs/design/android-single-webview-invariant-2026-11-01.md`，代码里两处引用已指过去。

它的结论值得单独说：这条不变式**写下来的理由全是资源/正确性**（一个 renderer、一个 view thread、一个构造点），**没有任何一处把它写成安全边界**。但它在效果上承担了隔离——`CookieManager` 是应用级单例，**同进程的第二个 WebView 共享同一个 cookie jar**，而页面唯一的凭据就是 D22 launch cookie。

⇒ **安全的「第二个 WebView」必须是独立进程**，那是一个模块级项目。本轮裁决：保持一个，HTML / 3D 走诚实卡片。

### `preview-surface.json` —— 手工登记的契约

`PreviewSurface.swift` 不是 `*Page` struct，提取器不生成契约。**今晚第四次撞上「提取器只认 `*Page`」**（L23 / L25 / L28 / L30）。

扩提取器会让「什么算入口 struct」变模糊（同文件还有 `PreviewDebugRoute` / `PreviewDebugChat`），所以做了**手工登记**：`handRegistered` 块写明它不是 `*Page`、提取器既不写也不删它、checker 每次校验它的 provenance。

---

## 三、实现

| 项 | 内容 |
|---|---|
| **6 个缺失页面** | `ChatPreferences` / `SettingsDocument` / `Shortcuts` / `AgentPresets` / `PluginInventory` / `PhonePermissions`（**23 个能力 × status/request/audit 三态**） |
| **chat 三个嵌入子页** | 命令结果页、后台任务 sheet、定时任务 sheet；`刷新` 真的 handoff 到宿主，回调缺失时**可见但置灰** |
| **automation 编辑器** | 页面内真表单 + 六种规则校验；提交走宿主 seam，**不谎报成功**（宿主观测不到 saved 就不关表单） |
| **产物预览** | 真 `ACTION_SEND` → 设备上弹出系统 chooser（`ChooserActivityLauncher`，带真实文件名与 MIME） |
| **文件预览面** | 路径解析器 + 512 KiB 分页 + 五类渲染器（文本 / Markdown / 图片 / PDF / 音视频）+ `FileProvider` 分享 + 有界重试 + 首帧 `正在读取…` |
| **Settings 五分页** | `通用 / 模型 / 插件 / 手机权限 / 预设`，主入口达可比级 |
| **抽屉 8 行** | `Files` 页首次可从 App 进入（设备证据：抽屉 → 手机工作区 → `notes.txt` → 预览） |

### 路径解析器的安全边界（逐条对齐，不简化）

```
空路径 / 任何 `..` 段（触盘之前，含 %2E%2E、含 sub/../inside.txt）
→ root.toRealPath()（非目录 = NO_WORKSPACE）
→ 词法前缀包含（Path 元素比较，workspace-evil 不算根内）
→ toRealPath() 后二次前缀包含（根内符号链接指向根外 = 拒绝）
→ isRegularFile
```

### 三处刻意偏离 iOS，因为 iOS 那三处是错的

| 处 | iOS 的行为 | Android |
|---|---|---|
| 预览原因 | 一律 `nil` → 连 `..` 也白等 6 秒 | 分 `REJECTED` / `NOT_A_FILE` / `MISSING` / `NO_WORKSPACE`，**只有后两类重试** |
| 分页 `eof` | 用 trim 后字节数 → **页边界切在多字节标量中间会丢后半** | 用文件长度 |
| 非法 UTF-8 | 拿同一 offset **死循环** | 标 `stalled` 并前进一个窗口 |

### 一个判断：`webm` / `gif` 不该在「不支持」里

L33 给它们的卡片文案是「这个视频格式还没有接到播放器上」。**但 Android 平台能解 VP8/VP9**——那句话是假话。

| 格式 | 处理 |
|---|---|
| `gif` | → IMAGE（第一帧）+ 已有的「动图只显示第一帧」提示。**那条常量一直是死代码，现在真的生效** |
| `webm` | → VIDEO，和 `mp4` 同一个 `MediaPlayer` |
| `ogv` | 仍不支持：「本机没有 Theora 解码器」——**这句是真的** |

**「架构做不到」和「平台没有解码器」是两件事，不该共用一张卡片。**

---

## 四、度量：像素级对齐的真实进度

**做到了**（都是设备实测，不是「看起来差不多」）：

| 项 | 数字 |
|---|---|
| Settings 行距 | **52.33pt**，分隔线间距 157px 四倍一致 |
| `model` 子页分隔线 | **2 vs iOS 2**，全中 |
| `network` | **12/12 段 52.33pt**，全中 |
| CTA | `x 32.0–370.0 / h 50.0`，**完全一致** |
| composer | **94.10dp vs iOS 94.0（+0.10）** |
| 抽屉 | `320 / 288 / 48 / 22`，全部实测复现 |
| 气泡 | 内边距 16.8dp、宽度上限 277dp |

**没做到**（全部有原因，全部登记）：

- HTML / 3D / OGV —— 单 WebView 不变式（见上）
- Office —— Android 无系统渲染器（PR15 研究本身建议 share-first）
- 视频进度条 / 全屏 —— 需页面方向策略
- 6 个采集状态仍 PENDING（Chat 家族 5 个 + env-home）

---

## 五、验证

```
13/13 CI 门禁 PASS
  phone-spec · phone-p0-conformance · phone-cross-tree · phone-compat
  phone-plugins · phone-bundle-fresh · phone-no-dsh-patch · phone-modules
  phone-android-structure · phone-android-tests · phone-license
  phone-hygiene · phone-gate-contracts

页面结构   ok (17 known / 0 new / 0 stale / 3 whitelisted)
JVM 测试   1746 tests / 0 failing
截图       gate 11/11 · ios-parity 21/21 · android-only 22/22 · --profile all 54/54
构建       :app:assembleDebug BUILD SUCCESSFUL
```

`phone-hygiene` 从 **148 → 0**，其中 **76 条是真泄漏**：24 个回执文件里写着开发机上的家目录绝对路径，而本仓有公开镜像——它们会把开发机的用户名和目录结构发出去。已脱敏，并把生成器改成 **fail-closed**（`assertRedacted` 幂等，脏产物写不出来）。

---

## 六、已知缺口：全部登记，一条没藏

- **17 条 known divergence**（`_baseline.json`，只减不增）
- **3 条 platform whitelist**（`copy-forced`，引用 `spec/platform/whitelist.json` 的真实 id）
- **6 个采集状态 PENDING**（`ParityState` 27 条里 21 条 CAPTURED）
- **2,160 行模块层零生产消费者**（DomainStore 1,019 + 设备能力契约/解码器 1,141，见下）
- **1 条 spec 漂移**（`wal.md §8` 的「uncompressed ustar」vs 实现的 gzip 外壳，无安全含义，见下）

其中一条特别值得记：

> `files / 完成` 是「3D 示例」demo sheet 的取消键（`FilesPage.swift:70`，`sheet-62`）。Android 从未实现该 sheet。**以前是预览工具栏的 `完成` 按标签假顶替了它**——删掉那段死代码，这个一直存在的缺口才第一次被看见。

**门禁在绿的，而它绿的原因是两个完全不同的控件标签相同。**

### 模块层：DomainStore 零消费者，设备能力契约/解码器仍无生产入口

`android/README.md` 把 `:domain` 写成两块招牌：「P6：DomainStore（framework SQLite）+ DeviceCapabilities」。
复核 HEAD + 2026-11-01 20:45 工作树：DomainStore 零消费者；DeviceCapabilities 的测量半边已被本轮电话权限
改动接进 `:app`，契约/解码半边仍为零。

**1. DomainStore：1,019 行，零生产消费者。**

```text
$ grep -rn "\bDomainStore\b" android/*/src/main --include="*.kt" | grep -v "android/domain/"
（空）
```

8 个文件 1,019 行：`DomainStore.kt` 191、`DomainStoreCore.kt` 211、`sqlite/SqliteConnection.kt` 220、
`testing/InMemoryConnection.kt` 123、`Connection.kt` 95、`KeyRange.kt` 56、`QuotaPolicy.kt` 64、
`DomainText.kt` 59（同簇的 `DomainStoreException.kt` 59 行是错误类型，未计入这 1,019）。调用它的只有测试：
`DomainStoreContract` 的 22 条用例分别跑在 JVM 内存假实现和 instrumented SQLite 上，另有
`SqliteDomainStoreDeviceTest` 的 2 条设备级用例。**生产零入口**——这一簇唯一的调用者就是测试
（`android/README.md` 的 2026-10-31 快照把 `:domain` 记为 79 条测试，本轮未复跑）。

**2. DeviceCapabilities：契约/解码半边仍是死的，测量半边刚被接上。** `device/` 当前 10 个文件 1,964 行
（2026-11-01 20:45 工作树）。生产代码引用到的有 4 个：`DeviceManifest` 226 + `CapabilityValue` 134 是
`PdshNavHost.kt` 里手写的 demo 清单字面量；`AndroidDeviceCapabilities` 166 与新增的 `AndroidHostPermissions`
297 已被本轮电话权限改动接进 `:app`（`ProbeEngineViewModel.kt` 调 `platformFacts`，
`HostPhonePermissions.kt` 读 23 项手机权限）。**仍零生产引用的是契约/解码这一半**：SPI 接口
`DeviceCapabilities` 130、能力词表 `DeviceCapability` 124、解码器家族 887（`DeviceManifestDecoder` 532 +
`DeviceManifestJson` 137 + `DeviceManifestException` 81 + `LocalDeviceManifest` 137），共 **1,141 行**；
`DeviceManifest.parse` 在生产代码里仍无调用点。

**3. 需要 owner 做的决定：接线，还是删除。**

- **接线的成本不是一条依赖，而是一个真实消费者。** PRD:169 要求原始证据 / 转写 / Main 过程 / 任务引用 /
  Memory 都指向同一个本地 DomainStore（`DomainStore.kt` 的 KDoc 展开成 session / memory / task 三类记录）；
  这些功能在 Android 上尚未实现。`:dsh` 已有 `HostStateStore`（JSON）与 `JournalStore`
  两条持久化路径；现在把 DomainStore 接进去只会多出第三条写路径和第二个 authority。
- **删除的成本是 1,019 行 + 22 条 contract × 2 驱动 + 2 条设备级测试**，可逆（git 历史仍在），但 P6 的
  交付叙事要相应收窄。
- **本车道不删**（1,019 行不是文档车道能处置的），只登记。**倾向：在第一个真实消费者出现前，按「未接线的
  候选实现」处置（移出默认构建或删除）；不要为了让死代码「有消费者」而硬接到 `:app`。** 若要保住 P6
  承诺，正确做法是同时命名那个消费者（memory / session 持久化），那是一条功能车道，不是本轮。

**4. 模块层死簇清单（UI 层自查的盲区）。**

| 簇 | 位置 | 行数 | 生产引用 |
| --- | --- | --- | --- |
| DomainStore | `domain/{DomainStore,DomainStoreCore,Connection,KeyRange,QuotaPolicy,DomainText}.kt` + `sqlite/` + `testing/` | 1,019 | **0** |
| 解码器家族 | `device/{DeviceManifestDecoder,DeviceManifestJson,DeviceManifestException,LocalDeviceManifest}.kt` | 887 | **0**（`DeviceManifest.parse` 无生产调用点） |
| 设备能力 SPI 接口 | `device/DeviceCapabilities.kt` | 130 | **0** |
| 能力词表 | `device/DeviceCapability.kt` | 124 | 仅被上面两个死簇引用 |

`device/` 另外 823 行有生产引用：`DeviceManifest` 226 + `CapabilityValue` 134（demo 字面量）、
`AndroidDeviceCapabilities` 166 + `AndroidHostPermissions` 297（电话权限接线）。所以「设备能力簇」不是整簇
死，死的是契约/解码半边；审计时记的 1,283 行里，142 行（旧的 `AndroidDeviceCapabilities`）已在工作树里接走。

**这四行是 UI 层自查的盲区。** 本轮 UI 死代码自查做了 11 次独立复核，数字精确一致、可信；但它的扫描面是
`:lib` 的页面与组件簇，**没有扫 `android/*/src/main` 的非 UI 模块**。「页面上没有入口」和「模块里没有死代码」
是两个命题：本轮页面数从 README 快照的 21 涨到 36，而 `:domain` 的 1,019 + 1,141 行一直没进过死簇名单。

对照：`domain/connector/`（2,493 行）**不在**死簇里——`DeviceConnectionSource` / `DeviceSessionSource` 和
transcript 值类型被 `:lib` 页面真实引用，`PdshNavHost.kt` 用 `InMemory*` 夹具把它们接成了 demo。它的问题是
「demo 级接线」而不是「零消费者」，两件事不该混在一起登记。

### spec 漂移：`wal.md §8` 写「uncompressed ustar」，实现是 `gzip(ustar)`

- `spec/v1/wal.md §8`：boot overlay 是「**uncompressed** ustar archive」。
- `:engine` 的 `BootOverlay.kt` 产出的是 `gzip(ustar(entries))`：一个 gzip member 包住一个 plain ustar。
  原因是 D21（iOS 的 `OverlayPacker.swift` 已如此；`spec/v1` 里没有这条文字）：pinned runtime 的
  `storage/image-gzip.ts` 拒绝首字节不是 `1f 8b` 的 body，image 与 overlay 共用 `readImage`；发 plain
  ustar 不是「被宽容接受」，是 **boot 失败**。
- **没有解包穿越含义。** 两侧都有 `home/` | `workspace/` 首段门禁（packer 只看这两个目录；
  `BootOverlay.kt` 的注释引用 `memory.ts` 的 `loadVfsOverlay`：`IMAGE_OVERLAY_DIRECTORIES` 恰为
  `['home', 'workspace']`），symlink / 特殊文件在 packer 里 fail-loud（`Refusal.SYMLINK`），不落盘、
  不跳过。所以这是**纯规范同步**，不是安全缺口。
- **本车道不改 `spec/**`**（spec 是只读判据，改动要 owner）。需要 owner 做的是把 D21 的 gzip 外壳写进
  `wal.md §8`（或在 §8 明确「archive 是 ustar，传输外壳是 gzip」）。同一漂移在
  `docs/receipts/android-port-2026-10-31.md` 的 P28 节已详细记录；PR 描述此前没有登记它。

---

## 七、这一轮的方式

- **多条车道并行**，每条都在隔离快照里验证，报告自己的 provenance
- **不许 `--write-baseline`**——它会把分歧吸收成「已知」，门禁立刻变绿，但问题一个没解决
- **不许为了让门禁过而改产品**：三条「真缺控件」查下去全是「已在，门禁读不到」；`This iPhone Worker` 至今红着并被如实登记，因为照抄是假话
- **不许编测量**：每条残差都贴数字和出处，测不出来的写「测不出来 + 原因」

### 这一轮我自己犯的两类错误

**1. 登记理由没核实。** 我给 `ToolsPage` 写的理由是「删掉 public composable 属于 API 变更」，实际它是**旧页面的视图包装器**，和 `PDSHShellPage` 共用同一个 reducer。另一条 `native-workbench 重试` 的理由「从本地状态读文件夹列表」也是假的——它是**组合包装器**。**两条都被车道揪出来并改正。**

**2. 登记了一个我自己刚修掉的症状。** 我把 `native-automation / 'text'` 写成「契约不可比」时，同一批改动里刚让检查器学会读 `title`——那个包装器存在的理由已经消失，正确的做法是删它，不是登记它。

**我一直在要求车道「先验证原因再登记」，而我自己登记时在赶进度。**
