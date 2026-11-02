// The host shell: drawer, page stack, back gesture and the Web surface container.
// SPDX-License-Identifier: MIT
//
// Spec: `spec/ui/v1/README.md` §1 (the 入口 column: 抽屉「工作文件夹」、
// 抽屉「定时任务」、页头 `⋯`、设置首行/设置内、页头运行状态点、引擎就绪时的默认面),
// §2 (`back-gesture` in §5: the stack pops one level per gesture) and §3.1 (a page
// is a pure function of facts and local state; nothing here reads the engine for
// a page).
//
// This file owns the three things a page library cannot own:
//
//   * the page stack ([PdshNavState]) and the system back gesture;
//   * the drawer and the page-header entries §1 names;
//   * the engine's one WebView as the Web surface's `surface` slot — the engine
//     owns the instance, this app only attaches it and never creates a second.
//
// Facts come from [ProbeEngineViewModel.navFacts]; every field the host could
// not read stays unknown inside that snapshot, so no page here shows a value the
// host did not measure. Write gestures reach [handleHostAction], which logs the
// handoff and performs only the actions this host really owns (engine stop and
// engine start).
//
// The one deliberate deviation from §1 is recorded at [HostShell]: the Web
// surface is the engine-ready default face only when the launch asks for it
// (`pdsh.face.web`), because the demo acceptance taps 跑一轮 on the native face
// after the worker is ready.
package com.phonedsh.probe

import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.phonedsh.engine.ChannelStatus
import com.phonedsh.engine.EngineState
import com.phonedsh.engine.HandshakeSummary
import com.phonedsh.engine.KernelVerdict
import com.phonedsh.platform.EngineHostState
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.nav.PdshBackLevel
import com.phonedsh.ui.nav.PdshDrawer
import com.phonedsh.ui.nav.PdshDrawerEntry
import com.phonedsh.ui.nav.PdshDrawerIcon
import com.phonedsh.ui.nav.PdshHostAction
import com.phonedsh.ui.nav.SettingsWrite
import com.phonedsh.ui.nav.PdshNavFacts
import com.phonedsh.ui.nav.PdshNavHost
import com.phonedsh.ui.nav.PdshNavState
import com.phonedsh.ui.nav.PdshRoute
import com.phonedsh.ui.page.PhoneCapability
import com.phonedsh.ui.page.ProviderDraft
import com.phonedsh.ui.page.WorkbenchSection
import com.phonedsh.ui.theme.PdshTheme
import com.phonedsh.ui.theme.PdshThemeValues

/**
 * The app's one screen: the navigation shell around every routed page.
 *
 * @param engine the composition root, read for facts and the WebView instance.
 * @param state the engine lifecycle state (also the runtime page's engine fact).
 * @param handshake the merged HELLO; its presence is what makes the Web surface
 *   live, because a HELLO can only come from the loaded page.
 * @param channel the channel counters (the native face renders them).
 * @param turn the one turn's state (the workbench's tool output comes from it).
 * @param recoveryNotice the fail-loud report from a previous process death, a torn
 *   WAL tail or an unreadable reclaim token; `null` when nothing is wrong.
 * @param backgroundRevision a counter the Activity bumps when the notification
 *   permission answer changes what the background facts may say. It is a fact
 *   key, not a value: the facts are still measured, this only says "measure
 *   again", because the grant happens after the first snapshot was taken.
 * @param permissionRevision the same kind of key for the phone-permissions matrix:
 *   bumped when a permission answer arrives, when the Activity resumes (a grant can
 *   change in the settings page this app sends the user to), and when a request is
 *   launched so the row can show it is in flight.
 * @param onStart start the engine.
 * @param onStop stop the engine.
 * @param onRunTurn run one turn through the App's own `/api` lane.
 * @param onPhonePermissionRequest 继续 on a capability row: raise the real system
 *   request (or, for a capability with no requestable permission, open the page that
 *   holds it).
 * @param onPhonePermissionSettings 去系统设置 on a capability row: open the system page
 *   that can change that capability's grant.
 */
