// The Chat family's iOS-baseline states, as pure `ChatScreenState` values.
// SPDX-License-Identifier: MIT
//
// Why this file exists: a parity capture is only comparable while its *content* is
// the baseline's content. Every string below is copied from the matching
// `docs/receipts/ios-baseline-screens/ios-chat-*.png` (and `ios-code-block.png`),
// so the Android frame and the iOS frame carry the same words in the same order;
// what is left to compare is geometry, not wording.
//
// Nothing here reads a clock, a locale, a file or the engine: each builder is a
// pure function, so the capture is reproducible. A field whose value the iOS shot
// does not show stays at the `ChatScreenState` default rather than being filled
// with invented content.
package com.phonedsh.ui.screenshot

import com.phonedsh.ui.UiStrings
import com.phonedsh.ui.state.BannerState
import com.phonedsh.ui.state.ChatEmpty
import com.phonedsh.ui.state.ChatPhase
import com.phonedsh.ui.state.ChatRow
import com.phonedsh.ui.state.ChatScreenState
import com.phonedsh.ui.state.ComposerState
import com.phonedsh.ui.state.EffortOption
import com.phonedsh.ui.state.GoalBarState
import com.phonedsh.ui.state.ModelMenu
import com.phonedsh.ui.state.PendingAnswer
import com.phonedsh.ui.state.PendingSlot
import com.phonedsh.ui.state.RunStatus
import com.phonedsh.ui.state.ToolEntry
import com.phonedsh.ui.surface.ComposerMode
import com.phonedsh.ui.surface.ToolRowState

/** The nine iOS Chat-family states, one builder each. */
object ChatParitySamples {

    // ------------------------------------------------------------------ wording

    /** `ios-chat-*.png` header centre: a new session has no name yet. */
    const val TITLE: String = "新会话"

    /** `ios-chat-default.png`: the permission card's body. */
    const val PERMISSION_BODY: String = "要跑一条命令,可以吗?"

    /** `ios-chat-permission.png`: the two cards' bodies. */
    const val PERMISSION_WRITE_BODY: String = "要写一个文件,可以吗?"
    const val PERMISSION_WRITE_DETAIL: String = "notes/todo.md"
    const val PERMISSION_RUN_DETAIL: String = "swift build"

    /** `ios-chat-assistant.png`: the user bubble and the answer's two paragraphs. */
    const val USER_HELLO: String = "hello test 123"
    const val ASSISTANT_INTRO: String = "先看了工程的入口,.build 目录已经在忽略列表里。"
    const val ASSISTANT_OUTRO: String = "接下来我会把这一段改动落到 ChatPage.swift,然后重新编译验证。"

    /** The `swift` fence of `ios-chat-assistant.png` / `ios-code-block.png`. */
    const val CODE_LANGUAGE: String = "swift"
    const val CODE_ASSISTANT: String =
        "let scheme = \"PhoneDSHProbe\"\nlet destination = \"platform=iOS Simulator\""

    /** `ios-code-block.png`: the long-line swatch (it scrolls horizontally on iOS). */
    const val CODE_SWATCH: String =
        "let scheme =\n" +
            "\"PhoneDSHProbeExampleLongLineToCheckHorizontalScroll\"\n" +
            "let destination = \"platform=iOS Simulator,id=65F16681\""

    /** `ios-chat-default.png`: the two paragraphs of the answer. */
    const val ASSISTANT_FIX: String =
        "第二个用例的边界写错了：不到一分钟的分支没有命中，落到了按分钟计算那一支。" +
            "改法是把这个判断提前，并且给 59 秒和 60 秒各加一条用例。"
    const val ASSISTANT_NEXT: String = "改完我会再跑一遍全量，把两个失败一起验证，然后把 diff 贴上来。"
    const val ASSISTANT_RUNNING: String = "已经改好了，正在跑最后一遍。"
    const val TOOL_ASSERTION: String = "第二个断言失败：期望「刚刚」，实际「10 分钟前」"
    const val TOOL_FILE: String = "Tests/SessionTimeTests.swift"
    const val TOOL_LINES: String = "42 行"

    /** `ios-chat-failed.png`: the two bubbles and the failure line. */
    const val USER_CREATE: String = "Create note.txt in the workspace with content hello dsh"
    const val FAILED_REASON: String = "示例：这次回合失败了。"

    /** `ios-chat-queued.png` */
    const val USER_QUEUED: String = "把这周的日志按天归档"

    /** The 5000-character draft of `ios-chat-draft.png` / `ios-chat-long.png`. */
    val LONG_DRAFT: String = buildString {
        repeat(40) {
            append("把这句话放进输入框里,看看行高和滚动还正不正常。")
            append("Mixed English words like accessibilityIdentifier and 1234567890 should wrap.")
            append('\n')
        }
    }

