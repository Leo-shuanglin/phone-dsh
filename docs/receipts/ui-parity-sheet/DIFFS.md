# iOS ↔ Android 逐张差异清单（L64，2026-11-02）

复核这份清单要三样东西，都在仓库里：

1. `docs/receipts/ui-parity-sheet/<state>__ios-left.png` —— 并排图，左 iOS、右 Android，
   同一个缩放系数、顶对齐，左边有 frame px 标尺。
2. 本文坐标：**pt = px / 3**（402×874 pt @3× 框），全部在 1206×2622 的原图上量。
   并排图是 0.5× 缩放，`pt = (sheet_y − 114) × 2 / 3`。
3. 测法：逐行取「非背景墨迹」的 y 带与 x 范围，再取该带最暗 1/12 像素的中位色作为字色；
   色块取出现次数最多的饱和色。**没有做像素 diff**（两平台字体栅格不同，diff 数字无意义）。

读字号的规矩：字体栅格不同，**同字号的中文墨高不同**——17 pt 的 CJK 在 iOS 上墨高 15.3 pt，
在 Android 上 16.3–17.0 pt。所以判断字号只用**同一个字符串**两边的墨高比，或回读
`examples/.../**.swift` 里的 `.font(...)`。下文写「墨高」的地方都是这个意思。

## 一条贯穿全集的已知偏移（不是缺陷）

iOS 截图带模拟器状态栏（墨迹 **y 13.7–50.7 pt**，安全区 62 pt），Android 隐藏系统栏。
因此 iOS 的内容整体比 Android 低 **约 61–62 pt**。下文「扣除 chrome 后」= `iOS y − 61.3`
（settings 家族实测 61.3；其余页因顶栏结构不同，落在 57.7–64.7 之间，逐条写明）。

---

# 一、settings 家族（7 张）

## page-settings-default

iOS 是 `ios-settings.png`：白色 44 pt 导航条（居中 17 pt 标题 `通用设置` + 右上白色胶囊 `完成`）
+ 5 段选择器（#EEEEEF 胶囊轨道）+ 4 张内嵌分组卡。Android 是 `SettingsPage` 通用 tab。

| 控件 | iOS | Android（修前） | Android（修后） | 差 |
|---|---|---|---|---|
| 导航条底色 | #FFFFFF，y 0–166.7 | #F2F3F5（页面灰），y 0–140.3 | 同左 | 无白条 |
| 标题 `通用设置` 墨迹 | y 76.7–92.0，h 15.7 | y 14.7–30.7，h 16.3 | 同左 | 扣 chrome 后 Δ1.7 pt |
| `完成` | 黑字 17 pt，ink x 337.0–368.7；**白色胶囊** y 60.0–104.7、x 302.7–389.7 | 蓝字 #3964FE，ink x 346.7–379.0，**无底** | 同左 | 一个胶囊控件缺失；右缘 +10.3 pt |
| 选择器轨道 | #EEEEEF 胶囊，y 126.0–157.7 | 无轨道（页面灰即底），白药丸选中 | 同左 | 轨道填充缺失 |
| 卡片 1 顶 | y 202.0 | y 140.7 | y 140.7 | 扣 chrome 后 Δ0.0 |
| 卡片 2 顶 | y 341.7 | y 284.0 | **y 280.3** | 修前 Δ3.7 → 修后 Δ0.1 |
| 卡片 3 顶 | y 586.0 | y 532.0 | **y 524.7** | 修前 Δ7.4 → 修后 Δ0.0 |
| 卡片 4 顶 | y 830.3 | y 780.0 | **y 769.0** | 修前 Δ11.3 → 修后 Δ0.0 |
| 卡片间距 | 35.0（4 条带：920→1024 等） | 38.7 | **35.0** | 修前 +3.7 pt/段，累积到第 4 张卡 11.3 pt |
| 卡片左右 | x 16.0–385.3 | x 16.0–385.3 | 同左 | 0 |
| 开关 bbox | x 309.0–371.7 × y 703.0–730.7（h 27.7） | x 283.7–346.3 × y 648.0–677.3（h 29.3） | **x 309.0–371.7** × y 640.7–670.0（h 29.3） | 修前水平偏左 25.3 pt；修后 0（高度仍 +1.6 pt） |
| 开关颜色 | **#34C759**（systemGreen，7078 px） | **#3964FE**（accent token，11296 px） | 同左 | 色相完全不同；无 token，未改（见 §8） |
| 额外卡片 | 无 | 第 6 张 `性能与用量`（y 832.3 起） | 同左 | Android 多一张 |

## page-settings-model

iOS 是**独立 sheet**（`ModelProviderSettingsPage`）：没有分段选择器，34 pt 大标题 `模型服务`。

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 容器 | 无分段选择器；大标题 y 125.7 起 | 设置 shell 的 5 段选择器 y 44.7–73.3；大标题 y 68.0–100.3 | Android 多一层 shell（注册表说「两边都是 模型 tab」，图上 iOS 没有 picker） |
| `服务` 分组头 | y 180.7，x 32.7（无折叠箭头） | y 124.0，x 33.7，带 `⌄` + 右侧 `1 项` | Android 多折叠箭头与计数 |
| 选中标记 | 蓝色实心圆勾 #0088FF，x 320.0–333.3 | 无圆勾；右侧文字 `默认 未知` | 控件不同 |
| 行内容 | `默认模型服务 [默认]` + URL + 模型 + `读不到密钥`（橙） | `模型 deepseek-flash` / `服务 api.deepseek.com` / `读取不到这项状态，未做任何假设` | 每行内容模型不同 |
| 动作 | 图标行 `添加模型服务` / `编辑默认服务`（⊕、滑杆图标）+ 2 条分隔线 | 纯文字链接 `编辑` `删除` / `添加模型服务` / `编辑默认服务`，无分隔线 | 图标与分隔线缺失 |
| `生效方式` | 左对齐 x 32.7，y 780.0 | **缩进到 x ~86 pt**（居中显示），y 500.7 | Android 错位 ~53 pt |
| 大标题字 | 墨高 30.0（34 pt/700） | 墨高 32.7（34 sp/700） | 同字号（raster 差） |

