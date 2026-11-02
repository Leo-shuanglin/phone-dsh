// The route to page host: one composable that turns a route into its page.
// SPDX-License-Identifier: MIT
//
// Spec: `spec/ui/v1/README.md` §1 (每页的 入口 / 导航关系), §3.1 (the ScreenState
// layer) and §5 (`back-gesture`: the page stack pops one level per gesture).
//
// The host only ever hands this file two things: [PdshNavFacts] (what it measured,
// `null` for what it could not read) and [PdshNavHost]'s callbacks. Every page here
// is a pure function of (facts, local script), so a route can be replayed on the
// JVM without a device; the local scripts (folds, tabs, drafts, stop confirmation)
// live in this file, never in a page.
//
// Write gestures are handed back as [PdshHostAction]. This Android host implements
// no settings write, no workspace mutation and no MCP save (§1 names no command for
// them), so the page's facts do not change on those gestures and the page never
// shows a success it did not observe. The two actions the host really owns (engine
// stop and engine start) are performed by the composition root.
package com.phonedsh.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.page.AutomationLocalState
import com.phonedsh.ui.page.AutomationScreenState
import com.phonedsh.ui.page.AutomationUiEvent
import com.phonedsh.ui.page.ChatPage
import com.phonedsh.ui.page.ConfigurationLocalState
import com.phonedsh.ui.page.ConfigurationScreenState
import com.phonedsh.ui.page.ConfigurationUiEvent
import com.phonedsh.ui.page.EgressAllowListLocalState
import com.phonedsh.ui.page.EgressAllowListPage
import com.phonedsh.ui.page.EgressAllowListScreenState
import com.phonedsh.ui.page.EgressAllowListUiEvent
import com.phonedsh.ui.page.ExportFacts
import com.phonedsh.ui.page.ExportPage
import com.phonedsh.ui.page.ExportScreenState
import com.phonedsh.ui.page.FilesLocalState
import com.phonedsh.ui.page.FilesPage
import com.phonedsh.ui.page.FilesScreenState
import com.phonedsh.ui.page.FilesSource
import com.phonedsh.ui.page.FilesUiEvent
import com.phonedsh.ui.page.MCPEditorLocalState
import com.phonedsh.ui.page.MCPEditorPage
import com.phonedsh.ui.page.MCPEditorScreenState
import com.phonedsh.ui.page.MCPEditorUiEvent
import com.phonedsh.ui.page.MCPLocalState
import com.phonedsh.ui.page.MCPScreenState
import com.phonedsh.ui.page.MCPSettingsPage
import com.phonedsh.ui.page.MCPUiEvent
import com.phonedsh.ui.page.ModelProviderLocalState
import com.phonedsh.ui.page.ModelProviderScreenState
import com.phonedsh.ui.page.ModelProviderSettingsPage
import com.phonedsh.ui.page.ModelProviderUiEvent
import com.phonedsh.ui.page.NativeAgentPresetsFacts
import com.phonedsh.ui.page.NativeAgentPresetsLocalState
import com.phonedsh.ui.page.NativeAgentPresetsPage
import com.phonedsh.ui.page.NativeAgentPresetsScreenState
import com.phonedsh.ui.page.NativeAgentPresetsUiEvent
import com.phonedsh.ui.page.NativeAutomationPage
import com.phonedsh.ui.page.NativeConfigurationPage
import com.phonedsh.ui.page.NativePluginInventoryPage
import com.phonedsh.ui.page.NativeWorkbenchPage
import com.phonedsh.ui.page.PDSHShellPage
import com.phonedsh.ui.page.PhoneCapability
import com.phonedsh.ui.page.PhoneCapabilityAction
import com.phonedsh.ui.page.PhonePermissionsLocalState
import com.phonedsh.ui.page.PhonePermissionsPage
import com.phonedsh.ui.page.PhonePermissionsScreenState
import com.phonedsh.ui.page.PhonePermissionsUiEvent
import com.phonedsh.ui.page.PluginInventoryFacts
import com.phonedsh.ui.page.PluginInventoryLocalState
import com.phonedsh.ui.page.PluginInventoryScreenState
import com.phonedsh.ui.page.PluginInventoryUiEvent
import com.phonedsh.ui.page.PermissionDefaultsPage
import com.phonedsh.ui.page.PermissionDefaultsState
import com.phonedsh.ui.page.RuntimeDetailsPage
import com.phonedsh.ui.page.RuntimeDetailsScreenState
import com.phonedsh.ui.page.RuntimeLocalState
import com.phonedsh.ui.page.RuntimeUiEvent
import com.phonedsh.ui.page.SettingsDestination
import com.phonedsh.ui.page.SettingsTab
import com.phonedsh.ui.page.SessionListing
import com.phonedsh.ui.page.SessionsLocalState
import com.phonedsh.ui.page.SessionsPage
import com.phonedsh.ui.page.SessionsScreenState
import com.phonedsh.ui.page.SessionWorkspace
import com.phonedsh.ui.page.SettingsPage
import com.phonedsh.ui.page.SettingsScreenState
import com.phonedsh.ui.page.ShellLocalState
import com.phonedsh.ui.page.ShellUiEvent
import com.phonedsh.ui.page.ToolsScreenState
import com.phonedsh.ui.page.WebSearchLocalState
import com.phonedsh.ui.page.WebSearchScreenState
import com.phonedsh.ui.page.WebSearchSettingsPage
import com.phonedsh.ui.page.WebSearchUiEvent
import com.phonedsh.ui.page.WebSurfaceLocalState
import com.phonedsh.ui.page.WebSurfacePage
import com.phonedsh.ui.page.WebSurfaceScreenState
import com.phonedsh.ui.page.WebSurfaceUiEvent
import com.phonedsh.ui.page.WorkspaceFile
import com.phonedsh.ui.page.WorkbenchLocalState
import com.phonedsh.ui.page.WorkbenchScreenState
import com.phonedsh.ui.page.WorkbenchSection
import com.phonedsh.ui.page.WorkbenchUiEvent
import com.phonedsh.ui.page.WorkspaceFoldersLocalState
import com.phonedsh.ui.page.WorkspaceFoldersPage
import com.phonedsh.ui.page.WorkspaceFoldersScreenState
import com.phonedsh.ui.page.WorkspaceFoldersUiEvent
import com.phonedsh.ui.page.apply

// Appended by the L13 lane: the three pages it mirrored (chat appearance, the
// redacted settings document, the shortcut reference). Grouped rather than
// alphabetised so this lane's edge is one block another lane can move.
import com.phonedsh.ui.page.ChatPreferencesFacts
import com.phonedsh.ui.page.ChatPreferencesPage
import com.phonedsh.ui.page.ChatPreferencesScreenState
import com.phonedsh.ui.page.FactState
import com.phonedsh.ui.page.NativeSettingsDocumentPage
import com.phonedsh.ui.page.NativeShortcutsFacts
import com.phonedsh.ui.page.NativeShortcutsPage
import com.phonedsh.ui.page.NativeShortcutsScreenState
import com.phonedsh.ui.page.SettingsDocumentFacts
import com.phonedsh.ui.page.SettingsDocumentScreenState
import com.phonedsh.ui.state.ChatLocalState
import com.phonedsh.ui.state.ChatScreenState
import com.phonedsh.ui.theme.LocalUiTokens

// Appended by the L24 lane: the chat family's three 内嵌子页 (`ChatPage.swift:3504`,
// `:3554`, `:4243`). Grouped so this lane's edge is one block another lane can move
// without a merge conflict.
import com.phonedsh.ui.page.ChatCommandResultState
import com.phonedsh.ui.page.ChatJobsSheetState
import com.phonedsh.ui.page.ChatSchedulesSheetState
import com.phonedsh.ui.page.ChatSubpageFacts
import com.phonedsh.ui.page.CommandExecutionResultPage
import com.phonedsh.ui.page.NativeJobListSheet
import com.phonedsh.ui.page.NativeScheduleListSheet

// Appended by the L5 lane: the device-connector routes read the `:domain` connector
// seam (its state machine, its read-only session transport and its paging window) and
// the three pages below it. They are grouped rather than alphabetised so this lane's
// edge stays one block another lane can move without a merge conflict.
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.phonedsh.domain.connector.DeviceConnectionEntry
import com.phonedsh.domain.connector.DeviceConnectionSource
import com.phonedsh.domain.connector.DeviceItemKind
import com.phonedsh.domain.connector.DeviceSessionSource
import com.phonedsh.domain.connector.DeviceTranscriptProjector
import com.phonedsh.domain.connector.Endpoint
import com.phonedsh.domain.connector.InMemoryConnector
import com.phonedsh.domain.connector.InMemoryDeviceSessionProvider
import com.phonedsh.domain.connector.InMemoryDeviceSessionTransport
import com.phonedsh.domain.device.CapabilityValue
import com.phonedsh.domain.device.DeviceAuth
import com.phonedsh.domain.device.DeviceLimits
import com.phonedsh.domain.device.DeviceManifest
import com.phonedsh.domain.device.DeviceProtocol
import com.phonedsh.ui.page.DeviceConnectionLocalState
import com.phonedsh.ui.page.DeviceConnectionScreenState
import com.phonedsh.ui.page.DeviceSessionLocalState
import com.phonedsh.ui.page.DeviceSessionScreenState
import com.phonedsh.ui.page.InMemorySessionArtifactSource
import com.phonedsh.ui.page.NativeDeviceConnectionPage
import com.phonedsh.ui.page.NativeDeviceSessionPage
import com.phonedsh.ui.page.SessionArtifactEvidence
import com.phonedsh.ui.page.SessionArtifactRef
import com.phonedsh.ui.page.SessionArtifactsLocalState
import com.phonedsh.ui.page.SessionArtifactsPage
import com.phonedsh.ui.page.SessionArtifactsScreenState
import kotlinx.coroutines.launch

