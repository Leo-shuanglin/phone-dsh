// Chat page: page head, failure banners, pending cards, transcript, composer dock.
// SPDX-License-Identifier: MIT
//
// Mirror: `examples/probe-app/PhoneDSHProbe/ChatPage.swift` (the page body, the
// transcript chrome, the composer dock), `NativeShell.swift` (`NativeHeaderStyle`
// and `RunStatusDot`) and `NativeMessageRows.swift` (the rows, via `ChatRows.kt`).
// The clause order is the iOS `body`'s: head, then the three failure banners,
// then the pending-card region, then the transcript; the composer is the bottom
// inset, not a stack item (spec §5 row `ime`).
//
// The page owns no screen state. Everything it shows comes from
// [ChatScreenState] (`ChatScreenState.from` is a pure function of the UiState
// sequence, the local UI events and the read-only host facts), including the
// composer lock reason, the pending-answer fold and the banners. The two platform
// observations it does make — IME visibility and the screen height — are reported
// upward (`onImeVisibleChange`) and read for a max-height clamp; neither is stored
// here, so a JVM replay of the state reaches the same screen. The only local
// `remember`s are the ones iOS also keeps as `@State` and that are not engine
// facts: an open menu, the rename dialog's text and one pending access-mode
// confirmation.
//
// Insets (§5 row `insets`): targetSdk 35 forces edge-to-edge, so the page consumes
// the insets explicitly — status bar above the header, navigation bar and IME
// below the composer, horizontal insets on the root. Nothing is drawn under a
// system bar.
//
// Geometry: the page-level tokens (`pageOuterMarginH`, `pageHeaderMinHeight`,
// `pageRowMinHeight`, `pageListRowMinHeight`, `statusDotDiameter`) are read where
// the iOS page reads its literal. Several lengths iOS types have no page-level
// token, so the closest token a signature surface declares is borrowed and marked
// `unverified geometry` at the use site (banner/card/composer geometry, the
// primary-button height reused as the tail padding and the 88 pt empty-state
// offset, the bubble max width reused as the empty-state column width).
//
// The 402 pt parity yardstick (official header 50 / composer 370x98 / content
// column 338 / tool row 24 / flow gaps 6-12) is owned by
// `com.phonedsh.ui.UiMetrics`, not by this page: it describes what the official UI
// shows, while this page draws the iOS app's lengths. Read it when measuring a
// capture, not when changing a layout.
package com.phonedsh.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonedsh.agentcore.JsonArray
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.JsonValue
import com.phonedsh.ui.ChatMetrics
import com.phonedsh.ui.UiMetrics
import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.UiTags
import com.phonedsh.ui.projection.CanonicalJson
import com.phonedsh.ui.state.AccessModeOption
import com.phonedsh.ui.state.ChatEmpty
import com.phonedsh.ui.state.ChatPhase
import com.phonedsh.ui.state.ChatRow
import com.phonedsh.ui.state.ChatScreenState
import com.phonedsh.ui.state.ComposerCommand
import com.phonedsh.ui.state.GoalBarState
import com.phonedsh.ui.state.PendingAnswer
import com.phonedsh.ui.state.PendingQuestionItem
import com.phonedsh.ui.state.PendingQuestionOption
import com.phonedsh.ui.state.PendingFold
import com.phonedsh.ui.state.PendingSlot
import com.phonedsh.ui.state.ReferenceMenu
import com.phonedsh.ui.state.RunStatus
import com.phonedsh.ui.state.SlashMenu
import com.phonedsh.ui.state.TodoDockItem
import com.phonedsh.ui.surface.ApprovalCard
import com.phonedsh.ui.surface.Composer
import com.phonedsh.ui.surface.ComposerMode
import com.phonedsh.ui.surface.FoldedPendingRow
import com.phonedsh.ui.surface.MarkdownBody
import com.phonedsh.ui.theme.LocalUiTokens
import com.phonedsh.ui.theme.LocalUiType
import com.phonedsh.ui.theme.generated.UiTokenNames
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The chat screen.
 *
 * @param state the pure screen state (`ChatScreenState.from`).
 * @param onDraftChange draft edits.
 * @param onSend send the draft.
 * @param onStop stop the running turn.
 * @param onToggleRow expand/collapse a tool entry or a thinking pill.
 * @param onApprove answer a pending permission ask (allow once).
 * @param onDeny decline a pending ask.
 * @param onDismissBanner dismiss the top failure banner.
 * @param onRetry retry the failed turn.
 * @param onOpenSessions open the sessions drawer.
 * @param title the session title; empty falls back to the product name.
 * @param onGoalPause pause the visible goal; `null` hides the action.
 * @param onGoalResume resume a paused/blocked goal; `null` hides the action.
 * @param onGoalClear clear the goal; `null` hides the action.
 * @param onOpenGoalDetail open the goal detail sheet; `null` hides the affordance.
 * @param onImeVisibleChange reports IME visibility so the host can feed it into
 *   `ChatLocalState.imeVisible`; the page never stores it (spec §3.1 rule 3).
 * @param onExpandPendingSlot the user tapped the folded slot while the keyboard is
 *   up; the host records `pendingSlotExpanded = true`.
 * @param onRenameSession the title was edited; the dialog passes the trimmed name
 *   and an empty string means "cancelled edit" (iOS keeps the old title).
 * @param onExportTranscript the page-head menu's 导出对话记录 (a share-sheet
 *   action, spec §5 `share-sheet`; the host owns the platform panel).
 * @param onOpenArtifacts the page-head menu's 产物 (`ChatPage.swift:256`); `null`
 *   hides the row, because the artifacts sheet is scoped to one session and this
 *   page never learns a session id from its state.
 * @param onOpenWorkbench the page-head menu's 右侧边栏 (`ChatPage.swift:260`, the
 *   baseline's `showWorkbench`); `null` hides the row for the same reason.
 * @param onOpenRunStatus the run-status dot and the menu's 运行状态 were tapped
 *   (iOS `RuntimeDetailsPage`, `ChatPage.swift:270`).
 * @param onOpenSessionSettings the page-head menu's 本会话设置.
 * @param onStartEngine the empty state's 启动本地引擎.
 * @param onConfigureProvider the empty state's 去配置模型服务.
 * @param onLoadEarlier the transcript's 加载更早消息.
 * @param onSubmitAnswer submit a question card's answer as the JSON the iOS
 *   question composer builds (`{"<item>": ["a","b"]}`; skipped items use `[]`).
 * @param onDismissQuestion discard/close the active question through its owning
 *   host channel; the page does not infer a denial from this action.
 * @param onRemoveAttachment remove one pending attachment by index.
 * @param onAddImages the composer's 添加图片.
 * @param onAddFiles the composer's 添加文件（写入工作区）.
 * @param onSelectAccessMode pick an access mode by id.
 * @param onSelectModel pick a model and an optional reasoning effort.
 * @param onPickCommand write a `/command` into the draft (iOS `pickCommand`).
 * @param onPickReference write an `@path` or `@session` into the draft.
 * @param onDismissComposerNotice close the attachment or import notice.
 * @param onRetryAccessModes retry the permissions read.
 * @param onReloadCommands retry the command-catalog read.
 * @param onOpenFile a workspace-relative path a row wants to open (the tool row's
 *   file chip and an attachment's file chip). The page never navigates itself: the
 *   host routes it (workbench preview, file list) exactly like `onPickCommand`.
 * @param jobs the session's background jobs, or `null` when the host made no read.
 *   The page-head menu's 后台任务（N） row (`ChatPage.swift:247`) exists only while
 *   this list has rows, so a facts-only host renders no row instead of a sheet that
 *   could only say 读取不到.
 * @param schedules the session's scheduled tasks, same contract (`:252`).
 * @param onOpenJobs opens the 后台任务 sheet (its route); `null` hides the row.
 * @param onOpenSchedules opens the 定时任务 sheet; `null` hides the row.
 */
@Composable
public fun ChatPage(
    state: ChatScreenState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleRow: (String) -> Unit,
    onApprove: (PendingAnswer) -> Unit,
    onDeny: (PendingAnswer) -> Unit,
    onDismissBanner: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    onGoalPause: (() -> Unit)? = null,
    onGoalResume: (() -> Unit)? = null,
    onGoalClear: (() -> Unit)? = null,
    onOpenGoalDetail: (() -> Unit)? = null,
    onImeVisibleChange: ((Boolean) -> Unit)? = null,
    onExpandPendingSlot: () -> Unit = {},
    onRenameSession: (String) -> Unit = {},
    onExportTranscript: () -> Unit = {},
    onOpenArtifacts: (() -> Unit)? = null,
    onOpenWorkbench: (() -> Unit)? = null,
    onOpenRunStatus: () -> Unit = {},
    onOpenDeviceConnections: () -> Unit = {},
    onOpenSessionSettings: () -> Unit = {},
    onStartEngine: () -> Unit = {},
    onRestartEngine: () -> Unit = {},
    onConfigureProvider: () -> Unit = {},
    onLoadEarlier: () -> Unit = {},
    onSubmitAnswer: (PendingAnswer, String) -> Unit = { _, _ -> },
    onDismissQuestion: (PendingAnswer) -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onAddImages: () -> Unit = {},
    onAddFiles: () -> Unit = {},
    onSelectAccessMode: (String) -> Unit = {},
    onSelectModel: (String, String?) -> Unit = { _, _ -> },
    onPickCommand: (String) -> Unit = {},
    onExecuteCommand: ((String) -> Unit)? = null,
    onPickReference: (String) -> Unit = {},
    onDismissComposerNotice: () -> Unit = {},
    onRetryAccessModes: () -> Unit = {},
    onReloadCommands: () -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    jobs: List<ChatJobRow>? = null,
    schedules: List<ChatScheduleRow>? = null,
    onOpenJobs: (() -> Unit)? = null,
    onOpenSchedules: (() -> Unit)? = null,
) {
    val tokens = LocalUiTokens.current
    // `colors.background` is tier: unmeasured (a dynamic system colour), so an
    // unspecified value leaves the platform surface in place instead of an
    // invented sRGB fill.
    val surface = if (tokens.colors.background.isSpecified) {
        Modifier.background(tokens.colors.background)
    } else {
        Modifier
    }
    // A platform observation, reported upward and never stored in the page: the
    // fold itself is ScreenState (`ChatScreenState.pendingFold`).
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val reportIme = onImeVisibleChange
    LaunchedEffect(imeVisible) { reportIme?.invoke(imeVisible) }

    // The rename alert is drawn in the page, NOT because a system dialog would be
    // wrong: a Compose `AlertDialog` + text field never settles under Robolectric
    // (its cursor frame loop keeps the test harness from going idle, measured as a
    // 60 s AppNotIdleException), so this alert is the same content in an in-page
    // scrim. That is a *test-harness* limitation, not an interaction choice — on a
    // device a platform dialog would be fine. The content, the two verbs and the
    // trimming are the iOS alert's (`ChatPage.swift` `.alert("重命名会话")`).
    var renaming by remember { mutableStateOf(false) }
    // iOS `ChatPage.swift:145-154 sessionTitle`: a real session name wins; a
    // conversation that already has a user turn reads 当前会话; only a fresh one
    // reads 新会话. The page used to fall back to the *product name* ("PDSH"), so
    // every real conversation was headed with the app's own name — a word the
    // baseline never paints there. A title that is the store's generic default is
    // not a name (iOS checks the same two strings).
    val headerTitle = remember(title, state.rows) {
        val generic = title.isEmpty() ||
            title == UiStrings.NEW_SESSION ||
            title == UiStrings.SESSION_TITLE_NEW ||
            title.equals("new conversation", ignoreCase = true) ||
            title.equals("conversation", ignoreCase = true)
        if (!generic) {
            title
        } else if (state.rows.any { it is ChatRow.User }) {
            UiStrings.SESSIONS_CURRENT
        } else {
            UiStrings.NEW_SESSION
        }
    }
    Box(modifier = modifier.fillMaxSize().then(surface).testTag(UiTags.CHAT_ROOT)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Edge-to-edge: the vertical bars are consumed by the header and the
            // dock; the horizontal ones are page-wide (landscape nav bar).
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        ChatHeader(
            title = headerTitle,
            onOpenSessions = onOpenSessions,
            onStartRename = { renaming = true },
            onOpenRunStatus = onOpenRunStatus,
            onOpenDeviceConnections = onOpenDeviceConnections,
            hasOnlineDevices = state.hasOnlineDevices,
            onExportTranscript = onExportTranscript,
            onOpenArtifacts = onOpenArtifacts,
            onOpenWorkbench = onOpenWorkbench,
            onOpenSessionSettings = onOpenSessionSettings,
            jobs = jobs,
            schedules = schedules,
            onOpenJobs = onOpenJobs,
            onOpenSchedules = onOpenSchedules,
        )
        // A failure that belongs to one row is rendered under it; every other
        // failure owns the top banner (iOS body: `failedItemID == nil` first).
        if (state.failedItemID == null && state.userFacingError != null) {
            val retryable = state.banners.firstOrNull()?.retryable == true
            TopFailureBanner(
                message = state.userFacingError,
                onRetry = if (retryable) onRetry else null,
                onRestart = if (state.lifecycle?.needsReset == true) onRestartEngine else null,
                onDismiss = { state.banners.firstOrNull()?.let { onDismissBanner(it.id) } },
            )
        }
        if (state.accessModeError != null) {
            NoticeBanner(
                glyph = LOCK_GLYPH,
                message = state.accessModeError,
                retryLabel = UiStrings.RETRY,
                onRetry = onRetryAccessModes,
                tag = UiPageTags.CHAT_BANNER_ACCESS_MODE,
            )
        }
        if (state.commandsError != null) {
            NoticeBanner(
                glyph = COMMAND_GLYPH,
                message = state.commandsError,
                retryLabel = UiStrings.RETRY,
                onRetry = onReloadCommands,
                tag = UiPageTags.CHAT_BANNER_COMMANDS,
            )
        }
        // The card region sits between the banners and the transcript, and never
        // takes more than 40% of the screen (iOS `cardsRegion`).
        // unverified geometry: the approval card's own `cardAreaMaxHeightRatio`
        // borrowed by the page to clamp the region that holds it.
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        // Where the cards sit depends on the transcript, exactly as in the baseline:
        // with rows on screen the strip and the card are page chrome under the head
        // (ios-chat-default.png), and with an empty transcript they hug the dock
        // (ios-chat-permission.png: cards at y 225–677, i.e. just above the composer).
        val cardsAtDock = state.rows.isEmpty() && !state.pending.isEmpty
        if (!cardsAtDock) {
            PendingCardsRegion(
                slot = state.pending,
                fold = state.pendingFold,
                onExpand = onExpandPendingSlot,
                onApprove = onApprove,
                onDeny = onDeny,
                maxHeight = screenHeight * tokens.geometry.cardAreaMaxHeightRatio,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ChatTranscript(
                state = state,
                onToggleRow = onToggleRow,
                onLoadEarlier = onLoadEarlier,
                onStartEngine = onStartEngine,
                onConfigureProvider = onConfigureProvider,
                onRetry = onRetry,
                onOpenFile = onOpenFile,
            )
        }
        if (cardsAtDock) {
            PendingCardsRegion(
                slot = state.pending,
                fold = state.pendingFold,
                onExpand = onExpandPendingSlot,
                onApprove = onApprove,
                onDeny = onDeny,
                maxHeight = screenHeight * tokens.geometry.cardAreaMaxHeightRatio,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // IME + navigation bar: the dock is always above the keyboard.
                .navigationBarsPadding()
                .imePadding(),
        ) {
            ComposerDock(
                state = state,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                onGoalPause = onGoalPause,
                onGoalResume = onGoalResume,
                onGoalClear = onGoalClear,
                onOpenGoalDetail = onOpenGoalDetail,
                onSubmitAnswer = onSubmitAnswer,
                onDismissQuestion = onDismissQuestion,
                onRemoveAttachment = onRemoveAttachment,
                onAddImages = onAddImages,
                onAddFiles = onAddFiles,
                onSelectAccessMode = onSelectAccessMode,
                onSelectModel = onSelectModel,
                onPickCommand = onPickCommand,
                onExecuteCommand = onExecuteCommand,
                onPickReference = onPickReference,
                onDismissNotice = onDismissComposerNotice,
                onReloadCommands = onReloadCommands,
            )
        }
    }
    }
    if (renaming) {
        RenameSessionOverlay(
            title = headerTitle,
            onCancel = { renaming = false },
            onSave = { name ->
                renaming = false
                onRenameSession(name)
            },
        )
    }
}

/**
 * The rename alert's content: the old title pre-filled, 取消 and 保存, and an empty
 * name is not saved (iOS `guard !title.isEmpty`).
 */
@Composable
private fun RenameSessionOverlay(
    title: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    var draft by remember(title) { mutableStateOf(title) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiPageTags.CHAT_RENAME_OVERLAY),
        contentAlignment = Alignment.Center,
    ) {
        // The dimmed area is the tap-outside-to-cancel target. It is a sibling of
        // the dialog, not its wrapper, so the dialog's title stays a title instead
        // of becoming the scrim's click label (iOS `.alert`).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.colors.primary.copy(alpha = SCRIM_ALPHA))
                .clickable(role = Role.Button, onClick = onCancel),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = tokens.geometry.pageOuterMarginH)
                .fillMaxWidth()
                .background(tokens.colors.input, RoundedCornerShape(tokens.radius.card))
                .semantics { contentDescription = UiStrings.RENAME_DIALOG_TITLE }
                .padding(tokens.geometry.cardPadding),
            verticalArrangement = Arrangement.spacedBy(tokens.geometry.bannerGap),
        ) {
            BasicText(
                text = UiStrings.RENAME_DIALOG_TITLE,
                style = type.headline.copy(color = tokens.colors.primary),
                modifier = Modifier.semantics { heading() },
            )
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = type.secondary.copy(color = tokens.colors.primary),
                cursorBrush = SolidColor(tokens.colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiPageTags.CHAT_RENAME_FIELD),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            BasicText(
                                text = UiStrings.RENAME_FIELD,
                                style = type.secondary.copy(color = tokens.colors.tertiary),
                            )
                        }
                        inner()
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(tokens.geometry.bannerGap, Alignment.End),
            ) {
                BasicText(
                    text = UiStrings.CANCEL,
                    style = type.secondary.copy(color = tokens.colors.secondary),
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onCancel)
                        .testTag(UiPageTags.CHAT_RENAME_CANCEL)
                        .padding(tokens.spacing.iconText),
                )
                BasicText(
                    text = UiStrings.RENAME_SAVE,
                    style = type.secondary.copy(color = tokens.colors.accent),
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            val name = draft.trim()
                            if (name.isNotEmpty()) onSave(name)
                        }
                        .testTag(UiPageTags.CHAT_RENAME_SAVE)
                        .padding(tokens.spacing.iconText),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Page head (ChatPage.swift `chatHeader`, NativeShell.swift `NativeHeaderStyle`)
