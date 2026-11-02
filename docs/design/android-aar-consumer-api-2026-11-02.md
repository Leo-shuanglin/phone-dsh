<!-- SPDX-License-Identifier: MIT -->
# `:lib` 的 AAR：consumer API 与接入

日期：2026-11-02 · 车道：L63（证明 `:lib` 的 AAR 真的能被别人用起来）
状态：**编译面成立，运行面不成立**。`android/lib-consumer-test` 用文件依赖引入
`lib-release.aar`（不是 `project(":lib")`）编译并出 APK；但裸 AAR 缺 `:core` 与 `:domain`
的 44 个类，同一个工程的 R8 release 直接失败（§6.2）。仓库里**没有 POM、没有发布仓库**，
所以「AAR 是交付物」今天只在编译面成立。

## 1. 一次实测的结论

| 问题 | 结果 | 证据 |
| --- | --- | --- |
| AAR 能构建吗 | 能，4,079,341 字节 | `bash scripts/ci/check.sh --only phone-android-release` |
| 别人能编译吗 | **能** | `:lib-consumer-test:assembleDebug`（debug 变体只有 AAR 一个依赖） |
| 别人能运行吗 | **不能**（只有 AAR 时） | 缺 `com.phonedsh.agentcore.*` / `com.phonedsh.domain.*`，见 §6.2 |
| 有 POM 吗 | **没有** | `android/lib/build.gradle.kts` 的 maven-publish 注释仍写着 "lands when the first AAR is cut" |
| 有 consumer-rules 吗 | **有**，随 AAR 以 `proguard.txt` 下发 | 与 `android/lib/src/main/consumer-rules.pro` 逐字节相同 |
| 有必要的东西是 `internal` 吗 | 没有 | §3.3 |

一句话：**AAR 是编译完备的，不是运行完备的**。它自己（`classes.jar` 1,576 个 class，全部在
`com.phonedsh.ui.*`）不含 `:dsh` / `:core` / `:domain` / `:platform` / `:shell` 的类；这些是
POM 里的传递依赖，而 POM 还没被生产出来。

## 2. consumer 需要的三个东西

### 2.1 `PdshNavHost` —— 路由 → 页面的唯一入口

`public fun PdshNavHost(...)`（`android/lib/src/main/kotlin/com/phonedsh/ui/nav/PdshNavHost.kt:333`）。

必填 8 个参数：`nav`、`facts`、`onNavigate`、`onBack`、`onHome`、`onHostAction`、
`home`（`@Composable () -> Unit`）、`webSurface`（`@Composable () -> Unit`）。

可选（都有默认值，consumer 可以先不接）：`sessions`（会话列表槽位，`null` = 宿主的
facts-only 空列表）、`onSettingsWrite`（设置页三个写手势，默认 no-op）、`chatSubpages`
（聊天族三个内嵌子页的读数，默认"未读"）、`workspaceRoot`（物化工作区根；`null` 时按
`LocalContext.current.filesDir/xl/materialized/workspace` 解析）、`backLevel`（路由内部
那一级返回）、`onPhonePermissionRequest` / `onPhonePermissionSettings`（系统权限弹窗
属于 Activity，库不代持）。

`nav: PdshNavState` 是纯值：`stack` + `push` / `pop` / `popToHome` / `replaceTop` /
`canGoBack`，根页固定是 `PdshRoute.Home`。路由是 `PdshRoute` 密封接口的 data object/class，
consumer 只能通过 `PdshNavState` 移动它，不能往页面里塞回调。

### 2.2 `PdshNavFacts` —— 宿主读到的只读快照

`public data class PdshNavFacts(...)`（`PdshNavFacts.kt:104`），15 个字段，**全部有默认值**：

`workspaceFolders` / `workbench` / `modelProvider` / `webSearch` / `mcp` / `mcpEditor` /
`runtime` / `egress` / `automation` / `configuration` / `webSurface` / `settings` /
`shell` / `phonePermissions` / `background`。