## page-settings-search

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 大标题 `网页搜索` | y 125.7–155.3，墨高 30.0 | y 68.0–100.3，墨高 32.7 | 扣 chrome 后 Δ0.0；同字号 |
| 关闭控件 | 无 | 蓝色 `✕` x 23.0–35.3，y 17.0–29.7 | Android 多一个关闭键（iOS 是 push 页） |
| 分组头 `用哪一份搜索` | #8A8A8E，墨高 15.3 | #66666B，墨高 16.3 | 色差：#8A8A8E vs token #66666B |
| 选项 1 标题 | #111114，y 234.0 | **蓝色 #3964FE**（选中态染色） | 选中项颜色不同 |
| 勾选标记 | 蓝勾 x 330 起 | 蓝勾 x 371 起 | Δ约 40 pt |
| `现在这个模型服务自带搜索…` | #8A8A8E，墨高 13.0（13 pt） | #66666B，墨高 13.3 | 只有色差 |
| `什么时候生效` | y 422.7，x 32.7 | y 331.7，x 33.7 | 位置扣 chrome 后 Δ2.3 |
| `还要知道` 三段正文 | 墨高 16.7，行距 22.3（17 pt） | 墨高 13.3，行距 16.3（13 sp） | **字号 14 vs 17**；修后墨高 17.7，行距 20.3 |
| `启动本地引擎` | #0088FF，墨高 15.7（17 pt） | #3964FE，墨高 12.3（≈13 sp），x 6.3 起 | 字号 13 vs 17；左边距 6.3 vs 32.7 |

## page-settings-network

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 页面标题 | `网络访问`（17 pt，居中，y 65.0） | `出网白名单`（17 sp 粗体，居中，y 19.0） | 文案不同；注册表/契约写的是 `网络访问` |
| 顶栏左侧 | 无控件 | 蓝色 `✕` x 23.0–35.3 | Android 多一个 |
| `允许连任何网站` 行 | y 153.0，开关 x 309–371（关，灰 #C5C5C7） | y 65.0，开关 x 306–350（关） | 行位置扣 chrome 后 Δ0.7；开关位置差 ~21 pt |
| 地址列表行高 | 52.33 pt（wttr.in y 264.0 → api.open-meteo y 318.7） | 35.0 pt（wttr.in y 148.0 → api 183.0） | **17.3 pt/行** |
| 列表行内容 | 单行主机名（17 pt） | 两行：主机名 + `精确主机 任意 http(s) scheme · 任意端口` | Android 多一行说明 |
| 行尾控件 | 无 | 每行右侧 `⋯` | Android 多一个 |
| 文字色 | #000000 | #111114 | 见 §8：iOS 用系统 .primary=纯黑 |
| 分隔线 | 起始 x 43 pt | 起始 x 16 pt | 缩进差 27 pt |
| 悬空 Material 色 | — | #E6E0E9 出现 8584 px、#9A9AA2 61205 px | 非 token 色（见 §8） |

## page-settings-plugins

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 页面标题 | `插件`（34 pt 大标题，y 285 起） | `本会话设置`（34 sp 大标题，y 68.0 起） | **文案不同**：契约 `native-configuration.title.text = 插件` |
| 顶栏 | 白色胶囊 `完成` | 蓝字 `完成` | 同 settings-default |
| 三个玻璃胶囊 | `插件说明` / `刷新` / `添加插件`，y 415–505，x 130–580 | 无胶囊；只有纯文字 `插件说明` `添加插件`（**无 `刷新`**） | 3 个控件 → 0 个胶囊 |
| 告警条 | 橙色 ⚠ `请先启动 DSH 主机，再读取插件和设置。` y 604–670 | 无 | 缺失 |
| 行图标 | 每行前置 ⓘ / 🌐 图标 | 无图标 | 缺失 |
| 子行 | `子 Agent` / `子 Agent 模型` / `自动执行轮次` / `终端` | `读写状态 可修改` ×2、`允许派生子 Agent`、`默认 Shell /system/bin/sh` | 行集合不同 |
| 分隔线 | 6 条 | 无（卡片内无分隔线，靠留白） | 缺失 |

## page-settings-about

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 背景 | #F2F2F7 主导（2.77 M px） | #FFFFFF 主导（1.75 M px）+ #F2F3F5 | 卡片面积不同 |
| 卡片数 | **1 张**（y 170–270） | **3 张**（版本 / 系统 / 更多） | Android 多 2 张 |
| 卡片折叠头 | 无（直接一行 `版本 0.1.0`） | 每张卡有 `⌄ 版本 2 项` | Android 多折叠头 |
| 行 | `版本 0.1.0` + 脚注 `会话记录和文件都保存在这台手机上。` | `版本号 0.1.0` / `构建号 1`；`Android 15` / `API 级别 35` / `ABI arm64-v8a` / `设备 sdk_gphone64_arm64` / `WebView 131…`；`开源许可 不可用 ›` | 行集合不同（Android 是平台附录） |
| 顶栏 | `关于` 17 pt 居中 | `关于` 17 sp 粗体居中 + 蓝 `✕` | 多了关闭键 |

