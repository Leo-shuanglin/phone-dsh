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
//
// L62 moved the launch face off the probe. [PdshRoute.Home] now renders the
// product home (`HostHomeScreen`: the session list, the drawer entry and the
// model-service onboarding). L72 took the probe *out of the drawer*: on iOS the
// drawer is the session list plus 定时任务 / 设置 (`NativeShell.swift:288`–`:307`),
// so a diagnostic console has no row there. `ProbeScreen` — the D3 console —
// renders at the home address only for a launch that asks for it
// (`--ez pdsh.face.probe true`), which is the developer entry the device scripts
// already pass. The product drawer is exactly the baseline's.
package com.phonedsh.probe

import com.phonedsh.dsh.localexec.UbuntuApproval
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.rememberCoroutineScope
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
import com.phonedsh.ui.page.FactState
import com.phonedsh.ui.page.PhoneCapability
import com.phonedsh.ui.page.ProviderDraft
import com.phonedsh.ui.page.ProviderKeyStatus
import com.phonedsh.ui.page.SettingsScreenState
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
    onPickFiles: () -> Unit = {},
    onPickImages: () -> Unit = {},
) {
    // The facts are one host walk per lifecycle/HELLO/turn change: a page never
    // asks the host for anything itself. `backgroundRevision` is the one key that
    // is not engine state: a permission answer lands after the first walk and must
    // not leave the runtime page showing the pre-answer verdict.
    // `settingsRevision` is the same kind of key for a write this host performed:
    // the device store changed, so the walk must run again — the facts themselves
    // still come only from reads.
    var settingsRevision by remember { mutableIntStateOf(0) }
    // The user preferences, collected so the theme and the facts follow a write.
    // The store is the view model's; this shell never reads the file itself.
    val preferences by engine.preferenceState.collectAsState()
    val systemDark = isSystemInDarkTheme()
    // L69: the chat page's one state. Collected here so a Worker event rebuilds the
    // page on the next frame; the view model is its only writer.
    val chatUi by engine.chat.collectAsState()
    LaunchedEffect(Unit) { engine.startNativeSurface() }
    LaunchedEffect(state is EngineHostState.Running, chatUi.chatId) {
        if (state is EngineHostState.Running) engine.prepareChatSession()
    }
    // L81: the 本 session 产物 attribution. Collected so a finished turn rebuilds the
    // artifacts page with the paths it wrote; the diff itself runs on the turn's IO
    // thread, never here.
    val artifactFacts by engine.artifactFacts.collectAsState()
    val automationFacts by engine.automation.collectAsState()
    var providerConnectionTest by remember {
        mutableStateOf<com.phonedsh.ui.page.ProviderConnectionTestFact?>(null)
    }
    var providerSave by remember {
        mutableStateOf<com.phonedsh.ui.page.ProviderSaveFact?>(null)
    }
    val providerProbeScope = rememberCoroutineScope()
    LaunchedEffect(state is EngineHostState.Running, chatUi.workerSessionId) {
        if (state is EngineHostState.Running) engine.refreshAutomation()
    }
    val facts: PdshNavFacts = remember(
        state,
        handshake,
        turn,
        backgroundRevision,
        permissionRevision,
        settingsRevision,
        preferences,
        automationFacts,
        chatUi.workerSessionId,
        providerConnectionTest,
        providerSave,
    ) {
        val measured = engine.navFacts(state, handshake, turn)
        measured.copy(
            automation = automationFacts.copy(currentSessionID = chatUi.workerSessionId),
            modelProvider = measured.modelProvider.copy(
                connectionTest = providerConnectionTest,
                providerSave = providerSave,
            ),
        )
    }
    // The one fact in that snapshot that is not an engine reading: whether the
    // platform really carries this app's back gesture through the invoker. It is
    // measured by the back callback (one gesture moves it), so it is applied here
    // rather than inside the remembered walk.
    val shownFacts = PredictiveBack.measured(facts)
    var nav by remember { mutableStateOf(PdshNavState()) }
    LaunchedEffect(chatUi.commandExecution?.id) {
        if (chatUi.commandExecution != null) nav = nav.push(PdshRoute.CommandResult)
    }
    var drawerOpen by remember { mutableStateOf(false) }
    var webFaceOpened by remember { mutableStateOf(false) }
    // L62: the probe is no longer the home face; it is the host's own surface
    // *at* the home address. Keeping it in the `home` slot (instead of a new
    // route) is deliberate: `PdshRoute` lives in `:lib` and this lane may not
    // extend it, and the probe was always the host's own face rather than a page
    // of §1. The default value is `false`, which is what makes the product home —
    // the session list — the launch face. L72 removed its drawer row (iOS has no
    // such row), so the only entry left is the launch the device scripts already
    // use: `--ez pdsh.face.probe true`.
    var probeOpen by remember { mutableStateOf(engine.probeFaceOnLaunch()) }
    val webFaceOnLaunch = remember { engine.webFaceOnLaunch() }
    // The one fact the home's onboarding card is drawn from. Re-read when this
    // host writes a model service (`settingsRevision`), because a save is what
    // turns the card off — and never on a recomposition alone: reading the
    // Keystore per frame would be a per-frame cost for a value that only a write
    // can change.
    val providerConfigured = remember(settingsRevision) {
        ApiKeyStore.activeFact().status == ProviderKeyStatus.CONFIGURED
    }

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

    // 外观 is a real preference here: the stored choice picks the token table for
    // every surface above the page (the probe face draws its own theme and is
    // handed the same resolved answer below). `system` follows the platform.
    PdshTheme(darkTheme = preferences.darkTheme(systemDark)) {
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
        //
        // L62 adds a fourth level *above* the route stack: the probe is the home
        // address's host-owned face, so while it is showing, one gesture leaves it
        // for the product home instead of closing the app.
        val backLevel = remember { PdshBackLevel() }
        PdshBackHandler(enabled = drawerOpen || probeOpen || backLevel.available || nav.canGoBack) {
            when {
                drawerOpen -> drawerOpen = false
                probeOpen -> probeOpen = false
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
                        onHome = {
                            // 原生恢复 returns to the *product* home, never back
                            // into the diagnostic face the person left open.
                            probeOpen = false
                            nav = nav.popToHome()
                        },
                        onHostAction = { action ->
                            // L72: one action is a navigation edge, not a handoff —
                            // `OpenWorkspaceFiles` is the documented "this folder
                            // asked for its file listing" gesture, and the page it
                            // names is a route this host already renders. It is
                            // dispatched by the 开发者工具 index's 工作文件夹 row, so
                            // the person lands on the files page with one back to
                            // the index (the drawer no longer carries either page).
                            if (action is PdshHostAction.OpenWorkspaceFiles) {
                                notice = null
                                nav = nav.push(PdshRoute.Files(action.id.ifBlank { null }))
                            } else if (action == PdshHostAction.RestartRuntime) {
                                engine.restartChatEngine()
                                notice = null
                            } else if (action is PdshHostAction.TestProvider) {
                                if (providerConnectionTest?.isTesting != true) {
                                    val draft = action.draft
                                    providerConnectionTest = com.phonedsh.ui.page.ProviderConnectionTestFact(
                                        profileId = draft.id, isNew = draft.isNew, isTesting = true,
                                        succeeded = null, message = null,
                                    )
                                    providerProbeScope.launch {
                                        val result = testProviderConnection(draft)
                                        providerConnectionTest = com.phonedsh.ui.page.ProviderConnectionTestFact(
                                            profileId = draft.id, isNew = draft.isNew, isTesting = false,
                                            succeeded = result is ProviderConnectionTestResult.Success, message = result.message,
                                        )
                                    }
                                }
                            } else if (action is PdshHostAction.SaveProvider) {
                                if (providerSave?.isSaving != true) {
                                    val draft = action.draft
                                    val requestId = action.requestId
                                    providerSave = com.phonedsh.ui.page.ProviderSaveFact(
                                        requestId = requestId,
                                        profileId = draft.id,
                                        isNew = draft.isNew,
                                        isSaving = true,
                                    )
                                    providerProbeScope.launch {
                                        val result = saveProvider(draft)
                                        if (result.recordWritten) settingsRevision++
                                        providerSave = com.phonedsh.ui.page.ProviderSaveFact(
                                            requestId = requestId,
                                            profileId = draft.id,
                                            isNew = draft.isNew,
                                            isSaving = false,
                                            succeeded = result.succeeded,
                                            message = result.message,
                                        )
                                    }
                                }
                            } else if (engine.handleAutomationAction(action)) {
                                notice = null
                            } else {
                                val feedback = handleHostAction(
                                    action = action,
                                    onStart = onStart,
                                    onStop = onStop,
                                    onSettingsChanged = { settingsRevision++ },
                                )
                                if (feedback != null) notice = feedback
                            }
                        },
                        // The settings index's writes reach the one preference store.
                        // 后台运行 and 繁忙时的发送行为 have typed handoffs; 新会话默认权限
                        // still has no consumer on this host (nothing derives a new
                        // session's permission tier from a preset), so it keeps the
                        // honest refusal instead of storing a value no path reads.
                        onSettingsWrite = { write ->
                            val applied = when (write) {
                                is SettingsWrite.BackgroundKeepAlive -> {
                                    engine.setBackgroundKeepAlive(write.value)
                                    true
                                }

                                is SettingsWrite.BusyEnter -> {
                                    engine.setBusyEnterMode(write.mode)
                                    true
                                }

                                is SettingsWrite.DefaultPermissionTier -> false
                            }
                            if (applied) {
                                Log.i(TAG_NAV, "PREF_WRITE ${write.wire}")
                                settingsRevision++
                            } else {
                                notice = "本宿主没有保存「${settingsWriteLabel(write)}」的命令，本次没有保存"
                            }
                        },
                        // The four generic pickers (语言 / 外观 / 工作步骤展示 / 收起时机 /
                        // 性能与用量) and the 显示代码工作视图 switch: the row id and the
                        // option id are the store's own vocabulary.
                        onPreferenceWrite = { choice ->
                            if (engine.applyPreference(choice.rowId, choice.optionId)) {
                                Log.i(
                                    TAG_NAV,
                                    "PREF_WRITE ${choice.rowId}=${choice.optionId}",
                                )
                                settingsRevision++
                            } else {
                                notice = "本宿主没有保存「${preferenceRowLabel(choice.rowId)}」的命令，本次没有保存"
                            }
                        },
                        onToggleDeveloperTools = { value ->
                            engine.setDeveloperTools(value)
                            Log.i(TAG_NAV, "PREF_WRITE ${SettingsScreenState.DEVELOPER_TOOLS_ROW_ID}=$value")
                            settingsRevision++
                        },
                        // The preference pages' own capability. This host owns a
                        // preference store, so the chat-appearance page's pickers and
                        // steppers are live; the session-config page keeps its own
                        // (still NO) `settingsWritable` fact.
                        preferencesWritable = FactState.YES,
                        // The two hosted gestures of the phone-permissions page: a real
                        // system request, and the settings page that can change the
                        // grant. Both are the Activity's, because a permission prompt
                        // is an Activity-scoped contract.
                        onPhonePermissionRequest = onPhonePermissionRequest,
                        onPhonePermissionSettings = onPhonePermissionSettings,
                        // L62: the home face is the product home — the session list
                        // (option (a): the list is rendered *in* the home slot, so
                        // `popToHome()` and the launch face are the same screen). The
                        // probe is the host's own face at this address, reached only by
                        // a launch that asks for it (`--ez pdsh.face.probe true`).
                        home = {
                            if (probeOpen) {
                                ProbeScreen(
                                    state = state,
                                    hostState = hostState,
                                    handshake = handshake,
                                    channel = channel,
                                    turn = turn,
                                    notice = recoveryNotice,
                                    darkTheme = preferences.darkTheme(systemDark),
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
                            } else {
                                HostHomeScreen(
                                    engine = engine,
                                    state = state,
                                    turn = turn,
                                    providerConfigured = providerConfigured,
                                    onHostNotice = { notice = it },
                                    onOpenDrawer = { drawerOpen = true },
                                    onOpenModelProvider = { nav = nav.push(PdshRoute.ModelProvider) },
                                    onOpenChat = { nav = nav.push(PdshRoute.Chat) },
                                )
                            }
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
                                // L69: selecting a row (or 新建会话) opens the chat at
                                // that conversation. The list stays the drawer's
                                // 会话 page; the person lands on the transcript they
                                // chose instead of an empty one.
                                onOpenChat = { nav = nav.push(PdshRoute.Chat) },
                            )
                        },
                        // The chain's middle level: what the top route can still go
                        // back to inside itself.
                        backLevel = backLevel,
                        // L69: the chat page's host half. `chatSurfaceState()` folds the
                        // Worker's projected events into the page's state; the seven
                        // callbacks are the App's own commands, not handoffs.
                        chat = engine.chatSurfaceState(),
                        chatTitle = chatUi.title,
                        chatSubpages = com.phonedsh.ui.page.ChatSubpageFacts(
                            commandExecution = chatUi.commandExecution,
                            schedules = automationFacts.snapshot?.schedules?.map { schedule ->
                                com.phonedsh.ui.page.ChatScheduleRow(schedule.id, schedule.title,
                                    listOfNotNull(schedule.rule, schedule.nextRun).joinToString(" · "),
                                    status = when (schedule.status) {
                                        com.phonedsh.ui.page.ScheduleStatus.ACTIVE -> "active"
                                        com.phonedsh.ui.page.ScheduleStatus.STOPPED -> "stopped"
                                        else -> null
                                    }, prompt = schedule.prompt)
                            },
                        ),
                        onChatDraftChange = engine::chatDraftChanged,
                        onChatSend = engine::sendChat,
                        onChatStop = engine::stopChat,
                        onChatRename = { title -> engine.renameCurrentChat(title) },
                        onChatAddFiles = onPickFiles,
                        onChatAddImages = onPickImages,
                        onChatRemoveAttachment = engine::removeChatAttachment,
                        onChatDismissNotice = engine::dismissChatNotice,
                        chatActions = com.phonedsh.ui.nav.ChatInteractionActions(
                            toggleRow = engine::toggleChatRow,
                            approve = { engine.answerChatPermission(it, true) },
                            deny = { engine.answerChatPermission(it, false) },
                            answer = engine::answerChatQuestion,
                            dismissQuestion = engine::dismissChatQuestion,
                            goalCommand = { engine.runChatCommand("/goal $it", showResult = false) },
                            selectAccessMode = engine::selectChatAccessMode,
                            refreshCatalogs = { engine.refreshChatSession() },
                            imeChanged = engine::chatImeChanged,
                            expandPending = engine::expandChatPending,
                            startEngine = onStart,
                            executeCommand = { engine.runChatCommand("/$it") },
                            selectModel = engine::selectChatModel,
                            restartEngine = engine::restartChatEngine,
                        ),
                        // L81: the 本 session 产物 page's attribution fact. The page
                        // reads the real materialized workspace; this is what tells
                        // it which of those files this session's turns wrote.
                        artifacts = artifactFacts,
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
                    // L72: the drawer is the iOS `SessionsDrawer` — the session list
                    // as its body, then the two furniture rows. It carries no app
                    // map: every row that used to be here has an entry of its own on
                    // iOS (see the lane's report), and the pages this host cannot
                    // reach that way are in 设置 → 开发者工具, never in the drawer.
                    entries = emptyList(),
                    footerEntries = DRAWER_FOOTER_ENTRIES,
                    body = {
                        // The drawer's body is the same list the 会话 route renders
                        // (`NativeShell.swift:288`), with no back arrow: the drawer
                        // is the root of that read, not a page pushed on top of it.
                        HostSessionsScreen(
                            engine = engine,
                            state = state,
                            turn = turn,
                            onBack = null,
                            onHostNotice = { notice = it },
                            // Picking a conversation closes the drawer and lands on
                            // it — the baseline's 选中会话即关闭抽屉
                            // (`NativeShell.swift:288`–`:291`); on iOS the chat page
                            // is the face behind the drawer.
                            onOpenChat = {
                                drawerOpen = false
                                notice = null
                                nav = nav.push(PdshRoute.Chat)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    onSelect = { route ->
                        // A line from a routed page is not about the next one.
                        notice = null
                        drawerOpen = false
                        // 定时任务 / 设置 are pages of §1: the probe is not behind
                        // them, so the next 返回 lands on the product home rather
                        // than on a diagnostic console.
                        probeOpen = false
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

        is PdshHostAction.SaveProvider -> run {
            val result = runBlocking { saveProvider(action.draft) }
            if (result.recordWritten) onSettingsChanged()
            result.message
        }

        is PdshHostAction.DeleteProvider -> deleteProvider(onSettingsChanged)

        // A connectivity test would have to reach the endpoint from this device,
        // through the same gateway the turn uses; this build has no such probe, so
        // the gesture gets the fact instead of a fabricated 通过.
        is PdshHostAction.TestProvider -> "本宿主没有往模型端点发测试请求的命令，本次未测试"

        is PdshHostAction.RunShellCommand ->
            "只读：本宿主列出了本机可执行的加速器，但没有调用 exec 桥的命令，本次未运行"

        // Design §3 promises a revoke "at any time within this session". Before
        // this branch the only way was to stop the local engine, which ends the
        // session the approval belonged to — a revoke that costs the thing it
        // was revoking for. The answer is the gate's own, never a local guess:
        // nothing granted means nothing was revoked, and the sentence says so.
        PdshHostAction.RevokeUbuntuApproval -> run {
            val revoked = UbuntuApproval.revoke()
            if (revoked) {
                "已撤销本会话的本地 Ubuntu 授权；下一次 ubuntu run 会重新询问"
            } else {
                "本会话没有已授予的本地 Ubuntu 授权，没有可撤销的东西"
            }
        }

        // 工作文件夹's 重试: this host owns exactly one workspace fact (the
        // materialized tree's existence), so the retry is a real re-read — the
        // settings revision forces the next facts walk, and nothing is claimed that
        // was not measured again.
        PdshHostAction.RefreshWorkspace -> {
            onSettingsChanged()
            "已重新读取：工作文件夹"
        }

        // §1 assigns the MCP save to the host, so the gesture is a handoff. The
        // registry lives in the Worker (`/dsh/home/mcp.json`) and is edited through
        // the `phone/mcpUpsert` host tool, which this build does not register: a
        // second registry in the App would be a document nothing reads.
        is PdshHostAction.SaveMcpServer ->
            "本次未保存：本宿主没有改写 MCP 注册表的工具（phone/mcpUpsert）"

        // Every remaining action names a command this build does not carry. The
        // line is specific instead of a template, and it never says a write landed:
        // the page keeps whatever it was showing, and the person is told why.
        // 新会话默认权限 and the settings writes above are the same shape (L74).
        else -> "本宿主没有「${actionLabel(action)}」这条命令，页面保持只读，本次没有改动"
    }
}

/** A failed result keeps the editor open; `recordWritten` separately tells the host to refresh facts. */
private suspend fun saveProvider(draft: ProviderDraft): HostProviderSaveResult = withContext(Dispatchers.IO) {
    fun reject(message: String) = HostProviderSaveResult(recordWritten = false, succeeded = false, message = message)
    val store = ProviderConfigStore.active() ?: return@withContext reject("本宿主没有安装模型服务存储，本次未保存")
    val keyStore = ApiKeyStore.active()
    val keyReason = if (draft.apiKey.isBlank()) null else ProviderDraftRules.keyRejection(draft.apiKey)
    if (keyReason != null) return@withContext reject(keyReason)
    if ((draft.apiKey.isNotBlank() || draft.removeStoredKey) && keyStore == null) {
        return@withContext reject("本宿主没有安装密钥存储，本次未保存")
    }
    draft.modelValidationError?.let { return@withContext reject(it) }

    val launch = ProbeLaunchConfig.active()
    val previous = store.current()
    val record = ProviderConfigStore.recordOf(
        draft = draft,
        previous = previous,
        launchEndpoint = launch.deepseekBaseUrl,
        launchModel = launch.modelName,
    )
    ProviderDraftRules.endpointRejection(record.endpoint)?.let { return@withContext reject(it) }
    if (record.name.isEmpty()) return@withContext reject("模型服务名不能为空，本次未保存")
    when (val outcome = store.save(record)) {
        ProviderWriteOutcome.Saved -> Unit
        is ProviderWriteOutcome.Rejected -> return@withContext reject(outcome.reason)
        else -> return@withContext reject("本机存储不可写，本次未保存")
    }

    // The non-secret profile has landed. The secret is still written only to the
    // Keystore-backed store; a failure leaves the editor open and reports that the
    // profile record may already exist so the user can repair the key deliberately.
    val keyLine = try {
        when {
            draft.removeStoredKey -> {
                keyStore?.clear()
                "，密钥已删除"
            }
            draft.apiKey.isNotBlank() -> {
                keyStore?.save(draft.apiKey)
                "，密钥已保存"
            }
            else -> ""
        }
    } catch (_: IllegalArgumentException) {
        return@withContext HostProviderSaveResult(
            recordWritten = true,
            succeeded = false,
            message = "配置已保存；密钥未通过校验，本次未保存密钥",
        )
    } catch (_: Exception) {
        return@withContext HostProviderSaveResult(
            recordWritten = true,
            succeeded = false,
            message = "配置已保存；密钥写入失败，本次未保存密钥",
        )
    }
    val effect = if (keyLine.isEmpty()) "下次启动 App 时生效" else "下次启动 App 时生效；密钥即刻生效"
    val replaceLine = if (draft.isNew && previous != null) "；本宿主只保存一个模型服务，本次替换了原有的" else ""
    HostProviderSaveResult(
        recordWritten = true,
        succeeded = true,
        message = "已保存到本机：模型服务$keyLine（$effect）$replaceLine",
    )
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
    is PdshHostAction.SaveAutomationSchedule -> "保存定时任务"
    is PdshHostAction.DeleteAutomationSchedule -> "删除定时任务"
    is PdshHostAction.LoadAutomationHistory -> "定时任务投递记录"
    PdshHostAction.LoadMoreAutomationHistory -> "加载更多定时任务历史"
    is PdshHostAction.OpenAutomationEditor -> "定时任务编辑器"
    PdshHostAction.RefreshConfiguration -> "重新读取会话配置"
    is PdshHostAction.OpenProviderEditor -> "模型服务编辑器"
    PdshHostAction.RefreshModelProvider -> "重新读取模型服务"
    is PdshHostAction.SaveProvider -> "保存模型服务"
    is PdshHostAction.DeleteProvider -> "删除模型服务"
    is PdshHostAction.TestProvider -> "测试模型服务"
    PdshHostAction.RevokeUbuntuApproval -> "撤销本地 Ubuntu 授权"
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

    PdshRoute.Automation -> null

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

    // 对话 is live now (L69): the transcript comes from the Worker's own events,
    // the composer sends a real prompt and 停止 cancels the turn, so there is
    // nothing read-only to disclose.
    PdshRoute.Chat -> null

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

    // 本 session 产物 is not demo data any more (L81): the page lists the real
    // materialized workspace and its 本 session rows come from the host's own
    // per-turn write measurement. The page prints its own read states (暂无工作区 +
    // 重试, 无法读取产物 + 重试, the two sections), so a route-wide line would be a
    // second, weaker version of its disclosure — the same reason the files and
    // preview routes have none.
    is PdshRoute.SessionArtifacts -> null

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

/**
 * The user-facing name of a preference row this host could not write.
 *
 * Only the rows the preference store really answers are wired, so the refusal is
 * the store's own `false` — this label exists so a future row cannot be handed
 * back as a bare id.
 */
private fun preferenceRowLabel(rowId: String): String = when (rowId) {
    SettingsScreenState.LANGUAGE_ROW_ID -> "语言"
    SettingsScreenState.APPEARANCE_ROW_ID -> "外观"
    SettingsScreenState.FONT_SIZE_ROW_ID -> "字号大小"
    SettingsScreenState.TRANSCRIPT_VIEW_ROW_ID -> "工作步骤展示"
    SettingsScreenState.COLLAPSE_TIMING_ROW_ID -> "工作步骤收起时机"
    SettingsScreenState.PERFORMANCE_ROW_ID -> "性能与用量"
    SettingsScreenState.TEXT_FONT_SIZE_ROW_ID,
    SettingsScreenState.CODE_FONT_SIZE_ROW_ID,
    SettingsScreenState.TERMINAL_FONT_SIZE_ROW_ID -> "字号大小"
    else -> rowId
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
 * The two rows the iOS drawer pins below its session list.
 *
 * That is the whole drawer (`NativeShell.swift:288`–`:307`: `SessionsPage`, a
 * divider, 定时任务, 设置). It carried eight more rows until L72 — 对话, 会话,
 * 工作文件夹, 文件, 命令, 设备连接, DSH 页面, 引擎探针 — and every one of them was
 * a second entrance for a page the baseline reaches somewhere else:
 *
 *   * 对话 / 会话 — the baseline's chat face and this drawer's own body;
 *   * 文件 / 命令 — the chat page's ⋯ → 右侧边栏 → 工作面板 (its 文件 / 命令
 *     segment), which this host's chat page does not draw yet;
 *   * 设备连接 — the chat page head's device icon, likewise not drawn yet;
 *   * 工作文件夹 — no product entry at all on iOS (only `-uiPreview workspace`);
 *     it lives in 设置 → 开发者工具 on this host;
 *   * DSH 页面 — deleted from iOS (PR 495da9b5); this host's own Web face, reached
 *     with `--ez pdsh.face.web true`;
 *   * 引擎探针 — this host's diagnostic console, reached with
 *     `--ez pdsh.face.probe true`.
 *
 * The drawer is not an app map on either platform: it is the session list plus
 * these two rows (`NativeShell.swift:296`).
 */
private val DRAWER_FOOTER_ENTRIES: List<PdshDrawerEntry> = listOf(
    PdshDrawerEntry(UiStrings.AUTOMATION_TITLE, PdshDrawerIcon.CLOCK, PdshRoute.Automation),
    PdshDrawerEntry(UiStrings.SETTINGS, PdshDrawerIcon.SETTINGS, PdshRoute.Settings),
)

/** The host's navigation log tag; only stable action labels are written to it. */
private const val TAG_NAV: String = "PDSHNav"

/** How long a gesture's outcome line stays on screen (four seconds). */
private const val NOTICE_MILLIS: Long = 4_000L