每个默认值都是「这个宿主没读到」的诚实值（未知 / EMPTY），**不是** `0`、`emptyList()`
或编造的成功。所以 `PdshNavFacts()` 就是"全未知"快照 —— 这里**没有** `unknown()` 工厂
（任务书里的 `PdshNavFacts.unknown()` 并不存在）：默认构造器就是那个入口。

写手势的反方向是 `PdshHostAction`（同一文件的 28 个 sealed 子类型，都带 token-free 的
`wire` 标签）与 `SettingsWrite`（3 个）。页面从不自己改 facts：宿主真的读了新值，facts 才变。

### 2.3 启动与生命周期 —— 库不提供，由宿主提供

库是 Compose 面，**不是** Activity：没有 `setContent`、没有 `BackHandler`、没有 `PdshTheme`
之外的任何环境假设。consumer 自己要接四件事：

1. **`PdshTheme { ... }` 必须包住 `PdshNavHost`**。这是硬要求，不是装饰：
   `LocalUiTokens` 的默认值是 `error("PdshTheme is missing: no UiTokens in the composition")`
   （`android/lib/src/main/kotlin/com/phonedsh/ui/theme/UiTheme.kt`），漏了它在编译期完全
   看不出来，第一个路由渲染就崩。库刻意不自己包：深浅色是宿主自己的决定。
2. **Activity**：`ComponentActivity` + `setContent { ... }`（`androidx.activity:activity-compose`）。
3. **返回链**：`PdshBackLevel` 由宿主 `remember` 后同时交给 `PdshNavHost(backLevel = ...)`
   和宿主自己的返回回调。顺序是「路由内部那一级 → 路由栈」；根路由**不注册**回调，
   让平台默认的 finish 与预测性返回动画接管。库只提供 `PdshBackLevel.available` /
   `consume()`，**不提供那个 handler**：`:app` 的 `PdshBackHandler`（含预测性返回测量，
   `android/app/src/main/kotlin/com/phonedsh/probe/BackNavigation.kt`）是应用级文件，
   没有发布。consumer 自己写（约 20 行，见 §5）。
4. **写手势**：`onHostAction` / `onSettingsWrite` / 权限回调是宿主自己的实现点。没实现的
   就老实留在 facts 不动，页面会显示"宿主未实现"，不要伪造成功。

## 3. 可见性清单

### 3.1 公开面（`grep '^public '`）

`android/lib/src/main/kotlin` 下 77 个 `.kt`，**556 个顶层 `public` 声明**、404 个 public
类型。按包：

| 包 | public 类型 | consumer 会用到吗 |
| --- | --- | --- |
| `com.phonedsh.ui.nav` | 11 | **是**（§2.1/2.2 全部在这里） |
| `com.phonedsh.ui.theme` | 9 | **是**（`PdshTheme` / `PdshThemeValues` / `UiTokens` 家族） |
| `com.phonedsh.ui.theme.generated` | 6（构建生成） | 是（token 表，`UiTokenData` / `UiTokenNames`） |
| `com.phonedsh.ui.page` | 318 | 部分：35 个 `public fun *Page(...)` + 每页的 `*Facts` / `*ScreenState` / `*LocalState` / `*UiEvent` |
| `com.phonedsh.ui.state` | 26 | 部分（`ChatScreenState` 等纯状态） |
| `com.phonedsh.ui.surface` | 27 | 部分（签名面 composable） |
| `com.phonedsh.ui.surface.preview` | 16 | 部分（`PreviewSource` / `PreviewShare`） |
| `com.phonedsh.ui.projection` | 6 | 部分（UiState 投影） |

**支持的入口是 `PdshNavHost` + `PdshRoute`**。单页 composable 是 public 的（截图测试与
`:app` 都直接用），但它们各自带一整套 `*ScreenState` 参数，直接嵌单页等于绕过路由；
而且 `NativeDeviceConnectionPage` / `NativeDeviceSessionPage` / `SessionArtifactsPage`
的 public 签名里有 `:domain` 的类型（`DeviceConnectionEntry` 等），直接嵌它们会把
`:domain` 变成编译期硬依赖。