@Composable
internal fun HostShell(
    engine: ProbeEngineViewModel,
    state: EngineState,
    hostState: com.phonedsh.controller.HostState,
    handshake: HandshakeSummary?,
    channel: ChannelStatus,
    turn: AppTurnState,
    recoveryNotice: RecoveryNotice?,
    backgroundRevision: Int,
    permissionRevision: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRunTurn: () -> Unit,
    onPhonePermissionRequest: (PhoneCapability) -> Unit = {},
    onPhonePermissionSettings: (PhoneCapability) -> Unit = {},
) {
    // The facts are one host walk per lifecycle/HELLO/turn change: a page never
    // asks the host for anything itself. `backgroundRevision` is the one key that
    // is not engine state: a permission answer lands after the first walk and must
    // not leave the runtime page showing the pre-answer verdict.
    // `settingsRevision` is the same kind of key for a write this host performed:
    // the device store changed, so the walk must run again — the facts themselves
    // still come only from reads.
    var settingsRevision by remember { mutableIntStateOf(0) }
    val facts: PdshNavFacts = remember(
        state,
        handshake,
        turn,
        backgroundRevision,
        permissionRevision,
        settingsRevision,
    ) {
        engine.navFacts(state, handshake, turn)
    }
    // The one fact in that snapshot that is not an engine reading: whether the
    // platform really carries this app's back gesture through the invoker. It is
    // measured by the back callback (one gesture moves it), so it is applied here
    // rather than inside the remembered walk.
    val shownFacts = PredictiveBack.measured(facts)
    var nav by remember { mutableStateOf(PdshNavState()) }
    var drawerOpen by remember { mutableStateOf(false) }
    var webFaceOpened by remember { mutableStateOf(false) }
    val webFaceOnLaunch = remember { engine.webFaceOnLaunch() }

    // The host's own voice. A page owns its controls, so this host cannot render
    // a button disabled; what it can do is state the truth on every route that
    // has no write command, and state the outcome of every gesture it cannot
    // complete. A dead button that says nothing reads as a missed tap.
    var notice by remember { mutableStateOf<String?>(null) }
    val routeNote: String? = hostRouteNote(nav.current)
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MILLIS)
            notice = null
        }
    }

    PdshTheme {
        val tokens = PdshThemeValues.tokens
        // §1 says the Web surface is 引擎就绪时的默认面. This host makes that
        // opt-in (`pdsh.face.web`) instead of unconditional: the demo acceptance
        // waits for the worker and then taps 跑一轮 on the native face, so an
        // automatic switch would take the button off screen and break the demo.
        LaunchedEffect(state, webFaceOnLaunch) {
            if (webFaceOnLaunch && !webFaceOpened && state is EngineHostState.Running) {
                webFaceOpened = true
                nav = nav.push(PdshRoute.WebSurface)
            }
        }
        // `spec/ui/v1/README.md` §5 back-gesture: one gesture pops one level, and the
        // root page exits the app. The chain is three deep because two of its levels
        // are not routes: the drawer, the top route's own inner level (a sheet's
        // child page, a folder drill-down, an embedded preview — registered by the
        // route through `PdshBackLevel`), then the route stack. When none of the
        // three can consume the gesture no callback is registered, which is what
        // leaves "exit the app" to the platform's own default behaviour.
        val backLevel = remember { PdshBackLevel() }
        PdshBackHandler(enabled = drawerOpen || backLevel.available || nav.canGoBack) {
            when {
                drawerOpen -> drawerOpen = false
                backLevel.consume() -> Unit
                else -> nav = nav.pop()
            }
        }
        val note = notice ?: routeNote
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(Modifier.fillMaxSize().background(tokens.colors.selector)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Only a route with a note consumes the status bar here: the
                    // strip painted below then owns that area, and the page's own
                    // statusBarsPadding sees zero instead of pushing the page
                    // header down a second time.
                    .then(
                        if (note != null) {
                            Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (note != null) HostNoteBar(text = note)
                Box(Modifier.weight(1f)) {
                    PdshNavHost(
                        nav = nav,
                        facts = shownFacts,
                        onNavigate = { route ->
                            drawerOpen = false
                            nav = nav.push(route)
                        },
                        onBack = { nav = nav.pop() },
                        onHome = { nav = nav.popToHome() },
                        onHostAction = { action ->
                            val feedback = handleHostAction(
                                action = action,
                                onStart = onStart,
                                onStop = onStop,
                                onSettingsChanged = { settingsRevision++ },
                            )
                            if (feedback != null) notice = feedback
                        },
                        // The mirrored settings index has three write gestures and
                        // this host has no settings write: say so instead of leaving
                        // a switch that moves and changes nothing.
                        onSettingsWrite = { write ->
                            notice = "宿主未实现：${settingsWriteLabel(write)}，本次未保存"
                        },
                        // The two hosted gestures of the phone-permissions page: a real
                        // system request, and the settings page that can change the
                        // grant. Both are the Activity's, because a permission prompt
                        // is an Activity-scoped contract.
                        onPhonePermissionRequest = onPhonePermissionRequest,
                        onPhonePermissionSettings = onPhonePermissionSettings,
                        home = {
                            ProbeScreen(
                                state = state,
                                hostState = hostState,
                                handshake = handshake,
                                channel = channel,
                                turn = turn,
                                notice = recoveryNotice,
                                onStart = onStart,
                                onStop = onStop,
                                onRunTurn = onRunTurn,
                                onOpenDrawer = { drawerOpen = true },
                                onOpenConfiguration = { nav = nav.push(PdshRoute.Configuration) },
                                onOpenRuntime = { nav = nav.push(PdshRoute.Runtime) },
                                onOpenWorkbench = { section, path ->
                                    nav = nav.push(
                                        PdshRoute.Workbench(previewPath = path, section = section),
                                    )
                                },
                            )
                        },
                        webSurface = { EngineWebSurface(engine, state) },
                        // 会话 is a route now, and this host is the one that owns
                        // the session read, so the route renders the real list
                        // instead of `:lib`'s facts-only empty page.
                        sessions = {
                            HostSessionsScreen(
                                engine = engine,
                                state = state,
                                turn = turn,
                                onBack = { nav = nav.pop() },
                                onHostNotice = { notice = it },
                            )
                        },
                        // The chain's middle level: what the top route can still go
                        // back to inside itself.
                        backLevel = backLevel,
                    )
                }
            }
            if (note != null && statusBarTop > 0.dp) {
                // The consumed status-bar strip: the same ink as the note bar, so
                // the note reads as one band from the system bar down. Drawn after
                // the routed page so it paints over the page, not under it.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(statusBarTop)
                        .background(tokens.colors.code),
                )
            }
            if (drawerOpen) {
                PdshDrawer(
                    entries = DRAWER_ENTRIES,
                    footerEntries = DRAWER_FOOTER_ENTRIES,
                    onSelect = { route ->
                        // A line from a routed page is not about the next one.
                        notice = null
                        drawerOpen = false
                        nav = nav.push(route)
                    },
                    onDismiss = { drawerOpen = false },
                )
            }
        }
    }
}