    /** `ios-chat-long.png`: the model answer the draft is typed under. */
    val LONG_ANSWER: String = buildString {
        repeat(12) {
            append("这段是模型的回答,用来核对长正文的行高与滚动是否正常。\n")
        }
    }

    /** `ios-chat-official.png`: the stats line under the composer card. */
    const val STATS: String = "5 轮 17 步  ·  54 tok/s  ·  1.0M tok  ·  缓命中 75%"

    // ------------------------------------------------------------------- states

    /** `ios-chat-default.png`: running host, one folded ask, tool rows, live answer. */
    fun chatDefault(): ChatScreenState = base().copy(
        phase = ChatPhase.RUNNING,
        runStatus = RunStatus.STARTED,
        // One permission ask on screen and one question ask folded into the
        // "还有 1 个待确认" line above it — the shot shows exactly that pair.
        pending = PendingSlot(
            primary = permissionAsk("ask-permission", PERMISSION_BODY),
            folded = listOf(questionAsk("ask-question")),
        ),
        rows = listOf(
            ChatRow.ToolRun(
                id = "row-tool-1",
                entries = listOf(
                    ToolEntry(
                        id = "row-tool-1",
                        title = TOOL_ASSERTION,
                        summary = "",
                        state = ToolRowState.ERROR,
                        body = "expected: 刚刚\nactual:   10 分钟前",
                    ),
                ),
            ),
            ChatRow.Assistant("row-assistant-1", ASSISTANT_FIX),
            ChatRow.Assistant("row-assistant-2", ASSISTANT_NEXT),
            ChatRow.ToolRun(
                id = "row-tool-2",
                entries = listOf(
                    ToolEntry(id = "row-tool-2", title = TOOL_FILE, summary = "", state = ToolRowState.OK),
                    ToolEntry(id = "row-tool-3", title = TOOL_LINES, summary = "", state = ToolRowState.OK),
                ),
            ),
            ChatRow.Thinking("row-thinking-1", ASSISTANT_RUNNING, streaming = true),
        ),
        composer = ComposerState(
            enabled = false,
            canSubmit = false,
            placeholder = UiStrings.COMPOSER_PLACEHOLDER_IDLE,
        ),
        modelMenu = ModelMenu(
            modelName = "deepseek-flash",
            effortTitle = "High",
            models = listOf("deepseek-flash"),
            currentModel = "deepseek-flash",
            efforts = listOf(EffortOption("high", "High")),
            currentEffort = "high",
            enabled = false,
        ),
    )

    /** `ios-chat-assistant.png`: one settled turn with a code block in the answer. */
    fun chatAssistant(): ChatScreenState = base().copy(
        phase = ChatPhase.IDLE,
        runStatus = RunStatus.STOPPED,
        rows = listOf(
            ChatRow.User("row-user-1", USER_HELLO, timestamp = null),
            ChatRow.ToolRun(
                id = "row-tool-1",
                entries = listOf(
                    ToolEntry(
                        id = "row-tool-1",
                        title = "Read",
                        summary = "Package.swift",
                        state = ToolRowState.OK,
                        body = "",
                    ),
                ),
            ),
            ChatRow.Assistant(
                "row-assistant-1",
                "$ASSISTANT_INTRO\n```$CODE_LANGUAGE\n$CODE_ASSISTANT\n```\n$ASSISTANT_OUTRO",
                closing = true,
            ),
        ),
        // The baseline's send key is the pale disabled one: the draft is empty and
        // nothing is running.
        composer = ComposerState(canSubmit = false),
    )

    /** `ios-chat-draft.png`: empty state A under a maximal draft. */
    fun chatDraft(): ChatScreenState = base().copy(
        phase = ChatPhase.IDLE,
        runStatus = RunStatus.STOPPED,
        empty = ChatEmpty.ENGINE_NOT_STARTED,
        rows = emptyList(),
        composer = ComposerState(
            draft = LONG_DRAFT,
            mode = ComposerMode.SEND,
            enabled = true,
            // The shot's send key is the pale one: the host is not started, so a
            // draft alone cannot be sent (`ChatModel.canSend` is false).
            canSubmit = false,
        ),
    )

    /** `ios-chat-long.png`: a long answer above the same maximal draft. */
    fun chatLong(): ChatScreenState = base().copy(
        phase = ChatPhase.IDLE,
        runStatus = RunStatus.STOPPED,
        rows = listOf(
            ChatRow.User("row-user-1", LONG_DRAFT),
            ChatRow.Assistant("row-assistant-1", LONG_ANSWER),
        ),
        composer = ComposerState(
            draft = LONG_DRAFT,
            mode = ComposerMode.SEND,
            enabled = true,
            canSubmit = false,
        ),
    )