// ---------------------------------------------------------------------------

/**
 * The iOS ChatPage header: drawer, centred title, device entry and menu.
 */
@Composable
private fun ChatHeader(
    title: String,
    onOpenSessions: () -> Unit,
    onStartRename: () -> Unit,
    onOpenRunStatus: () -> Unit,
    onOpenDeviceConnections: () -> Unit,
    hasOnlineDevices: Boolean?,
    onExportTranscript: () -> Unit,
    onOpenSessionSettings: () -> Unit,
    onOpenArtifacts: (() -> Unit)? = null,
    onOpenWorkbench: (() -> Unit)? = null,
    jobs: List<ChatJobRow>? = null,
    schedules: List<ChatScheduleRow>? = null,
    onOpenJobs: (() -> Unit)? = null,
    onOpenSchedules: (() -> Unit)? = null,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    var menuOpen by remember { mutableStateOf(false) }

    // iOS ChatPage centers the title with a 104pt reserve on each side. The
    // control row is drawer 32x44, then device 36x44 + menu 44x44 with 4pt gap.
    Column(modifier = Modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(UiMetrics.headerHeight),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = tokens.geometry.pageOuterMarginH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatMetrics.CHAT_HEADER_GROUP_GAP_PT.dp),
        ) {
            HeaderAction(
                description = UiStrings.HEADER_DRAWER,
                onClick = onOpenSessions,
                modifier = Modifier.size(width = ChatMetrics.CHAT_DRAWER_WIDTH_PT.dp, height = tokens.geometry.pageHeaderMinHeight)
                    .testTag(UiPageTags.CHAT_HEADER_DRAWER),
            )
            Spacer(Modifier.weight(1f))
            DeviceHeaderAction(
                hasOnlineDevices = hasOnlineDevices,
                onClick = onOpenDeviceConnections,
                modifier = Modifier.testTag(CHAT_HEADER_DEVICE_TAG),
            )
        // The menu's rows are the baseline's (`ChatPage.swift:240-286`), in its
        // order: 产物, 右侧边栏, 导出对话记录, 运行状态, 本会话设置. The three
        // conditional rows are session-scoped destinations; `null` keeps them off
        // the menu instead of drawing a row that cannot go anywhere.
        val artifacts = onOpenArtifacts
        val workbench = onOpenWorkbench
        Box {
            HeaderMenuAction(
                glyph = MORE_GLYPH,
                onClick = { menuOpen = true },
                modifier = Modifier
                    .size(width = ChatMetrics.CHAT_HEADER_MENU_WIDTH_PT.dp, height = tokens.geometry.pageHeaderMinHeight)
                    .testTag(UiPageTags.CHAT_HEADER_MENU)
                    // `ChatPage.swift:296`: the `⋯` carries an `.accessibilityLabel`
                    // and draws a mark; the baseline's menu has no visible word. The
                    // name stays a content description (a screen reader must still
                    // announce the control) and is declared here, at the call site,
                    // because it is not a visible action label.
                    .semantics { contentDescription = UiStrings.HEADER_MORE },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // iOS `ChatPage.swift:247` / `:252`: the two session-activity rows
                // exist only while their list has rows, and the label carries the
                // count (`后台任务（2）`). A host that reported nothing renders no
                // row at all — the sheets are then unreachable from this menu, and
                // their own 读取不到 body is the honest answer if something else
                // opens them. The count makes the label data, not a fixed name.
                val jobRows = jobs
                if (jobRows != null && jobRows.isNotEmpty() && onOpenJobs != null) {
                    DropdownMenuItem(
                        text = { Text("${UiStrings.CHAT_JOBS_TITLE}（${jobRows.size}）") },
                        onClick = {
                            menuOpen = false
                            onOpenJobs()
                        },
                    )
                }
                val scheduleRows = schedules
                if (scheduleRows != null && scheduleRows.isNotEmpty() && onOpenSchedules != null) {
                    DropdownMenuItem(
                        text = { Text("${UiStrings.CHAT_SCHEDULES_TITLE}（${scheduleRows.size}）") },
                        onClick = {
                            menuOpen = false
                            onOpenSchedules()
                        },
                    )
                }
                if (artifacts != null) {
                    DropdownMenuItem(
                        text = { Text(UiStrings.HEADER_ARTIFACTS) },
                        onClick = {
                            menuOpen = false
                            artifacts()
                        },
                    )
                }
                if (workbench != null) {
                    DropdownMenuItem(
                        text = { Text(UiStrings.HEADER_WORKBENCH) },
                        onClick = {
                            menuOpen = false
                            workbench()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(UiStrings.HEADER_EXPORT) },
                    onClick = {
                        menuOpen = false
                        onExportTranscript()
                    },
                )
                DropdownMenuItem(
                    text = { Text(UiStrings.HEADER_RUN_STATUS) },
                    onClick = {
                        menuOpen = false
                        onOpenRunStatus()
                    },
                )
                DropdownMenuItem(
                    text = { Text(UiStrings.HEADER_SESSION_SETTINGS) },
                    onClick = {
                        menuOpen = false
                        onOpenSessionSettings()
                    },
                )
            }
        }
        }
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ChatMetrics.CHAT_HEADER_TITLE_RESERVE_PT.dp)
                .height(tokens.geometry.pageHeaderMinHeight).align(Alignment.Center)
                .clickable(role = Role.Button) { if (title.isNotEmpty()) onStartRename() }
                .semantics { contentDescription = title }
                .testTag(UiPageTags.CHAT_HEADER_TITLE),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = title,
                style = type.headline.copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(tokens.stroke.hairline)
            .background(tokens.colors.border),
    )
    }
}

/**
 * One header control: its glyph, the header's 50 pt floor and its accessibility label.
 *
 * The drawer has no word on the baseline page (`accessibilityLabel` only), so the
 * control's name lives in `description`.
 */
@Composable
private fun HeaderAction(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tokens.geometry.pageHeaderMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = tokens.spacing.iconText),
        contentAlignment = Alignment.Center,
    ) {
        DrawerGlyph()
    }
}

/**
 * The page-head menu trigger: the `⋯` mark, in the accent ink.
 *
 * It draws a mark and nothing else, so it takes no label: the baseline's `Menu`
 * carries an `.accessibilityLabel` but no visible word (`ChatPage.swift:296`), and
 * the header's accessible name is therefore applied by the caller as a content
 * description rather than passed as this helper's first parameter. That is also
 * what the page-structure contract records — the entry is `menu-240` with
 * `label: null` — so a screen-reader string must not pose as a visible action.
 *
 * @param glyph what is drawn.
 * @param onClick opens the menu.
 * @param modifier the caller's tag, accessible name and placement.
 */
@Composable
private fun HeaderMenuAction(
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tokens.geometry.pageHeaderMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = tokens.spacing.iconText),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = type.secondary.copy(color = tokens.colors.secondary),
        )
    }
}

@Composable
private fun DeviceHeaderAction(
    hasOnlineDevices: Boolean?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    Box(
        modifier = modifier.size(width = ChatMetrics.CHAT_DEVICE_ENTRY_WIDTH_PT.dp, height = tokens.geometry.pageHeaderMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (hasOnlineDevices == true) "Device，有在线设备" else "Device"
            },
        contentAlignment = Alignment.Center,
    ) {
        DevicePhoneGlyph()
        if (hasOnlineDevices == true) {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(
                    top = ChatMetrics.CHAT_DEVICE_STATUS_INSET_PT.dp,
                    end = ChatMetrics.CHAT_DEVICE_STATUS_INSET_PT.dp,
                ).size(ChatMetrics.CHAT_DEVICE_STATUS_DOT_PT.dp).clip(CircleShape)
                    .background(tokens.colors.success)
                    .border(ChatMetrics.CHAT_DEVICE_STATUS_BORDER_PT.dp, tokens.colors.page, CircleShape),
            )
        }
    }
}

@Composable
private fun DrawerGlyph() {
    val tokens = LocalUiTokens.current
    androidx.compose.foundation.Canvas(Modifier.size(ChatMetrics.CHAT_DRAWER_ICON_PT.dp)) {
        val stroke = ChatMetrics.CHAT_DRAWER_ICON_STROKE_TENTH_PT.dp.toPx()
        val left = ChatMetrics.CHAT_DRAWER_ICON_INSET_PT.dp.toPx()
        val right = size.width - ChatMetrics.CHAT_DRAWER_ICON_INSET_PT.dp.toPx()
        val top = ChatMetrics.CHAT_DRAWER_ICON_INSET_PT.dp.toPx()
        val bottom = size.height - ChatMetrics.CHAT_DRAWER_ICON_INSET_PT.dp.toPx()
        drawRoundRect(
            color = tokens.colors.secondary,
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(ChatMetrics.CHAT_DRAWER_ICON_CORNER_TENTH_PT.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        drawLine(
            tokens.colors.secondary,
            androidx.compose.ui.geometry.Offset(size.width * 0.38f, top),
            androidx.compose.ui.geometry.Offset(size.width * 0.38f, bottom),
            stroke,
        )
    }
}

@Composable
private fun DevicePhoneGlyph() {
    val tokens = LocalUiTokens.current
    androidx.compose.foundation.Canvas(Modifier.size(width = ChatMetrics.CHAT_DEVICE_ICON_BOX_WIDTH_PT.dp, height = ChatMetrics.CHAT_DEVICE_ICON_BOX_HEIGHT_PT.dp)) {
        val stroke = ChatMetrics.CHAT_DEVICE_BODY_STROKE_TENTH_PT.dp.toPx()
        val left = (size.width - ChatMetrics.CHAT_DEVICE_BODY_WIDTH_PT.dp.toPx()) / 2f
        drawRoundRect(
            color = tokens.colors.secondary,
            topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
            size = androidx.compose.ui.geometry.Size(ChatMetrics.CHAT_DEVICE_BODY_WIDTH_PT.dp.toPx(), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(ChatMetrics.CHAT_DEVICE_BODY_CORNER_PT.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
        )
        drawLine(
            tokens.colors.secondary,
            androidx.compose.ui.geometry.Offset(left + ChatMetrics.CHAT_DEVICE_SPEAKER_LEFT_PT.dp.toPx(), size.height - ChatMetrics.CHAT_DEVICE_SPEAKER_BOTTOM_PT.dp.toPx()),
            androidx.compose.ui.geometry.Offset(left + ChatMetrics.CHAT_DEVICE_SPEAKER_RIGHT_PT.dp.toPx(), size.height - ChatMetrics.CHAT_DEVICE_SPEAKER_BOTTOM_PT.dp.toPx()),
            stroke,
        )
    }
}

/**
 * The one run-status indicator (iOS `RunStatusDot`): an 8 pt dot plus its word.
 *
 * iOS maps parked to amber, started to green and stopped to the tertiary label;
 * those literals are `colors.warning`, `colors.success` and `colors.tertiary`.
 */
@Composable
private fun RunStatusDot(
    runStatus: RunStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val (ink, label) = when (runStatus) {
        RunStatus.STARTED -> tokens.colors.success to UiStrings.RUN_STATUS_STARTED
        RunStatus.PARKED -> tokens.colors.warning to UiStrings.RUN_STATUS_PARKED
        RunStatus.STOPPED -> tokens.colors.tertiary to UiStrings.RUN_STATUS_STOPPED
    }
    Row(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .defaultMinSize(minHeight = UiMetrics.headerHeight)
            .padding(horizontal = tokens.spacing.iconText),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.copyGap),
    ) {
        Box(
            modifier = Modifier
                .size(tokens.geometry.statusDotDiameter)
                .clip(CircleShape)
                .background(ink),
        )
        BasicText(
            text = label,
            style = type.caption.copy(color = tokens.colors.secondary),
        )
    }
}

// ---------------------------------------------------------------------------
// Failure banners (ChatPage.swift `errorBanner`, `accessModeErrorBanner`,
// `commandErrorBanner`)
// ---------------------------------------------------------------------------

/**
 * The top failure banner: the message in warning ink, an optional 重试 and the
 * xmark that dismisses it. A per-message failure never comes here; it is drawn
 * under its own row (iOS `failureLine`).
 */
@Composable
private fun TopFailureBanner(
    message: String,
    onRetry: (() -> Unit)?,
    onRestart: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.FAIL_LOUD_BANNER)
            .padding(
                horizontal = tokens.geometry.bannerInsetH,
                vertical = tokens.geometry.bannerInsetV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.geometry.bannerGap),
    ) {
        BasicText(
            text = message,
            style = type.secondary.copy(color = tokens.colors.warning),
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            BasicText(
                text = UiStrings.RETRY,
                style = type.secondary.copy(color = tokens.colors.accent),
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onRetry)
                    .testTag(UiTags.BANNER_RETRY)
                    .padding(tokens.spacing.iconText),
            )
        }
        if (onRestart != null) {
            BasicText(
                text = UiStrings.ENGINE_RESTART,
                style = type.secondary.copy(color = tokens.colors.accent),
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onRestart)
                    .testTag(CHAT_RESTART_ENGINE_TAG)
                    .padding(tokens.spacing.iconText),
            )
        }
        BasicText(
            text = DISMISS_GLYPH,
            style = type.caption.copy(color = tokens.colors.secondary),
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = UiStrings.CLOSE_HINT }
                .testTag(UiTags.BANNER_DISMISS)
                .padding(tokens.spacing.iconText),
        )
    }
}