// Appended by the L25 lane: the two embedded subpages it implemented
// (`ScheduleEditorPage` and `SessionArtifactPreviewPage`, README §1
// 「内嵌子页随父页实现」). Grouped rather than alphabetised so this lane's edge
// stays one block another lane can move without a merge conflict.
import com.phonedsh.ui.page.AutomationScheduleDraft
import com.phonedsh.ui.page.SessionArtifactPreview
import java.time.ZoneId

// Appended by the L29 lane: the one preview face (`PdshRoute.Preview`). The surface
// lives in its own package so it can carry the resolver, the pager and the file
// source together; the route is the only thing the router has to know about it.
import androidx.compose.ui.platform.LocalContext
import com.phonedsh.ui.surface.preview.PreviewPage
import com.phonedsh.ui.surface.preview.PreviewShare
import com.phonedsh.ui.surface.preview.rememberPreviewSource
import java.io.File

// Appended by the L44 lane: the one level of back that lives *inside* a route.
// The system back gesture reaches the host, not the page, so a route that shows an
// inner level (a sheet's child page, a folder drill-down, an embedded preview) has
// nowhere to register it. These two declarations are that place; they are the only
// state the back chain needs beyond the stack itself.
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState

/**
 * One level of back that lives inside the top route.
 *
 * `spec/ui/v1/README.md` §5 `back-gesture` says one gesture pops one level; for the
 * routes that render a child page of their own, "one level" is not the route stack
 * but this. The host owns the single system-back callback, so it holds one of these
 * and asks it before popping:
 *
 * ```
 * drawer open? -> close it
 * [PdshBackLevel.available]? -> consume()
 * else -> pop the route stack
 * ```
 *
 * The rendered route is the only writer, and only while it really shows an inner
 * level ([enabled] in [RouteBackLevel]), so a pushed route never hides the level of
 * the route below it.
 */
@Stable
public class PdshBackLevel {
    private var entry: (() -> Unit)? by mutableStateOf(null)

    /** Whether the rendered route has an inner level to leave. */
    public val available: Boolean get() = entry != null

    /**
     * Leaves one inner level.
     *
     * @return `true` when a level was left, `false` when there was none and the
     *   caller should pop the route stack instead.
     */
    public fun consume(): Boolean {
        val action = entry ?: return false
        action()
        return true
    }

    internal fun register(action: () -> Unit) {
        entry = action
    }

    /**
     * Removes [action] only while it is still the registered one.
     *
     * The identity check matters for the one nesting this router has: the settings
     * sheet's 手机权限 tab renders the permissions page inside the settings route. A
     * blindly-clearing dispose would drop the outer route's level when a page that
     * never registered one left the composition.
     */
    internal fun unregister(action: () -> Unit) {
        if (entry === action) entry = null
    }
}

/**
 * Registers [onBack] as the top route's inner level while [enabled] holds.
 *
 * The registration lives exactly as long as this route is the rendered one: the
 * effect is disposed when the composable leaves the composition (another route
 * replaced it) or when [enabled] turns false (the level closed).
 *
 * @param level the host's chain, or `null` when the host keeps none.
 * @param enabled whether this route currently shows an inner level.
 * @param onBack leaves that level.
 */
@Composable
private fun RouteBackLevel(
    backLevel: PdshBackLevel?,
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(backLevel, enabled) {
        if (backLevel == null || !enabled) {
            return@DisposableEffect onDispose {}
        }
        val action: () -> Unit = { current() }
        backLevel.register(action)
        onDispose { backLevel.unregister(action) }
    }
}

/**
 * Renders the top route of [nav].
 *
 * @param nav the page stack; only [PdshNavState.current] is rendered.
 * @param facts every page's read-only input.
 * @param onNavigate push a route (the drawer and the in-page links call this).
 * @param onBack pop one route.
 * @param onHome drop the stack back to the native home (the Web surface's
 *   原生恢复 path).
 * @param onHostAction a gesture this host cannot complete itself.
 * @param onSettingsWrite a settings write this host cannot persist; the mirrored
 *   index's switch, menu and permission preset hand it back here. The default is
 *   a no-op so a host that has no settings write keeps compiling honestly.
 * @param home the native face ([PdshRoute.Home]).
 * @param webSurface the host-owned WebView container handed to the Web surface.
 * @param sessions the host's real session list, or `null` for this router's
 *   facts-only empty list. The drawer's 会话 row is a route push now, and the
 *   host that owns the session read (the probe app's `HostSessionsScreen`) is
 *   the only place that data lives; passing it here keeps [PdshRoute.Sessions]
 *   the one address of the list instead of a second, emptier page.
 * @param chatSubpages the chat family's three 内嵌子页 input (`ChatSubpageFacts`):
 *   one command settlement, the session's jobs and its schedules. The default
 *   reports no read, so [PdshRoute.CommandResult], [PdshRoute.SessionJobs] and
 *   [PdshRoute.SessionSchedules] render their 读取不到 body instead of an empty
 *   list — "not read" and "read, and it is empty" are different facts.
 * @param workspaceRoot the materialized workspace the files page lists and the
 *   preview resolves against (`<workspace>/materialized/workspace`), or `null` for
 *   the library's default device root — this app's `filesDir/xl/materialized/
 *   workspace`, the tree `:app`'s composition root already builds the WAL stores
 *   over. A host that measures its own root passes it here; a host with no
 *   workspace passes a path that does not exist and the pages say "not reported"
 *   instead of inventing one.
 * @param backLevel the host's one inner-level back chain (see [PdshBackLevel]), or
 *   `null` when the host keeps none. A route whose "one step back" is inside the
 *   page (the settings sheet's 新会话默认权限 child, the files list's folder
 *   drill-down, the artifacts preview, the schedule editor) registers there, so the
 *   system gesture leaves that level instead of closing the whole route. The
 *   default is `null` because a host that renders these pages without a back
 *   gesture is still a valid host.
 */
@Composable
public fun PdshNavHost(
    nav: PdshNavState,
    facts: PdshNavFacts,
    onNavigate: (PdshRoute) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    home: @Composable () -> Unit,
    webSurface: @Composable () -> Unit,
    sessions: (@Composable () -> Unit)? = null,
    onSettingsWrite: (SettingsWrite) -> Unit = {},
    chatSubpages: ChatSubpageFacts = ChatSubpageFacts(),
    workspaceRoot: File? = null,
    backLevel: PdshBackLevel? = null,
    // The phone-permissions page is the one page whose 请求 is not a write this file
    // can perform: a system permission prompt belongs to the Activity, so the gesture
    // is handed back with the row it was made on. Empty by default, so a host that
    // cannot prompt still renders the page — as a read-only surface.
    onPhonePermissionRequest: (PhoneCapability) -> Unit = {},
    onPhonePermissionSettings: (PhoneCapability) -> Unit = {},
) {
    val route = nav.current
    if (route == PdshRoute.Home) {
        home()
        return
    }
    RouteSurface(route.presentation) {
        when (route) {
            PdshRoute.Home -> Unit // rendered above; kept for the exhaustive when
            PdshRoute.Sessions ->
                if (sessions != null) sessions() else SessionsRoute(facts, onBack)
            PdshRoute.Chat -> ChatRoute(chatSubpages, onNavigate)
            PdshRoute.WorkspaceFolders -> WorkspaceFoldersRoute(facts, onBack, onHostAction, onNavigate)
            is PdshRoute.Files -> FilesRoute(onBack, onNavigate, workspaceRoot, backLevel)
            is PdshRoute.Preview -> PreviewRoute(route, onBack, workspaceRoot)
            PdshRoute.Automation -> AutomationRoute(facts, onBack, onHostAction, backLevel)
            PdshRoute.Configuration -> ConfigurationRoute(facts, onBack, onNavigate)
            PdshRoute.Settings -> SettingsRoute(
                facts = facts,
                onBack = onBack,
                onNavigate = onNavigate,
                onHostAction = onHostAction,
                onSettingsWrite = onSettingsWrite,
                backLevel = backLevel,
                onPhonePermissionRequest = onPhonePermissionRequest,
                onPhonePermissionSettings = onPhonePermissionSettings,
            )
            PdshRoute.ModelProvider -> ModelProviderRoute(facts, onBack, onHostAction)
            PdshRoute.WebSearch -> WebSearchRoute(facts, onBack, onHostAction)
            PdshRoute.Mcp -> McpRoute(facts, onBack, onHostAction, onNavigate)
            is PdshRoute.McpEditor -> McpEditorRoute(route, facts, onBack, onHostAction)
            PdshRoute.Runtime -> RuntimeRoute(facts, onBack, onHostAction, backLevel)
            PdshRoute.Egress -> EgressRoute(facts, onBack)
            PdshRoute.Tools -> ToolsRoute(facts, onBack, onHostAction)
            PdshRoute.Export -> ExportRoute(onBack)
            is PdshRoute.Workbench ->
                WorkbenchRoute(route, facts, onBack, onHostAction, onNavigate, backLevel)
            PdshRoute.WebSurface -> WebSurfaceRoute(facts, onNavigate, onHome, onHostAction, webSurface)
            PdshRoute.DeviceConnection -> DeviceConnectionRoute(onNavigate, onBack)
            is PdshRoute.DeviceSession -> DeviceSessionRoute(route.deviceId, onBack)
            is PdshRoute.SessionArtifacts ->
                SessionArtifactsRoute(route.sessionId, onBack, backLevel)
            PdshRoute.AgentPresets -> AgentPresetsRoute(onBack)
            PdshRoute.PluginInventory -> PluginInventoryRoute(facts, onBack, onNavigate)
            PdshRoute.PhonePermissions -> PhonePermissionsRoute(
                facts = facts,
                onBack = onBack,
                onRequestPermission = onPhonePermissionRequest,
                onOpenPermissionSettings = onPhonePermissionSettings,
                backLevel = backLevel,
            )
            PdshRoute.ChatPreferences -> ChatPreferencesRoute(facts, onBack)
            PdshRoute.SettingsDocument -> SettingsDocumentRoute(onBack)
            PdshRoute.Shortcuts -> ShortcutsRoute(onBack)
            PdshRoute.CommandResult -> CommandResultRoute(chatSubpages, onBack)
            PdshRoute.SessionJobs -> SessionJobsRoute(chatSubpages, onBack, onHostAction)
            PdshRoute.SessionSchedules -> SessionSchedulesRoute(chatSubpages, onBack, onHostAction)
        }
    }
}