## page-settings-runtime

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 大标题 | `运行状态`（34 pt，y 300 起） | `运行时详情`（34 sp，y 68.0 起） | **文案不同**（契约 title=`运行状态`） |
| 内容量 | 5 块：`状态 已停止`、`最近出错`、`最近没有出错`、`启动本地引擎`、脚注 | 8 个折叠段：本次运行 3 项 / 最近出错 1 项 / 引擎 2 项 / 已运行 / WebView 3 项 / 内存 3 项 … | Android 多 3 个折叠段 |
| `读取不到这项状态，未做任何假设` | 0 次 | **6 次** | Android 重复文案 |
| 关闭/返回 | 无 | 蓝色 `✕` x 23.0–35.3 | 多一个控件 |
| 分隔线 | 2 条 | 每行一条 | 数量不同 |

---

# 二、sessions 家族（7 张）

## 公共差异（除 schedules 外的 6 张）

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 抽屉宽度 | 满宽 402 pt（搜索框 x 30–370，CTA x 16–385.7） | 320 pt 抽屉（内容 x 16–303.7） | **CTA 宽度 369.7 vs 287.7 pt（差 82）** |
| 搜索框 | y 100.7–144.0，x 30.0–370.0，白底 + 放大镜 | y 49.3–88.7，x 26.3–312.7 | 高度 43.3 vs 39.3；位置扣 chrome 后 Δ7.3 |
| 会话行前导图标 | `bubble.left` 墨迹 16.0×15.3，x 24.3–40.0 | 修前：字符 `▢`，墨迹 9.7×10.3，x 25.3 | **形状/大小/基线都不同**；修后：矢量气泡 17.0×15.7，x 24.0–40.7 |
| 行高 | 56.0 pt（20 行实测 345.0→401.0） | 56.7 pt（263.0→319.7） | +0.7 pt/行，20 行累积 +14 pt |
| 行标题字号 | 17 pt（墨高 15.3） | 17 sp（墨高 16.3–24.0，含图标带） | 同字号 |
| 行时间字号 | 13 pt（墨高 12.7） | 13 sp（墨高 11.0–11.3） | 同量级 |
| 次级文字色 | #8A8A8E / #66666B | #66666B / #9A9AA2 | 见 §8 |
| 行尾 `⋯` | `•••`（三点，较大） | `⋯`（三点，较小） | 字形不同 |
| 新建会话行色 | #C3C3C6（禁用） | #9A9AA2 | 色差 |
| 已归档 | `› 已归档  0`，y 1117（sheet） | `› 已归档  0`，y 763（sheet） | 扣 chrome 后位置接近 |

## page-sessions-default

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 搜索占位墨迹 | y 130.7–146.7，墨高 16.3 | y 66.7–82.7，墨高 16.3 | 扣 chrome 后 Δ2.7 |
| `+ 新建会话` | y 194.3–210.0，x 18.3–111.0 | y 143.0–158.3，x 16.7–102.7 | 扣 chrome 后 Δ10.7 |
| `新建会话` 下分隔线 | 有（y 214.6） | **无** | 缺失 |
| `先启动本地引擎,才能新建会话。` | y 250.7，墨高 12.7 | y 188.7，墨高 13.3 | 扣 chrome 后 Δ0.7 |
| `还没有工作文件夹` | 居中（中心 200.9），墨高 15.7 | 居中于 320 抽屉（中心 159.7），墨高 16.3 | **居中基准差 41.2 pt** |
| CTA `新建工作文件夹` | y 348.7–398.3，x 16.0–385.7，h 50.0，#2F5BFF | y 303.7–353.3，x 16.0–303.7，h 50.0，#2F5BFF | 高一致；宽差 82 pt |
| 返回键 | 无 | `‹` 墨迹 x 22.7–25.0，y 20.3–25.0 | Android 多一个 |

## page-sessions-nothing

**这一张不可能对。** `android/.../ios-parity/page-sessions-default.png` 与
`page-sessions-nothing.png` 是**同一个文件**（md5 `64dcaccb…`，byte-identical），而两张 iOS 图不同
（diff 74,939 px，行 223.7–398.3 pt）。所以 `page-sessions-nothing` 的「captured」是假的：
Android 用一张图顶了两个注册状态。差异同 page-sessions-default（iOS 侧另有
`this capture equals page-sessions-nothing` 的注册表注记自认）。

## page-sessions-empty

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| `项目资料` 分组头 | y 288.7（sheet 547） | y 224.0（sheet 450） | 扣 chrome 后 Δ2.0 |
| 分组头 `•••` / `⋯` | `•••` x 222.7 | `⋯` x 227.3 | 字形与位置 |
| `还没有会话` | y 588.7，墨高 15.3 | y 275.3（sheet 880 → 510）… 见行 | 行距压缩 |
| `点上面的「新建会话」开始。` | 13 pt | 13 sp | 同量级 |
| 每行之间的留白 | 分组块之间 ~35 pt | ~12 pt | Android 更紧 |
| `+ 添加工作文件夹` | 17 pt，#007AFF 系 | 17 sp，蓝 | 字号同 |
| `› 已归档  0` | y 848 | y 763 | — |

