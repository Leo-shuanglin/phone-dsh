// The two settings-sheet surfaces that are not routes: 关于 and 开发者工具.
// SPDX-License-Identifier: MIT
//
// Spec: `spec/ui/v1/README.md` §1. The iOS settings sheet owns both faces:
// `SettingsAboutPage` is the settings family's about page (`NativeShell.swift:897`,
// preview-only after PR #13), and the developer-tools page that hosted
// `MemoryPage` / `ProbesPage` / `BackgroundPage` / `TaskScreenPage` was deleted by
// the same PR (§1 records all four as 不可达). Android implements every one of
// those pages and every one of them had no caller — the L55 audit's zero-call
// list.
//
// They are rendered here instead of on the route stack on purpose:
//
//   * the about page and the developer index are **children of the settings
//     sheet** in the baseline, exactly like 新会话默认权限, which this router
//     already swaps in place (`SettingsRoute`'s `permissionDefaults`);
//   * adding `PdshRoute` subtypes would break `:app`'s exhaustive
//     `hostRouteNote(route: PdshRoute)` when-expression, a file this lane may not
//     edit, for surfaces that are not routes on either platform.
//
// So the reachable path is: drawer → 设置 → 通用 (or the tab that was open) →
// 版本 → 关于, and 设置 → 通用 → 开发者工具 → one page. The contract gate cannot
// see a settings row, which is why every one of these paths is also proven on a
// device (the L55 report carries the uiautomator dumps).
//
// Every page here is handed its honest unread state: `MemoryFacts()` /
// `ProbesFacts()` / `BackgroundFacts()` / `TaskScreenFacts()` /
// `DailyBriefFacts()` / `SessionActivitySource()` report no read, so each page
// renders 未知 / its own empty copy instead of a fabricated measurement. The
// callbacks that would leave the app (run a probe, present the black screen, share
// a brief) are no-ops here: this host has no such command, and a control that
// claims one would be the fake this file exists to avoid.
//
// L72 appended 工作文件夹 and 命令 for a different reason: their iOS entries are
// surfaces of the chat page (the DEBUG preview router, and 工作面板's 命令
// segment), and this host's chat page still has neither. They live here rather
// than in the drawer because iOS's drawer is the session list plus 定时任务 and
// 设置 — the eight Android rows that used to mirror the app map were the defect
// this lane removed.
package com.phonedsh.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.page.AboutFacts
import com.phonedsh.ui.page.AboutLocalState
import com.phonedsh.ui.page.AboutPage
import com.phonedsh.ui.page.AboutScreenState
import com.phonedsh.ui.page.AboutUiEvent
import com.phonedsh.ui.page.BackgroundPage
import com.phonedsh.ui.page.BackgroundScreenState
import com.phonedsh.ui.page.DailyBriefFacts
import com.phonedsh.ui.page.DailyBriefPage
import com.phonedsh.ui.page.DailyBriefScreenState
import com.phonedsh.ui.page.InfoRow
import com.phonedsh.ui.page.MemoryFacts
import com.phonedsh.ui.page.MemoryPage
import com.phonedsh.ui.page.MemoryScreenState
import com.phonedsh.ui.page.PDSHShellPage
import com.phonedsh.ui.page.PageFrame
import com.phonedsh.ui.page.ProbesFacts
import com.phonedsh.ui.page.ProbesPage
import com.phonedsh.ui.page.ProbesScreenState
import com.phonedsh.ui.page.SessionActivityPage
import com.phonedsh.ui.page.SessionActivityScreenState
import com.phonedsh.ui.page.SessionActivitySource
import com.phonedsh.ui.page.ShellLocalState
import com.phonedsh.ui.page.ShellUiEvent
import com.phonedsh.ui.page.TaskScreenFacts
import com.phonedsh.ui.page.TaskScreenLocalState
import com.phonedsh.ui.page.TaskScreenPage
import com.phonedsh.ui.page.TaskScreenScreenState
import com.phonedsh.ui.page.TaskScreenUiEvent
import com.phonedsh.ui.page.ToolsScreenState
import com.phonedsh.ui.page.WorkspaceFoldersLocalState
import com.phonedsh.ui.page.WorkspaceFoldersPage
import com.phonedsh.ui.page.WorkspaceFoldersScreenState
import com.phonedsh.ui.page.WorkspaceFoldersUiEvent
import com.phonedsh.ui.page.apply