/**
 * Hands one page gesture to the composition root and returns the line to show.
 *
 * The engine commands (stop, start, retry) and the model-service save are real
 * host commands and are performed here; their line says what happened. Every
 * other gesture is a handoff this build cannot complete, and the returned line
 * says exactly that instead of leaving a dead control silent.
 *
 * @param action the page's gesture.
 * @param onStart start the engine.
 * @param onStop stop the engine.
 * @param onSettingsChanged bumped after a write really landed, so the next facts
 *   walk re-reads the device store. It never carries a value.
 * @return the note-bar line for the gesture, or `null` when nothing should be
 *   shown: the page already says it itself (its inline editor opened, or the
 *   route note covers a read-only page).
 */
private fun handleHostAction(
    action: PdshHostAction,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettingsChanged: () -> Unit = {},
): String? {
    // The label is token-free: an action never carries user text into the log.
    Log.i(TAG_NAV, "host action handed off: ${action.wire}")
    return when (action) {
        PdshHostAction.StopRuntime -> {
            onStop()
            "已请求：停止本地引擎"
        }

        PdshHostAction.RestartRuntime -> {
            onStart()
            "已请求：启动本地引擎"
        }

        PdshHostAction.RetryWebSurface -> {
            onStart()
            "已请求：重试本地引擎"
        }

        // The page's editor is its own inline form (`ModelProviderSettingsPage`
        // opens it before calling this), so the handoff is only the notification
        // and there is nothing for the host to open. No line: a notice saying
        // "opened" would duplicate the editor the person can already see.
        is PdshHostAction.OpenProviderEditor -> null

        PdshHostAction.RefreshModelProvider -> {
            onSettingsChanged()
            "已重新读取：模型服务"
        }

        is PdshHostAction.SaveProvider -> saveProvider(action.draft, onSettingsChanged)

        is PdshHostAction.DeleteProvider -> deleteProvider(onSettingsChanged)

        // A connectivity test would have to reach the endpoint from this device,
        // through the same gateway the turn uses; this build has no such probe, so
        // the gesture gets the fact instead of a fabricated 通过.
        is PdshHostAction.TestProvider -> "本宿主没有往模型端点发测试请求的命令，本次未测试"

        is PdshHostAction.RunShellCommand ->
            "只读：本宿主列出了本机可执行的加速器，但没有调用 exec 桥的命令，本次未运行"

        // §1 assigns the MCP save to the host, so the gesture is a handoff. The
        // registry lives in the Worker (`/dsh/home/mcp.json`) and is edited through
        // the `phone/mcpUpsert` host tool, which this build does not register: a
        // second registry in the App would be a document nothing reads.
        is PdshHostAction.SaveMcpServer ->
            "本次未保存：本宿主没有改写 MCP 注册表的工具（phone/mcpUpsert）"

        else -> "宿主未实现：${actionLabel(action)}，本次未提交"
    }
}