/**
 * The modal frame around a non-home route.
 *
 * PUSH and FULLSCREEN pages cover the screen. A SHEET keeps a strip of the page
 * below visible and rounds its own top edge, so the modal form §1 names is
 * visible rather than only behavioural. No sheet geometry token exists, so the
 * strip reuses `geometry.cardOuterMarginV` and the radius reuses `radius.card`;
 * both are marked unverified at this call site.
 */
@Composable
private fun RouteSurface(presentation: PdshPresentation, content: @Composable () -> Unit) {
    val tokens = LocalUiTokens.current
    val sheet = presentation == PdshPresentation.SHEET
    Box(modifier = Modifier.fillMaxSize().background(tokens.colors.selector)) {
        if (sheet) {
            // unverified token: no scrim colour token exists; the page ink at a
            // fixed opacity is the only non-invented dimming the token table has.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(tokens.colors.primary.copy(alpha = SHEET_SCRIM_ALPHA)),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (sheet) {
                        // unverified geometry: no sheet token; the page margin is the
                        // smallest published inset that keeps the page visible behind.
                        Modifier.padding(top = tokens.geometry.cardOuterMarginV)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (sheet) {
                        Modifier.clip(
                            RoundedCornerShape(
                                topStart = tokens.radius.card,
                                topEnd = tokens.radius.card,
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .background(tokens.colors.selector),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------- pages

/**
 * 对话: §1's root native face.
 *
 * `PdshNavFacts` carries no chat field yet (this host reads no transcript, no
 * pending ask and no model catalog through the router), so the projector is handed
 * an empty node sequence and the page renders its honest empty session: empty
 * state A — 引擎还没启动 — over a live composer dock. No row, no ask and no stat is
 * invented to fill the frame; the dock's own gestures are no-ops here because the
 * host owns no send/stop command yet, exactly like [ToolsRoute] and [ExportRoute].
 *
 * @param onNavigate push the sessions list: the page head's leading control is
 *   the baseline's session-drawer control (`ChatPage.swift`), and this host's
 *   drawer body is the sessions page, so the control opens that route.
 */
@Composable
private fun ChatRoute(subpages: ChatSubpageFacts, onNavigate: (PdshRoute) -> Unit) {
    ChatPage(
        state = ChatScreenState.from(nodes = emptyList(), local = ChatLocalState()),
        onDraftChange = {},
        onSend = {},
        onStop = {},
        onToggleRow = {},
        onApprove = {},
        onDeny = {},
        onDismissBanner = {},
        onRetry = {},
        onOpenSessions = { onNavigate(PdshRoute.Sessions) },
        title = UiStrings.SESSION_TITLE_NEW,
        // The page-head menu's 后台任务（N）/定时任务（N） rows exist only while the
        // host reported rows (`ChatPage.swift:247`/`:252`); with no read they are
        // absent instead of opening a sheet that can only say 读取不到.
        jobs = subpages.jobs,
        schedules = subpages.schedules,
        onOpenJobs = { onNavigate(PdshRoute.SessionJobs) },
        onOpenSchedules = { onNavigate(PdshRoute.SessionSchedules) },
        // Appended by the L25 lane: the page-head menu's 产物 row
        // (`ChatPage.swift:256`), which opens the artifacts page and from there the
        // preview subpage this lane implemented. `ChatPage` deliberately takes the
        // callback as `null`-able because "this page never learns a session id from
        // its state"; the router is the only place that can name one, and the only
        // session this host has reads is the demo transport's — the same one the
        // demo artifacts' evidence came from.
        onOpenArtifacts = {
            onNavigate(PdshRoute.SessionArtifacts(InMemoryDeviceSessionTransport.DEMO_SESSION_ID))
        },
        // The menu's 运行状态 and 本会话设置 rows were drawn with the page's default
        // empty callbacks, so they closed the menu and did nothing. Both pages are
        // already routes (`PdshRoute.Runtime` is the page-head dot's, and the
        // configuration sheet is the native face's ⋯), so the wiring is the two
        // edges — no new page, no new state.
        onOpenRunStatus = { onNavigate(PdshRoute.Runtime) },
        onOpenSessionSettings = { onNavigate(PdshRoute.Configuration) },
    )
}

/**
 * 命令结果: the chat page's own subpage, reached by the shell's route rather than
 * by an in-page row (the baseline opens it from `executeCommand`, `:2516`).
 *
 * `完成` pops; there is no write here, so the only host interaction a page of this
 * family has is the two sheets' 刷新.
 */
@Composable
private fun CommandResultRoute(subpages: ChatSubpageFacts, onBack: () -> Unit) {
    CommandExecutionResultPage(
        state = ChatCommandResultState(subpages.commandExecution),
        onDone = onBack,
    )
}

/**
 * 后台任务: `刷新` re-reads the session activity through the host seam.
 *
 * The baseline's sheet takes one `onRefresh` (`ChatPage.swift:441`) and calls
 * `management.refreshActivity(sessionID:)` — the same read the 定时任务 sheet uses,
 * because one call fills `jobs` and `scheduleRecords` together. This host owns
 * neither, so the gesture is a [PdshHostAction] handoff: it reaches the composition
 * root, is logged, and comes back as one honest line; the facts only change if a
 * real read reports them.
 */
@Composable
private fun SessionJobsRoute(
    subpages: ChatSubpageFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
) {
    NativeJobListSheet(
        state = ChatJobsSheetState.from(subpages.jobs),
        onRefresh = { onHostAction(PdshHostAction.RefreshSessionActivity) },
        onClose = onBack,
    )
}

/** 定时任务: the same one read as [SessionJobsRoute], sharing its handoff. */
@Composable
private fun SessionSchedulesRoute(
    subpages: ChatSubpageFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
) {
    NativeScheduleListSheet(
        state = ChatSchedulesSheetState.from(subpages.schedules),
        onRefresh = { onHostAction(PdshHostAction.RefreshSessionActivity) },
        onClose = onBack,
    )
}

@Composable
private fun WorkspaceFoldersRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    onNavigate: (PdshRoute) -> Unit,
) {
    var local by remember { mutableStateOf(WorkspaceFoldersLocalState()) }
    WorkspaceFoldersPage(
        state = WorkspaceFoldersScreenState.from(facts.workspaceFolders, local),
        onBack = onBack,
        // §1: every row opens the files page (`NativeShell.swift:326`–`:336`), so
        // this is a route push, not a host handoff.
        onOpenFiles = { folder -> onNavigate(PdshRoute.Files(folder.id)) },
        onSwitch = { folder -> onHostAction(PdshHostAction.WorkspaceSwitch(folder.id)) },
        onBeginCreate = { local = local.apply(WorkspaceFoldersUiEvent.BeginCreate) },
        onDraftChange = { text -> local = local.apply(WorkspaceFoldersUiEvent.DraftChange(text)) },
        onCancelCreate = { local = local.apply(WorkspaceFoldersUiEvent.CancelCreate) },
        onCreate = {
            onHostAction(PdshHostAction.WorkspaceCreate(local.draft))
            // No write command exists: the create row stays open with the typed
            // name instead of claiming a folder that was never created.
        },
        onDeleteRequest = { folder ->
            local = local.apply(WorkspaceFoldersUiEvent.RequestDelete(folder.id))
        },
        onDeleteConfirm = { folder ->
            onHostAction(PdshHostAction.WorkspaceDelete(folder.id))
            local = local.apply(WorkspaceFoldersUiEvent.CancelDelete)
        },
        onDeleteCancel = { local = local.apply(WorkspaceFoldersUiEvent.CancelDelete) },
    )
}

/**
 * 定时任务: the schedule catalog and its embedded editor.
 *
 * Two of the four gestures are host reads (`刷新`, `加载更早记录`) and reach
 * [PdshHostAction]; the other two (`新建`, `编辑`) present the page's own
 * `ScheduleEditorPage` (`NativeAutomationPage.swift:212`, README §1: an embedded
 * subpage ships with its parent). The form's `保存` is the write handoff: the route
 * records the attempt, re-projects the form, and only a validated draft reaches
 * the host.
 *
 * A page whose bar renders but does nothing is worse than a page that never renders
 * it, because the user reads it as broken — which is why every one of the four
 * controls is wired to something real.
 *
 * The bar controls only exist when their callback is non-null
 * (`NativeAutomationPage.kt:598`/`:607`), which is exactly why leaving them
 * null made the collected screenshots disagree with the app.
 *
 * @param onHostAction the handoff seam for the four schedule gestures.
 */
@Composable
private fun AutomationRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    backLevel: PdshBackLevel?,
) {
    var local by remember { mutableStateOf(AutomationLocalState()) }
    // The page's three child levels (detail, history, the embedded editor) and its
    // delete confirmation are local state, so the gesture used to skip straight from
    // the editor back to the previous route. It now leaves them one at a time, in
    // the same order the page's own ✕/取消 controls do.
    RouteBackLevel(
        backLevel = backLevel,
        enabled = local.editor != null ||
            local.route != com.phonedsh.ui.page.AutomationRoute.CATALOG ||
            local.deleteConfirmId != null,
        onBack = {
            local = when {
                local.editor != null -> local.apply(AutomationUiEvent.CancelEditor)
                local.route != com.phonedsh.ui.page.AutomationRoute.CATALOG ->
                    local.apply(AutomationUiEvent.Back)
                else -> local.apply(AutomationUiEvent.CancelDelete)
            }
        },
    )
    val automationFacts = facts.automation
    // The draft the editor opens on: the reported schedule's own values, or the
    // baseline's defaults for a new one. The platform zone is measured here and
    // handed in, because the state function must not read the platform (§3.1).
    fun draftFor(id: String?): AutomationScheduleDraft = AutomationScheduleDraft.from(
        schedule = id?.let { wanted -> automationFacts.snapshot?.schedules?.firstOrNull { it.id == wanted } },
        timeZone = ZoneId.systemDefault().id,
    )

    NativeAutomationPage(
        state = AutomationScreenState.from(automationFacts, local),
        onBack = onBack,
        onOpenDetail = { id -> local = local.apply(AutomationUiEvent.OpenDetail(id)) },
        onOpenHistory = { id -> local = local.apply(AutomationUiEvent.OpenHistory(id)) },
        onBackToCatalog = { local = local.apply(AutomationUiEvent.Back) },
        // §1's 定时任务 bar: ✕ (back, above), 刷新, 新建. The history tail is still a
        // host read.
        onRefresh = { onHostAction(PdshHostAction.RefreshAutomation) },
        // 新建 / 编辑 present the page's own `ScheduleEditorPage` (README §1: an
        // embedded subpage ships with its parent). Before this lane they were a
        // handoff to a host editor that does not exist, so this form is the first
        // implementation of the two gestures.
        onNewSchedule = { local = local.apply(AutomationUiEvent.OpenEditor(draftFor(null))) },
        onEditSchedule = { id -> local = local.apply(AutomationUiEvent.OpenEditor(draftFor(id))) },
        onLoadMoreHistory = { onHostAction(PdshHostAction.LoadMoreAutomationHistory) },
        onDraftChange = { draft -> local = local.apply(AutomationUiEvent.EditDraft(draft)) },
        onCancelEditor = { local = local.apply(AutomationUiEvent.CancelEditor) },
        onSubmitDraft = {
            // The page's contract: record the attempt first, then hand the draft
            // over only when the re-projected form validates. The form keeps its
            // values either way — this page never claims a write it did not see.
            local = local.apply(AutomationUiEvent.SubmitDraft)
            val draft = local.editor?.draft
            if (draft != null && draft.canSubmit && draft.timingError() == null) {
                // `PdshHostAction`'s vocabulary has one automation write channel:
                // `automation.editor`. A dedicated `automation.save` would be a new
                // sealed subtype in `PdshNavFacts.kt`, whose exhaustive `when` in the
                // app's `HostShell` is outside this lane's edge.
                onHostAction(PdshHostAction.OpenAutomationEditor(draft.id))
            }
        },
    )
}

@Composable
private fun ConfigurationRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onNavigate: (PdshRoute) -> Unit,
) {
    var local by remember { mutableStateOf(ConfigurationLocalState()) }
    NativeConfigurationPage(
        state = ConfigurationScreenState.from(facts.configuration, local),
        onDone = onBack,
        onToggleSection = { id -> local = local.apply(ConfigurationUiEvent.ToggleSection(id)) },
        onOpenWebSearch = { onNavigate(PdshRoute.WebSearch) },
    )
}

/**
 * 命令: the accelerator shell over the host's measured command surface.
 *
 * The facts carry what the host really advertises (`PdshNavFacts.shell`): the
 * accelerators its HELLO named and the limits the bridge enforces. A host that
 * advertises none leaves the snapshot at its empty default, and the page shows
 * its 还没接上命令 body rather than an invented command list.
 *
 * The run form is local UI state; 运行 is the host's gesture, so it is handed
 * over as one [PdshHostAction.RunShellCommand] instead of an empty receiver. A
 * host with no exec entry point answers with that fact, which is the same
 * "never a dead tap" rule every other host-owned action follows.
 *
 * The body is `PDSHShellPage`, the inset-grouped card layout of
 * `PDSHShellPage.swift`; `ToolsScreenState` stays the reducer the JVM tests drive.
 *
 * @param facts the host snapshot; only `shell` is read here.
 * @param onHostAction the 运行 handoff.
 */
@Composable
private fun ToolsRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
) {
    var local by remember { mutableStateOf(ShellLocalState()) }
    val state = ToolsScreenState.from(facts.shell, local)
    PDSHShellPage(
        state = state,
        onSelectCommand = { name -> local = local.apply(ShellUiEvent.SelectCommand(name)) },
        onWorkingDirectoryChange = { value ->
            local = local.apply(ShellUiEvent.SetWorkingDirectory(value))
        },
        onArgumentsChange = { value -> local = local.apply(ShellUiEvent.SetArguments(value)) },
        onRun = {
            onHostAction(
                PdshHostAction.RunShellCommand(
                    command = state.selectedCommand,
                    workingDirectory = state.workingDirectory,
                    arguments = state.arguments,
                ),
            )
        },
        onCancel = {},
        onBack = onBack,
    )
}

/**
 * 工作区文件树 (`FilesPage`) — with a real listing, at last.
 *
 * The L28 audit's finding was that this route handed the page `files = null` while
 * the bytes were already on disk (`:app`'s `filesDir/xl/materialized/workspace`), so
 * no renderer could ever show anything. The route now reads through
 * [PreviewFileSource]: one `files()` call on an IO dispatcher, from the same
 * materialized tree the preview resolves against, so the listing and the preview can
 * never disagree about what exists.
 *
 * `null` keeps its old meaning — the host reported no listing — and the page keeps
 * rendering 等待引擎连接… for it, never 「还没有文件」 (`FilesPage.swift:102`). A root
 * that exists and is empty really is an empty workspace, and the page's own card
 * says exactly that.
 *
 * @param onNavigate the file row's edge: PR #15 replaced the in-page preview sheet
 *   with a pushed surface (`FilesPage.swift:59`), so a row opens
 *   [PdshRoute.Preview] and one back returns to this listing.
 * @param workspaceRoot the host's materialized workspace, or `null` for the
 *   library's default device root (see [PdshNavHost]).
 */
@Composable
private fun FilesRoute(
    onBack: () -> Unit,
    onNavigate: (PdshRoute) -> Unit,
    workspaceRoot: File?,
    backLevel: PdshBackLevel?,
) {
    var local by remember { mutableStateOf(FilesLocalState()) }
    // §5's "one gesture pops one level" applies inside the listing too: in a folder
    // that level is the folder. Without this the gesture closed the whole listing
    // and left the person at the folder page — one level higher than they were.
    RouteBackLevel(backLevel, local.directory.isNotEmpty()) {
        local = local.apply(FilesUiEvent.NavigateUp)
    }
    val source = rememberPreviewSource(workspaceRoot)
    // `null` until the read answers: the page's waiting state, not an empty list.
    var listing by remember(source) { mutableStateOf<List<WorkspaceFile>?>(null) }
    LaunchedEffect(source) { listing = source.files() }
    FilesPage(
        state = FilesScreenState.from(FilesSource(files = listing), local),
        onOpenFolder = { folder -> local = local.apply(FilesUiEvent.OpenFolder(folder)) },
        onOpenFile = { file -> onNavigate(PdshRoute.Preview(file.relativePath)) },
        onQueryChange = { query -> local = local.apply(FilesUiEvent.SetQuery(query)) },
        onNavigateUp = { local = local.apply(FilesUiEvent.NavigateUp) },
        onBack = onBack,
    )
}

/**
 * 文件预览: the single preview face for one workspace path.
 *
 * PR #15's `PreviewSurface` is presented as a push from the files list and as a
 * full-screen cover from the chat page; on Android a pushed route is both, because
 * the surface is one composable and the back gesture is the stack's.
 *
 * The share action is the route's, not the page's: `FileProvider` needs a `Context`
 * and the page must stay a pure function of its state. A `false` answer is handed
 * back to the surface, which says 「没有可以接收这个文件的 App。」 instead of
 * claiming it shared.
 */
@Composable
private fun PreviewRoute(
    route: PdshRoute.Preview,
    onBack: () -> Unit,
    workspaceRoot: File?,
) {
    val context = LocalContext.current
    val source = rememberPreviewSource(workspaceRoot)
    PreviewPage(
        relativePath = route.path,
        source = source,
        onBack = onBack,
        onShare = { file -> PreviewShare.share(context, file) },
    )
}

/**
 * 导出诊断: the diagnostics export page.
 *
 * The host has no export command, so the page opens on its no-snapshot state and
 * the write gesture has no effect; the page never claims a file it did not write.
 */
@Composable
private fun ExportRoute(onBack: () -> Unit) {
    ExportPage(
        state = ExportScreenState.from(ExportFacts()),
        onBack = onBack,
        onWrite = {},
    )
}

@Composable
private fun SettingsRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onNavigate: (PdshRoute) -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    onSettingsWrite: (SettingsWrite) -> Unit,
    backLevel: PdshBackLevel?,
    // 手机权限 is a *tab* of this sheet as well as a route (§1 names both entry
    // points), so the two hosted gestures reach it here too — a tab whose 请求 did
    // nothing would be the same fake control the pushed page no longer has.
    onPhonePermissionRequest: (PhoneCapability) -> Unit = {},
    onPhonePermissionSettings: (PhoneCapability) -> Unit = {},
) {
    // 新会话默认权限 is the settings sheet's own child page in the baseline, so it
    // is a local page swap here, not a route the stack owns.
    var permissionDefaults by remember { mutableStateOf(false) }
    // …which means the gesture had nothing to pop but the sheet: back from the child
    // page closed the whole settings sheet. One level means the child page.
    RouteBackLevel(backLevel, permissionDefaults) { permissionDefaults = false }
    // L55: 关于 and 开发者工具 are the sheet's other two sub-surfaces. They are page
    // swaps for the same reason as 新会话默认权限 — the baseline renders both inside
    // the settings sheet, and neither is a route. Each surface registers its own back
    // level (see `SettingsSurfaces.kt`), so this frame only decides what the body is.
    var surface by remember { mutableStateOf(SettingsSurface.NONE) }
    // The sheet's five tabs are host state (§1: 通用 / 模型 / 插件 / 手机权限 / 预设).
    // Before this lane the tab was never set and the picker's callback was empty, so
    // the four non-通用 bodies were unreachable even though each page already ships
    // its `embedded: true` form and `SettingsPage` declares the `tabContent` slot —
    // the L31 "code exists, not wired" finding. The slot is the page's own seam, so
    // this is wiring only: no page file changes.
    var tab by remember { mutableStateOf(SettingsTab.GENERAL) }
    // The mirrored index reads `settings`, appendix included: the facts are one
    // value, and the host fills the platform rows where it measures them.
    val settings = facts.settings
    if (surface == SettingsSurface.ABOUT) {
        AboutSurface(facts = facts, onBack = { surface = SettingsSurface.NONE }, backLevel = backLevel)
    } else if (surface == SettingsSurface.DEV_TOOLS) {
        DeveloperToolsSurface(
            facts = facts,
            onBack = { surface = SettingsSurface.NONE },
            backLevel = backLevel,
            onAction = onHostAction,
        )
    } else if (permissionDefaults) {
        PermissionDefaultsPage(
            state = PermissionDefaultsState.from(settings.defaultPermissionTier),
            onSelect = { tier -> onSettingsWrite(SettingsWrite.DefaultPermissionTier(tier)) },
            onBack = { permissionDefaults = false },
        )
    } else {
        SettingsPage(
            state = SettingsScreenState.from(settings),
            onNavigate = { destination ->
                when (destination) {
                    SettingsDestination.PERMISSION_DEFAULTS -> permissionDefaults = true
                    SettingsDestination.ABOUT -> surface = SettingsSurface.ABOUT
                    SettingsDestination.DEV_TOOLS -> surface = SettingsSurface.DEV_TOOLS
                    else -> PdshSettingsRows.targetOf(destination)?.let(onNavigate)
                }
            },
            onToggleBackgroundKeepAlive = { value ->
                onSettingsWrite(SettingsWrite.BackgroundKeepAlive(value))
            },
            onSelectBusyEnterMode = { mode -> onSettingsWrite(SettingsWrite.BusyEnter(mode)) },
            onBack = onBack,
            tab = tab,
            onSelectTab = { selected -> tab = selected },
            tabContent = { selected ->
                when (selected) {
                    // 通用 is the sheet's own body (permission defaults above is its
                    // one child page), so the slot is never asked to draw it.
                    SettingsTab.GENERAL -> Unit
                    // 模型: the page the app already routes as a standalone sheet.
                    SettingsTab.MODELS -> ModelProviderRoute(
                        facts = facts,
                        onBack = onBack,
                        onHostAction = onHostAction,
                        embedded = true,
                    )
                    // 插件: 插件配置 inside the body pushes the configuration page.
                    SettingsTab.PLUGINS -> PluginInventoryRoute(
                        facts = facts,
                        onBack = onBack,
                        onNavigate = onNavigate,
                        embedded = true,
                    )
                    SettingsTab.PERMISSIONS ->
                        PhonePermissionsRoute(
                            facts,
                            onBack = onBack,
                            embedded = true,
                            backLevel = backLevel,
                            onRequestPermission = onPhonePermissionRequest,
                            onOpenPermissionSettings = onPhonePermissionSettings,
                        )
                    SettingsTab.PRESETS -> AgentPresetsRoute(onBack = onBack, embedded = true)
                }
            },
        )
    }
}

@Composable
private fun ModelProviderRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    embedded: Boolean = false,
) {
    var local by remember { mutableStateOf(ModelProviderLocalState()) }
    ModelProviderSettingsPage(
        state = ModelProviderScreenState.from(facts.modelProvider, local),
        onAdd = { onHostAction(PdshHostAction.OpenProviderEditor(null)) },
        onEdit = { id -> onHostAction(PdshHostAction.OpenProviderEditor(id)) },
        onToggleSection = { id -> local = local.apply(ModelProviderUiEvent.ToggleSection(id)) },
        onBack = onBack,
        // The settings sheet's 模型 tab draws the same page without its own header.
        embedded = embedded,
        // The page's editor is its own inline form; these four are the writes it
        // cannot perform itself. Each is handed over as one host action and the
        // page still never mutates its facts: the host re-reads them after a save,
        // so a row can only change because the device store changed.
        onStartEngine = { onHostAction(PdshHostAction.RestartRuntime) },
        onSave = { draft -> onHostAction(PdshHostAction.SaveProvider(draft)) },
        onTest = { draft -> onHostAction(PdshHostAction.TestProvider(draft)) },
        onDelete = { id -> onHostAction(PdshHostAction.DeleteProvider(id)) },
    )
}