/** The accessory-read banner: a glyph, the message and a retry verb. */
@Composable
private fun NoticeBanner(
    glyph: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    tag: String,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.geometry.toolDetailVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.codeToolbar),
    ) {
        BasicText(text = glyph, style = type.caption.copy(color = tokens.colors.warning))
        BasicText(
            text = message,
            style = type.caption.copy(color = tokens.colors.secondary),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = retryLabel,
            style = type.caption.copy(color = tokens.colors.accent),
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(tokens.spacing.iconText),
        )
    }
}

// ---------------------------------------------------------------------------
// Pending cards (ChatPage.swift `cardsRegion` + `QuestionCard`)
// ---------------------------------------------------------------------------

/**
 * The pending-permission region. Questions are routed to the composer seat, as in
 * iOS `questionTakeover`; this region holds only permission approvals.
 *
 * The older "还有 1 个待确认" fold line is gone on purpose: the baseline's
 * `cardsRegion` now holds the cards themselves (`pendingCardCount` counts the one
 * permission ask, `ChatPage.swift:840-842`), so a line standing in for a card has
 * no counterpart to mirror — and the contract stopped listing it as an action.
 * The second ask is drawn instead of folded, so nothing is hidden behind a row the
 * baseline no longer has.
 */
@Composable
private fun PendingCardsRegion(
    slot: PendingSlot,
    fold: PendingFold,
    onExpand: () -> Unit,
    onApprove: (PendingAnswer) -> Unit,
    onDeny: (PendingAnswer) -> Unit,
    maxHeight: Dp,
) {
    // Questions own the composer seat (ChatPage.swift `questionTakeover`); only
    // permissions remain in the page card region.
    val approvals = (listOfNotNull(slot.primary) + slot.folded).filter { it.kind != QUESTION }
    if (approvals.isEmpty()) return
    val primary = approvals.firstOrNull()
    val count = approvals.size + slot.hidden
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.ANSWER_SLOT),
    ) {
        if (fold == PendingFold.FOLDED) {
            FoldedPendingRow(
                label = UiStrings.PENDING_SLOT.format(count),
                onExpand = onExpand,
                modifier = Modifier.testTag(UiPageTags.CHAT_PENDING_FOLD),
            )
        } else {
            // No region-level clamp: iOS lets the slot grow to the two cards' own
            // heights (ios-chat-permission.png shows both whole, 452 pt) and each card
            // scrolls inside `maxHeight` by itself. Clamping the region too cut the
            // second card in half.
            Column(modifier = Modifier.fillMaxWidth()) {
                if (primary != null) {
                    ApprovalCard(
                        title = primary.title,
                        subtitle = primary.subtitle,
                        primaryLabel = UiStrings.ALLOW_ONCE,
                        onPrimary = { onApprove(primary) },
                        secondaryLabel = UiStrings.DENY,
                        onSecondary = { onDeny(primary) },
                        maxHeight = maxHeight,
                    )
                }
                approvals.drop(1).forEach { ask ->
                    ApprovalCard(
                        title = ask.title,
                        subtitle = ask.subtitle,
                        primaryLabel = UiStrings.ALLOW_ONCE,
                        onPrimary = { onApprove(ask) },
                        secondaryLabel = UiStrings.DENY,
                        onSecondary = { onDeny(ask) },
                        maxHeight = maxHeight,
                    )
                }
            }
        }
    }
}

/**
 * The question card (iOS `QuestionCard`): one option row per choice, a free-text
 * field, an optional "可多选" hint and the submit key, which stays disabled until
 * every question has an answer.
 *
 * The selections are widget-local, exactly like the iOS `@State` fields: they are
 * not engine facts and they leave with the card.
 */
@Composable
private fun QuestionCard(
    question: PendingAnswer,
    maxHeight: Dp,
    onSubmit: (String) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    var single by remember { mutableStateOf<String?>(null) }
    var multiple by remember { mutableStateOf(setOf<String>()) }
    var custom by remember { mutableStateOf("") }
    val chosen = if (question.multiple) multiple.isNotEmpty() else single != null
    val canSubmit = chosen || custom.trim().isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.geometry.cardOuterMarginH,
                vertical = tokens.geometry.cardOuterMarginV,
            )
            .testTag(UiTags.APPROVAL_CARD)
            .background(tokens.colors.selector, RoundedCornerShape(tokens.radius.card))
            .padding(tokens.geometry.cardPadding),
        verticalArrangement = Arrangement.spacedBy(tokens.geometry.cardPadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(tokens.geometry.bannerGap)) {
                BasicText(
                    text = UiStrings.QUESTION_CARD_TITLE,
                    style = type.headline.copy(color = tokens.colors.primary),
                    modifier = Modifier.semantics { heading() },
                )
                if (question.subtitle.isNotEmpty()) {
                    BasicText(
                        text = question.subtitle,
                        style = type.secondary.copy(color = tokens.colors.secondary),
                    )
                }
                question.options.forEach { option ->
                    val selected = if (question.multiple) option in multiple else single == option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
                            .clickable(role = Role.Button) {
                                if (question.multiple) {
                                    multiple = if (selected) multiple - option else multiple + option
                                } else {
                                    single = option
                                    custom = ""
                                }
                            }
                            .semantics(mergeDescendants = true) {
                                contentDescription = option
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
                    ) {
                        BasicText(
                            text = if (selected) SELECTED_GLYPH else EMPTY_GLYPH,
                            style = type.secondary.copy(color = tokens.colors.accent),
                        )
                        BasicText(
                            text = option,
                            style = type.secondary.copy(
                                color = if (selected) tokens.colors.accent else tokens.colors.primary,
                            ),
                        )
                    }
                }
                BasicTextField(
                    value = custom,
                    onValueChange = { value ->
                        custom = value
                        if (value.isNotEmpty() && !question.multiple) single = null
                    },
                    textStyle = type.secondary.copy(color = tokens.colors.primary),
                    cursorBrush = SolidColor(tokens.colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiPageTags.CHAT_QUESTION_ANSWER),
                    decorationBox = { inner ->
                        Box {
                            if (custom.isEmpty()) {
                                BasicText(
                                    text = if (question.options.isEmpty()) {
                                        UiStrings.QUESTION_INPUT_ANSWER
                                    } else {
                                        UiStrings.QUESTION_CUSTOM_ANSWER
                                    },
                                    style = type.secondary.copy(color = tokens.colors.tertiary),
                                )
                            }
                            inner()
                        }
                    },
                )
                if (question.multiple) {
                    BasicText(
                        text = UiStrings.QUESTION_MULTIPLE_HINT,
                        style = type.caption.copy(color = tokens.colors.secondary),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = tokens.geometry.primaryButtonHeight)
                .background(
                    color = if (canSubmit) tokens.colors.ctaOverride else tokens.colors.border,
                    shape = RoundedCornerShape(tokens.radius.card),
                )
                .clickable(enabled = canSubmit, role = Role.Button) {
                    onSubmit(answerJson(question, single, multiple, custom))
                }
                .testTag(UiPageTags.CHAT_QUESTION_SUBMIT),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = UiStrings.QUESTION_SUBMIT,
                style = type.headline.copy(
                    color = if (canSubmit) tokens.colors.onAccent else tokens.colors.tertiary,
                ),
            )
        }
    }
}

/**
 * The question composer takes the bottom seat while a real question is pending,
 * matching ChatPage.swift `questionTakeover`. It only renders fields present in
 * the host projection; absent progress/availability facts remain unknown.
 */
@Composable
private fun QuestionTakeover(
    question: PendingAnswer,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    maxHeight: Dp,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val items = remember(question.requestId, question.questionItems, question.options, question.subtitle) {
        question.questionItems.ifEmpty {
            listOf(
                PendingQuestionItem(
                    id = ANSWER_KEY,
                    prompt = question.subtitle,
                    options = question.options.map { PendingQuestionOption(it) },
                    multiple = question.multiple,
                ),
            )
        }
    }
    val recommendedDrafts = remember(items) {
        items.mapNotNull { q ->
            val first = q.options.firstOrNull()?.label
            val recommendation = q.recommendedWireLabel ?: first?.takeIf(::hasRecommendedSuffix)
            recommendation?.let { q.id to QuestionDraft(selected = listOf(it)) }
        }.toMap()
    }
    var index by remember(question.requestId) {
        mutableStateOf(question.initialProgress?.index?.coerceIn(0, (items.size - 1).coerceAtLeast(0)) ?: 0)
    }
    var drafts by remember(question.requestId) {
        mutableStateOf(
            question.initialProgress?.drafts?.mapValues { (_, selected) -> QuestionDraft(selected = selected) }
                ?: recommendedDrafts,
        )
    }
    var minimized by remember(question.requestId) { mutableStateOf(false) }
    var validationError by remember(question.requestId) { mutableStateOf<String?>(null) }
    val item = items.getOrNull(index) ?: return
    val draft = drafts[item.id] ?: QuestionDraft()
    val busy = question.submitting == true
    val last = index == items.lastIndex
    val answered = draft.selected.isNotEmpty() || draft.custom.trim().isNotEmpty()
    val canRespond = question.channelAvailable != false

    fun update(next: QuestionDraft) {
        drafts = drafts + (item.id to next)
        validationError = null
    }
    fun submit(sourceDrafts: Map<String, QuestionDraft> = drafts) {
        if (busy) return
        val missing = items.indexOfFirst { q ->
            val answer = sourceDrafts[q.id] ?: QuestionDraft()
            !answer.skipped && answer.selected.isEmpty() && answer.custom.trim().isEmpty()
        }
        if (missing >= 0) {
            index = missing
            validationError = "请先完成这道问题。"
            return
        }
        val values = items.associate { q ->
            val answer = sourceDrafts[q.id] ?: QuestionDraft()
            val selected = if (answer.skipped) emptyList() else {
                val custom = answer.custom.trim()
                val choices = if (custom.isNotEmpty() && !q.multiple) emptyList() else answer.selected
                choices + listOfNotNull(custom.takeIf(String::isNotEmpty))
            }
            q.id to JsonArray(selected.map(::JsonString))
        }
        onSubmit(CanonicalJson.write(JsonObject(values)))
    }
    fun skip() {
        if (busy) return
        val skippedDrafts = drafts + (item.id to QuestionDraft(skipped = true))
        drafts = skippedDrafts
        validationError = null
        if (!last) index += 1 else submit(skippedDrafts)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.geometry.composerOuterMarginH)
            .padding(bottom = ChatMetrics.COMPOSER_CONTROL_BOTTOM_PT.dp)
            .testTag(QUESTION_TAKEOVER_TAG)
            .shadow(ChatMetrics.QUESTION_CARD_SHADOW_PT.dp, RoundedCornerShape(ChatMetrics.QUESTION_CARD_RADIUS_PT.dp))
            .background(tokens.colors.selector, RoundedCornerShape(ChatMetrics.QUESTION_CARD_RADIUS_PT.dp))
            .border(tokens.stroke.hairline, tokens.colors.borderLight, RoundedCornerShape(ChatMetrics.QUESTION_CARD_RADIUS_PT.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = ChatMetrics.QUESTION_HEADER_LEADING_PT.dp,
                top = ChatMetrics.QUESTION_HEADER_TOP_PT.dp,
                end = ChatMetrics.QUESTION_HEADER_TRAILING_PT.dp,
                bottom = if (minimized) ChatMetrics.QUESTION_HEADER_MINIMIZED_BOTTOM_PT.dp
                else ChatMetrics.QUESTION_HEADER_EXPANDED_BOTTOM_PT.dp,
            ),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ChatMetrics.QUESTION_HEADER_COPY_ACTION_GAP_PT.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ChatMetrics.QUESTION_HEADER_STACK_GAP_PT.dp),
            ) {
                item.header?.takeIf(String::isNotBlank)?.let {
                    BasicText(text = it, style = type.caption.copy(fontSize = ChatMetrics.QUESTION_EYEBROW_SIZE_PT.sp, color = tokens.colors.tertiary))
                }
                BasicText(
                    text = item.prompt,
                    style = type.secondary.copy(fontSize = ChatMetrics.QUESTION_PROMPT_SIZE_PT.sp, color = tokens.colors.primary),
                    modifier = Modifier.semantics { heading() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ChatMetrics.CHAT_HEADER_GROUP_GAP_PT.dp)) {
                BasicText(
                    text = if (minimized) "⌃" else "⌄",
                    style = type.secondary.copy(color = tokens.colors.secondary),
                    modifier = Modifier.size(ChatMetrics.QUESTION_HEADER_ACTION_SIZE_PT.dp, ChatMetrics.QUESTION_HEADER_ACTION_SIZE_PT.dp)
                        .clickable(enabled = !busy, role = Role.Button) { minimized = !minimized }
                        .semantics {
                            contentDescription = if (minimized) "展开问题卡片" else "收起问题卡片"
                        },
                )
                BasicText(
                    text = "×",
                    style = type.secondary.copy(color = tokens.colors.secondary),
                    modifier = Modifier.size(ChatMetrics.QUESTION_HEADER_ACTION_SIZE_PT.dp, ChatMetrics.QUESTION_HEADER_ACTION_SIZE_PT.dp)
                        .clickable(enabled = !busy, role = Role.Button) {
                            if (canRespond) onDismiss() else validationError = "当前无法提交，请稍候再试。"
                        }
                        .semantics { contentDescription = "放弃整组问题" },
                )
            }
        }
        if (!minimized) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                    .padding(horizontal = ChatMetrics.QUESTION_BODY_INSET_H_PT.dp),
                verticalArrangement = Arrangement.spacedBy(tokens.geometry.bannerGap),
            ) {
                item.detail?.takeIf(String::isNotBlank)?.let {
                    MarkdownBody(markdown = it, modifier = Modifier
                        .padding(horizontal = ChatMetrics.QUESTION_DETAIL_INSET_H_PT.dp)
                        .padding(vertical = ChatMetrics.QUESTION_DETAIL_INSET_V_PT.dp))
                }
                if (item.options.isNotEmpty()) {
                    Spacer(Modifier.height(ChatMetrics.QUESTION_OPTIONS_TOP_PT.dp))
                }
                item.options.forEachIndexed { optionIndex, option ->
                val selected = option.label in draft.selected
                val displayLabel = option.label.replace(
                    Regex("""\s*(?:\((?:recommended|推荐)\)|（(?:recommended|推荐)）)\s*$""", RegexOption.IGNORE_CASE),
                    "",
                )
                val recommended = option.label == item.recommendedWireLabel || displayLabel != option.label
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = ChatMetrics.QUESTION_OPTION_ROW_HEIGHT_PT.dp)
                        .background(
                            if (selected && !item.multiple) tokens.colors.selector else Color.Transparent,
                            RoundedCornerShape(ChatMetrics.QUESTION_OPTION_RADIUS_PT.dp),
                        )
                        .clickable(enabled = !busy, role = Role.Button) {
                            val next = if (item.multiple) {
                                if (selected) draft.selected - option.label else draft.selected + option.label
                            } else listOf(option.label)
                            update(draft.copy(selected = next, custom = "", skipped = false))
                        }
                        .padding(
                            horizontal = ChatMetrics.QUESTION_OPTION_PADDING_H_PT.dp,
                            vertical = ChatMetrics.QUESTION_OPTION_PADDING_V_PT.dp,
                        )
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (recommended) "$displayLabel，推荐" else displayLabel
                            this.selected = selected
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
                ) {
                    if (item.multiple) {
                        BasicText(text = if (selected) "☑" else "□", style = type.secondary.copy(color = tokens.colors.accent))
                    } else {
                        Box(Modifier.size(ChatMetrics.QUESTION_OPTION_MARK_SIZE_PT.dp).background(tokens.colors.selector, CircleShape), contentAlignment = Alignment.Center) {
                            BasicText(text = (optionIndex + 1).toString(), style = type.caption.copy(color = tokens.colors.secondary))
                        }
                    }
                    Column {
                        BasicText(
                            text = displayLabel,
                            style = type.secondary.copy(fontSize = ChatMetrics.QUESTION_OPTION_TEXT_SIZE_PT.sp, color = tokens.colors.primary),
                        )
                        option.description?.takeIf(String::isNotBlank)?.let {
                            BasicText(text = it, style = type.secondary.copy(fontSize = ChatMetrics.QUESTION_OPTION_TEXT_SIZE_PT.sp, color = tokens.colors.tertiary))
                        }
                        if (recommended) {
                            BasicText("推荐", style = type.caption.copy(color = tokens.colors.accent))
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = if (item.options.isEmpty()) ChatMetrics.QUESTION_FREE_ANSWER_HEIGHT_PT.dp else ChatMetrics.QUESTION_OPTION_ROW_HEIGHT_PT.dp)
                    .padding(
                        horizontal = ChatMetrics.QUESTION_ANSWER_INSET_H_PT.dp,
                        vertical = ChatMetrics.QUESTION_ANSWER_INSET_V_PT.dp,
                    ),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(ChatMetrics.QUESTION_HEADER_STACK_GAP_PT.dp),
            ) {
                if (item.options.isNotEmpty()) {
                    BasicText("＋", style = type.secondary.copy(color = tokens.colors.secondary))
                }
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = draft.custom,
                        onValueChange = { value ->
                            update(draft.copy(custom = value, selected = if (item.multiple) draft.selected else emptyList(), skipped = false))
                        },
                        textStyle = type.secondary.copy(color = tokens.colors.primary),
                        cursorBrush = SolidColor(tokens.colors.accent),
                        modifier = Modifier.fillMaxWidth().testTag(UiPageTags.CHAT_QUESTION_ANSWER),
                        decorationBox = { inner ->
                            if (draft.custom.isEmpty()) BasicText(
                                text = if (item.options.isEmpty()) "输入你的答案" else UiStrings.QUESTION_CUSTOM_ANSWER,
                                style = type.secondary.copy(color = tokens.colors.tertiary),
                            )
                            inner()
                        },
                    )
                }
            }
            if (item.options.isNotEmpty()) {
                Spacer(Modifier.height(ChatMetrics.QUESTION_OPTIONS_BOTTOM_PT.dp))
            }
            }
            (question.submissionError ?: validationError)?.let { error ->
                BasicText(text = error, style = type.caption.copy(color = tokens.colors.warning))
            }
            Column(modifier = Modifier.padding(
                top = ChatMetrics.QUESTION_FOOTER_TOP_PT.dp,
                start = ChatMetrics.QUESTION_FOOTER_INSET_H_PT.dp,
                end = ChatMetrics.QUESTION_FOOTER_INSET_H_PT.dp,
                bottom = ChatMetrics.QUESTION_PANEL_BOTTOM_PT.dp,
            )) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ChatMetrics.QUESTION_PAGER_GAP_PT.dp)) {
                    QuestionPagerButton("‹", "上一题", enabled = index > 0 && !busy) { index -= 1 }
                    BasicText(
                        text = "${index + 1} / ${items.size}",
                        style = type.secondary.copy(fontSize = ChatMetrics.QUESTION_OPTION_TEXT_SIZE_PT.sp, color = tokens.colors.secondary),
                        modifier = Modifier.semantics { contentDescription = "第 ${index + 1} 题，共 ${items.size} 题" },
                    )
                    QuestionPagerButton("›", "下一题", enabled = !last && !busy) { index += 1 }
                    Spacer(Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = ChatMetrics.QUESTION_FOOTER_GAP_PT.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(ChatMetrics.QUESTION_FOOTER_GAP_PT.dp),
                ) {
                    QuestionActionText("跳过", enabled = !busy) { skip() }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier.defaultMinSize(minHeight = ChatMetrics.QUESTION_ACTION_HEIGHT_PT.dp)
                            .background(if (!busy && canRespond && answered) tokens.colors.ctaOverride else tokens.colors.border, RoundedCornerShape(ChatMetrics.QUESTION_ACTION_RADIUS_PT.dp))
                            .clickable(enabled = !busy && canRespond && answered, role = Role.Button) {
                                if (!last) index += 1 else submit()
                            }
                            .testTag(UiPageTags.CHAT_QUESTION_SUBMIT)
                            .padding(horizontal = ChatMetrics.QUESTION_ACTION_PADDING_H_PT.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = if (busy) "正在提交…" else if (last) "提交" else "下一题",
                            style = type.secondary.copy(fontSize = ChatMetrics.QUESTION_OPTION_TEXT_SIZE_PT.sp, color = if (!busy && canRespond && answered) tokens.colors.onAccent else tokens.colors.tertiary),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(ChatMetrics.QUESTION_CARD_BOTTOM_PT.dp))
    }
}