### 3.2 consumer 真正要认识的类型（全部 public，已由消费工程编译验证）

`PdshNavHost`、`PdshNavFacts`、`PdshNavState`、`PdshRoute`、`PdshPresentation`、
`PdshHostAction`、`SettingsWrite`、`PdshBackLevel`、`PdshTheme`、`PdshThemeValues`、
`ChatSubpageFacts`、`ShellSnapshot`。全部由 `:lib-consumer-test` 的编译验证过。

### 3.3 `internal`（123 个顶层声明）—— 没有一个是 consumer 必需的

按类别：

| 类别 | 例子 | 为什么 internal 是对的 |
| --- | --- | --- |
| 页面 token 表 | `ChatPageTokens`、`SettingsPageTokens`、`EgressAllowListTokens` …（34 个 `internal object *Tokens`） | 页面内部样式；consumer 若要 token 用 `PdshThemeValues.tokens` / `UiTokenData` |
| 布局助手 | `PageFrame`、`SectionHeader`、`InfoRow`、`EmptyHint`、`pageListGap`、`PageTone.ink` | 页面自用，签名不承诺 |
| 文案/格式化 | `sessionTitle`、`displayTitle`、`middleTruncate`、`relativeTime`、`workspaceNameErrorText`、`egressRuleProblemText` | 纯函数实现细节 |
| 归约器 | `SessionTurns` / `TurnGroup` / `TurnOutcome`、`ChatRows` 的 split/row 助手 | 页面私有状态机 |
| 公共类里的内部属性 | `ExportFacts.hasMeasurement`、`WebSurfaceFacts.hasMeasurement`、`NativeConfigurationPage` 的 `hasContent` | 只服务页面渲染，不是读数 |
| **唯一一个 internal 的页面 composable** | `PDSHShellPage`（`android/lib/src/main/kotlin/com/phonedsh/ui/page/PDSHShellPage.kt:98`） | 只由 `PdshNavHost` 的 `ToolsRoute` 渲染；consumer 走 `PdshRoute.Tools` 即可。只有"想直接嵌单页"的人会碰到它，那不是本库承诺的用法 |

`PdshBackLevel.register` / `unregister` 是 `internal`：路由自己注册，consumer 只用
`available` / `consume()`。这是对的。

**结论：没有"必要但被标成 internal"的类。** 唯一值得讨论的是 `PdshBackHandler` 根本不在
`:lib` 里（在 `:app` 里且 internal），见 §8。

## 4. AAR 的依赖要求

### 4.1 AAR 里有什么

| 条目 | 内容 |
| --- | --- |
| `classes.jar` | 1,576 个 `.class`，全部 `com/phonedsh/ui/**`（nav / page / projection / state / surface / surface.preview / theme / theme.generated） |
| `AndroidManifest.xml` | `minSdkVersion 26`；一个 `androidx.core.content.FileProvider`，authority `${applicationId}.pdsh.preview` |
| `res/xml/pdsh_preview_file_paths.xml` | 上述 provider 的路径表 |
| `proguard.txt` | `consumer-rules.pro` 的合并产物（R8 规则，见 §6.1） |
| `R.txt` | 1 个资源 id |
| `META-INF/.../aar-metadata.properties` | `minCompileSdk=1`（AAR 自身不抬 compileSdk） |

### 4.2 consumer 必须自己提供的（文件依赖没有 POM）