/** Which settings-sheet surface replaced the index (and [NONE] is the index). */
internal enum class SettingsSurface {
    /** The 通用 tab's own body. */
    NONE,

    /** 关于: the settings about page. */
    ABOUT,

    /** 开发者工具: the Android-only index of the pages iOS left without an entry. */
    DEV_TOOLS,
}

/**
 * The one page the developer index can open.
 *
 * The last two entries are not orphaned in the baseline the way the first six are
 * (`MemoryPage` … `SessionActivityPage` have no call site at all): iOS reaches
 * 工作文件夹 only from its DEBUG preview router, and 命令 only from the chat
 * page's 右侧边栏 → 工作面板 命令 segment. Neither edge exists on this host —
 * the chat page's page-head menu is the chat lane's file, and `PreviewScreen` is a
 * DEBUG launch router this host does not carry — so the two pages live here while
 * they have no product entry. They are the same pages, rendered from the same
 * facts and local state their routes use (`PdshNavHost`'s `WorkspaceFoldersRoute` /
 * `ToolsRoute`); when those entries land, the rows below are the duplicate to drop.
 */
private enum class DevTool(val label: String) {
    MEMORY(UiStrings.MEMORY_PROBE_TITLE),
    PROBES(UiStrings.PROBES_TITLE),
    BACKGROUND(UiStrings.BACKGROUND_TITLE),
    TASK_SCREEN(UiStrings.TASK_SCREEN_TITLE),
    DAILY_BRIEF(UiStrings.DAILY_BRIEF_TITLE),
    ACTIVITY(UiStrings.ACTIVITY_TITLE),
    WORKSPACE_FOLDERS(UiStrings.WORKSPACE_FOLDERS),
    COMMAND(UiStrings.TOOLS),
}

/**
 * 关于: version, platform and licenses, over the settings sheet.
 *
 * The version comes from the host's own settings read (`SettingsFacts.versionName`)
 * so the index row and the page can never disagree; the platform block stays
 * 未知, because `PdshNavFacts` carries no `Build` snapshot and the page must not
 * invent one.
 *
 * @param facts the host snapshot.
 * @param onBack return to the settings index.
 * @param backLevel the host's inner-level back chain, registered while the page is
 *   open so the system gesture returns to the index instead of closing the sheet.
 */
@Composable
internal fun AboutSurface(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    backLevel: PdshBackLevel? = null,
) {
    // One registration while this surface is the sheet's body: `PdshBackLevel` holds
    // one entry, so the surface owns it and the page never nests a second one.
    SurfaceBackLevel(backLevel, enabled = true) { onBack() }
    var local by remember { mutableStateOf(AboutLocalState()) }
    AboutPage(
        state = AboutScreenState.from(
            facts = AboutFacts(version = facts.settings.versionName),
            local = local,
        ),
        onToggleSection = { id -> local = local.apply(AboutUiEvent.ToggleSection(id)) },
        onBack = onBack,
        // 开源许可 is the iOS-only list this app does not have, and the developer
        // entry is hidden (the index below is the Android entry, not a DEBUG page);
        // `null` renders both rows as unavailable instead of pretending they open.
        onOpenLicenses = null,
        onOpenDeveloper = null,
    )
}