## page-sessions-many

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 行前导图标 | 气泡 16.0×15.3 | `▢` 9.7×10.3（修前）→ 气泡 17.0×15.7（修后） | 修前形状不符；修后 Δ1.0 pt 宽 |
| 行标题 | 17 pt，x 51.0 | 17 sp，x 51.7 | Δ0.7 |
| 行距 | 56.0 | 56.7 | +0.7/行 |
| 分组 `项目资料 · 20 个会话` | y 299.3，`•••` x 222.7 | y 223.0，`⋯` x 228.0 | 扣 chrome 后 Δ13.0 |
| 行右侧勾选 | 当前会话有 `✓`（20 pt） | 无 | 未验证（本图无选中项） |

## page-sessions-ungrouped

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 行距 | 56.0（预览 1 y 345.0 → 预览 2 y 401.0） | 56.7（y 263.0 → 319.7） | +0.7 |
| 首行顶 | y 345.0 | y 263.0（修前 263.0，图标改后同） | 扣 chrome 后 Δ20.7 |
| `添加工作文件夹` | y 489.0，x 32.7 | y 433.0，x 16.7 | 左边距差 16 pt |
| 行前导图标 | 气泡 | `▢` → 气泡 | 同 many |

## page-sessions-error

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 失败行 | `读不到会话` + `重试`（红/蓝） | 有同类行，颜色 `#C0392B` | 色值不同（token danger=#C0392B，iOS 用系统红） |
| 行距 | 56.0 | 56.7 | +0.7 |
| 重试行 `重试` | 17 pt | 17 sp | 同 |

## page-sessions-schedules

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 背景 | #FFFFFF | #F2F3F5 | 底色不同 |
| 左上控件 | **圆形玻璃按钮 ↻**（y 204–270，白圆 + 阴影） | 蓝字 `✕` + 蓝字 `刷新`（x 22.7 / 50.0） | 控件不同 |
| 右上 `新建` | **白色胶囊**（y 204–270） | 蓝字（y 14.7–30.7） | 胶囊缺失 |
| 大标题 `定时任务` | y 300–366，墨高 30.3 | y 68.0–100.3，墨高 32.7 | 扣 chrome 后 Δ0.0 |
| 空态标题 | y 410.0，墨高 15.7 | y 127.0，墨高 16.3 | 扣 chrome 后 Δ7.0 |
| CTA `新建任务` | y 500.0–560.0，x 20.0–330.0（w 310） | y 203.3–251.0，x 16.0–303.7（w 287.7） | 宽差 22.3 |
| `需要处理` 分组头 | #8A8A8E，墨高 15.3 | #66666B，墨高 12.0（13 sp） | **字号 13 vs 17** |
| 错误正文 | 17 pt 红字，两行 y 720.0/738.0 | 13 sp 红字，两行 y 320.7/333.3 | 字号 13 vs 17 |
| 顶栏底色 | 白 | 灰 | 同 settings |

---

# 三、chat 家族（8 张）

## 公共差异

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 顶栏 | 44 pt：侧栏图标 x 20.3、居中标题 17 pt、右侧手机图标 + `•••` | 44 dp：`☰` x 22.7、居中标题 17 sp、`● 未启动/运行中` + `⋯` | 右侧控件集合不同；标题粗体不同 |
| 正文行文字 | **17 pt**（墨高 16.0–16.7） | **14 sp**（墨高 13.3–13.7） | 3 pt；token `body=14` 与基线矛盾（§8） |
| 正文左边距 | x 32.7 | x 16.7 | 16 pt |
| 气泡（用户） | 灰底，右缘 x 352.7 | 灰底，右缘 x 368.7 | 16 pt |
| composer | y 756.7 起（占位 17 pt） | y 766.3 起（占位 14 sp） | 字号与位置 |
| 统计行 | 17 pt，带 `○` 环形图标 | 14 sp | 字号与图标 |
| 语法着色 | 代码块有颜色（字符串 #22C55E，关键字蓝） | 全 #111114 | 无高亮 |

## page-chat-default

iOS 是另一段对话（注册表自认），所以按结构列：

| 控件 | iOS | Android |
|---|---|---|
| 权限卡 | 有：`等待授权` y 135.3 + `要跑一条命令,可以吗?` + 蓝色 `允许一次`（y 193.3–241.0, h 48.0）+ `拒绝` | **无** |
| 审批问题卡 | 有：`两个失败的用例怎么处理?`（y 614.3）+ 编号选项 + `1/1` + `跳过`/`提交` | **无** |
| 工具行 | `已调用工具` 折叠头 y 430.3 + 2 行 `工具调用 · 42 行` | 1 行 `工具调用 · screenshot-capture · ok · 10 张` y 624.3 |
| 正文 | 3 段（y 371.0–541.3），17 pt | 1 段（y 485.7–519.7），14 sp |
| composer | 帧内无 | 有（y 766.3–868.7） |

## page-chat-assistant

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 时间戳右缘 | x 352.7 | x 368.7 | 16.0 pt |
| 头像/名字 | #81858C，x 38.7 | #9A9AA2，x 21.7 | 色值 + 17 pt |
| 工具行 | y 399.7，墨高 12.7 | y 475.0，墨高 12.7 | 扣 chrome 后 Δ10.6 |
| 正文 | y 432.0，墨高 16.7（17 pt） | y 517.0，墨高 13.7（14 sp） | 字号 |
| 代码块 | 有语言标签 + 着色（#22C55E） | 无着色 | 高亮缺失 |