| 项 | 值 | 依据 |
| --- | --- | --- |
| Kotlin | 2.1.0 + `org.jetbrains.kotlin.plugin.compose` | `android/gradle/libs.versions.toml`；Kotlin 2.x 不装 Compose 插件编译不了 composable |
| Compose BOM | `2025.01.00`（**catalog 里仍标 `# unverified pin`**） | 同上 |
| Compose 构件（`:lib` 声明为 `api`） | `ui` 1.7.6、`foundation` 1.7.6、`ui-text` 1.7.6、`animation` 1.7.6、`material3` 1.3.1 | `:lib-consumer-test:dependencies --configuration debugCompileClasspath` 实测 |
| `androidx.core` | `core-ktx` 1.15.0（AAR manifest 里的 FileProvider 运行时需要它） | `android/lib/build.gradle.kts` 的 `implementation` |
| `androidx.activity:activity-compose` | 1.9.3（AAR 不带 Activity） | 同上 |
| minSdk | **≥ 26**（AAR manifest 的 `uses-sdk` 会在合并时拒绝更小的值） | AAR 内 manifest |
| compileSdk | 35（Compose 2025.01 / core 1.15 要求） | 实测 |
| JVM target | 17 | `:lib` 的 `compileOptions` |

### 4.3 运行时闭包（POM 该带来的，今天要手给）

`:lib` 的 `api` 边是 `:dsh` + `:platform` + `:domain`；`:dsh` 的 `api` 边是
`:platform` + `:core` + `:net` + `:controller` + `:shell`。所以一次完整消费需要 8 个
`com.phonedsh:*:0.0.0-SNAPSHOT` 构件：

`lib`(AAR) · `dsh`(AAR) · `domain`(AAR) · `controller`(AAR) · `shell`(AAR) ·
`core`(JAR) · `net`(JAR) · `platform`(JAR)

**要真正跑起宿主**还差 `:platform-android`（`AndroidLifecycleSignals` 等 SPI 的 Android
实现，`DefaultPhoneDshHost` 的构造参数要 `Clock` / `LifecycleSignals` / `Notifier`）与
`libpdsh_jni.so`（JNI 编解码）。这些都不在 AAR 里，也不在 `:lib` 的 `api` 边上。

## 5. 最小接入代码（就是 `:lib-consumer-test` 里能编译的那段）

文件：`android/lib-consumer-test/src/main/kotlin/com/phonedsh/consumer/ProbeConsumer.kt`
（`assembleDebug` 已验证）。下面是它的核心；文件里只多了一个 `HomeFace`（两个 `Text` +
一个 `Button`，`Button` 用来 push `PdshRoute.Sessions`），其余逐行相同：

```kotlin
@Composable
public fun ProbeConsumer() {
    PdshTheme {                                            // 必须：否则第一个路由就崩
        var nav by remember { mutableStateOf(PdshNavState()) }
        val facts: PdshNavFacts = remember { PdshNavFacts() }   // 全"未知"快照
        val backLevel = remember { PdshBackLevel() }
        BackHandler(enabled = backLevel.available || nav.canGoBack) {
            if (!backLevel.consume()) nav = nav.pop()      // 库不提供 handler
        }
        PdshNavHost(
            nav = nav,
            facts = facts,
            onNavigate = { route -> nav = nav.push(route) },
            onBack = { nav = nav.pop() },
            onHome = { nav = nav.popToHome() },
            onHostAction = { },                            // 宿主没实现的手势：不伪造成功
            backLevel = backLevel,
            home = { Text("host home") },
            webSurface = { Text("host owns the WebView") },
        )
    }
}
```

Activity 侧只有一个 `setContent { ProbeConsumer() }`
（`android/lib-consumer-test/src/main/kotlin/com/phonedsh/consumer/ConsumerActivity.kt`）。

## 6. consumer-rules 与 R8

### 6.1 有什么

`android/lib/src/main/consumer-rules.pro`（`consumerProguardFiles(...)` 引入）随 AAR 下发为
`proguard.txt`，内容是：

- `-keepclasseswithmembernames class com.phonedsh.wire.NativeWire { native <methods>; }`
- 同上 `NativeWal`（Rust 侧符号是 `Java_com_phonedsh_wire_<Class>_<method>`，改名即
  `UnsatisfiedLinkError`）