private fun hasRecommendedSuffix(label: String): Boolean = Regex(
    """\s*(?:\((?:recommended|推荐)\)|（(?:recommended|推荐)）)\s*$""",
    RegexOption.IGNORE_CASE,
).containsMatchIn(label)

@Composable
private fun QuestionPagerButton(text: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    BasicText(
        text = text,
        style = type.secondary.copy(color = if (enabled) tokens.colors.secondary else tokens.colors.tertiary),
        modifier = Modifier.size(ChatMetrics.QUESTION_PAGER_TARGET_PT.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun QuestionActionText(label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    BasicText(
        text = label,
        style = type.secondary.copy(color = if (enabled) tokens.colors.secondary else tokens.colors.tertiary),
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(
                horizontal = ChatMetrics.QUESTION_ACTION_TEXT_INSET_H_PT.dp,
                vertical = ChatMetrics.QUESTION_ACTION_TEXT_INSET_V_PT.dp,
            ),
    )
}

private data class QuestionDraft(
    val selected: List<String> = emptyList(),
    val custom: String = "",
    val skipped: Boolean = false,
)

/** Plan-review variant is selected only from the explicit source intent. */
@Composable
private fun PlanReviewTakeover(
    question: PendingAnswer,
    item: PendingQuestionItem,
    onApprove: (String) -> Unit,
    onRequestChanges: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val markdown = item.detail.orEmpty().trim()
    val firstLine = markdown.lineSequence().firstOrNull().orEmpty().trimStart('#').trim()
    val body = markdown.lineSequence().drop(1).dropWhile { it.isBlank() }.takeWhile { it.isNotBlank() }
        .joinToString(" ").trim()
    val title = firstLine.ifEmpty { item.prompt }
    val summary = body.takeIf { it.isNotEmpty() && it != title }
    val busy = question.submitting == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.geometry.composerOuterMarginH)
            .testTag(PLAN_REVIEW_TAKEOVER_TAG)
            .background(tokens.colors.input, RoundedCornerShape(tokens.radius.card))
            .border(tokens.stroke.hairline, tokens.colors.borderLight, RoundedCornerShape(tokens.radius.card)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(tokens.colors.warning.copy(alpha = 0.12f))
                .padding(horizontal = tokens.spacing.cardInset, vertical = tokens.spacing.iconText),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
        ) {
            Box(Modifier.size(tokens.geometry.statusDotDiameter).clip(CircleShape).background(tokens.colors.warning))
            BasicText("计划待审", style = type.secondary.copy(color = tokens.colors.warning))
            Spacer(Modifier.weight(1f))
            if (busy) BasicText("正在提交…", style = type.caption.copy(color = tokens.colors.secondary))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(tokens.spacing.cardInset),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
        ) {
            BasicText(title, style = type.subheadline.copy(color = tokens.colors.primary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary != null) {
                BasicText(summary, style = type.secondary.copy(color = tokens.colors.secondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (question.submissionError != null) {
                BasicText(question.submissionError, style = type.caption.copy(color = tokens.colors.warning))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onRequestChanges)
                        .border(tokens.stroke.hairline, tokens.colors.borderLight, RoundedCornerShape(ChatMetrics.PLAN_REVIEW_BUTTON_RADIUS_PT.dp))
                        .padding(horizontal = tokens.spacing.cardInset, vertical = tokens.spacing.iconText)
                        .testTag(PLAN_REVIEW_CHANGE_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ChatMetrics.PLAN_REVIEW_BUTTON_GAP_PT.dp),
                ) {
                    BasicText("✎", style = type.caption.copy(color = tokens.colors.secondary))
                    BasicText("要求修改", style = type.secondary.copy(color = tokens.colors.primary))
                }
                Box(
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = ChatMetrics.PLAN_REVIEW_BUTTON_HEIGHT_PT.dp)
                        .background(if (busy) tokens.colors.border else tokens.colors.ctaOverride, RoundedCornerShape(ChatMetrics.PLAN_REVIEW_BUTTON_RADIUS_PT.dp))
                        .clickable(enabled = !busy, role = Role.Button) {
                            val value = JsonArray(listOf(JsonString(item.approveWireLabel.orEmpty())))
                            onApprove(CanonicalJson.write(JsonObject(mapOf(item.id to value))))
                        }
                        .testTag(PLAN_REVIEW_APPROVE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("同意执行", style = type.secondary.copy(color = tokens.colors.onAccent))
                }
            }
        }
    }
}

/** Builds the JSON the iOS card submits: one key, a string or a list of strings. */
private fun answerJson(
    question: PendingAnswer,
    single: String?,
    multiple: Set<String>,
    custom: String,
): String {
    val trimmed = custom.trim()
    val value: JsonValue = if (question.multiple) {
        val answers = question.options.filter { it in multiple } + listOfNotNull(trimmed.takeIf { it.isNotEmpty() })
        JsonArray(answers.map { JsonString(it) })
    } else {
        JsonString(single ?: trimmed)
    }
    return CanonicalJson.write(JsonObject(mapOf(ANSWER_KEY to value)))
}

// ---------------------------------------------------------------------------
// Transcript (ChatPage.swift `transcript` + `emptyTimeline`)
// ---------------------------------------------------------------------------

/**
 * The transcript.
 *
 * The list follows the tail while the reader is at the bottom, refuses to jump
 * when they scrolled away, and offers "回到最新" in that case (spec §5 row
 * `scroll-refresh`: the follow-the-tail condition is the same on both platforms).
 * Above the rows sit the history controls: 加载更早消息, the history read failure
 * and the "显示上次对话" cache notice.
 */
@Composable
private fun ChatTranscript(
    state: ChatScreenState,
    onToggleRow: (String) -> Unit,
    onLoadEarlier: () -> Unit,
    onStartEngine: () -> Unit,
    onConfigureProvider: () -> Unit,
    onRetry: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val listState = rememberLazyListState()
    // True while the last row is on screen: the only condition that lets a new row
    // pull the viewport (iOS `nearBottom`).
    val nearBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    // "The reader scrolled away" is a user gesture, not a measurement: only a
    // user drag can set it, and being near the tail again clears it (iOS
    // `userScrolledAway` + `nearBottom`).
    var scrolledAway by remember { mutableStateOf(false) }
    // True while this composable itself is placing the viewport. A programmatic
    // scroll is not a reader gesture: without this flag the open-at-tail scroll
    // counts as "the reader scrolled away", the pill appears over a transcript
    // nobody touched, and the next rows update refuses to follow the tail.
    var autoScrolling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to nearBottom }.collect { (scrolling, near) ->
            if (autoScrolling) return@collect
            if (scrolling && !near) scrolledAway = true
            if (near) scrolledAway = false
        }
    }
    // The tail is keyed on the rows' *identity*, not only their count: a restored
    // session can replace its history with re-derived row ids of the same length,
    // and a LazyColumn that cannot match a previous key starts again at the top.
    // Keyed on `size` that replacement was invisible, so the transcript stayed on
    // its first message (measured on the phone, 2026-11-02).
    val rowsKey = remember(state.rows) {
        state.rows.joinToString(separator = "\u0000") { it.id }
    }
    LaunchedEffect(rowsKey) {
        if (scrolledAway || state.rows.isEmpty()) return@LaunchedEffect
        // A session opened with history composes its rows and its layout in the
        // same frame, so `totalItemsCount` is still 0 when this effect starts and a
        // pre-populated transcript used to stand at its *top* (the official preview
        // capture showed the first row while iOS showed the last). iOS opens at the
        // tail: wait for the list's first layout, then land on the last row.
        val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        autoScrolling = true
        try {
            // Land on the last row, then scroll the remaining distance so a last
            // row taller than the viewport shows its *end* (iOS opens at the tail
            // of the answer, not at its first line). Measuring the remainder is
            // what `scrollToItem(Int.MAX_VALUE)` was trying to do by overflowing
            // the offset, which lands nowhere on a real device.
            listState.scrollToItem(total - 1)
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last != null && last.index >= total - 1) {
                val past = last.offset + last.size -
                    (info.viewportEndOffset - info.afterContentPadding)
                if (past > 0) listState.scrollBy(past.toFloat())
            }
        } finally {
            autoScrolling = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.rows.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TranscriptChrome(state = state, onLoadEarlier = onLoadEarlier)
                // A pending ask replaces the empty state: ios-chat-permission.png has
                // two cards over a blank transcript, not the product mark.
                if (state.pending.isEmpty) {
                    EmptyTimeline(
                        state = state,
                        onStartEngine = onStartEngine,
                        onConfigureProvider = onConfigureProvider,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTags.CHAT_LIST),
                // iOS: top 22, bottom 48 (room for the 28 pt "回到最新" pill).
                // unverified geometry: the compact list-row floor borrowed as the
                // 22 pt top inset and the primary-button height as the 48 pt tail
                // inset; tokens.json has neither page token.
                contentPadding = PaddingValues(
                    top = tokens.geometry.pageListRowMinHeight,
                    bottom = tokens.geometry.primaryButtonHeight,
                ),
                // ios-chat-assistant.png / -failed.png / -queued.png: a short
                // conversation sits at the *bottom* of the transcript, against the
                // dock, leaving the space under the header empty. A top-anchored list
                // put the first row directly under the header.
                verticalArrangement = Arrangement.Bottom,
            ) {
                item(key = CHROME_ITEM) { TranscriptChrome(state = state, onLoadEarlier = onLoadEarlier) }
                itemsIndexed(items = state.rows, key = { _, row -> row.id }) { index, row ->
                    val gap = rowGap(previous = state.rows.getOrNull(index - 1), current = row)
                    Box(modifier = if (gap == null) Modifier else Modifier.padding(top = gap)) {
                        Column {
                            ChatRowItem(
                                row = row,
                                expandedRowIds = state.expandedRowIds,
                                onToggleRow = onToggleRow,
                                onOpenFile = onOpenFile,
                            )
                            if (row.id == state.failedItemID && state.userFacingError != null) {
                                FailureLine(
                                    reason = state.userFacingError,
                                    retry = if (state.banners.firstOrNull()?.retryable == true) {
                                        onRetry
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (scrolledAway && !nearBottom) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = tokens.spacing.cardInset)
                        .testTag(UiPageTags.CHAT_BACK_TO_LATEST)
                        // iOS draws this pill as a capsule (94 x 30 pt, radius =
                        // height / 2) with a **near-white glass fill** and a 0.5 pt
                        // border (`VISUAL.md` back-to-latest row). Filling it with
                        // `selector` — the page's own #F2F3F5 — made the capsule
                        // disappear against the transcript and left the label
                        // floating mid-air on a real device.
                        .clip(CircleShape)
                        .background(tokens.colors.input)
                        .border(
                            width = tokens.stroke.hairline,
                            color = tokens.colors.border,
                            shape = CircleShape,
                        )
                        .clickable(role = Role.Button) {
                            scrolledAway = false
                            scope.launch {
                                listState.animateScrollToItem(
                                    (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                                )
                            }
                        }
                        .padding(
                            horizontal = tokens.spacing.cardInset,
                            vertical = tokens.spacing.codeToolbar,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
                ) {
                    // iOS is `Label("回到最新", systemImage: "arrow.down")`: the glyph
                    // is part of the control, and without it the pill reads as a
                    // stray link rather than a "jump to the tail" action.
                    BasicText(
                        text = BACK_TO_LATEST_GLYPH,
                        style = LocalUiType.current.subheadline.copy(color = tokens.colors.accent),
                    )
                    BasicText(
                        text = UiStrings.BACK_TO_LATEST,
                        // iOS label is 17 pt in the accent; the nearest declared
                        // regular role is `subheadline`.
                        style = LocalUiType.current.subheadline.copy(color = tokens.colors.accent),
                    )
                }
            }
        }
    }
}

/**
 * The history controls above the rows. Rendered inside the list so they scroll
 * with it, exactly like the iOS `LazyVStack` header block.
 */
@Composable
private fun TranscriptChrome(
    state: ChatScreenState,
    onLoadEarlier: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.transcript.hasMore) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
                    .clickable(
                        enabled = !state.transcript.loadingEarlier,
                        role = Role.Button,
                        onClick = onLoadEarlier,
                    )
                    .testTag(UiPageTags.CHAT_LOAD_EARLIER),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText, Alignment.CenterHorizontally),
            ) {
                if (state.transcript.loadingEarlier) {
                    CircularProgressIndicator(
                        color = tokens.colors.tertiary,
                        strokeWidth = tokens.stroke.hairline,
                        modifier = Modifier.size(tokens.geometry.composerControlSize),
                    )
                    // iOS `loadingEarlierTranscript ? "正在加载…" : "加载更早消息"`:
                    // one word per state, each written out here.
                    BasicText(
                        text = UiStrings.LOADING_EARLIER,
                        style = type.secondary.copy(color = tokens.colors.secondary),
                    )
                } else {
                    BasicText(
                        text = UiStrings.LOAD_EARLIER,
                        style = type.secondary.copy(color = tokens.colors.secondary),
                    )
                }
            }
        }
        val transcriptError = state.transcript.error
        if (transcriptError != null) {
            // iOS paints this line with the warning ink over a 8% warning wash.
            BasicText(
                text = transcriptError,
                style = type.caption.copy(color = tokens.colors.warning),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiPageTags.CHAT_TRANSCRIPT_ERROR)
                    .padding(tokens.geometry.bannerInsetH)
                    .background(
                        tokens.colors.warning.copy(alpha = WARNING_WASH_ALPHA),
                        RoundedCornerShape(tokens.radius.card),
                    )
                    .padding(tokens.geometry.bannerInsetH),
            )
        }
        if (state.transcript.showingCached) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiPageTags.CHAT_CACHED_NOTICE)
                    .padding(vertical = tokens.geometry.toolDetailVertical),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText, Alignment.CenterHorizontally),
            ) {
                CircularProgressIndicator(
                    color = tokens.colors.tertiary,
                    strokeWidth = tokens.stroke.hairline,
                    modifier = Modifier.size(tokens.geometry.disclosureRowHeight),
                )
                BasicText(
                    text = UiStrings.TRANSCRIPT_CACHED,
                    style = type.caption.copy(color = tokens.colors.secondary),
                )
            }
        }
    }
}