@Composable
private fun WebSearchRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
) {
    var local by remember { mutableStateOf(WebSearchLocalState()) }
    WebSearchSettingsPage(
        state = WebSearchScreenState.from(facts.webSearch, local),
        onToggle = { enabled -> onHostAction(PdshHostAction.SetWebSearchEnabled(enabled)) },
        onSelectBackend = { id -> onHostAction(PdshHostAction.SelectWebSearchBackend(id)) },
        onToggleBackends = { local = local.apply(WebSearchUiEvent.ToggleBackends) },
        onBack = onBack,
        // 什么时候生效's two engine verbs are real host commands. The write controls stay
        // disabled (`writable = NO` and the host's own reason under them), but without
        // these two the page still drew a live-looking 启动本地引擎 that nobody performs.
        onStartEngine = { onHostAction(PdshHostAction.RestartRuntime) },
        onStopEngine = { onHostAction(PdshHostAction.StopRuntime) },
    )
}

@Composable
private fun McpRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    onNavigate: (PdshRoute) -> Unit,
) {
    var local by remember { mutableStateOf(MCPLocalState()) }
    MCPSettingsPage(
        state = MCPScreenState.from(facts.mcp, local),
        onAdd = { onNavigate(PdshRoute.McpEditor(null)) },
        onEdit = { id -> onNavigate(PdshRoute.McpEditor(id)) },
        onToggleServers = { local = local.apply(MCPUiEvent.ToggleServers) },
        onBack = onBack,
        // 操作's 刷新注册表 (`MCPSettingsPage.swift:87`). This host has no MCP read
        // yet, so the handoff is what it can do and the page keeps the row visible.
        onRefresh = { onHostAction(PdshHostAction.RefreshMcp) },
    )
}