## page-chat-official（已知差异 #3）

| 控件 | iOS | Android |
|---|---|---|
| 富文本 transcript | 代码块 `struct NativeChat`（y 122.0–174.3）、`E=mc²`（210.7）、原始 HTML（247.7）、`[note]`（271.3）、`脚注`（308.7）、**`已编辑 6 个文件` 卡**（393.3–640.0，含 4 行文件 + `+1 −1` + `全部 6 个文件 ⌄`） | **全部没有**；只有 1 条用户气泡 + 1 段正文 |
| 用户气泡 | 无 | `hello test 123` y 531.3，右缘 368.7 |
| 正文 | — | y 612.7–646.0，14 sp |
| composer | y 736.7 起，17 pt | y 772.3 起，14 sp |
| 统计行 | y 780.0，17 pt，带 `○` | y 818.0，14 sp |

差异 = **内容缺失**（样本在 `android/lib/src/androidTest/.../ScreenshotSamples.kt`，本 lane 不可写）。

## page-chat-failed

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 失败呈现 | 最后一泡下 **红字 `not sent`** #C0392B（y 663.0） | 顶部 **橙色横幅** #B45309（y 60.0–73.0，x 4.7–391.0） | 完全不同的组件 |
| 气泡 | y 545.0 / 570.7（灰） | y 485.7 / 506.7 | — |
| 正文 | 17 pt | 14 sp | 字号 |

## page-chat-queued

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| `排队中` 条 | composer 上方 y 705.0（`#61656A`） | **无**（注册表：sample 未填 `ComposerState.queued`） | 缺失 |
| 排队徽标 | 气泡右下 y 616.7 `#66666B` | 无 | 缺失 |
| 时间戳右缘 | 352.7 | 368.7 | 16.0 |

## page-chat-long

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 内容起点 | y 393.0（蓝链接） | y 82.3 | 两边**滚动位置不同**，逐行不可比 |
| 正文行距 | 24.0（456.3→480.3） | 20.7（186.7→207.0） | 行距 3.3 |
| 正文墨高 | 16.0 | 13.3–15.0 | 字号 17 vs 14 |

## page-chat-draft

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 空态标题 | y 261.7，墨高 15.7 | y 137.0，墨高 16.3 | 扣 chrome 后 Δ2.0 |
| 蓝色主按钮 | y 327.0–376.7，h 50.0，#2F5BFF | y 203.3–251.0，h 48.0，**#3964FE** | 高度 50 vs 48；颜色 accent vs ctaOverride |
| 正文 | 17 pt | 14 sp | 字号 |

## page-chat-permission

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 页面顶栏 | **无**（直接是卡片） | 有（`☰ 新会话 ●未启动 ⋯`，y 14.7–30.7） | Android 多一条顶栏 |
| 卡片底色 | #F5F6F7（浅灰卡） | #F2F3F5（与页面同色） | 卡片不可分辨 |
| `✋` 图标 | 有（x 34.0，与标题同高） | 无 | 缺失 |
| 标题墨高 | 18.7（17 pt 粗） | 16.3（17 sp 粗） | 同字号 |
| CTA `允许一次` | y 327.0–374.7，h 48.0，**#3964FE** | y 394.0–441.7，h 48.0，**#2F5BFF** | **颜色不同**（accent vs ctaOverride） |
| `拒绝` | y 400.3 | y 471.7 | — |
| composer | 无 | 有（y 798.3 起） | Android 多 |

---

# 四、shell / files / workspace-folders（3 张）

## page-shell

| 控件 | iOS | Android（修前） | Android（修后） | 差 |
|---|---|---|---|---|
| 分组标题 `这台手机` | 墨高 15.3（17 pt），x 33.0 | 墨高 11.3（12 sp），x 32.3 | **墨高 16.3** | 修前 −4.0 pt |
| 行 `状态` | 墨高 15.3（17 pt），y 175.0 | 墨高 13.3（14 sp），y 113.7 | **墨高 16.3** | 修前 −2.0 pt |
| 行距 | 52.3（175.0→227.3） | 44.6（113.7→158.3） | 44.7 | **仍差 7.6 pt** |
| 脚注 `这些命令…` | 墨高 12.7，y 333.7 | 墨高 13.3，y 247.3 | 13.3 | 0 |
| 空态图标 | 55.0×43.3，圆角框 #808080 | 43.7×44.0，#9A9AA2 | 同左 | 宽 11.3、颜色偏浅 |
| 空态标题 `还没接上命令` | 墨高 19.7（≈20 pt 粗） | 墨高 16.3（17 sp 粗） | 16.3 | **19.7 vs 16.3**：无 20 pt token，未改 |
| 空态说明 | 17 pt，折两行（y 528.3 / 548.3） | 13 sp，一行（y 456.7） | 13.3 | 字号导致换行不同 |