/** The per-row failure line: 没发出去 · reason · 重试, aligned to the trailing edge. */
@Composable
private fun FailureLine(
    reason: String,
    retry: (() -> Unit)?,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.CHAT_FAILURE_LINE)
            .padding(top = tokens.spacing.iconText),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText, Alignment.End),
    ) {
        BasicText(
            text = UiStrings.SEND_FAILED_LINE,
            style = type.secondary.copy(color = tokens.colors.danger),
        )
        if (reason.isNotEmpty()) {
            BasicText(
                text = UiStrings.DOT_SEPARATOR,
                style = type.secondary.copy(color = tokens.colors.secondary),
            )
            BasicText(
                text = reason,
                style = type.secondary.copy(color = tokens.colors.secondary),
            )
        }
        if (retry != null) {
            BasicText(
                text = UiStrings.DOT_SEPARATOR,
                style = type.secondary.copy(color = tokens.colors.secondary),
            )
            BasicText(
                text = UiStrings.RETRY,
                style = type.secondary.copy(color = tokens.colors.accent),
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = retry)
                    .padding(tokens.spacing.iconText),
            )
        }
    }
}

/** The three mutually exclusive empty states (iOS `emptyTimeline`). */
@Composable
private fun EmptyTimeline(
    state: ChatScreenState,
    onStartEngine: () -> Unit,
    onConfigureProvider: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    when (state.empty) {
        // The iOS empty block lives inside the transcript's ScrollView, whose
        // `defaultScrollAnchor(.bottom)` puts a short block against the dock
        // (`ChatPage.swift:1186`, `:1310-1322`): ios-chat-draft.png's title ink
        // starts at y 261.3 pt with the composer top at 438, i.e. 61 pt above the
        // dock — not directly under the header, which is where a top-anchored
        // Column used to put it (y 137). The block keeps the dock's 48 pt bottom
        // inset (`transcript`'s `.padding(.bottom, 48)`).
        ChatEmpty.ENGINE_NOT_STARTED -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            EngineNotStartedState(
                busy = state.starting,
                onStartEngine = onStartEngine,
                modifier = Modifier.padding(bottom = tokens.geometry.primaryButtonHeight),
            )
        }

        ChatEmpty.PROVIDER_SETUP -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ProviderSetupState(
                onConfigureProvider = onConfigureProvider,
                modifier = Modifier.padding(bottom = tokens.geometry.primaryButtonHeight),
            )
        }

        ChatEmpty.READY -> ReadyState()
    }
}

/**
 * A: the engine is not started yet — the official 338 pt column
 * ([com.phonedsh.ui.UiMetrics.CONTENT_COLUMN_WIDTH_PT]), text left-aligned.
 */
@Composable
private fun EngineNotStartedState(
    busy: Boolean,
    onStartEngine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        // iOS pins the column to the official 338 pt
        // (com.phonedsh.ui.UiMetrics.CONTENT_COLUMN_WIDTH_PT) on the baseline
        // device, which is simply "screen width minus both page margins". That is
        // a device result, not a design value, so the column fills the width and
        // takes the page margin; it stays correct at every width.
        modifier = modifier
            .fillMaxWidth()
            // ios-chat-draft.png: the title ink starts at x 33 and the CTA spans
            // x 32–370 — the shared 338 pt content column (`UiMetrics`), not the
            // transcript's 16 pt page margin. Read from the metric owner, not
            // re-typed.
            .padding(horizontal = UiMetrics.CONTENT_COLUMN_INSET_PT.dp)
            .testTag(UiPageTags.CHAT_EMPTY_ENGINE),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.codeToolbar),
    ) {
        BasicText(
            text = UiStrings.ENGINE_NOT_STARTED,
            style = type.headline.copy(color = tokens.colors.primary),
        )
        BasicText(
            text = UiStrings.ENGINE_NOT_STARTED_HINT,
            style = type.secondary.copy(color = tokens.colors.secondary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.geometry.composerOuterMarginTop)
                .defaultMinSize(minHeight = tokens.geometry.primaryButtonHeight)
                // iOS `Self.accent` = ChatPage's own #2F5BFF (`ChatPage.swift:1058`,
                // applied at `:1343`), not the theme accent.
                .background(tokens.colors.pageAccent, RoundedCornerShape(tokens.radius.card))
                .clickable(enabled = !busy, role = Role.Button, onClick = onStartEngine)
                .testTag(UiPageTags.CHAT_START_ENGINE),
            contentAlignment = Alignment.Center,
        ) {
            // iOS `model.busy ? "正在启动…" : "启动本地引擎"`: one word per state.
            if (busy) {
                BasicText(
                    text = UiStrings.STARTING_ENGINE,
                    style = type.headline.copy(color = tokens.colors.onAccent),
                )
            } else {
                BasicText(
                    text = UiStrings.START_ENGINE,
                    style = type.headline.copy(color = tokens.colors.onAccent),
                )
            }
        }
    }
}

/** B: the engine is up, only the model service is missing. */
@Composable
private fun ProviderSetupState(
    onConfigureProvider: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.geometry.pageOuterMarginH)
            .testTag(UiPageTags.CHAT_EMPTY_PROVIDER),
        verticalArrangement = Arrangement.spacedBy(tokens.geometry.bannerGap),
    ) {
        BasicText(
            text = UiStrings.PROVIDER_SETUP_TITLE,
            style = type.headline.copy(color = tokens.colors.primary),
        )
        BasicText(
            text = UiStrings.PROVIDER_SETUP_HINT,
            style = type.secondary.copy(color = tokens.colors.secondary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tokens.spacing.codeToolbar)
                .defaultMinSize(minHeight = tokens.geometry.primaryButtonHeight)
                // iOS `Self.accent` = ChatPage's own #2F5BFF (`ChatPage.swift:1058`,
                // applied at `:1343`), not the theme accent.
                .background(tokens.colors.pageAccent, RoundedCornerShape(tokens.radius.card))
                .clickable(role = Role.Button, onClick = onConfigureProvider)
                .testTag(UiPageTags.CHAT_PROVIDER_SETUP),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = UiStrings.PROVIDER_SETUP_ACTION,
                style = type.headline.copy(color = tokens.colors.onAccent),
            )
        }
    }
}

/**
 * C: both ready. The iOS state centres the product mark over the tagline; the
 * mark is a bundled image (`LogoBackground`) with no Android asset, so only the
 * two lines are drawn, centred in the content area.
 */
@Composable
private fun ReadyState() {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiPageTags.CHAT_EMPTY_READY),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = UiStrings.READY_TITLE,
                style = type.headline.copy(color = tokens.colors.secondary),
                maxLines = 1,
            )
            BasicText(
                text = UiStrings.READY_SUBTITLE,
                style = type.secondary.copy(color = tokens.colors.tertiary),
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Composer dock (ChatPage.swift `composer` + `goalBar` + `queuedStrip` + menus)
// ---------------------------------------------------------------------------

/**
 * The bottom dock: the goal bar, the queued strip, the pending attachments, the
 * two notice lines, the in-place `/` and `@` menus, the input card and the stat
 * pills — the iOS `composer` VStack in the same order.
 */
@Composable
private fun ComposerDock(
    state: ChatScreenState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onGoalPause: (() -> Unit)?,
    onGoalResume: (() -> Unit)?,
    onGoalClear: (() -> Unit)?,
    onOpenGoalDetail: (() -> Unit)?,
    onSubmitAnswer: (PendingAnswer, String) -> Unit,
    onDismissQuestion: (PendingAnswer) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onAddImages: () -> Unit,
    onAddFiles: () -> Unit,
    onSelectAccessMode: (String) -> Unit,
    onSelectModel: (String, String?) -> Unit,
    onPickCommand: (String) -> Unit,
    onExecuteCommand: ((String) -> Unit)?,
    onPickReference: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onReloadCommands: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val composer = state.composer
    val pendingQuestion = (listOfNotNull(state.pending.primary) + state.pending.folded)
        .firstOrNull { it.kind == QUESTION }
    // iOS keeps the truncation notice in the same slot as the attachment error.
    var truncated by remember { mutableStateOf(false) }
    var slashMenuDismissed by remember { mutableStateOf(false) }
    var slashHighlight by remember { mutableIntStateOf(0) }
    val slashMenu = state.composer.slash.takeUnless { slashMenuDismissed }
    val slashCommands = (slashMenu as? SlashMenu.Matches)?.commands.orEmpty()

    fun chooseCommand(name: String) {
        val command = slashCommands.firstOrNull { it.name == name }
        if (command?.hasInput == false && onExecuteCommand != null) {
            onDraftChange("")
            onExecuteCommand(name)
        } else {
            // This local update is essential for screen hosts that only supply a
            // catalog: selecting a menu row must never be a no-op.
            onDraftChange("/$name ")
        }
        onPickCommand(name)
        slashMenuDismissed = true
    }

    fun handleSlashKey(event: KeyEvent): Boolean {
        if (slashMenu == null || event.type != KeyEventType.KeyDown) return false
        if (slashCommands.isEmpty()) {
            if (event.key == Key.Escape) {
                slashMenuDismissed = true
                return true
            }
            if (event.key == Key.Enter) return true
            return false
        }
        return when (event.key) {
            Key.DirectionDown -> {
                slashHighlight = (slashHighlight + 1).coerceAtMost(slashCommands.lastIndex)
                true
            }
            Key.DirectionUp -> {
                slashHighlight = (slashHighlight - 1).coerceAtLeast(0)
                true
            }
            Key.Tab, Key.Enter -> {
                slashCommands.getOrNull(slashHighlight)?.let { chooseCommand(it.name) }
                true
            }
            Key.Escape -> {
                slashMenuDismissed = true
                true
            }
            else -> false
        }
    }

    LaunchedEffect(composer.draft) {
        slashMenuDismissed = false
        slashHighlight = 0
    }
    Column(modifier = Modifier.fillMaxWidth().onPreviewKeyEvent(::handleSlashKey)) {
        // iOS puts the 16 pt horizontal inset on the dock's VStack; the input card
        // is that same surface and applies it itself (`Composer`), so the inset is
        // applied here only to the pieces around the card.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.geometry.composerOuterMarginH)
                .padding(top = tokens.geometry.composerOuterMarginTop),
            // iOS stacks the dock's rows with `NativeParityTokens.flowGap` = 6 pt
            // (`ChatPage.swift:1795`); the old 40 pt was the trailing *reserve*
            // for the send key, which pushed the queue strip 28 pt too high.
            verticalArrangement = Arrangement.spacedBy(UiMetrics.flowGap),
        ) {
            if (state.todoItems.isNotEmpty()) {
                TodoDock(
                    items = state.todoItems,
                    modifier = Modifier.widthIn(max = ChatMetrics.CONTENT_COLUMN_WIDTH_PT.dp)
                        .fillMaxWidth().align(Alignment.CenterHorizontally),
                )
            }
            val goal = state.goal
            if (goal is GoalBarState.Visible) {
                GoalBarView(
                    goal = goal,
                    onPause = onGoalPause,
                    onResume = onGoalResume,
                    onClear = onGoalClear,
                    onOpenDetail = onOpenGoalDetail,
                )
            }
            if (composer.queued.isNotEmpty()) {
                QueuedStrip(messages = composer.queued)
            }
            if (composer.attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = composer.attachments,
                    onRemove = onRemoveAttachment,
                )
            }
            if (composer.attachmentError != null) {
                NoticeLine(text = composer.attachmentError, onClose = onDismissNotice)
            }
            if (composer.running && composer.steering && composer.attachments.isNotEmpty()) {
                BasicText(
                    text = UiStrings.COMPOSER_STEER_ATTACHMENT,
                    style = type.caption.copy(color = tokens.colors.warning),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (composer.fileImportNotice != null) {
                NoticeLine(text = composer.fileImportNotice, onClose = onDismissNotice)
            }
            if (truncated) {
                NoticeLine(text = UiStrings.COMPOSER_TRUNCATED, onClose = { truncated = false })
            }
            slashMenu?.let { menu ->
                SlashMenuView(
                    menu = menu,
                    highlightedIndex = slashHighlight,
                    onHighlight = { slashHighlight = it },
                    onPick = ::chooseCommand,
                    onRetry = onReloadCommands,
                )
            }
            composer.reference?.let { menu ->
                ReferenceMenuView(menu = menu, onPick = { value ->
                    val selected = if (value in menu.sessions) "@$value " else "$value "
                    onDraftChange(selected)
                    onPickReference(value)
                })
            }
        }
        if (pendingQuestion != null) {
            val plan = pendingQuestion.questionItems.singleOrNull()?.takeIf {
                it.intentKind == PLAN_REVIEW_INTENT && !it.detail.isNullOrBlank() &&
                    !it.approveWireLabel.isNullOrBlank()
            }
            if (plan != null) {
                PlanReviewTakeover(
                    question = pendingQuestion,
                    item = plan,
                    onApprove = { json -> onSubmitAnswer(pendingQuestion, json) },
                    onRequestChanges = { onDismissQuestion(pendingQuestion) },
                )
            } else {
                QuestionTakeover(
                    question = pendingQuestion,
                    onSubmit = { json -> onSubmitAnswer(pendingQuestion, json) },
                    onDismiss = { onDismissQuestion(pendingQuestion) },
                    maxHeight = minOf(
                        LocalConfiguration.current.screenHeightDp.dp * 0.6f,
                        ChatMetrics.QUESTION_MAX_HEIGHT_PT.dp,
                    ),
                )
            }
        } else Composer(
                // The dock is floored at the iOS-measured collapsed composer height
                // (94 pt since commit `86a39aac`; `ChatMetrics.COMPOSER_HEIGHT_PT`
                // carries the commit reference, not a guess). The card's own padding
                // stack already draws more than this, so the floor pins the page's
                // number against a collapse rather than adding space today. The outer
                // *bottom* margin the same commit took from 8 to 0 is applied by the
                // Composer surface itself (`geometry.composerOuterMarginBottom`), not
                // by this page, so it stays a separate follow-up.
                modifier = Modifier.heightIn(min = ChatMetrics.composerHeight),
                text = composer.draft,
                onTextChange = onDraftChange,
                placeholder = composer.placeholder,
                mode = composer.mode,
                enabled = composer.enabled,
                canSubmit = composer.canSubmit,
                running = composer.running,
                onSend = onSend,
                onStop = onStop,
                stat = state.stats,
                contextPercent = state.contextPercent,
                sendLabel = composer.sendLabel,
                onTruncated = { truncated = true },
                onMenuKey = ::handleSlashKey,
                onMenuSubmit = {
                    if (slashMenu == null) {
                        false
                    } else {
                        slashCommands.getOrNull(slashHighlight)?.let { chooseCommand(it.name) }
                        true
                    }
                },
                controls = {
                    ComposerControls(
                        state = state,
                        onDraftChange = onDraftChange,
                        onAddImages = onAddImages,
                        onAddFiles = onAddFiles,
                        onSelectAccessMode = onSelectAccessMode,
                        onSelectModel = onSelectModel,
                    )
                },
        )
    }
}

/**
 * The goal bar (iOS `goalBar`): the phase label, the one-line objective and the
 * pause/resume/clear actions.
 */
@Composable
private fun GoalBarView(
    goal: GoalBarState.Visible,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onClear: (() -> Unit)?,
    onOpenDetail: (() -> Unit)?,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChatMetrics.GOAL_BAR_HEIGHT_PT.dp)
            .testTag(UiTags.GOAL_BAR)
            .background(tokens.colors.selector, RoundedCornerShape(ChatMetrics.GOAL_BAR_RADIUS_PT.dp))
            .then(
                if (onOpenDetail != null) {
                    Modifier.clickable(role = Role.Button, onClick = onOpenDetail)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ChatMetrics.GOAL_BAR_INSET_H_PT.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChatMetrics.GOAL_BAR_GAP_PT.dp),
    ) {
        GoalTargetGlyph()
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = goalBarPhaseLabel(goal.phase),
                style = type.caption.copy(fontSize = ChatMetrics.GOAL_BAR_PHASE_SIZE_PT.sp, color = tokens.colors.secondary),
                maxLines = 1,
            )
            BasicText(
                text = goal.objective,
                style = type.secondary.copy(fontSize = ChatMetrics.GOAL_BAR_OBJECTIVE_SIZE_PT.sp, color = tokens.colors.secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (goal.phase == PHASE_ACTIVE && onPause != null) {
            BasicText(
                text = UiStrings.GOAL_PAUSE,
                style = type.caption.copy(fontSize = ChatMetrics.GOAL_BAR_ACTION_SIZE_PT.sp, color = tokens.colors.secondary),
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onPause)
                    .testTag(UiPageTags.GOAL_PAUSE)
                    .padding(tokens.spacing.iconText),
            )
        } else if ((goal.phase == PHASE_PAUSED || goal.phase == PHASE_BLOCKED) && onResume != null) {
            BasicText(
                text = UiStrings.GOAL_RESUME,
                style = type.caption.copy(fontSize = ChatMetrics.GOAL_BAR_ACTION_SIZE_PT.sp, color = tokens.colors.secondary),
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onResume)
                    .testTag(UiPageTags.GOAL_RESUME)
                    .padding(tokens.spacing.iconText),
            )
        }
        BasicText(
                text = DISMISS_GLYPH,
                style = type.caption.copy(color = tokens.colors.secondary),
                modifier = Modifier
                    .clickable(enabled = onClear != null, role = Role.Button, onClick = { onClear?.invoke() })
                    .semantics { contentDescription = UiStrings.GOAL_CLEAR_TARGET }
                    .testTag(UiPageTags.GOAL_CLEAR)
                    .padding(tokens.spacing.iconText),
        )
    }
}