@Composable
private fun McpEditorRoute(
    route: PdshRoute.McpEditor,
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
) {
    // A stored server this host cannot read is 未知, never an empty new-server
    // form: the editor's targetUnreadable flag is the honest statement, and the
    // page owns the wording for it.
    val editorFacts = if (route.serverId != null && facts.mcpEditor.server?.id != route.serverId) {
        facts.mcpEditor.copy(server = null, targetUnreadable = true)
    } else {
        facts.mcpEditor
    }
    var local by remember(route) { mutableStateOf(MCPEditorLocalState()) }
    val state = MCPEditorScreenState.from(editorFacts, local)
    MCPEditorPage(
        state = state,
        onEditName = { value -> local = local.apply(MCPEditorUiEvent.EditName(value), editorFacts) },
        onEditCommand = { value -> local = local.apply(MCPEditorUiEvent.EditCommand(value), editorFacts) },
        onEditUrl = { value -> local = local.apply(MCPEditorUiEvent.EditUrl(value), editorFacts) },
        onSelectTransport = { transport ->
            local = local.apply(MCPEditorUiEvent.SelectTransport(transport), editorFacts)
        },
        onToggleEnabled = { enabled ->
            local = local.apply(MCPEditorUiEvent.ToggleEnabled(enabled), editorFacts)
        },
        onEditTimeout = { value ->
            local = local.apply(MCPEditorUiEvent.EditTimeout(value), editorFacts)
        },
        // 认证头与设备密钥 (`MCPSettingsPage.swift:203`): the rows are local draft
        // state, so the host applies one event per edit like every other field.
        onAddHeader = { local = local.apply(MCPEditorUiEvent.AddHeader, editorFacts) },
        onRemoveHeader = { index ->
            local = local.apply(MCPEditorUiEvent.RemoveHeader(index), editorFacts)
        },
        onEditHeaderName = { index, value ->
            local = local.apply(MCPEditorUiEvent.EditHeaderName(index, value), editorFacts)
        },
        onEditHeaderEnv = { index, value ->
            local = local.apply(MCPEditorUiEvent.EditHeaderEnv(index, value), editorFacts)
        },
        onEditHeaderValue = { index, value ->
            local = local.apply(MCPEditorUiEvent.EditHeaderValue(index, value), editorFacts)
        },
        onSubmit = { draft ->
            // The page's contract: apply Submit first, then persist only when the
            // re-projected state has no errors. This host has no save command, so
            // a valid draft is handed off; the form keeps its values either way.
            val submitted = local.apply(MCPEditorUiEvent.Submit, editorFacts)
            local = submitted
            if (MCPEditorScreenState.from(editorFacts, submitted).errors.isEmpty()) {
                onHostAction(PdshHostAction.SaveMcpServer(draft))
            }
        },
        onBack = onBack,
    )
}