    /** `ios-chat-failed.png`: two bubbles, the second one failed with the inline line. */
    fun chatFailed(): ChatScreenState = base().copy(
        phase = ChatPhase.FAILED,
        runStatus = RunStatus.STOPPED,
        rows = listOf(
            ChatRow.User("row-user-1", USER_HELLO),
            ChatRow.User("row-user-2", USER_CREATE),
        ),
        failedItemID = "row-user-2",
        userFacingError = FAILED_REASON,
        // Not retryable in the shot: iOS draws the words only, no 重试.
        banners = listOf(
            BannerState(
                id = "banner-1",
                message = FAILED_REASON,
                reason = "turn",
                retryable = false,
                dismissible = false,
            ),
        ),
        composer = ComposerState(canSubmit = false),
    )

    /** `ios-chat-permission.png`: two permission cards, no strip, no transcript. */
    fun chatPermission(): ChatScreenState = base().copy(
        phase = ChatPhase.IDLE,
        runStatus = RunStatus.STOPPED,
        pending = PendingSlot(
            primary = permissionAsk("ask-write", PERMISSION_WRITE_BODY, PERMISSION_WRITE_DETAIL),
            folded = listOf(permissionAsk("ask-run", PERMISSION_BODY, PERMISSION_RUN_DETAIL)),
        ),
        pendingExpanded = true,
        rows = emptyList(),
        composer = ComposerState(enabled = false, canSubmit = false),
    )

    /** `ios-chat-queued.png`: one sent bubble and one queued bubble under it. */
    fun chatQueued(): ChatScreenState = base().copy(
        phase = ChatPhase.RUNNING,
        runStatus = RunStatus.STOPPED,
        rows = listOf(
            ChatRow.User("row-user-1", USER_HELLO),
            ChatRow.User("row-user-2", USER_QUEUED, badge = UiStrings.QUEUED_TAG),
        ),
        // A turn runs, but the shot shows the pale send key rather than a stop key.
        // The message is in the host queue, so the queue strip renders above the
        // composer (`ios-chat-queued.png` y 705-719 pt: text + edit + delete +
        // send-now); without `queued` the strip had no state to draw.
        composer = ComposerState(
            running = true,
            canSubmit = false,
            queued = listOf(USER_QUEUED),
        ),
    )

    /** `ios-chat-official.png`: a short thread plus the stats line under the card. */
    fun chatOfficial(): ChatScreenState = base().copy(
        phase = ChatPhase.IDLE,
        runStatus = RunStatus.STOPPED,
        rows = listOf(
            ChatRow.User("row-user-1", USER_HELLO),
            ChatRow.Assistant("row-assistant-1", ASSISTANT_NEXT, closing = true),
        ),
        stats = STATS,
        contextPercent = 10,
        composer = ComposerState(canSubmit = false),
    )

    /** `ios-code-block.png`: the `swift` swatch on its own. */
    const val CODE_BLOCK_LANGUAGE: String = CODE_LANGUAGE
    const val CODE_BLOCK_BODY: String = CODE_SWATCH

    // ------------------------------------------------------------------ helpers

    /**
     * The defaults every state shares.
     *
     * `empty = READY` because every shot except the draft one has the transcript
     * on screen; `transcript` and `goal` stay at their "host reported nothing"
     * values, which is also what the shots show (no 加载更早消息 row, no goal bar).
     */
    private fun base(): ChatScreenState = ChatScreenState(
        sessionID = "sess-chat-parity",
        phase = ChatPhase.IDLE,
        rows = emptyList(),
        pending = PendingSlot(),
        banners = emptyList(),
        goal = GoalBarState.Hidden,
        expandedRowIds = emptySet(),
        stats = null,
        empty = ChatEmpty.READY,
        runStatus = RunStatus.STOPPED,
        composer = ComposerState(),
    )

    private fun permissionAsk(id: String, body: String, detail: String? = null): PendingAnswer =
        PendingAnswer(
            id = id,
            requestId = id,
            kind = "permission",
            title = "等待授权",
            subtitle = if (detail == null) body else "$body\n$detail",
        )

    private fun questionAsk(id: String): PendingAnswer = PendingAnswer(
        id = id,
        requestId = id,
        kind = "question",
        title = "需要回答",
        subtitle = "这个问题先折叠，等上一条授权处理完再展开。",
        options = listOf("继续", "先停一下"),
    )
}