/**
 * 开发者工具: the index of the pages this host cannot reach the way iOS does.
 *
 * Two kinds of row live here: the pages the baseline deleted an entry for
 * (`MemoryPage` … `SessionActivityPage`, see `DevTool`), and the pages whose iOS
 * entry is a surface of the chat page this host has not wired yet (工作文件夹's
 * preview router, 命令's 工作面板 segment). The drawer is not a home for either —
 * iOS's drawer is the session list plus 定时任务 / 设置 and nothing else.
 *
 * The label is the page's own title (`UiStrings`), so the row a person taps and
 * the header they land on are the same word.
 *
 * @param facts the host snapshot; the background page reads its own field from it.
 * @param onBack return to the settings index.
 * @param backLevel the host's inner-level back chain; the sheet-level back leaves
 *   the open page first, then the index.
 * @param onAction the host action channel: the two action rows (revoking the
 *   session's Ubuntu approval, and 工作文件夹's "open this folder's files") and the
 *   command page's run gesture.
 */
@Composable
internal fun DeveloperToolsSurface(
    facts: PdshNavFacts,
    onBack: () -> Unit,
    backLevel: PdshBackLevel? = null,
    onAction: (PdshHostAction) -> Unit = {},
) {
    var open by remember { mutableStateOf<DevTool?>(null) }
    // The revoke row is an action, not a page: it clears the session's Ubuntu
    // approval and leaves the index open, so the person sees the row's own
    // answer instead of landing on a screen with nothing on it.
    var revoked by remember { mutableStateOf<String?>(null) }
    // The surface always owns the one level while it is the sheet's body: a page
    // open means "back to the index", the index means "back to the settings".
    SurfaceBackLevel(backLevel, enabled = true) {
        if (open != null) open = null else onBack()
    }
    val tool = open
    if (tool == null) {
        PageFrame(title = DEV_TOOLS_TITLE, onBack = onBack) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(DevTool.entries.size) { index ->
                    val entry = DevTool.entries[index]
                    InfoRow(
                        title = entry.label,
                        value = null,
                        onClick = { open = entry },
                    )
                }
                item {
                    InfoRow(
                        title = REVOKE_UBUNTU_TITLE,
                        // The value is the host's answer, never a local guess: the
                        // row only says something after the host wrote back.
                        value = revoked,
                        onClick = {
                            revoked = null
                            onAction(PdshHostAction.RevokeUbuntuApproval)
                            revoked = REVOKE_UBUNTU_ANSWER
                        },
                    )
                }
            }
        }
        return
    }
    when (tool) {
        DevTool.MEMORY -> MemoryPage(
            state = MemoryScreenState.from(MemoryFacts()),
            onRunOverlay = {},
            onRunLongSession = {},
            onCancel = {},
            onBack = { open = null },
        )

        DevTool.PROBES -> ProbesPage(
            state = ProbesScreenState.from(ProbesFacts()),
            onBack = { open = null },
            onRunAll = {},
        )

        DevTool.BACKGROUND -> BackgroundPage(
            state = BackgroundScreenState.from(facts.background),
            onBack = { open = null },
            onStart = {},
        )

        DevTool.TASK_SCREEN -> {
            var local by remember { mutableStateOf(TaskScreenLocalState()) }
            TaskScreenPage(
                state = TaskScreenScreenState.from(TaskScreenFacts(), local),
                onBack = { open = null },
                onShowTaskScreen = { _, _ -> },
                onStatusLineChange = { text ->
                    local = local.apply(TaskScreenUiEvent.EditStatusLine(text))
                },
                onBrightnessChange = { value ->
                    local = local.apply(TaskScreenUiEvent.EditMinimumBrightness(value))
                },
            )
        }

        DevTool.DAILY_BRIEF -> DailyBriefPage(
            state = DailyBriefScreenState.from(DailyBriefFacts()),
            onRefresh = {},
            onBack = { open = null },
        )

        DevTool.ACTIVITY -> SessionActivityPage(
            state = SessionActivityScreenState.from(SessionActivitySource()),
            onRefresh = {},
            onToggleError = {},
            onConfirm = {},
            onCancel = {},
            onBack = { open = null },
        )

        // 工作文件夹: the baseline's preview-only folder page. Its one navigation
        // relation (§1: 每行打开 FilesPage) is the documented handoff whose whole
        // meaning is "this folder asked for its file listing"
        // (`PdshHostAction.OpenWorkspaceFiles`); the shell answers it by pushing
        // the files route, because this surface has no route stack of its own.
        DevTool.WORKSPACE_FOLDERS -> {
            var local by remember { mutableStateOf(WorkspaceFoldersLocalState()) }
            WorkspaceFoldersPage(
                state = WorkspaceFoldersScreenState.from(facts.workspaceFolders, local),
                onBack = { open = null },
                onOpenFiles = { folder ->
                    onAction(PdshHostAction.OpenWorkspaceFiles(folder.id))
                },
                onSwitch = { folder -> onAction(PdshHostAction.WorkspaceSwitch(folder.id)) },
                onBeginCreate = { local = local.apply(WorkspaceFoldersUiEvent.BeginCreate) },
                onDraftChange = { text ->
                    local = local.apply(WorkspaceFoldersUiEvent.DraftChange(text))
                },
                onCancelCreate = { local = local.apply(WorkspaceFoldersUiEvent.CancelCreate) },
                onCreate = { onAction(PdshHostAction.WorkspaceCreate(local.draft)) },
                onDeleteRequest = { folder ->
                    local = local.apply(WorkspaceFoldersUiEvent.RequestDelete(folder.id))
                },
                onDeleteConfirm = { folder ->
                    local = local.apply(WorkspaceFoldersUiEvent.CancelDelete)
                    onAction(PdshHostAction.WorkspaceDelete(folder.id))
                },
                onDeleteCancel = { local = local.apply(WorkspaceFoldersUiEvent.CancelDelete) },
            )
        }

        // 命令: the read-only accelerator shell. Its run gesture is the host
        // action it always was (`PdshHostAction.RunShellCommand`), so the page is
        // fully live here — no read is duplicated, nothing is faked.
        DevTool.COMMAND -> {
            var local by remember { mutableStateOf(ShellLocalState()) }
            val state = ToolsScreenState.from(facts.shell, local)
            PDSHShellPage(
                state = state,
                onSelectCommand = { name ->
                    local = local.apply(ShellUiEvent.SelectCommand(name))
                },
                onWorkingDirectoryChange = { value ->
                    local = local.apply(ShellUiEvent.SetWorkingDirectory(value))
                },
                onArgumentsChange = { value ->
                    local = local.apply(ShellUiEvent.SetArguments(value))
                },
                onRun = {
                    onAction(
                        PdshHostAction.RunShellCommand(
                            command = state.selectedCommand,
                            workingDirectory = state.workingDirectory,
                            arguments = state.arguments,
                        ),
                    )
                },
                onCancel = {},
                onBack = { open = null },
            )
        }
    }
}

/**
 * Registers the index→page back level while a page is open.
 *
 * `RouteBackLevel` is private to `PdshNavHost.kt`; this is the same registration
 * for a surface that file does not own. The identity-checked unregister inside
 * [PdshBackLevel] keeps the nesting safe when the index leaves the composition
 * while the settings sheet is still open.
 */
@Composable
private fun SurfaceBackLevel(backLevel: PdshBackLevel?, enabled: Boolean, onBack: () -> Unit) {
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

/** The 开发者工具 header. The baseline deleted this page, so there is no iOS word. */
private const val DEV_TOOLS_TITLE: String = "开发者工具"

/** The revoke row: it names the thing and the scope, not just the verb. */
private const val REVOKE_UBUNTU_TITLE: String = "撤销本地 Ubuntu 授权（本会话）"

/**
 * What the row says after the action is dispatched.
 *
 * It stops short of "revoked": the host owns the gate and this surface cannot see
 * whether one was granted, so the sentence says what was asked, and the next
 * `ubuntu run` is what proves it either way.
 */
private const val REVOKE_UBUNTU_ANSWER: String = "已请求撤销；下一次 ubuntu run 会重新询问"