@Composable
private fun RuntimeRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    backLevel: PdshBackLevel?,
) {
    var local by remember { mutableStateOf(RuntimeLocalState()) }
    // 停止引擎's confirmation is this page's own modal, and the page's 取消 already
    // dismisses it: the gesture must dismiss the same thing, not the page.
    RouteBackLevel(backLevel, local.stopConfirmationOpen) {
        local = local.apply(RuntimeUiEvent.CancelStop)
    }
    RuntimeDetailsPage(
        state = RuntimeDetailsScreenState.from(facts.runtime, local),
        onBack = onBack,
        onToggleSection = { id -> local = local.apply(RuntimeUiEvent.ToggleSection(id)) },
        onRequestStop = { local = local.apply(RuntimeUiEvent.RequestStop) },
        onCancelStop = { local = local.apply(RuntimeUiEvent.CancelStop) },
        onConfirmStop = {
            local = local.apply(RuntimeUiEvent.ConfirmStop)
            onHostAction(PdshHostAction.StopRuntime)
        },
        onRestart = { onHostAction(PdshHostAction.RestartRuntime) },
    )
}

@Composable
private fun EgressRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
) {
    var local by remember { mutableStateOf(EgressAllowListLocalState()) }
    EgressAllowListPage(
        state = EgressAllowListScreenState.from(facts.egress, local),
        onBack = onBack,
        onToggleSection = { id -> local = local.apply(EgressAllowListUiEvent.ToggleSection(id)) },
    )
}

@Composable
private fun WorkbenchRoute(
    route: PdshRoute.Workbench,
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    onNavigate: (PdshRoute) -> Unit,
    backLevel: PdshBackLevel?,
) {
    var local by remember(route) {
        mutableStateOf(
            WorkbenchLocalState(
                section = route.section ?: WorkbenchSection.FILES,
                previewPath = route.previewPath,
            ),
        )
    }
    // A workbench entered with a preview already on top (the home screen's write
    // row) has one level inside the sheet: closing that preview, not the sheet.
    RouteBackLevel(backLevel, local.previewPath != null) {
        local = local.apply(WorkbenchUiEvent.ClosePreview)
    }
    NativeWorkbenchPage(
        state = WorkbenchScreenState.from(facts.workbench, local),
        onSelectSection = { section -> local = local.apply(WorkbenchUiEvent.SelectSection(section)) },
        // PR #15 turned "workbench row → in-page preview" into "row → the one
        // preview face" (`ChatPage.swift:389`, `FilesPage.swift:59`), so the row
        // pushes [PdshRoute.Preview] and nothing else. The page's own
        // `WorkbenchPreview` body stays for its contract and its tests, but the app
        // no longer reaches it: one file, one surface, one back.
        //
        // This used to also apply `WorkbenchUiEvent.OpenFile` here. That made the
        // workbench project a preview *behind* the pushed one, so one tap opened two
        // previews and the back gesture landed on the inline copy instead of the
        // list. The route push is the whole edge; `onClosePreview` below still
        // serves a route that was entered with `previewPath` (the home screen's
        // write row), which is a different entry and not a second apply of this tap.
        onOpenFile = { file -> onNavigate(PdshRoute.Preview(file.path)) },
        onClosePreview = { local = local.apply(WorkbenchUiEvent.ClosePreview) },
        onDone = onBack,
    )
}

@Composable
private fun WebSurfaceRoute(
    facts: PdshNavFacts,
    onNavigate: (PdshRoute) -> Unit,
    onHome: () -> Unit,
    onHostAction: (PdshHostAction) -> Unit,
    webSurface: @Composable () -> Unit,
) {
    var local by remember { mutableStateOf(WebSurfaceLocalState()) }
    WebSurfacePage(
        state = WebSurfaceScreenState.from(facts.webSurface, local),
        surface = webSurface,
        onRetry = { onHostAction(PdshHostAction.RetryWebSurface) },
        onOpenModelSettings = { onNavigate(PdshRoute.ModelProvider) },
        onNativeRecovery = onHome,
        onToggleDetails = { local = local.apply(WebSurfaceUiEvent.ToggleDetails) },
    )
}

// ------------------------------------------------------- device connector (L5)

/**
 * 设备连接: the injected connector's devices, and the session each one opens.
 *
 * The connector is the `:domain` in-memory double. iOS ships a `nil` factory
 * (`NativeDeviceDependencies.swift`) and renders 未接入设备连接器; this host has no
 * real device transport either, but it does have the connector seam, so the seam is
 * wired to the double and the page names the source it is reading ("注入的连接器").
 * Nothing here scans, pairs or invents a device: the published list is constructed
 * once, and [DeviceConnectionSource] projects the connector's own state.
 */
@Composable
private fun DeviceConnectionRoute(
    onNavigate: (PdshRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val source = demoDeviceConnectionSource
    var local by remember { mutableStateOf(DeviceConnectionLocalState()) }
    LaunchedEffect(source) { source.start(scope) }
    DisposableEffect(source) { onDispose { source.stop() } }
    val snapshot by source.snapshot.collectAsState()
    NativeDeviceConnectionPage(
        state = DeviceConnectionScreenState.from(snapshot, local),
        onOpen = { device ->
            local = DeviceConnectionLocalState(openingDeviceId = device.deviceId)
            scope.launch {
                val selection = source.open(device.deviceId)
                local = DeviceConnectionLocalState()
                if (selection != null) onNavigate(PdshRoute.DeviceSession(selection.deviceId))
            }
        },
        onForgetMapping = { deviceId -> source.forgetMapping(deviceId) },
        onDone = onBack,
    )
}

/**
 * 设备会话: the device's fixed session, read-only.
 *
 * The monitor reads one history window and follows the live stream in the same
 * scope the page lives in; leaving the page cancels the follow. The paging gesture
 * only forwards [DeviceSessionSource.loadEarlier], which owns the `beforeSeq` window
 * and the stall guard.
 */
@Composable
private fun DeviceSessionRoute(deviceId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val source = remember(deviceId) { demoDeviceSessionSource(deviceId) }
    var local by remember(deviceId) { mutableStateOf(DeviceSessionLocalState()) }
    LaunchedEffect(source) { source.start(scope) }
    DisposableEffect(source) { onDispose { source.stop() } }
    val snapshot by source.snapshot.collectAsState()
    NativeDeviceSessionPage(
        state = DeviceSessionScreenState.from(snapshot, local),
        onLoadEarlier = { scope.launch { source.loadEarlier() } },
        onToggleRow = { rowId -> local = local.toggle(rowId) },
        onBack = onBack,
    )
}

/**
 * 本 session 产物: the artifacts a session's transcript proves, plus the rest.
 *
 * The store is read page by page until it stops offering a cursor, exactly as the
 * baseline's `loadArtifacts` loop does. The open gesture checks the file is still
 * there and otherwise reports the baseline's 文件已不在本机产物存储中; a file that
 * does exist has no preview on this host, and the page says that too instead of
 * silently doing nothing.
 */
@Composable
private fun SessionArtifactsRoute(
    sessionId: String,
    onBack: () -> Unit,
    backLevel: PdshBackLevel?,
) {
    val scope = rememberCoroutineScope()
    var artifacts by remember { mutableStateOf(emptyList<SessionArtifactRef>()) }
    var waiting by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var openFailure by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableStateOf(SessionArtifactsLocalState()) }
    // 产物预览 is a subpage of this route (`SessionArtifactsPage.swift:101`): one
    // gesture closes the preview, the next one leaves the list.
    RouteBackLevel(backLevel, local.preview != null) {
        local = SessionArtifactsLocalState()
    }

    suspend fun load() {
        waiting = true
        try {
            val loaded = mutableListOf<SessionArtifactRef>()
            var cursor = 0
            while (true) {
                val page = demoArtifactSource.list(cursor, ARTIFACT_PAGE_SIZE)
                loaded += page.items
                val next = page.nextCursor ?: break
                cursor = next
            }
            artifacts = loaded
            failure = null
        } catch (t: Throwable) {
            failure = UiStrings.ARTIFACTS_LOAD_FAILED.format(t.message ?: "")
        } finally {
            waiting = false
        }
    }

    LaunchedEffect(sessionId) { load() }
    val state = SessionArtifactsScreenState.from(
        artifacts = artifacts,
        evidence = demoArtifactEvidence,
        local = local,
        waiting = waiting,
        failure = failure,
    )
    SessionArtifactsPage(
        state = SessionArtifactsScreenState.withOpenFailure(state, openFailure),
        onOpen = { artifact ->
            local = SessionArtifactsLocalState(openingId = artifact.artifactId)
            scope.launch {
                val present = runCatching { demoArtifactSource.fileExists(artifact.artifactId) }
                    .getOrDefault(false)
                // A file the store still holds opens the preview subpage
                // (`SessionArtifactsPage.swift:101`, `:296`); one it does not is the
                // baseline's 文件已不在本机产物存储中 alert (`:168`).
                local = if (present) {
                    SessionArtifactsLocalState(preview = SessionArtifactPreview(artifact))
                } else {
                    SessionArtifactsLocalState()
                }
                openFailure = if (present) null else UiStrings.ARTIFACTS_MISSING_FILE
            }
        },
        onDone = onBack,
        onRetry = { scope.launch { load() } },
        onDismissOpenFailure = { openFailure = null },
        onClosePreview = { local = SessionArtifactsLocalState() },
    )
}