/**
 * Stores one model-service draft and, when it carries one, its key.
 *
 * The order is deliberate: every rejection is decided before anything is
 * written, so a refused save cannot leave half a record behind. The non-secret
 * record goes to [ProviderConfigStore]; the key goes to [ApiKeyStore], which is
 * the only component that ever holds it.
 *
 * @param draft the page's edited form (may carry the typed key).
 * @param onSettingsChanged bumped only after the record is really on disk.
 * @return the note-bar line; it never quotes the key or the URL.
 */
private fun saveProvider(draft: ProviderDraft, onSettingsChanged: () -> Unit): String {
    val store = ProviderConfigStore.active()
        ?: return "本宿主没有安装模型服务存储，本次未保存"
    val keyStore = ApiKeyStore.active()
    if (draft.apiKey.isNotBlank()) {
        ProviderDraftRules.keyRejection(draft.apiKey)?.let { return it }
    }
    if ((draft.apiKey.isNotBlank() || draft.removeStoredKey) && keyStore == null) {
        return "本宿主没有安装密钥存储，本次未保存"
    }
    val launch = ProbeLaunchConfig.active()
    // Captured before the write so the line can say whether this replaced the row:
    // the store holds exactly one service (one key account, one Worker env slot), so
    // 添加 behaves as 替换. Saying so is the difference between a host limit and a
    // row that silently swallowed the previous configuration.
    val previous = store.current()
    val record = ProviderConfigStore.recordOf(
        draft = draft,
        previous = previous,
        launchEndpoint = launch.deepseekBaseUrl,
        launchModel = launch.modelName,
    )
    ProviderDraftRules.endpointRejection(record.endpoint)?.let { return it }
    if (record.name.isEmpty()) return "模型服务名不能为空，本次未保存"
    when (val outcome = store.save(record)) {
        ProviderWriteOutcome.Saved -> Unit
        is ProviderWriteOutcome.Rejected -> return outcome.reason
        else -> return "本机存储不可写，本次未保存"
    }
    // The record is on disk; now the key. A key failure is reported as itself —
    // the record stays saved, and the page's key state says 未配置 afterwards.
    val keyLine: String = when {
        draft.removeStoredKey -> {
            keyStore?.clear()
            "，密钥已删除"
        }
        draft.apiKey.isNotBlank() -> {
            try {
                keyStore?.save(draft.apiKey)
                "，密钥已保存"
            } catch (rejected: IllegalArgumentException) {
                return "配置已保存；密钥未通过校验，本次未保存密钥"
            } catch (failed: java.io.IOException) {
                return "配置已保存；密钥写入失败，本次未保存密钥"
            }
        }
        else -> ""
    }
    onSettingsChanged()
    // Precise on purpose: `ProbeEngineViewModel` builds the Worker env and the model
    // edge once, when the App process starts, so the stored endpoint/model apply at
    // the next *App* start — an in-session 停止/启动 re-runs the host but keeps the
    // env it was constructed with. The key is read live by ApiKeyStore, so it is
    // already in effect for later requests; that half is only claimed when one was
    // actually written.
    val effect = if (keyLine.isEmpty()) {
        "下次启动 App 时生效"
    } else {
        "下次启动 App 时生效；密钥即刻生效"
    }
    // A saved *new* service with no previous record is the ordinary first save. One
    // with a previous record replaced it: this host keeps a single model service.
    val replaceLine = if (draft.isNew && previous != null) {
        "；本宿主只保存一个模型服务，本次替换了原有的"
    } else {
        ""
    }
    return "已保存到本机：模型服务$keyLine（$effect）$replaceLine"
}