@Composable
private fun GoalTargetGlyph() {
    val tokens = LocalUiTokens.current
    androidx.compose.foundation.Canvas(Modifier.size(ChatMetrics.GOAL_BAR_ICON_SIZE_PT.dp)) {
        val stroke = ChatMetrics.GOAL_BAR_ICON_STROKE_TENTH_PT.dp.toPx()
        drawCircle(tokens.colors.secondary, radius = size.minDimension * 0.36f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawCircle(tokens.colors.secondary, radius = size.minDimension * 0.1f)
        drawLine(tokens.colors.secondary, androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.3f), androidx.compose.ui.geometry.Offset(size.width * 0.98f, size.height * 0.02f), stroke)
    }
}

/** Structured task projection at the top of the iOS composer dock. */
@Composable
private fun TodoDock(items: List<TodoDockItem>, modifier: Modifier = Modifier) {
    val todos = items.filter { it.status in setOf(TODO_COMPLETED, TODO_IN_PROGRESS, TODO_PENDING) }
    if (todos.isEmpty()) return
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    var collapsed by remember(items) { mutableStateOf(true) }
    val completed = todos.count { it.status == TODO_COMPLETED }
    val active = todos.count { it.status == TODO_IN_PROGRESS }
    val pending = todos.count { it.status == TODO_PENDING }
    val progress = buildList {
        if (completed > 0) add("$completed 已完成")
        if (active > 0) add("$active 进行中")
        if (pending > 0) add("$pending 待处理")
    }.joinToString("\u2002·\u2002")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(ChatMetrics.TODO_DOCK_SHADOW_PT.dp, RoundedCornerShape(ChatMetrics.TODO_DOCK_RADIUS_PT.dp))
            .background(tokens.colors.selector, RoundedCornerShape(ChatMetrics.TODO_DOCK_RADIUS_PT.dp))
            .border(tokens.stroke.hairline, tokens.colors.borderLight, RoundedCornerShape(ChatMetrics.TODO_DOCK_RADIUS_PT.dp))
            .padding(
                horizontal = ChatMetrics.TODO_DOCK_INSET_H_PT.dp,
                vertical = ChatMetrics.TODO_DOCK_INSET_V_PT.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(ChatMetrics.TODO_DOCK_GAP_PT.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = ChatMetrics.TODO_DOCK_HEADER_LINE_PT.dp)
                .clickable(role = Role.Button) { collapsed = !collapsed }
                .semantics {
                    contentDescription = "任务 $progress"
                    stateDescription = if (collapsed) "已折叠" else "已展开"
                }
                .testTag(TODO_DOCK_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
        ) {
            TodoChecklistIcon()
            BasicText("任务", style = type.secondary.copy(fontSize = ChatMetrics.TODO_TEXT_SIZE_PT.sp, color = tokens.colors.primary))
            BasicText(
                progress,
                style = type.secondary.copy(fontSize = ChatMetrics.TODO_TEXT_SIZE_PT.sp, color = tokens.colors.tertiary),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                if (collapsed) "⌃" else "⌄",
                style = type.secondary.copy(color = tokens.colors.tertiary),
            )
        }
        if (!collapsed) {
            Column(
                modifier = Modifier.heightIn(max = ChatMetrics.TODO_LIST_MAX_HEIGHT_PT.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ChatMetrics.TODO_DOCK_GAP_PT.dp),
            ) {
                todos.forEach { item ->
                    val status = when (item.status) {
                        TODO_COMPLETED -> "已完成" to tokens.colors.success
                        TODO_IN_PROGRESS -> "进行中" to tokens.colors.accent
                        else -> "待处理" to tokens.colors.tertiary
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = ChatMetrics.TODO_ROW_HEIGHT_PT.dp)
                            .testTag(TODO_DOCK_ROW_TAG + item.id)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${status.first}，${item.content}"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ChatMetrics.TODO_STATUS_GAP_PT.dp),
                    ) {
                        Box(Modifier.size(ChatMetrics.TODO_STATUS_DOT_PT.dp).clip(CircleShape).background(status.second))
                        BasicText(item.content, style = type.secondary.copy(fontSize = ChatMetrics.TODO_TEXT_SIZE_PT.sp, color = tokens.colors.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoChecklistIcon() {
    val tokens = LocalUiTokens.current
    androidx.compose.foundation.Canvas(Modifier.size(ChatMetrics.TODO_CHECKLIST_ICON_PT.dp)) {
        val stroke = ChatMetrics.TODO_GLYPH_STROKE_PT.dp.toPx()
        val dot = ChatMetrics.TODO_GLYPH_DOT_RADIUS_PT.dp.toPx()
        listOf(ChatMetrics.TODO_GLYPH_ROW1_Y_PT, ChatMetrics.TODO_GLYPH_ROW2_Y_PT).forEach { y ->
            drawCircle(tokens.colors.tertiary, radius = dot, center = androidx.compose.ui.geometry.Offset(ChatMetrics.TODO_GLYPH_BULLET_X_PT.dp.toPx(), y.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawLine(tokens.colors.tertiary, start = androidx.compose.ui.geometry.Offset(ChatMetrics.TODO_GLYPH_LINE_START_X_PT.dp.toPx(), y.dp.toPx()), end = androidx.compose.ui.geometry.Offset(ChatMetrics.TODO_GLYPH_LINE_END_X_PT.dp.toPx(), y.dp.toPx()), strokeWidth = stroke)
        }
    }
}

/** The phase label: the exact iOS table, never the host's wire word. */
private fun goalBarPhaseLabel(phase: String): String = when (phase) {
    PHASE_ACTIVE -> UiStrings.GOAL_PHASE_ACTIVE
    PHASE_PAUSED -> UiStrings.GOAL_PHASE_PAUSED
    PHASE_BLOCKED -> UiStrings.GOAL_PHASE_BLOCKED
    else -> UiStrings.GOAL_PHASE_COMPLETE
}

/** The queued strip: "排队中 N 条" and one 28 pt line per queued message. */
@Composable
private fun QueuedStrip(messages: List<String>) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.COMPOSER_QUEUE),
        verticalArrangement = Arrangement.spacedBy(tokens.geometry.toolDetailVertical),
    ) {
        BasicText(
            text = UiStrings.QUEUED_COUNT.format(messages.size),
            style = type.caption.copy(color = tokens.colors.secondary),
        )
        messages.forEach { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = tokens.geometry.composerControlSize)
                    .background(tokens.colors.code, RoundedCornerShape(tokens.radius.codeBlock))
                    .padding(horizontal = tokens.spacing.badgeInsetH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = message,
                    style = type.secondary.copy(color = tokens.colors.secondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The pending-attachment chips with their remove keys (iOS `attachmentStrip`). */
@Composable
private fun AttachmentStrip(
    attachments: List<com.phonedsh.ui.state.ChatAttachment>,
    onRemove: (Int) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.COMPOSER_ATTACHMENTS),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        attachments.forEachIndexed { index, attachment ->
            Row(
                modifier = Modifier
                    .background(tokens.colors.selector, CircleShape)
                    .padding(start = tokens.spacing.badgeInsetH, end = tokens.spacing.iconText),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.copyGap),
            ) {
                BasicText(
                    text = IMAGE_GLYPH,
                    style = type.caption.copy(color = tokens.colors.secondary),
                )
                BasicText(
                    text = attachment.name,
                    style = type.caption.copy(color = tokens.colors.primary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    text = DISMISS_GLYPH,
                    style = type.caption.copy(color = tokens.colors.secondary),
                    modifier = Modifier
                        .clickable(role = Role.Button) { onRemove(index) }
                        .semantics {
                            contentDescription = UiStrings.COMPOSER_REMOVE_IMAGE.format(attachment.name)
                        }
                        .padding(tokens.spacing.iconText),
                )
            }
        }
    }
}

/** One notice line: the text and the 关闭 verb (iOS attachmentError / fileImportNotice). */
@Composable
private fun NoticeLine(
    text: String,
    onClose: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.CHAT_COMPOSER_NOTICE),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.codeToolbar),
    ) {
        BasicText(
            text = text,
            style = type.caption.copy(color = tokens.colors.secondary),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = UiStrings.COMPOSER_NOTICE_CLOSE,
            style = type.caption.copy(color = tokens.colors.accent),
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onClose)
                .padding(tokens.spacing.iconText),
        )
    }
}

/**
 * The in-place `/` menu (iOS `slashCommandMenu`): the live catalog's names and
 * hints, a loading line, the read failure with its retry, or "没有匹配的命令".
 */
@Composable
private fun SlashMenuView(
    menu: SlashMenu,
    highlightedIndex: Int,
    onHighlight: (Int) -> Unit,
    onPick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.COMPOSER_SLASH_MENU)
            .background(tokens.colors.selector, RoundedCornerShape(tokens.radius.card)),
    ) {
        when (menu) {
            is SlashMenu.Loading -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(tokens.spacing.cardInset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.codeToolbar),
            ) {
                CircularProgressIndicator(
                    color = tokens.colors.tertiary,
                    strokeWidth = tokens.stroke.hairline,
                    modifier = Modifier.size(tokens.geometry.composerControlSize),
                )
                BasicText(
                    text = UiStrings.COMPOSER_SLASH_LOADING,
                    style = type.secondary.copy(color = tokens.colors.secondary),
                )
            }

            is SlashMenu.Failed -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(tokens.spacing.cardInset),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.codeToolbar),
            ) {
                BasicText(
                    text = menu.message,
                    style = type.secondary.copy(color = tokens.colors.warning),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    text = UiStrings.RETRY,
                    style = type.secondary.copy(color = tokens.colors.accent),
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = onRetry)
                        .padding(tokens.spacing.iconText),
                )
            }

            is SlashMenu.Matches -> menu.commands.forEachIndexed { index, command ->
                SlashCommandRow(
                    command = command,
                    highlighted = index == highlightedIndex,
                    onHighlight = { onHighlight(index) },
                    onPick = onPick,
                )
            }

            is SlashMenu.Empty -> BasicText(
                text = UiStrings.COMPOSER_SLASH_EMPTY,
                style = type.secondary.copy(color = tokens.colors.secondary),
                modifier = Modifier.padding(tokens.spacing.cardInset),
            )
        }
    }
}