/**
 * The demo wiring, one instance per process so a device's saved mapping survives a
 * page swap (the baseline keeps its mapping in the coordinator, not in the page).
 */
private val demoDeviceConnectionSource: DeviceConnectionSource by lazy {
    val endpoint = Endpoint(DEMO_PROTOCOL, DEMO_ADDRESS)
    val manifest = demoDeviceManifest
    DeviceConnectionSource(
        connector = InMemoryConnector(mapOf(endpoint to manifest)),
        publishedDevices = listOf(
            DeviceConnectionEntry(
                deviceId = manifest.deviceId,
                displayName = DEMO_DISPLAY_NAME,
                endpoint = endpoint,
                capabilityCount = manifest.capabilities.size,
            ),
        ),
        provider = InMemoryDeviceSessionProvider(
            assignments = mapOf(manifest.deviceId to InMemoryDeviceSessionTransport.DEMO_SESSION_ID),
        ),
    )
}

/**
 * The manifest the demo device publishes: the fixtures' devicews ESP32, so the
 * connector seam is exercised with a schema-valid document rather than a stub.
 */
private val demoDeviceManifest: DeviceManifest by lazy {
    DeviceManifest(
        deviceId = DEMO_DEVICE_ID,
        protocol = DeviceProtocol(DEMO_PROTOCOL, "1.0"),
        capabilities = linkedMapOf(
            "audio.tts" to CapabilityValue.of("codec" to "opus", "rate" to 24_000),
            "render.pixel" to CapabilityValue.of("w" to 64, "h" to 64, "format" to "rgb565"),
        ),
        limits = DeviceLimits(maxFrameBytes = 65_536, audioKbps = 32),
        auth = DeviceAuth(DeviceAuth.Pairing.BLE_PASSKEY),
    )
}

/** One monitor per page visit; the transport double is shared because it is fixed. */
private fun demoDeviceSessionSource(deviceId: String): DeviceSessionSource = DeviceSessionSource(
    deviceId = deviceId,
    sessionId = InMemoryDeviceSessionTransport.DEMO_SESSION_ID,
    displayName = DEMO_DISPLAY_NAME,
    transport = demoSessionTransport,
)

/** The scripted transcript and its live tail, built once. */
private val demoSessionTransport: InMemoryDeviceSessionTransport by lazy {
    InMemoryDeviceSessionTransport.demo()
}

/** The artifact store double: two files still present, one already gone. */
private val demoArtifactSource: InMemorySessionArtifactSource by lazy {
    InMemorySessionArtifactSource(
        artifacts = listOf(
            SessionArtifactRef("art_weekly_doc", "weekly-report.docx", 48_120),
            SessionArtifactRef("art_budget_sheet", "budget.xlsx", 12_800),
            SessionArtifactRef("art_old_draft", "旧草稿.pdf", 230_400),
        ),
        present = setOf("art_weekly_doc", "art_budget_sheet"),
    )
}

/**
 * The attribution evidence: the demo transcript's own tool rows, projected by the
 * same `:domain` projector the session page uses. `art_weekly_doc` and
 * `art_budget_sheet` are named by successful producing calls, so they land in
 * 本 session; `art_old_draft` is claimed by nobody and lands in 未归属.
 */
private val demoArtifactEvidence: List<SessionArtifactEvidence> by lazy {
    DeviceTranscriptProjector.items(
        InMemoryDeviceSessionTransport.demoRecords(),
        InMemoryDeviceSessionTransport.DEMO_SESSION_ID,
    )
        .filter { it.kind == DeviceItemKind.TOOL_CALL }
        .map { SessionArtifactEvidence.from(it) }
}

/** The fixtures' device id, protocol and address: the demo devices' own values. */
private const val DEMO_DEVICE_ID: String = "esp32-8c4f01"
private const val DEMO_PROTOCOL: String = "devicews"
private const val DEMO_ADDRESS: String = "wss://device.invalid:443/channel"
private const val DEMO_DISPLAY_NAME: String = "客厅网关"

/** The artifact read's page size; the store double caps it as well. */
private const val ARTIFACT_PAGE_SIZE: Int = 50

/** The one scrim opacity the sheet frame uses; no token publishes one. */
private const val SHEET_SCRIM_ALPHA: Float = 0.32f

// ------------------------------------------------------------- sessions route

/**
 * 会话: the sessions family's list face (`SessionsPage`).
 *
 * It is [PdshRoute]'s one subtype declared outside `PdshRoute.kt`. A sealed
 * interface requires its subtypes to share the *package*, not the file, and that
 * file belongs to the shell lane while this page lands; moving this declaration
 * into `PdshRoute.kt` is a cut-and-paste once the shell lane's edits settle.
 */
/**
 * The sessions list.
 *
 * The router has no session-list field in [PdshNavFacts] yet (the probe app reads
 * its rows through `HostSessionsScreen`), so the only host fact this route can
 * hand the page is the folder list; the session list is therefore reported as
 * empty rather than invented. A host that reports folders then renders one section
 * per folder with the baseline's 还没有会话 copy; a host that reports an empty
 * folder list renders the big empty block; a host that reports no folder list at
 * all renders 工作文件夹状态未知, which is the honest reading of an unmeasured list
 * and the state the default `PdshNavFacts()` produces.
 *
 * No clock is read here: with no row there is no timestamp to render, and a clock
 * in the router would make the route unreplayable (`spec/ui/v1/README.md` §3.1).
 * The two write gestures the host cannot perform (create a session, create a
 * folder) are dropped on purpose instead of being reported as success.
 */
@Composable
private fun SessionsRoute(facts: PdshNavFacts, onBack: () -> Unit) {
    var local by remember { mutableStateOf(SessionsLocalState()) }
    val folders = facts.workspaceFolders.folders
    SessionsPage(
        state = SessionsScreenState.from(
            sessions = emptyList<SessionListing>(),
            local = local,
            nowMs = 0L,
            zone = java.time.ZoneOffset.UTC,
            failure = facts.workspaceFolders.failure,
            workspaces = folders?.map { folder ->
                // The host reports a folder's own count, not its membership: the
                // page's group count is the filtered row count, so it stays 0 here.
                SessionWorkspace(id = folder.id, title = folder.name)
            },
            archivedSessions = null,
            newSessionBlock = null,
        ),
        onSelect = {},
        onQueryChange = { query -> local = local.copy(query = query) },
        onRenameStart = {},
        onRenameDraftChange = {},
        onRenameCommit = {},
        onRenameCancel = {},
        onNewSession = {},
        onBack = onBack,
        onToggleWorkspace = { workspace ->
            local = local.copy(
                collapsedWorkspaceIDs = if (workspace.id in local.collapsedWorkspaceIDs) {
                    local.collapsedWorkspaceIDs - workspace.id
                } else {
                    local.collapsedWorkspaceIDs + workspace.id
                },
            )
        },
        onWorkspaceMenuToggle = { workspace ->
            local = local.copy(
                workspaceMenuID = if (local.workspaceMenuID == workspace.id) null else workspace.id,
            )
        },
        onAddWorkspace = {},
        onToggleArchived = { local = local.copy(showArchived = !local.showArchived) },
    )
}