/**
 * Removes what this host really owns: the saved record and its key.
 *
 * When no record is stored the row *is* the launch's service, which this App
 * cannot delete; only its key can be removed, and the line says which of the two
 * happened.
 *
 * @param onSettingsChanged bumped only after the record really changed.
 */
private fun deleteProvider(onSettingsChanged: () -> Unit): String {
    val store = ProviderConfigStore.active()
        ?: return "本宿主没有安装模型服务存储，本次未删除"
    val hasRecord = store.current() != null
    if (hasRecord) {
        when (store.delete()) {
            ProviderWriteOutcome.Deleted -> Unit
            else -> return "本机存储不可写，本次未删除"
        }
    }
    val keyStore = ApiKeyStore.active()
    keyStore?.clear()
    onSettingsChanged()
    return when {
        !hasRecord && keyStore == null -> "本宿主没有安装密钥存储：没有可删除的内容"
        hasRecord && keyStore == null ->
            "已删除本机保存的模型服务；密钥存储未安装，密钥未删除"
        hasRecord -> "已删除本机保存的模型服务与密钥；引擎退回启动配置的那个服务"
        else -> "已删除密钥；模型服务本身来自启动配置，本宿主不能删除"
    }
}

/** The person-facing name of one handed-off action; every action has one. */
private fun actionLabel(action: PdshHostAction): String = when (action) {
    is PdshHostAction.WorkspaceCreate -> "新建文件夹"
    is PdshHostAction.WorkspaceSwitch -> "切换文件夹"
    is PdshHostAction.WorkspaceDelete -> "删除文件夹"
    is PdshHostAction.OpenWorkspaceFiles -> "打开文件夹的文件列表"
    PdshHostAction.RefreshWorkspace -> "重新读取文件夹"
    PdshHostAction.RefreshAutomation -> "重新读取定时任务"
    PdshHostAction.LoadMoreAutomationHistory -> "加载更多定时任务历史"
    is PdshHostAction.OpenAutomationEditor -> "定时任务编辑器"
    PdshHostAction.RefreshConfiguration -> "重新读取会话配置"
    is PdshHostAction.OpenProviderEditor -> "模型服务编辑器"
    PdshHostAction.RefreshModelProvider -> "重新读取模型服务"
    is PdshHostAction.SaveProvider -> "保存模型服务"
    is PdshHostAction.DeleteProvider -> "删除模型服务"
    is PdshHostAction.TestProvider -> "测试模型服务"
    is PdshHostAction.SetWebSearchEnabled -> "网页搜索开关"
    is PdshHostAction.SelectWebSearchBackend -> "网页搜索后端"
    PdshHostAction.RefreshWebSearch -> "重新读取网页搜索"
    PdshHostAction.RefreshMcp -> "重新读取 MCP 服务器"
    is PdshHostAction.SaveMcpServer -> "保存 MCP 服务器"
    PdshHostAction.StopRuntime -> "停止本地引擎"
    PdshHostAction.RestartRuntime -> "启动本地引擎"
    PdshHostAction.RefreshRuntime -> "重新读取运行时"
    is PdshHostAction.RunShellCommand -> "运行命令"
    PdshHostAction.RefreshEgress -> "重新读取出网白名单"
    is PdshHostAction.OpenWorkbenchFile -> "读取文件预览"
    PdshHostAction.RetryWebSurface -> "重试本地引擎"
    // L24: the chat sheets' one read (both `ChatPage.swift:441`/`:449` call
    // `refreshActivity`, which fills jobs and schedules together).
    PdshHostAction.RefreshSessionActivity -> "重新读取本会话的定时任务与后台任务"
}