## page-files

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 大标题 `手机工作区` | 34 pt，y 376（sheet 125.3–155.3 pt） | 17 sp 粗体，y 14.7–30.7 | **没有 34 pt 大标题** |
| 3D 入口 | 右上白圆 + 立方体图标，x 353.0–374.3，y 72.3–96.3 | **无** | 缺失（= `3D 示例` sheet 的入口） |
| 搜索框 | 灰底胶囊，y 167–190，放大镜 + 17 pt 占位 | 灰底胶囊，y 60.7–81.0，放大镜 + 17 sp 占位 | 扣 chrome 后 Δ0.4；高度 23 vs 20.3 |
| 空态标题 | y 269.7，墨高 15.7 | y 127.0，墨高 16.3 | 扣 chrome 后 Δ7.0 |
| 空态说明 | 17 pt（墨高 11.7？—— 见 §10） | 13 sp（墨高 12.3） | 待查 |
| 背景 | #FFFFFF | #F2F3F5 | 底色不同 |

## page-workspace-folders

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 大标题 `工作文件夹` | 34 pt，y 125.7–155.3 | 17 sp 粗体，y 14.7–30.7 | **没有 34 pt 大标题** |
| 右上 `添加` | 白色胶囊 y 204–270 | 蓝字 y 14.7–30.7 | 胶囊缺失 |
| 背景 | #FFFFFF | #F2F3F5 | 底色不同 |
| 空态标题 | y 293.3，墨高 15.7 | y 127.0，墨高 16.3 | 扣 chrome 后 Δ7.0 |
| CTA | x 20.0–330.0（w 310），h 50.0，#2F5BFF | x 16.0–381.0（w 365），h 48.0，**#3964FE** | 宽差 55；色 accent vs ctaOverride |

---

# 五、surface（1 张）

## surface-code-block

| 控件 | iOS | Android | 差 |
|---|---|---|---|
| 卡片顶 | y 92.7（首行墨迹 115.7） | y 0.7（首行墨迹 14.3） | **整块上移 92 pt**：Android 无顶部留白 |
| 语法着色 | 关键字蓝 `let` + 字符串 **#22C55E** | 全 #111114（8462→0 个绿像素） | 无高亮 |
| 头部按钮 | 两个：换行图元 + 复制 | 一个：复制 | 缺一个 |
| 头部底色 | #E9E9ED（100,182 px） | 无 banner 底（`codeBanner` 未用） | 缺 banner |
| 代码字号 | 墨高 12.7（12 pt mono） | 墨高 9.0（12 sp mono，Latin 墨高天然更矮） | 不可比（同字号） |

---

# 六、env-home（补上的那一条）

| 控件 | iOS | Android（2026-11-02 采集） |
|---|---|---|
| 帧 | 1206×2622 | 1206×2622 |
| 语言 | 中文（地图 / 日历 / 照片 / 设置） | 英文（Mon, Nov 2 / Gmail / Photos / YouTube） |
| 状态栏 | 09:41 + 灵动岛 + 信号/wifi/电池 | 1:03 + 3G + 信号 + 电池，无灵动岛 |
| 小组件 | 有：地图 2×2、日历 2×2（`今天无日程`） | 无 |
| 图标网格 | 4×2 + 2 dock | 3 + 4 dock |
| 搜索 | 底部胶囊 `搜索` | 底部 Google pill |
| 壁纸 | 蓝绿抽象 | 深蓝渐变 |
| 手势条 | 无（iOS home indicator 不在图内） | 有 |

两边是**两个不同操作系统的桌面**，逐项对齐没有意义；这张图的作用是证明 Android 侧真的
跑在 402×874 pt @3× 的同一帧上。它不进 `parity` 基线（注册表仍是 `pending`）。

---

# 七、修了什么（修前 → 修后）

| # | 文件 | 改动 | 修前 | 修后 | 依据 |
|---|---|---|---|---|---|
| 1 | `ui/SettingsMetrics.kt` | `SECTION_GAP_PT 38.67f → 35f` | Android 卡间距 38.7 pt；第 4 张卡比 iOS 低 11.3 pt | 35.0 pt；4 张卡顶 y = 140.7 / 280.3 / 524.7 / 769.0，iOS−61.3 = 140.7 / 280.4 / 524.7 / 769.0，**Δ ≤ 0.1 pt** | `ios-settings.png` 4 条灰带 105 px（920→1024、1653→1757、2386→2490）；原注释引用的 914/1030、116 px 在该文件里不存在 |
| 2 | `ui/surface/SettingsChrome.kt` + `ui/page/SettingsPage.kt` | `SettingsRowFrame` 新增 `trailingInset`，开关行传 `SWITCH_TRAILING_INSET_PT`(14) | 开关 x 283.7–346.3（离卡右缘 39.3） | x 309.0–371.7（离卡右缘 13.6） | iOS 开关 x 309.0–371.7，卡右缘 385.3 → 14.0 pt |
| 3 | `ui/page/SessionsPage.kt` | `▢` 文本字符 → `Canvas` 画的 `bubble.left`（17 pt 框内，描边 8.5%） | 墨迹 9.7×10.3，空心方块 | 墨迹 17.0×15.7，x 24.0–40.7 | iOS `bubble.left` 墨迹 16.0×15.3，x 24.3–40.0（`SessionsPage.swift:549`） |
| 4 | `ui/surface/SettingsChrome.kt` + `ui/page/WebSearchSettingsPage.kt` | 新增 `SettingsBodyRow`（17 pt），`还要知道` 三段改用它 | 墨高 13.3（13 sp），行距 16.3 | 墨高 17.7，行距 20.3 | iOS 同一字符串墨高 16.7，行距 22.3；Swift 三段是裸 `Text`（`.body` 17），同页其余行显式 `.font(.system(size: 13))` |
| 5 | `ui/page/PDSHShellPage.kt` | 分组标题 `caption(12) → rowLabel(17)`；行标签/值 `body(14) → rowLabel(17)` | 墨高 11.3 / 13.3 | 墨高 16.3 / 16.3 | `ios-shell.png` 同一字符串墨高 15.3 / 15.3（17 pt） |