// ------------------------------------------------------ L14: the B-group pages
//
// The settings sheet's 插件 / 手机权限 / 预设 tabs are these same three pages, and
// `SettingsPage` already takes a `tabContent` slot for them
// (`SettingsPage.swift:82-90`). Passing it belongs to the shell lane: the five-tab
// `SettingsPage` is that lane's file, so this lane declares the routes and the
// pages' `embedded` form and leaves the slot wiring to whoever owns the shell.

/**
 * Agent 预设: the preset roster and its read-only document panel.
 *
 * The host has no preset read and no creator-mode command yet, so the roster is
 * `null` — the page's 未知 line, never an invented empty engine — and the two row
 * verbs reproduce the baseline's own failure wording when the engine answers
 * nothing (`NativeAgentPresetsPage.swift:82,95`). The one local value is the
 * document panel, exactly the baseline's `showYAML` / `yaml` pair.
 *
 * @param onBack close the sheet, or the 完成 of the document panel.
 * @param embedded render as the settings sheet's 预设 tab body.
 */
@Composable
private fun AgentPresetsRoute(
    onBack: () -> Unit,
    embedded: Boolean = false,
) {
    var local by remember { mutableStateOf(NativeAgentPresetsLocalState()) }
    // The baseline keeps one `error` for both the roster read and a row verb
    // (`NativeAgentPresetsPage.swift:20`), so one local override is enough.
    var error by remember { mutableStateOf<String?>(null) }
    NativeAgentPresetsPage(
        state = NativeAgentPresetsScreenState.from(
            facts = NativeAgentPresetsFacts(error = error),
            local = local,
        ),
        onBack = onBack,
        // 让 Agent 创建预设 opens creator mode on the engine; this host owns no
        // session command through the router, so the gesture changes nothing.
        onCreate = {},
        onViewConfiguration = { error = NativeAgentPresetsScreenState.NO_DOCUMENT_FAILURE },
        onMakeDefault = { error = NativeAgentPresetsScreenState.NO_WRITABLE_DEFAULT_FAILURE },
        onCloseDocument = { local = local.apply(NativeAgentPresetsUiEvent.CloseDocument) },
        embedded = embedded,
    )
}

/**
 * 内置插件: the read-only loader inventory.
 *
 * The rows come from the host's one plugin read — `ConfigurationFacts`, the same
 * snapshot the 插件配置 page renders — so the inventory and the page it links to
 * can never disagree. A host that reported no snapshot leaves `plugins == null`
 * and the page says 未知.
 *
 * @param facts the host snapshot.
 * @param onBack pop the page.
 * @param onNavigate 插件配置 pushes the configuration page, the baseline's own
 *   `NavigationLink { NativeConfigurationPage() }` (`NativePluginInventoryPage.swift:19`).
 * @param embedded render as the settings sheet's 插件 tab body.
 */
@Composable
private fun PluginInventoryRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    onNavigate: (PdshRoute) -> Unit,
    embedded: Boolean = false,
) {
    var local by remember { mutableStateOf(PluginInventoryLocalState()) }
    val snapshot = facts.configuration.snapshot
    NativePluginInventoryPage(
        state = PluginInventoryScreenState.from(
            facts = PluginInventoryFacts(
                plugins = snapshot?.plugins,
                isLoading = false,
                failure = snapshot?.inventoryFailure ?: facts.configuration.failure,
                managementNotice = snapshot?.pluginManagementMessage,
            ),
            local = local,
        ),
        onOpenConfiguration = { onNavigate(PdshRoute.Configuration) },
        onQueryChange = { query -> local = local.apply(PluginInventoryUiEvent.SetQuery(query)) },
        onToggleRow = { id -> local = local.apply(PluginInventoryUiEvent.ToggleRow(id)) },
        onBack = onBack,
        embedded = embedded,
    )
}

@Composable
private fun PhonePermissionsRoute(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    embedded: Boolean = false,
    backLevel: PdshBackLevel? = null,
    onRequestPermission: (PhoneCapability) -> Unit = {},
    onOpenPermissionSettings: (PhoneCapability) -> Unit = {},
) {
    var local by remember { mutableStateOf(PhonePermissionsLocalState()) }
    // The request confirmation and the disable notice are this page's own modals:
    // back dismisses the modal, exactly like the page's 取消 / 知道了, instead of
    // closing the page under it.
    RouteBackLevel(
        backLevel = backLevel,
        enabled = local.confirmation != null || local.disableNotice != null,
        onBack = {
            local = if (local.confirmation != null) {
                local.apply(PhonePermissionsUiEvent.CancelConfirmation)
            } else {
                local.apply(PhonePermissionsUiEvent.AcknowledgeDisable)
            }
        },
    )
    // The read is the host's, not this route's: the composition root walks its own
    // permissions into `PdshNavFacts.phonePermissions` (`:domain`'s
    // `AndroidHostPermissions` + `AndroidDeviceCapabilities.platformFacts`), and this
    // route only projects. A host that read nothing keeps the default facts
    // (`statuses == null`): every row says 未知 and no control is armed, which is the
    // honest face of an unread permission. The one thing this route must never do is
    // fill that gap with 已允许.
    val state = PhonePermissionsScreenState.from(facts.phonePermissions, local)
    PhonePermissionsPage(
        state = state,
        onDone = onBack,
        onTapCapability = { capability ->
            // The tap performs the row's own action, so no clickable row is inert:
            // 请求 confirms the purpose first, and a row whose only repair is the
            // settings page opens it (the baseline's `activate()` does the same for
            // `.denied`). BLOCKED / ALWAYS_ON rows are not clickable at all.
            val row = state.groups.asSequence().flatMap { it.rows }
                .firstOrNull { it.capability == capability }
            when (row?.action) {
                PhoneCapabilityAction.REQUEST ->
                    local = local.apply(PhonePermissionsUiEvent.Confirm(capability))
                PhoneCapabilityAction.OPEN_SETTINGS -> onOpenPermissionSettings(capability)
                else -> Unit
            }
        },
        onToggleCapability = { capability, value ->
            if (!value) local = local.apply(PhonePermissionsUiEvent.NotifyDisabled(capability))
        },
        // 去系统设置 opens the page that holds this capability's grant: the same host
        // gesture as a 继续 on a row that has no prompt to raise.
        onOpenSettings = onOpenPermissionSettings,
        // 继续 is a real request now, not a dismissal: the purpose has been shown, and
        // the host performs the row's request — a system permission prompt, or the
        // system page that holds the grant when there is no prompt to raise. The
        // confirmation closes first so the platform's own dialog is the only thing on
        // screen; the row's new state comes from the host's next read, never from
        // this callback.
        onConfirmRequest = {
            val capability = local.confirmation
            local = local.apply(PhonePermissionsUiEvent.CancelConfirmation)
            if (capability != null) onRequestPermission(capability)
        },
        onCancelConfirmation = { local = local.apply(PhonePermissionsUiEvent.CancelConfirmation) },
        onAcknowledgeDisable = { local = local.apply(PhonePermissionsUiEvent.AcknowledgeDisable) },
        onDismissDisable = { local = local.apply(PhonePermissionsUiEvent.AcknowledgeDisable) },
        embedded = embedded,
    )
}

// ------------------------------------------------- L13: the three page routes

/**
 * 聊天外观: the appearance / font-size / process-collapse sheet.
 *
 * The values are the host's one preference read — `PdshNavFacts.settings.preferences`,
 * the same snapshot the settings index's 通用 tab draws — and the write capability
 * is the measured `ConfigurationSnapshot.settingsWritable`. This host reports
 * `NO`, so the page disables its pickers and steppers and prints the family's
 * read-only line instead of accepting a change it cannot persist; the five
 * callbacks therefore never fire here, and they are empty rather than pretending
 * a write succeeded.
 */
@Composable
private fun ChatPreferencesRoute(facts: PdshNavFacts, onBack: () -> Unit) {
    val configuration = facts.configuration.snapshot
    ChatPreferencesPage(
        state = ChatPreferencesScreenState.from(
            ChatPreferencesFacts.from(
                preferences = facts.settings.preferences,
                writable = configuration?.settingsWritable ?: FactState.UNKNOWN,
            ),
        ),
        onSelectAppearance = {},
        onSelectCollapseTiming = {},
        onSelectTranscriptView = {},
        onSetTextFontSize = {},
        onSetCodeFontSize = {},
        onDone = onBack,
    )
}

/**
 * 配置文件: the redacted settings document, read-only.
 *
 * `PdshNavFacts` carries no settings document and this host has no share command,
 * so the page renders its 读取不到脱敏配置 state and keeps the share glyph
 * disabled. No document text is invented and no share is claimed.
 */
@Composable
private fun SettingsDocumentRoute(onBack: () -> Unit) {
    NativeSettingsDocumentPage(
        state = SettingsDocumentScreenState.from(SettingsDocumentFacts()),
        onShare = null,
        onDone = onBack,
    )
}

/**
 * 快捷键: the shortcut reference.
 *
 * This host has no shortcut store, so `NativeShortcutsFacts()` reports
 * `overrides == null`: every value renders 未知 instead of the baseline's
 * defaults, and 恢复全部默认 stays disabled. The page's own footnote says the
 * host owns the bindings — the route adds no second claim.
 */
@Composable
private fun ShortcutsRoute(onBack: () -> Unit) {
    NativeShortcutsPage(
        state = NativeShortcutsScreenState.from(NativeShortcutsFacts()),
        onResetAll = {},
        onBack = onBack,
    )
}