/**
 * The route-wide read-only statement, or `null` on a route whose actions are real.
 *
 * These are the routes §1 gives host-owned writes that this host has no command
 * for. The note is the host's disclosure; a page that owns a field for the same
 * sentence (网页搜索's `readOnlyReason`) prints it itself, and this function then
 * returns `null` so the reason is not doubled.
 */
private fun hostRouteNote(route: PdshRoute): String? = when (route) {
    // Files are read: the row's file list opens the materialized workspace (the
    // `is PdshRoute.Files` note below is null because the page reports its own
    // read). Only the folder writes are missing, so the note names just those.
    PdshRoute.WorkspaceFolders ->
        "只读：本宿主未实现文件夹的新建、切换、删除"

    // The model service is writable now: the device record holds the row and the
    // Keystore holds the key, and the page's own 生效方式 line says when a save
    // applies. A route note would contradict it.
    PdshRoute.ModelProvider -> null

    // 网页搜索 prints the host's reason itself, under the controls it disabled
    // (`WebSearchFacts.readOnlyReason`): one statement, not a bar plus a footnote.
    PdshRoute.WebSearch -> null

    // The registry is the Worker's document; this App has no `phone/mcpUpsert`
    // host tool to change it, and writing a second registry here would be a
    // document nothing reads. The note names the missing tool, not just 只读.
    PdshRoute.Mcp ->
        "只读：MCP 注册表在 Worker 的 /dsh/home/mcp.json，改写要走宿主的 phone/mcpUpsert 工具；" +
            "本宿主只注册了 phone info 与 phone ubuntu"

    is PdshRoute.McpEditor ->
        "保存不会落盘：本宿主没有 phone/mcpUpsert 这个 host tool"

    PdshRoute.Configuration ->
        "只读：本宿主未实现会话配置的读取与写入"

    PdshRoute.Automation ->
        "只读：本宿主未实现定时任务存储"

    // The rules are real: they are the App's own /v1/http policy. The note is
    // what keeps them from being mistaken for the DSH settings allow-list.
    PdshRoute.Egress ->
        "本机 App 的出网规则（不是 DSH 设置里的白名单）"

    is PdshRoute.Workbench ->
        "本宿主未实现文件内容读取；预览保持待读"

    // 命令 has a real snapshot now (the HELLO's accelerators): when there is one
    // the page shows the run form, and when there is none its own 还没接上命令 body
    // is the honest state. A route-wide line would be a second, weaker version.
    PdshRoute.Tools -> null

    PdshRoute.Export ->
        "本宿主没有导出诊断的命令，页面只显示它读到的状态"

    // §1's files page is read-only here: the page itself reports what it could and
    // could not read, so a route note would only repeat its own line.
    is PdshRoute.Files -> null

    // 文件预览 (L29) reads the materialized workspace through the same seam the
    // files page does, and prints its own four honest states (正在读取… / 无法预览 +
    // 原因 + 重试 / 暂时不能预览 + 原因 + 分享 / the body). A host-wide line would be a
    // second, weaker version of the page's own disclosure.
    is PdshRoute.Preview -> null

    // 对话 is the one session surface whose data this host still cannot read
    // (`PdshNavFacts` carries no transcript), so the page renders its own empty
    // session and the note says why instead of looking broken.
    PdshRoute.Chat ->
        "只读：本宿主未接入会话记录的读取"

    // 会话 has a real host read (`HostSessionsScreen`), handed to the route through
    // `PdshNavHost(sessions = …)`: no disclosure is needed, and a read-only line
    // here would now be false.
    PdshRoute.Sessions -> null

    // The three device routes read the `:domain` connector's in-memory double: a
    // page full of data that is not a real pairing must say so, or the demo reads
    // as a working transport.
    PdshRoute.DeviceConnection ->
        "演示数据：客厅网关来自内存连接器，本宿主没有真实设备通道"

    is PdshRoute.DeviceSession ->
        "演示数据：记录来自内存传输，不是真实设备会话"

    is PdshRoute.SessionArtifacts ->
        "演示数据：产物来自内存存储，不是真实设备文件"

    // The settings family's reference sheets render their own state: the route
    // hands each page the host's facts (聊天外观, 手机权限 — whose rows are the
    // host's own permission read, so an unread row prints no status line while the
    // audit card says 宿主没有报告使用审计) or an empty snapshot that the page prints
    // as 未知/读取不到 (配置文件, 快捷键), and the plugin page is a local matrix. A
    // host-wide line here would be a second, weaker version of the page's own
    // disclosure. None of them is a drawer row; they are opened from the settings
    // index.
    PdshRoute.AgentPresets,
    PdshRoute.PluginInventory,
    PdshRoute.PhonePermissions,
    PdshRoute.ChatPreferences,
    PdshRoute.SettingsDocument,
    PdshRoute.Shortcuts,
    -> null

    // L24: the chat family's three 内嵌子页 each print their own 读取不到 line when
    // this host made no read (`ChatSubpageFacts` stays at its "not reported"
    // default), so a route-wide note would be a second, weaker version of the
    // page's own disclosure — the same reason the settings sheets above have none.
    PdshRoute.CommandResult,
    PdshRoute.SessionJobs,
    PdshRoute.SessionSchedules,
    -> null

    PdshRoute.Home, PdshRoute.Settings, PdshRoute.Runtime, PdshRoute.WebSurface -> null
}