/** One command line: `/name` in 15 pt semibold, its hint in 12 pt secondary. */
@Composable
private fun SlashCommandRow(
    command: ComposerCommand,
    highlighted: Boolean,
    onHighlight: () -> Unit,
    onPick: (String) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) tokens.colors.selector else Color.Transparent, RoundedCornerShape(tokens.radius.card))
            .clickable(role = Role.Button) { onHighlight(); onPick(command.name) }
            .semantics { selected = highlighted }
            .testTag(SLASH_COMMAND_TAG_PREFIX + command.name)
            .padding(
                horizontal = tokens.spacing.cardInset,
                vertical = tokens.spacing.codeToolbar,
            ),
    ) {
        BasicText(
            text = "/${command.name}",
            style = type.subheadline.copy(color = tokens.colors.primary),
        )
        BasicText(
            text = command.description,
            style = type.caption.copy(color = tokens.colors.secondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The in-place `@` menu (iOS `referenceMenu`): matching files, then sessions. */
@Composable
private fun ReferenceMenuView(
    menu: ReferenceMenu,
    onPick: (String) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiPageTags.COMPOSER_REFERENCE_MENU)
            .background(tokens.colors.selector, RoundedCornerShape(tokens.radius.card)),
    ) {
        if (menu.isEmpty) {
            BasicText(
                text = UiStrings.COMPOSER_REFERENCE_EMPTY,
                style = type.secondary.copy(color = tokens.colors.secondary),
                modifier = Modifier.padding(tokens.spacing.cardInset),
            )
        }
        menu.files.forEach { path ->
            // The row's name is data (a workspace path), so the container takes the
            // click and the text only draws the value.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(path) }
                    .padding(
                        horizontal = tokens.spacing.cardInset,
                        vertical = tokens.spacing.codeToolbar,
                    ),
            ) {
                BasicText(
                    text = path,
                    style = type.secondary.copy(color = tokens.colors.primary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        menu.sessions.forEach { session ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(session) }
                    .padding(
                        horizontal = tokens.spacing.cardInset,
                        vertical = tokens.spacing.codeToolbar,
                    ),
            ) {
                BasicText(
                    text = session,
                    style = type.secondary.copy(color = tokens.colors.primary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The tool row docked inside the card (iOS `composerControls`): the plus menu, the
 * permissions menu, the model menu and the running spinner.
 */
@Composable
private fun ComposerControls(
    state: ChatScreenState,
    onDraftChange: (String) -> Unit,
    onAddImages: () -> Unit,
    onAddFiles: () -> Unit,
    onSelectAccessMode: (String) -> Unit,
    onSelectModel: (String, String?) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val composer = state.composer
    var addOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.codeToolbar),
    ) {
        Box {
            BasicText(
                text = PLUS_GLYPH,
                style = type.secondary.copy(color = tokens.colors.primary),
                modifier = Modifier
                    .size(tokens.geometry.composerControlSize)
                    .clip(CircleShape)
                    .background(tokens.colors.selector)
                    .clickable(enabled = composer.enabled, role = Role.Button) { addOpen = true }
                    .semantics { contentDescription = UiStrings.COMPOSER_ADD_MENU }
                    .testTag(UiPageTags.COMPOSER_ADD)
                    .padding(tokens.spacing.iconText),
            )
            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                DropdownMenuItem(
                    text = { Text(UiStrings.COMPOSER_ADD_COMMAND) },
                    onClick = {
                        addOpen = false
                        // iOS: a leading `/` opens the command menu in place.
                        onPickSlashTrigger(onDraftChange)
                    },
                )
                DropdownMenuItem(
                    text = { Text(UiStrings.COMPOSER_ADD_REFERENCE) },
                    onClick = {
                        addOpen = false
                        onPickReferenceTrigger(onDraftChange)
                    },
                )
                DropdownMenuItem(
                    text = { Text(UiStrings.COMPOSER_ADD_IMAGE) },
                    onClick = {
                        addOpen = false
                        onAddImages()
                    },
                )
                DropdownMenuItem(
                    text = { Text(UiStrings.COMPOSER_ADD_FILE) },
                    onClick = {
                        addOpen = false
                        onAddFiles()
                    },
                )
            }
        }
        AccessModeMenuButton(state = state, onSelect = onSelectAccessMode)
        Box(modifier = Modifier.weight(1f))
        ModelMenuButton(state = state, onSelect = onSelectModel)
        if (composer.running) {
            CircularProgressIndicator(
                color = tokens.colors.tertiary,
                strokeWidth = tokens.stroke.hairline,
                modifier = Modifier
                    .size(tokens.geometry.composerControlSize)
                    .semantics { contentDescription = UiStrings.COMPOSER_RUNNING },
            )
        }
    }
}

/** The permissions menu: the translated modes, the current one checked. */
@Composable
private fun AccessModeMenuButton(
    state: ChatScreenState,
    onSelect: (String) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val menu = state.accessModeMenu
    var confirmation by remember { mutableStateOf<AccessModeOption?>(null) }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = tokens.geometry.composerControlSize)
                .clickable(enabled = menu.enabled, role = Role.Button) { open = true }
                .semantics { contentDescription = UiStrings.ACCESS_MODE_LABEL.format(menu.label) }
                .testTag(UiPageTags.COMPOSER_ACCESS_MODE)
                .padding(tokens.spacing.iconText),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.copyGap),
        ) {
            // iOS draws `lock.shield` + `chevron.down` as bare glyphs (no capsule). The
            // mark has to be a monochrome dingbat: an emoji rasterises in colour and
            // breaks the grey control row.
            BasicText(
                text = SHIELD_GLYPH,
                style = type.secondary.copy(color = tokens.colors.secondary),
            )
            BasicText(
                text = CHEVRON,
                style = type.caption.copy(color = tokens.colors.secondary),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val error = menu.error
            if (error != null) {
                // iOS `Section("暂不可用") { Text(error) }`: a group title and the
                // read failure's own words. The retry is the page banner's
                // (`accessModeErrorBanner`), so the menu does not repeat it.
                ChatMenuSectionLabel(text = UiStrings.ACCESS_MODE_UNAVAILABLE)
                BasicText(
                    text = error,
                    style = type.caption.copy(color = tokens.colors.warning),
                    modifier = Modifier.padding(
                        horizontal = tokens.spacing.cardInset,
                        vertical = tokens.spacing.codeToolbar,
                    ),
                )
            }
            if (menu.options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(UiStrings.ACCESS_MODE_NONE) },
                    onClick = {},
                    enabled = false,
                )
            } else {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (menu.asksBeforeWrite) {
                                UiStrings.ACCESS_MODE_HEADER_ASK
                            } else {
                                UiStrings.ACCESS_MODE_HEADER_ALLOW
                            },
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
                menu.options.forEach { option ->
                    ComposerMenuOption(
                        title = option.title,
                        selected = option.id == menu.currentID,
                        onClick = {
                            open = false
                            // The three most permissive ids ask for a confirmation
                            // first (iOS `confirmAccessMode`).
                            if (option.id in CONFIRM_IDS) confirmation = option else onSelect(option.id)
                        },
                    )
                }
            }
        }
    }
    val pending = confirmation
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmation = null },
            confirmButton = {
                Text(
                    text = UiStrings.ACCESS_MODE_CONFIRM_ACTION,
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            confirmation = null
                            onSelect(pending.id)
                        }
                        .padding(tokens.spacing.codeToolbar),
                )
            },
            dismissButton = {
                Text(
                    text = UiStrings.CANCEL,
                    modifier = Modifier
                        .clickable(role = Role.Button) { confirmation = null }
                        .padding(tokens.spacing.codeToolbar),
                )
            },
            title = { Text(UiStrings.ACCESS_MODE_CONFIRM_OPEN.format(pending.title)) },
            text = { Text(UiStrings.ACCESS_MODE_CONFIRM_MESSAGE) },
        )
    }
}