- 两条 Ktor 的 `-dontwarn java.lang.management.*`（Android 无此包）

它**不**用规则钉住 UI 的 public API。这是对的：R8 按"consumer 用到什么留什么"裁剪，
钉住一个没人调的 public 类只会把死代码留在 APK 里。

### 6.2 裸 AAR 的 R8 实测：直接失败（44 个缺失类）

把 release 变体的 `pomClosure` 拿掉（release 也只剩 AAR）、再跑
`./gradlew :lib-consumer-test:assembleRelease`，`minifyReleaseWithR8` 报：

```
ERROR: Missing classes detected while running R8.
Missing class com.phonedsh.agentcore.JsonValue            (…ChatPageKt.answerJson…)
Missing class com.phonedsh.domain.connector.DeviceConnectionSnapshot
Missing class com.phonedsh.domain.device.DeviceManifest
…
```

44 条 = `agentcore` 7 条（canonical JSON：`JsonValue` / `JsonObject` / `JsonBool` …）+
`domain.connector` 30 条（设备连接/会话的值类型与内存实现）+ `domain.device` 7 条
（设备清单/能力）。也就是说：**UI 的 public 面在编译期不需要这些类，但运行期需要**——
聊天页要 agentcore，设备页要 domain。

debug 包也验证了同一件事：dex 里定义了 2,807 个 `com.phonedsh.ui.*` 类、0 个
`com.phonedsh.domain.*` 类。

### 6.3 补上闭包后的 R8 实测：通过

release 变体加了 `:core`(jar) + `:platform`(jar) + `:net`(jar) + `:domain`(aar) 后 R8 通过。
报告（`android/lib-consumer-test/build/outputs/mapping/release/`）：

- `mapping.txt`：`PdshNavHostKt` / `PdshNavFacts` / `PdshNavState` **都在**，被混淆成
  `t1.o0` / `t1.v` / `t1.p0` —— 用到的 public 面没被裁掉。
- `usage.txt`：`PdshNavFacts.copy(...)`、`PdshNavState.contains/replaceTop` 被删 ——
  probe 没调用，属于正确裁剪，不是 API 丢失。
- `configuration.txt:134-138`：AAR 的 `proguard.txt` 被 AGP 读进了 app 的 R8 配置。
- probe 自己的 `proguard-rules.pro` 是空的（只留注释）：**没有一条规则是为了保住 SDK
  的类而加的**，否则那规则就该住在 `:lib`。

注意 `-keepclasseswithmembernames` 的语义是"没被裁掉才保名字"（带 `allowshrinking`）：
这个只画 UI、从不碰引擎的 probe 里 `NativeWire` 不可达，于是整类被裁 —— 正常。真正用
引擎的 consumer 里 `WireCodec` 可达 → `NativeWire` 可达 → 规则生效，类名与 native 方法名
都被保住。

### 6.4 够不够

- **对 JNI**：够（AGP 确实把它并进了 app 的 R8 配置，规则覆盖 class 名 + native 方法名）。
- **对 UI public API**：不需要规则；实测用到的类/方法全部保留。
- **对"裸 AAR"这种交付形态**：**不够，而且不是规则的错**。规则保的 `com.phonedsh.wire.*`
  根本不在 AAR 里；R8 在缺失 44 个类时连跑都跑不完。缺的是 POM/仓库，不是 proguard。

## 7. CI 里 AAR 怎么进

`scripts/ci/check.sh` 的 `phone-android-release` stage，**放在所有 skip 之前**：

```bash
android_require_jdk
./gradlew :lib:assembleRelease :lib-consumer-test:assembleDebug :lib-consumer-test:assembleRelease
```

然后断言（任一失败 = stage FAIL，不是 SKIP）：

1. AAR 存在且非空；
2. `classes.jar` 里有 5 个 consumer 入口：`PdshNavHostKt` / `PdshNavFacts` / `PdshNavState` /
   `PdshRoute` / `UiThemeKt`；