/** The person-facing name of one settings write the host cannot persist. */
private fun settingsWriteLabel(write: SettingsWrite): String = when (write) {
    is SettingsWrite.BackgroundKeepAlive -> "后台运行开关"
    is SettingsWrite.BusyEnter -> "发送消息时的排队方式"
    is SettingsWrite.DefaultPermissionTier -> "新会话默认权限"
}

/** One host-owned line above the page: a disclosure, then a gesture's outcome. */
@Composable
private fun HostNoteBar(text: String) {
    val tokens = PdshThemeValues.tokens
    val type = PdshThemeValues.type
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.colors.code)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.geometry.bannerInsetV,
            ),
    ) {
        BasicText(
            text = text,
            style = type.caption.copy(color = tokens.colors.secondary),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The engine's one WebView, attached as the Web surface — or the honest notice
 * that replaces it.
 *
 * The view is owned by the engine; [key] on the instance is what makes a
 * renderer-death rebuild attach the fresh view instead of the destroyed one.
 *
 * A failed engine, or a kernel whose runtime probe says the worker cannot run
 * there, must not be rendered as the WebView: an attached blank WebView is
 * indistinguishable from a hang, and the plan's §6.2/§6.3 rule is a fail-loud
 * report instead. The notice names the reason the host actually measured (the
 * engine's failure reason, or the missing feature ids) — it is not a generic
 * "something went wrong".
 */
@Composable
private fun EngineWebSurface(engine: ProbeEngineViewModel, state: EngineState) {
    val kernel = engine.kernelReport()
    val failure = (state as? EngineHostState.Failed)?.reason
    val unsupportedKernel = kernel?.verdict == KernelVerdict.UNSUPPORTED
    if (failure != null || unsupportedKernel) {
        EngineUnavailableNotice(
            title = if (unsupportedKernel) {
                UiStrings.RUNTIME_WEBVIEW_GATE_MISSING
            } else {
                UiStrings.RUNTIME_SECTION_ENGINE + "不可用"
            },
            reason = failure,
            detail = kernel?.featureGateDetail(),
        )
        return
    }
    val view: View? = engine.currentWebView()
    if (view == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    key(view) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The page the host renders when the engine cannot be the page.
 *
 * Same tokens and type as every other host-owned line, so it looks like part of
 * the app rather than a debug dump; the technical detail stays on its own line
 * and is allowed to be English because it is evidence (feature ids, provider).
 */
@Composable
private fun EngineUnavailableNotice(title: String, reason: String?, detail: String?) {
    val tokens = PdshThemeValues.tokens
    val type = PdshThemeValues.type
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(tokens.geometry.pageOuterMarginH),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(tokens.geometry.bannerGap)) {
            BasicText(text = title, style = type.body.copy(color = tokens.colors.primary))
            if (reason != null) {
                BasicText(text = reason, style = type.caption.copy(color = tokens.colors.secondary))
            }
            if (detail != null) {
                BasicText(text = detail, style = type.caption.copy(color = tokens.colors.tertiary))
            }
        }
    }
}

/**
 * The drawer's app map, in the order a user scans it: conversation, sessions,
 * folders, files, commands, devices, the Web face — then the two furniture rows the
 * iOS drawer pins to its bottom (定时任务, 设置).
 *
 * The iOS baseline reaches 对话 as the shell's root face and 会话 as the drawer
 * body; this host has neither (its root face is the engine probe, and its drawer
 * body is this list), so each destination needs a row of its own. Without them
 * the pages are routing-reachable but not touch-reachable — the L1/L2/L5 report.
 */
private val DRAWER_ENTRIES: List<PdshDrawerEntry> = listOf(
    // No UiStrings constant for the baseline's tab title 对话.
    PdshDrawerEntry("对话", PdshDrawerIcon.CHAT, PdshRoute.Chat),
    // UiStrings.SESSIONS is 会话列表 (the page name); the drawer's word is 会话.
    PdshDrawerEntry("会话", PdshDrawerIcon.SESSIONS, PdshRoute.Sessions),
    PdshDrawerEntry(UiStrings.WORKSPACE_FOLDERS, PdshDrawerIcon.FOLDER, PdshRoute.WorkspaceFolders),
    // 文件: the files page (its own title is 手机工作区). §1's entry for that page is
    // a folder row or the chat's workbench, and this host has the folder row; but
    // the folder page's row is drawn disabled while that page is read-only (a page
    // decision this shell cannot override), so the listing keeps one entry that
    // depends on no other page's writable flag. It opens the same single workspace
    // the folder row names; the route's `folderId` stays null because a drawer row
    // has no folder to name, and the page reads the host's one materialized tree.
    // No UiStrings constant names this entry: WORKBENCH_FILES is the workbench
    // segment's label, a different surface whose wording can move independently.
    PdshDrawerEntry("文件", PdshDrawerIcon.FOLDER, PdshRoute.Files()),
    PdshDrawerEntry(UiStrings.TOOLS, PdshDrawerIcon.TERMINAL, PdshRoute.Tools),
    // The device family's page title is 设备连接; UiStrings has no constant for
    // the drawer's short name (its DEVICE_TITLE is the page's "Device" string).
    PdshDrawerEntry(DEVICE_CONNECTION_LABEL, PdshDrawerIcon.DEVICE, PdshRoute.DeviceConnection),
    PdshDrawerEntry(UiStrings.WEB_SURFACE_TITLE, PdshDrawerIcon.WEB, PdshRoute.WebSurface),
)

/** The two rows the iOS drawer pins below its session list (`NativeShell.swift:296`). */
private val DRAWER_FOOTER_ENTRIES: List<PdshDrawerEntry> = listOf(
    PdshDrawerEntry(UiStrings.AUTOMATION_TITLE, PdshDrawerIcon.CLOCK, PdshRoute.Automation),
    PdshDrawerEntry(UiStrings.SETTINGS, PdshDrawerIcon.SETTINGS, PdshRoute.Settings),
)

/** The host's navigation log tag; only stable action labels are written to it. */
private const val TAG_NAV = "PDSHNav"

/** The drawer's short name for the device family; the page title is 设备连接. */
private const val DEVICE_CONNECTION_LABEL: String = "设备连接"

/** How long a gesture's outcome line stays on screen (four seconds). */
private const val NOTICE_MILLIS: Long = 4_000L