/** The model menu: the model list and the effort list, one check each. */
@Composable
private fun ModelMenuButton(
    state: ChatScreenState,
    onSelect: (String, String?) -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val menu = state.modelMenu
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = tokens.geometry.composerControlSize)
                .clickable(enabled = menu.enabled, role = Role.Button) { open = true }
                .semantics { contentDescription = UiStrings.MODEL_LABEL }
                .testTag(UiPageTags.COMPOSER_MODEL_MENU)
                .padding(tokens.spacing.iconText),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.copyGap),
        ) {
            BasicText(
                text = menu.label,
                style = type.secondary.copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (menu.effortTitle != null) {
                BasicText(
                    text = menu.effortTitle,
                    style = type.secondary.copy(color = tokens.colors.secondary),
                    maxLines = 1,
                )
            }
            BasicText(
                text = CHEVRON,
                style = type.caption.copy(color = tokens.colors.secondary),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (menu.unavailable) {
                DropdownMenuItem(
                    text = { Text(UiStrings.MODEL_UNAVAILABLE) },
                    onClick = {},
                    enabled = false,
                )
            } else {
                if (menu.efforts.isNotEmpty()) {
                    ChatMenuSectionLabel(text = UiStrings.MODEL_SECTION_EFFORT)
                    menu.efforts.forEach { effort ->
                        ComposerMenuOption(
                            title = effort.title,
                            selected = effort.id == menu.currentEffort,
                            onClick = {
                                open = false
                                onSelect(menu.currentModel.orEmpty(), effort.id)
                            },
                        )
                    }
                    // 「默认强度」不是一档,是「按这个模型自己那档来」:
                    // 后面跟上它现在实际等于哪个档,点了才不会没底。
                    ComposerMenuOption(
                        title = menu.defaultEffortTitle,
                        selected = menu.currentEffort == null,
                        onClick = {
                            open = false
                            onSelect(menu.currentModel.orEmpty(), null)
                        },
                    )
                }
                ChatMenuSectionLabel(text = UiStrings.MODEL_SECTION_MODEL)
                menu.models.forEach { model ->
                    ComposerMenuOption(
                        title = model,
                        selected = model == menu.currentModel,
                        onClick = {
                            open = false
                            onSelect(model, menu.currentEffort)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A titled group inside a composer menu (iOS `Section("强度")` / `Section("模型")`).
 *
 * The system menu draws no section chrome of its own, so the group gets a caption
 * line above its rows; the caption is a `UiStrings` word, never an assembled one.
 *
 * @param text the group's title.
 */
@Composable
private fun ChatMenuSectionLabel(text: String) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    BasicText(
        text = text,
        style = type.caption.copy(color = tokens.colors.tertiary),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.spacing.cardInset,
                vertical = tokens.spacing.codeToolbar,
            )
            .semantics { heading() },
    )
}

/**
 * One option line of a composer menu (iOS `Label(title, systemImage: "checkmark")`).
 *
 * The offer is data (a mode title, a model name, an effort title), so the row draws
 * the title exactly as the host reports it and marks the choice with a separate
 * fixed glyph plus the `selected` semantics. Nothing here assembles a label from a
 * condition, so the drawn word and the chosen state stay two different things.
 *
 * @param title the option's visible name.
 * @param selected true for the option in force.
 * @param onClick picks it.
 */
@Composable
private fun ComposerMenuOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = tokens.geometry.pageRowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(
                horizontal = tokens.spacing.cardInset,
                vertical = tokens.spacing.codeToolbar,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.iconText),
    ) {
        if (selected) {
            BasicText(
                text = CHECK_GLYPH,
                style = type.secondary.copy(color = tokens.colors.accent),
            )
        }
        BasicText(
            text = title,
            style = type.secondary.copy(color = tokens.colors.primary),
        )
    }
}

/** The page's token inventory; `PageTokenReferenceTest` fails when a use site reads a token that is not listed here. */
internal object ChatPageTokens {
    val refs: List<String> = listOf(
        UiTokenNames.COLORS_ACCENT,
        UiTokenNames.COLORS_BACKGROUND,
        UiTokenNames.COLORS_BORDER,
        UiTokenNames.COLORS_BORDER_LIGHT,
        UiTokenNames.COLORS_CODE,
        UiTokenNames.COLORS_CTA_OVERRIDE,
        UiTokenNames.COLORS_PAGE_ACCENT,
        UiTokenNames.COLORS_DANGER,
        UiTokenNames.COLORS_INPUT,
        UiTokenNames.COLORS_ON_ACCENT,
        UiTokenNames.COLORS_PAGE,
        UiTokenNames.COLORS_PRIMARY,
        UiTokenNames.COLORS_SECONDARY,
        UiTokenNames.COLORS_SELECTOR,
        UiTokenNames.COLORS_SUCCESS,
        UiTokenNames.COLORS_TERTIARY,
        UiTokenNames.COLORS_WARNING,
        UiTokenNames.GEOMETRY_BANNER_GAP,
        UiTokenNames.GEOMETRY_BANNER_INSET_H,
        UiTokenNames.GEOMETRY_BANNER_INSET_V,
        UiTokenNames.GEOMETRY_CARD_AREA_MAX_HEIGHT_RATIO,
        UiTokenNames.GEOMETRY_CARD_OUTER_MARGIN_H,
        UiTokenNames.GEOMETRY_CARD_OUTER_MARGIN_V,
        UiTokenNames.GEOMETRY_CARD_PADDING,
        UiTokenNames.GEOMETRY_COMPOSER_CONTROL_SIZE,
        UiTokenNames.GEOMETRY_COMPOSER_OUTER_MARGIN_H,
        UiTokenNames.GEOMETRY_COMPOSER_OUTER_MARGIN_TOP,
        UiTokenNames.GEOMETRY_DISCLOSURE_ROW_HEIGHT,
        UiTokenNames.GEOMETRY_PAGE_HEADER_MIN_HEIGHT,
        UiTokenNames.GEOMETRY_PAGE_LIST_ROW_MIN_HEIGHT,
        UiTokenNames.GEOMETRY_PAGE_OUTER_MARGIN_H,
        UiTokenNames.GEOMETRY_PAGE_ROW_MIN_HEIGHT,
        UiTokenNames.GEOMETRY_PRIMARY_BUTTON_HEIGHT,
        UiTokenNames.GEOMETRY_STATUS_DOT_DIAMETER,
        UiTokenNames.GEOMETRY_TOOL_DETAIL_VERTICAL,
        UiTokenNames.RADIUS_CARD,
        UiTokenNames.RADIUS_CODE_BLOCK,
        UiTokenNames.SPACING_BADGE_INSET_H,
        UiTokenNames.SPACING_BADGE_INSET_V,
        UiTokenNames.SPACING_CARD_INSET,
        UiTokenNames.SPACING_CODE_TOOLBAR,
        UiTokenNames.SPACING_COPY_GAP,
        UiTokenNames.SPACING_ICON_TEXT,
        UiTokenNames.STROKE_HAIRLINE,
        // L24: the three embedded subpages' type roles — the iOS `.callout`-sized
        // row label and the `.monospaced` command line.
        UiTokenNames.TYPOGRAPHY_BODY,
        UiTokenNames.TYPOGRAPHY_CAPTION,
        UiTokenNames.TYPOGRAPHY_CODE,
        UiTokenNames.TYPOGRAPHY_HEADLINE,
        UiTokenNames.TYPOGRAPHY_SECONDARY,
        UiTokenNames.TYPOGRAPHY_SUBHEADLINE,
    )

    /** No reviewed deviations: every token this page uses is declared by the spec. */
    val gaps: List<String> = emptyList()
}

/** The `kind` of ask that submits an answer instead of allowing an action. */
private const val QUESTION: String = "question"
private const val MORE_GLYPH: String = "⋯"
private const val LOCK_GLYPH: String = "⛨"
private const val COMMAND_GLYPH: String = "⌘"
private const val SHIELD_GLYPH: String = "⛨"
private const val PLUS_GLYPH: String = "+"
private const val CHEVRON: String = "▾"
private const val IMAGE_GLYPH: String = "▣"
private const val EMPTY_GLYPH: String = "○"
private const val SELECTED_GLYPH: String = "◉"

/** The menu's "this one is in force" mark (iOS `Label(title, systemImage: "checkmark")`). */
private const val CHECK_GLYPH: String = "✓"

/** iOS `arrow.down` on the 回到最新 capsule. */
private const val BACK_TO_LATEST_GLYPH: String = "↓"

/** The goal phases the bar translates (the host's wire words). */
private const val PHASE_ACTIVE: String = "active"
private const val PHASE_PAUSED: String = "paused"
private const val PHASE_BLOCKED: String = "blocked"

/** The modes whose switch needs a confirmation first (iOS `mode.id ==` table). */
private val CONFIRM_IDS: Set<String> = setOf("bypassPermissions", "danger-full-access", "phone")

/** The stable key of the transcript's chrome item. */
private const val CHROME_ITEM: String = "chat-transcript-chrome"

/** The rename alert's scrim opacity (the platform alert dims the page behind it). */
private const val SCRIM_ALPHA: Float = 0.25f

/** The question answer's single key (iOS `HarnessQuestionItem(id: "answer")`). */
private const val ANSWER_KEY: String = "answer"

/** iOS `errorBanner`'s wash behind a transcript failure (an 8% warning layer). */
private const val WARNING_WASH_ALPHA: Float = 0.08f

/** iOS `composerControls`' 调用指令: a bare `/` opens the command menu in place. */
private fun onPickSlashTrigger(onDraftChange: (String) -> Unit) {
    onDraftChange("/")
}

/** iOS `composerControls`' 引用文件或对话: a bare `@` opens the reference menu. */
private fun onPickReferenceTrigger(onDraftChange: (String) -> Unit) {
    onDraftChange("@")
}

// ---------------------------------------------------------------------------
// 内嵌子页 — the three surfaces the iOS page keeps beside itself
// ---------------------------------------------------------------------------
//
// README §1 line 86: 内嵌子页随父页实现、不单独排期 — CommandExecutionResultPage,
// SessionQueuePage, … are implemented with their parent page, not scheduled as
// pages of their own. The three surfaces below are the chat page's:
//
//   * `ChatPage.swift:3504` `NativeScheduleListSheet` (presented at `:445`),
//   * `ChatPage.swift:3554` `NativeJobListSheet` (presented at `:437`),
//   * `ChatPage.swift:4243` `CommandExecutionResultPage` (presented at `:407`).
//
// They live in this file because the baseline keeps them in `ChatPage.swift` as
// private structs of the same page; `PdshNavHost` additionally gives each a route
// so a screenshot test (and a host that owns the read) can open it directly.
//
// Data honesty, the rule the whole page follows:
//   * `jobs` / `schedules` are `null` when the host made no read. The baseline's
//     model arrays always exist, so it renders `没有后台任务` for an empty one; an
//     unread list must not wear that sentence, and renders the Android-only
//     `读取不到…` line instead (the same rule as `SessionActivityPage`'s 未知 rows).
//   * `刷新` is the host's read, not a local relabel: both baseline sheets call
//     the one `management.refreshActivity(sessionID:)` (`:441`, `:449`), so both
//     `刷新` actions hand off the same `PdshHostAction`. A `null` callback draws the
//     verb greyed-but-visible — an affordance the user has seen on the sheet is
//     never silently removed.

/**
 * One executed `/command` and its settlement (iOS `NativeCommandExecution`,
 * `ChatModel.swift:71`–`:77`).
 *
 * @param kind the host's own word; `success` is what makes the baseline open the
 *   result sheet (`ChatPage.swift:2516`). Kept raw so an unknown kind prints as
 *   itself instead of being coerced to a failure.
 * @param text the settlement body; `null` and `""` are the same branch (the
 *   baseline's `if let text, !text.isEmpty`).
 */
public data class ChatCommandExecution(
    public val id: String,
    public val name: String,
    public val line: String,
    public val kind: String,
    public val text: String? = null,
) {
    /** iOS `execution.kind == "success"` (`ChatPage.swift:4251`). */
    public val succeeded: Boolean get() = kind == KIND_SUCCESS
}

/** One background-job row (iOS `ActivityRow` as `NativeJobListSheet` draws it, `ChatPage.swift:3572`–`:3585`). */
public data class ChatJobRow(
    public val id: String,
    public val title: String,
    public val detail: String,
    /** The host's status word, or `null` at which the baseline draws nothing. */
    public val status: String? = null,
)

/** One scheduled-task row (iOS `ManagedSchedule` as `NativeScheduleListSheet` draws it, `ChatPage.swift:3521`–`:3540`). */
public data class ChatScheduleRow(
    public val id: String,
    public val title: String,
    public val detail: String,
    public val status: String? = null,
    /** The user's prompt; the baseline gives it two lines and only draws it when present. */
    public val prompt: String? = null,
)

/**
 * The three subpages' read-only input.
 *
 * A `null` list is "this host made no read" and a `null` execution is "no `/command`
 * ran on this host"; neither is ever `emptyList()`/a placeholder row. The host that
 * owns the read fills the field, and the sheet stops saying 读取不到 by itself —
 * nothing else changes.
 *
 * @param commandExecution the settlement the result page shows, if any ran.
 * @param jobs `management.jobs` (`ChatPage.swift:437`).
 * @param schedules `management.scheduleRecords` (`ChatPage.swift:445`).
 */
public data class ChatSubpageFacts(
    public val commandExecution: ChatCommandExecution? = null,
    public val jobs: List<ChatJobRow>? = null,
    public val schedules: List<ChatScheduleRow>? = null,
)

/** Which body a list sheet draws; [UNREAD] is the Android-only "the host did not read" case. */
public enum class ChatSubpageBody {
    /** The host reported no read; the sheet says 读取不到 rather than "none". */
    UNREAD,

    /** The host read the list and it is empty (`ContentUnavailableView` in the baseline). */
    EMPTY,

    /** The host read the list and it has rows. */
    ROWS,
}

/** 后台任务 sheet's body (`ChatPage.swift:3554`-`:3601`). */
public data class ChatJobsSheetState(
    public val body: ChatSubpageBody,
    public val rows: List<ChatJobRow> = emptyList(),
) {
    public companion object {
        /** The baseline has no unread branch; the Android page does, and says so. */
        public fun from(jobs: List<ChatJobRow>?): ChatJobsSheetState = when {
            jobs == null -> ChatJobsSheetState(ChatSubpageBody.UNREAD)
            jobs.isEmpty() -> ChatJobsSheetState(ChatSubpageBody.EMPTY)
            else -> ChatJobsSheetState(ChatSubpageBody.ROWS, jobs)
        }
    }
}

/** 定时任务 sheet's body (`ChatPage.swift:3504`-`:3552`). */
public data class ChatSchedulesSheetState(
    public val body: ChatSubpageBody,
    public val rows: List<ChatScheduleRow> = emptyList(),
) {
    public companion object {
        public fun from(schedules: List<ChatScheduleRow>?): ChatSchedulesSheetState = when {
            schedules == null -> ChatSchedulesSheetState(ChatSubpageBody.UNREAD)
            schedules.isEmpty() -> ChatSchedulesSheetState(ChatSubpageBody.EMPTY)
            else -> ChatSchedulesSheetState(ChatSubpageBody.ROWS, schedules)
        }
    }
}

/** 命令结果 page's body (`ChatPage.swift:4243`-`:4299`); a `null` execution is the unread case. */
public data class ChatCommandResultState(
    public val execution: ChatCommandExecution? = null,
) {
    /** The output body, or `null` for the baseline's 这次没有更多输出。 branch. */
    public val output: String? get() = execution?.text?.takeIf { it.isNotEmpty() }

    /** Whether the settlement says success; only read when [execution] is present. */
    public val succeeded: Boolean get() = execution?.succeeded == true
}

/**
 * 命令结果: one `/command` execution's settlement.
 *
 * `完成` is the baseline's `dismiss()` (`.topBarTrailing`, `ChatPage.swift:4276`), so
 * it only closes this surface; nothing here writes to the host.
 *
 * @param state what the host reported (`UNREAD` by default: this host owns no
 *   `commands/execute`, so no execution reaches it).
 * @param onDone close the sheet (the shell pops the route).
 * @param modifier the caller's placement.
 */
@Composable
public fun CommandExecutionResultPage(
    state: ChatCommandResultState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    val execution = state.execution
    ChatSubpageFrame(
        title = UiStrings.CHAT_COMMAND_RESULT_TITLE,
        rootTag = UiPageTags.CHAT_COMMAND_RESULT_ROOT,
        onClose = null,
        modifier = modifier,
        trailing = {
            SessionTextAction(
                label = UiStrings.CHAT_SUBPAGE_DONE,
                onClick = onDone,
                tone = SessionActionTone.ACCENT,
            )
        },
    ) {
        if (execution == null) {
            EmptyHint(
                title = UiStrings.CHAT_COMMAND_RESULT_UNREAD,
                hint = null,
                tag = UiPageTags.CHAT_COMMAND_RESULT_UNREAD,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Page-level margin; the baseline padds this page by 18 pt.
                    .padding(tokens.geometry.pageOuterMarginH)
                    .testTag(UiPageTags.CHAT_COMMAND_RESULT_BODY),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.cardInset),
            ) {
                BasicText(
                    text = if (state.succeeded) {
                        UiStrings.CHAT_COMMAND_RESULT_SUCCESS
                    } else {
                        UiStrings.CHAT_COMMAND_RESULT_FAILED
                    },
                    style = type.headline.copy(
                        color = if (state.succeeded) tokens.colors.primary else tokens.colors.danger,
                    ),
                    modifier = Modifier
                        .testTag(UiPageTags.CHAT_COMMAND_RESULT_STATUS)
                        .semantics { heading() },
                )
                // The command line in its own box: iOS
                // `.system(.callout, design: .monospaced)` on
                // `secondarySystemBackground` with a 12 pt radius.
                BasicText(
                    text = execution.line,
                    style = type.code.copy(color = tokens.colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiPageTags.CHAT_COMMAND_RESULT_LINE)
                        .clip(RoundedCornerShape(tokens.radius.codeBlock))
                        .background(tokens.colors.code)
                        .padding(tokens.spacing.cardInset),
                )
                val output = state.output
                if (output != null) {
                    BasicText(
                        text = output,
                        style = type.body.copy(color = tokens.colors.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiPageTags.CHAT_COMMAND_RESULT_OUTPUT),
                    )
                } else {
                    BasicText(
                        text = UiStrings.CHAT_COMMAND_RESULT_EMPTY,
                        style = type.secondary.copy(color = tokens.colors.secondary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(UiPageTags.CHAT_COMMAND_RESULT_OUTPUT),
                    )
                }
            }
        }
    }
}

/**
 * 后台任务: this session's background jobs (`ChatPage.swift:3554`-`:3601`).
 *
 * @param state the host's read, or `UNREAD`.
 * @param onRefresh re-read the session activity; `null` draws 刷新 greyed but
 *   visible (the sheet can never hide a verb it renders on the baseline).
 * @param onClose `关闭` (`.cancellationAction`, `:3596`).
 * @param modifier the caller's placement.
 */
@Composable
public fun NativeJobListSheet(
    state: ChatJobsSheetState,
    onRefresh: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    ChatSubpageFrame(
        title = UiStrings.CHAT_JOBS_TITLE,
        rootTag = UiPageTags.CHAT_JOBS_SHEET_ROOT,
        onClose = onClose,
        modifier = modifier,
        trailing = {
            SessionTextAction(
                label = UiStrings.CHAT_SUBPAGE_REFRESH,
                onClick = { onRefresh?.invoke() },
                tone = SessionActionTone.ACCENT,
                enabled = onRefresh != null,
            )
        },
    ) {
        when (state.body) {
            ChatSubpageBody.UNREAD -> EmptyHint(
                title = UiStrings.CHAT_JOBS_UNREAD,
                hint = null,
                tag = UiPageTags.CHAT_JOBS_SHEET_UNREAD,
            )

            ChatSubpageBody.EMPTY -> EmptyHint(
                title = UiStrings.CHAT_JOBS_EMPTY_TITLE,
                hint = UiStrings.CHAT_JOBS_EMPTY_DETAIL,
                tag = UiPageTags.CHAT_JOBS_SHEET_EMPTY,
            )

            ChatSubpageBody.ROWS -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiPageTags.CHAT_JOBS_SHEET_LIST),
                contentPadding = PaddingValues(vertical = tokens.geometry.cardOuterMarginV),
            ) {
                itemsIndexed(items = state.rows, key = { _, row -> row.id }) { index, row ->
                    if (index > 0) SessionHairline()
                    ChatJobRowView(row)
                }
            }
        }
    }
}

/**
 * 定时任务: this session's scheduled tasks (`ChatPage.swift:3504`-`:3552`).
 *
 * @param state the host's read, or `UNREAD`.
 * @param onRefresh re-read the session activity (the baseline's `onRefresh`, `:3549`).
 * @param onClose `关闭` (`.cancellationAction`, `:3547`).
 * @param modifier the caller's placement.
 */
@Composable
public fun NativeScheduleListSheet(
    state: ChatSchedulesSheetState,
    onRefresh: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalUiTokens.current
    ChatSubpageFrame(
        title = UiStrings.CHAT_SCHEDULES_TITLE,
        rootTag = UiPageTags.CHAT_SCHEDULES_SHEET_ROOT,
        onClose = onClose,
        modifier = modifier,
        trailing = {
            SessionTextAction(
                label = UiStrings.CHAT_SUBPAGE_REFRESH,
                onClick = { onRefresh?.invoke() },
                tone = SessionActionTone.ACCENT,
                enabled = onRefresh != null,
            )
        },
    ) {
        when (state.body) {
            ChatSubpageBody.UNREAD -> EmptyHint(
                title = UiStrings.CHAT_SCHEDULES_UNREAD,
                hint = null,
                tag = UiPageTags.CHAT_SCHEDULES_SHEET_UNREAD,
            )

            ChatSubpageBody.EMPTY -> EmptyHint(
                title = UiStrings.CHAT_SCHEDULES_EMPTY_TITLE,
                hint = UiStrings.CHAT_SCHEDULES_EMPTY_DETAIL,
                tag = UiPageTags.CHAT_SCHEDULES_SHEET_EMPTY,
            )

            ChatSubpageBody.ROWS -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiPageTags.CHAT_SCHEDULES_SHEET_LIST),
                contentPadding = PaddingValues(vertical = tokens.geometry.cardOuterMarginV),
            ) {
                itemsIndexed(items = state.rows, key = { _, row -> row.id }) { index, row ->
                    if (index > 0) SessionHairline()
                    ChatScheduleRowView(row)
                }
            }
        }
    }
}

/**
 * The subpage frame: the baseline's inline navigation bar (title centred between
 * `关闭` and the sheet's one trailing verb) over a full-height body.
 *
 * The title is data-free — every caller passes a `UiStrings` constant — so this
 * helper never assembles a label at runtime.
 */
@Composable
private fun ChatSubpageFrame(
    title: String,
    rootTag: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    // `colors.background` is tier: unmeasured, so an unspecified value leaves the
    // platform surface in place instead of an invented sRGB fill.
    val surface = if (tokens.colors.background.isSpecified) {
        Modifier.background(tokens.colors.background)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(surface)
            .testTag(rootTag)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .defaultMinSize(minHeight = tokens.geometry.pageHeaderMinHeight)
                .padding(horizontal = tokens.geometry.pageOuterMarginH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Equal weights keep the title centred whatever the two verbs measure,
            // the same reason the page header derives its 104 pt reserve.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (onClose != null) {
                    SessionTextAction(
                        label = UiStrings.DISMISS,
                        onClick = onClose,
                        tone = SessionActionTone.SECONDARY,
                    )
                }
            }
            BasicText(
                text = title,
                style = type.headline.copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                trailing?.invoke()
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            content()
        }
    }
}

/** One job row: title, one-line detail, and the status on the right when reported. */
@Composable
private fun ChatJobRowView(row: ChatJobRow) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.geometry.bannerInsetV,
            )
            .testTag(UiPageTags.CHAT_JOBS_SHEET_ROW_PREFIX + row.id),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetH),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetV),
        ) {
            BasicText(
                text = row.title,
                style = type.body.copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = row.detail,
                style = type.caption.copy(color = tokens.colors.tertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.status != null) {
            BasicText(
                text = row.status,
                style = type.caption.copy(color = tokens.colors.secondary),
                maxLines = 1,
            )
        }
    }
}

/** One schedule row: title + status, its detail line, then the prompt (two lines). */
@Composable
private fun ChatScheduleRowView(row: ChatScheduleRow) {
    val tokens = LocalUiTokens.current
    val type = LocalUiType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.geometry.pageOuterMarginH,
                vertical = tokens.geometry.bannerInsetV,
            )
            .testTag(UiPageTags.CHAT_SCHEDULES_SHEET_ROW_PREFIX + row.id),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetV),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.badgeInsetH),
        ) {
            BasicText(
                text = row.title,
                style = type.body.copy(color = tokens.colors.primary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.status != null) {
                BasicText(
                    text = row.status,
                    style = type.caption.copy(color = tokens.colors.secondary),
                    maxLines = 1,
                )
            }
        }
        BasicText(
            text = row.detail,
            style = type.caption.copy(color = tokens.colors.tertiary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.prompt != null) {
            BasicText(
                text = row.prompt,
                style = type.secondary.copy(color = tokens.colors.secondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The kind word that means "the command ran" (`ChatPage.swift:2516`). */
private const val KIND_SUCCESS: String = "success"
private const val TODO_COMPLETED: String = "completed"
private const val TODO_IN_PROGRESS: String = "in_progress"
private const val TODO_PENDING: String = "pending"
private const val TODO_DOCK_TAG: String = "pdsh.chat.todo-dock"
private const val TODO_DOCK_ROW_TAG: String = "pdsh.chat.todo-row."
private const val QUESTION_TAKEOVER_TAG: String = "pdsh.chat.question-takeover"
private const val PLAN_REVIEW_TAKEOVER_TAG: String = "pdsh.chat.plan-review-takeover"
private const val PLAN_REVIEW_APPROVE_TAG: String = "pdsh.chat.plan-review-approve"
private const val PLAN_REVIEW_CHANGE_TAG: String = "pdsh.chat.plan-review-change"
private const val PLAN_REVIEW_INTENT: String = "plan-review"
private const val SLASH_COMMAND_TAG_PREFIX: String = "pdsh.chat.slash-command."
private const val CHAT_HEADER_DEVICE_TAG: String = "pdsh.chat.header.device"
private const val CHAT_RESTART_ENGINE_TAG: String = "pdsh.chat.restart-engine"