**回归证据**：改完在同一台 AVD 上重跑 `--profile ios-parity`（对比模式），26 个状态里
**18 个 0 diff px**，其余 8 个是本次改动的页面（settings-default 9.38%、shell 6.12%、
settings-about 4.88%、settings-search 3.54%、sessions-many 0.38%、settings-model 0.13%、
sessions-ungrouped 0.10%、sessions-error 0.07%）。0 diff 的 18 个证明模拟器能逐字节复现原基线，
8 个的差异就是改动本身。

---

# 八、改不了的（缺 token / 需要 iOS 那边改 / 结构性）

1. **开关绿 vs 蓝**。iOS #34C759（systemGreen）；Android #3964FE（accent token）。
   `spec/ui/v1/tokens.json` 里没有开关色：`colors.success` 是 **#27865A**，不是 #34C759。
   造一个新 token 不是本 lane 能做的，所以**没改**。
2. **正文 14 vs 17**（8 张 chat 全部命中）。这不是 Android 用错 token：
   `typography.body.baseSize = 14`，而 iOS 渲染的是 `.font(.body)` = 17
   （`ChatPage.swift:4010`），`NativeParityTokens.bodySize = 14` 只用于 `NativeTurnProcessRow` 等。
   **token 与基线互相矛盾**，改 Android 只是让一边服从另一边。需要 token 侧裁决。
3. **纯黑 vs #111114**。iOS 的 settings/shell/sessions 文字实测 #000000（系统 `.primary`），
   token `colors.primary = #111114`。Android 跟 token，所以差 17/255。
   要么 iOS 改用主题色，要么 token 承认 `.primary`。
4. **iOS 用系统色，token 用一个值**：`#8A8A8E`(secondaryLabel) vs token `#66666B`；
   `#0088FF`(systemBlue) vs token `#3964FE`。同一页里两种蓝都存在（`启动本地引擎` 是 #0088FF，
   而 `完成` 是 #3964FE）—— 需要先把「哪些页用系统色」定下来。
5. **ctaOverride 用错层**：`page-chat-permission`、`page-chat-draft`、`page-workspace-folders`
   的 iOS 主按钮实测是 **#3964FE**（accent），Android 用 **#2F5BFF**（ctaOverride）。
   token 自己的注记说 ctaOverride 是「审批卡 CTA 的页面层固定色」，但基线在审批卡上画的是 accent。
   改哪一边都要先决定 token 的意图，**没改**。
6. **没有白色 bar / 胶囊的 token**：iOS settings/schedules/files/workspace 的顶栏是 #FFFFFF，
   `colors.background` 是 `null`（未测量），`colors.input` 是 #FFFFFF 但语义是输入框。
   胶囊按钮（`完成`/`添加`/`新建`/`↻`）需要圆角 + 阴影 + 白底三个值，一个都没有。
7. **没有 20 pt 字号的 token**：`ios-shell.png` 的空态标题是 20 pt（墨高 19.7），
   `typography` 只有 17（headline/rowLabel）和 15（subheadline）。Android 停在 17。
8. **行高 52.33 vs 44**：iOS `pageRowMinHeight` 实际行高 52.3；`geometry.pageRowMinHeight = 44`
   是下限而不是行高。没有「行高」token。
9. **语法高亮没有颜色 token**：iOS 代码块字符串 **#22C55E**、关键字蓝；tokens 里没有 code 语法色。
   Android 全单色 #111114。
10. **Material 默认色泄漏**：`page-settings-network` 里有 **#E6E0E9**（Material3 的 surface tint）
    8584 px，不是任何 token；`#9A9AA2` 61205 px 是 tertiary token 被大面积使用。
11. **结构性（页面本身就是两个东西）**：
    - `settings-runtime`：iOS 5 块 vs Android 8 个折叠段，标题也不同（运行状态 / 运行时详情）。
    - `settings-about`：iOS 1 张卡 vs Android 3 张（版本/系统/更多）+ 折叠头。
    - `settings-plugins`：iOS 三个玻璃胶囊 + 告警条 + 图标行 vs Android 纯文字链接、无刷新。
    - `settings-model`：iOS 独立 sheet（无 picker）vs Android shell 内嵌（有 picker）。
    - `settings-network`：iOS 单行 52.33 pt 列表 vs Android 两行 35 pt + 行尾 `⋯`。
    - `sessions`：iOS 满宽 402 pt vs Android 320 pt 抽屉（CTA 窄 82 pt，空态居中偏左 41.2 pt）。
    - `chat-official / chat-failed / chat-queued / chat-default`：transcript 与失败/排队组件内容不同，
      样本在 `android/lib/src/androidTest/**`（本 lane 不可写）。
    - `page-files`：iOS 的 `3D 示例` sheet 与它的 `完成` 在 Android 没有对应物（见 §9）。

---

# 九、「门禁绿但实际不对齐」——找到 7 处