3. AAR 里有 `AndroidManifest.xml` 与 `proguard.txt`，且 `proguard.txt` 仍带
   `-keepclasseswithmembernames class com.phonedsh.wire.NativeWire`；
4. 消费工程产出了 debug APK（证明"真的编译了"，不是"恰好有个旧 AAR"）。

**为什么放这**：这个 stage 原本先查 `.so`、再查 worker bundle，两者都是 gitignored，
所以干净主机上它会 **SKIP**，一个产物都不构建。把 AAR 放在它们后面，等于让交付物继承
APK 的 skip 条件——AAR 可以一直构建不出来而门禁只报 SKIP。放在最前面，"AAR 坏 / AAR
不可消费"就变成 FAIL。实测：`CI_ANDROID_SO=/tmp/definitely-not-here/libpdsh_jni.so` 时
AAR 段照样跑并打印
`AAR: 4079341 bytes, 1576 classes, 5/5 consumer entry points, consumer rules packaged`，
之后才 SKIP（exit 2）。

红路径也验过（把断言脚本单独跑三种坏 AAR）：缺 entry point、缺 keep 规则、缺
`classes.jar` 分别给出各自的错误并以 1 退出。

## 8. 缺口与 open questions

1. **没有 POM / 没有 maven-publish。** 文档说"组合根只声明 `com.phonedsh:lib` 这一条，
   兄弟模块作为 POM 传递依赖"，但 `lib/build.gradle.kts` 里的 `maven-publish` 仍是注释。
   今天 consumer 拿到的 AAR 没有传递依赖，8 个构件得手给。这是本车道最大的缺口：
   §6.2 的 44 个缺失类不是 AAR 的 bug，是"没有发布坐标"的直接后果。
2. **`:platform-android` 不在 `:lib` 的 `api` 边上。** 想真正跑宿主的人要自己加，否则
   `DefaultPhoneDshHost` 的 `Clock` / `LifecycleSignals` / `Notifier` 没有实现。要么把它
   加进 `:lib` 的 `api`（会污染纯 UI 消费者），要么在文档里点名（本文件这么做了）。
3. **`PdshBackHandler` 没发布。** 库给了 `PdshBackLevel` 却不给消费它的 handler，
   `:app` 那份含预测性返回测量的实现是 `internal`。每个 consumer 都得重写一遍，且很容易
   写成"根路由也注册回调"（会抢掉系统 finish/预测动画）。建议把它提升为 `:lib` 的 public
   API（`android/lib/src/main/...`，本车道不允许改 `src/`，只报告）。
4. **`PDSHShellPage` 是唯一 internal 的页面 composable。** 走 `PdshRoute.Tools` 没问题；
   只有"直接嵌单页"的用法拿不到它。如果要单页 API，需要先决定它是不是 public 面。
5. **`PdshNavFacts` 没有 `unknown()` 工厂。** 默认构造器已经够用，但任务书/外部文档很容易
   写成 `PdshNavFacts.unknown()`。要么加一个 `public fun unknown()` 别名，要么在文档里
   明确（本文件这么做了）。
6. **AAR 门禁在手动 stage 上。** `phone-android-release` 在 workflow 里只有
   `workflow_dispatch` 才跑，所以每个 push 仍然不构建 AAR。要让它成为每 push 的门禁，需要
   在 `android-jvm` job 里加一条（那是 workflow 文件，不在本车道可写范围）。
7. **版本 pin 仍是 `# unverified pin`。** 本文件里的 1.7.6 / 1.3.1 / 1.15.0 是从
   解析结果读的，不是从"已验证"的 pin 读的；catalog 自己的注释还没摘掉。
8. **AAR 的 targetSdk 35 对 AAR 无约束力**（AGP 用 consumer 的 targetSdk）；这里保留只是
   为了 WP-12 的声明一致。