1. **`files` 的 `3D 示例`：源码里有、运行时永远不渲染，检查器算它存在。**
   `FilesPage.kt:478-483` 的入口被 `if (onOpenDemo != null)` 包着，而 `PdshNavHost.kt` 的
   `FilesRoute` **从不传** `onOpenDemo`。`page_structure_check.py` 按源码标签配对，于是
   `3D 示例` 被记为「已有」，`--verbose` 里 files 只报 `完成` 一条 missing。
   像素证据：Android 面板右上角**没有**立方体按钮，iOS 有（x 353.0–374.3，y 72.3–96.3）。
   这正是 `files / 完成` 那条老故事的下一层：注册表记的是 **sheet 的取消键**，
   而真正缺的是**入口 + sheet 本身**；入口因为静态存在而被门禁放过。
2. **页面标题从不比较。** 契约里有 `title.text`，检查器只比 sections 和 actions。三张图上的
   标题不同而门禁全绿：`runtime-details` 运行状态 → 运行时详情；
   `native-configuration` 插件 → 本会话设置；`egress-allow-list` 网络访问 → 出网白名单。
3. **`page-sessions-default` 与 `page-sessions-nothing` 共用一张 Android 图**
   （md5 `64dcaccb…`），两张 iOS 图不同。27 个状态里有 1 个是「一图顶两态」，
   registry 却把两个都记成 `captured`。
4. **`chat`：契约 67 个 action / Android 29 个 label，divergences 0。**
   `page_structure_check.py` 的摘要行自己印出这两个数字，然后判绿。
5. **`settings-plugins` 的「插件」**：iOS 的插件 tab 是一整页（胶囊 + 告警 + 图标行），
   Android 的插件 tab 渲染的是**另一页**（本会话设置）。检查器按 section/action 配对，
   而 tab 选择器里那个 `插件` 恰好让页面看起来「叫对了」。
6. **`settings-model`**：注册表写「两边都是 模型 tab」，但 iOS 图上**没有分段选择器**，
   是独立 sheet。门禁不比容器。
7. **`egress-allow-list`**：契约 7 个 action / Android 7 个 label，`divergences 0`；
   但图上 Android 每行多一个 `⋯` 菜单、iOS 没有，且行高 35 vs 52.33。标签数量相等 ≠ 控件相同。

另外一条不是「绿但不齐」而是**红得没意义**：`page_structure_check.py` 的 `actions` 汇总里，
`files` 是 `contract 6 / android 2 / divergences 1` —— 6 个动作只核了 1 个，
另外 4 个落在「baseline 打不开的 presentation」里被跳过。数字看着像覆盖率，其实不是。

---

# 十、open questions

1. `ios-settings.png` 的卡片间距到底是 35.0 还是 35.33？灰带 105 px，卡到卡 106 px。
   我按「灰带 = 布局间距」取了 35.0；若 Android 该画 106 px，需要 +0.33。
2. `SECTION_GAP_PT` 原注释的 116 px 从哪来？`settings-chat.png` 实测也是 105 px。
   是抄错了文件，还是曾经有过另一版 iOS 布局？
3. 会话行 56.7 vs 56.0 的 +0.7 pt 来自哪里？行是 `heightIn(min=56)`，多出的 2 px 可能是
   分隔线占位。要不要把分隔线改成叠加（overlay）？
4. token `body=14` 与 iOS `.body`=17 的矛盾，以哪边为准？影响 8 张 chat 图。
5. `ctaOverride` 的适用面：基线在审批卡上画 accent（#3964FE），token 说页面层固定 #2F5BFF。
   是 token 注记过时，还是 iOS 那张图是旧版？
6. 是否要引入图标依赖（`material-icons`）或资源管线？现在 `:lib` 没有任何图标，全部用字符画；
   `bubble.left` 只能用 Canvas 近似。
7. `env-home` 要不要进 `parity` 基线？我让它留在 `pending`，从
   `docs/receipts/ui-parity-sheet/adb/` 配对（见 §11），因为桌面截图不是页面基线。
8. `page-files` 空态说明的字号：iOS 墨高 11.7 对 17 pt 偏小、对 13 pt 偏大，
   需要在 iOS 端确认 `ContentUnavailableView` 实际用的 role。

---

# 十一、重采后的图在哪

| 东西 | 路径 | 数量 |
|---|---|---|
| 并排图（27 张，含 env-home） | `docs/receipts/ui-parity-sheet/*__ios-left.png` | 27 |
| 索引（含 `source` 列） | `docs/receipts/ui-parity-sheet/metrics.txt` | 1 |
| Android 重采基线（设备写） | `android/lib/src/test/resources/screenshots/ios-parity/*.png` | 26 |
| env-home 的 adb 采集 | `docs/receipts/ui-parity-sheet/adb/env-home.png` | 1 |
| 本次逐张差异 | `docs/receipts/ui-parity-sheet/DIFFS.md` | 1 |

重采环境：自建 AVD `pdsh-l64-5728`（android-35 / google_apis / arm64-v8a），
端口 **5728**，`-no-window`，en-US，`font_scale 1.0`，`wm size 1206x2622` + `wm density 480`，
API 35。未触碰 5556 / 5558 / 5670–5726。用完删除。

复现命令：

```bash
bash android/tools/screenshot-capture.sh --profile ios-parity            # 对比模式（应当全 0 diff）
bash android/tools/screenshot-capture.sh --profile ios-parity --update   # 重写 26 张基线
python3 android/tools/ui-parity-sheet.py                                 # 重铺 27 张并排图
```
