// The chat surface's host half: Worker events -> `ui-state/1` nodes, and the one
// `ChatScreenState` the page renders.
// SPDX-License-Identifier: MIT
//
// ---- why the mapping lives here and not in `:lib` -------------------------
// `:lib` owns the *pure* layer: `ChatScreenState.from(nodes, local, host)` folds a
// node sequence into rows, and `UiStateNode`/`ChatRow` are its types. What a node
// *is* comes from the host's event stream, which `:lib` must not know about: it
// has no `:core`/`:dsh` edge and no idea that a Worker exists. So the Worker's
// durable events (`turn/start`, `assistant/message`, `tool/call`, ...) become
// nodes here, in the App, and the page stays a pure function of the result.
//
// ---- what is a node here --------------------------------------------------
// Only what `ChatScreenState.from` reads is projected: a user bubble, an assistant
// bubble, the two halves of a tool row, and a visible turn-end row for an ending
// that is not `completed`. Structural nodes (`session`, the running `turn`) are
// added by [chatScreenState] because they describe the App's own current
// conversation, not the Worker's history. `usage` is folded into the message and
// has no row of its own (`event-kinds.md` §1).
//
// ---- what is live and what is durable -------------------------------------
// The streaming text is *not* a node: `ChatScreenState.from` would treat a
// streamed bubble as a settled row, and the durable `assistant/message` that
// arrives at the end replaces it anyway. `ChatUiState.streaming` is therefore
// drawn as one extra row while the turn runs and disappears when the settled
// message lands. Only durable rows are persisted (`ChatSessionStore`).
package com.phonedsh.probe

import com.phonedsh.agentcore.JsonArray
import com.phonedsh.agentcore.JsonBool
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.JsonValue
import com.phonedsh.hostevents.HostEvent
import com.phonedsh.hostevents.HostEventKind
import com.phonedsh.ui.projection.UiStateKind
import com.phonedsh.ui.projection.UiStateNode
import com.phonedsh.ui.projection.UiStateSurface
import com.phonedsh.ui.state.ChatAttachment
import com.phonedsh.ui.state.ChatEmpty
import com.phonedsh.ui.state.ChatHostFacts
import com.phonedsh.ui.state.ChatLocalState
import com.phonedsh.ui.state.ChatPhase
import com.phonedsh.ui.state.ChatRow
import com.phonedsh.ui.state.ChatScreenState
import com.phonedsh.ui.state.RunStatus

/**
 * The chat screen's host state.
 *
 * @param chatId this App's stable conversation id; empty means "no conversation
 *   started yet" (a fresh 新建会话 before its first message).
 * @param title the user's name; empty renders the page's 新会话 word.
 * @param draft what the person typed.
 * @param nodes every durable row of the conversation, in order.
 * @param attachments imports the person added but has not sent.
 * @param running a turn is in flight.
 * @param activeTurnNodeId the `turn` node of the running turn, or `null`; it is
 *   what makes `ChatScreenState.from` report RUNNING and the dock show 停止.
 * @param streaming the live text the Worker has streamed for the running turn.
 * @param notice the last workspace-import status line; `null` hides it.
 * @param error the last send failure; `null` hides the banner.
 * @param attachmentError the last attachment failure; `null` hides the line.
 * @param updatedAtMs when this conversation last changed (list order).
 * @param bannerDismissed whether the person closed the current failure banner.
 */
internal data class ChatUiState(
    val chatId: String = "",
    val title: String = "",
    val draft: String = "",
    val nodes: List<UiStateNode> = emptyList(),
    /**
     * The Worker session the next turn continues, or `null` before the first
     * send.
     *
     * Persisted in the chat record, so it also survives a cold start. It is a
     * *resumable* id, not just a claim: the Worker keeps its session log under
     * `/dsh/home/sessions`, repacks it into every boot overlay, and
     * `session/prompt` resumes a non-live session from it — which is what keeps
     * the model's context equal to the transcript (`ChatTurnRunner`).
     */
    val workerSessionId: String? = null,
    /**
     * The prefix of this send's turn ids (`t<n>-`).
     *
     * A Worker session that had to be replaced (the stored log was really gone)
     * starts its turn numbering at `turn-0001` again, so the Worker's own turn id is not
     * unique inside one conversation. Prefixing it per send keeps every row's
     * nodeID unique — the page uses the id as its list key, and a duplicate key
     * crashed the frame (`IllegalArgumentException: Key ... was already used`,
     * measured 2026-11-02).
     */
    val turnToken: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val running: Boolean = false,
    val activeTurnNodeId: String? = null,
    val streaming: String = "",
    val reasoningStreaming: String = "",
    val notice: String? = null,
    val error: String? = null,
    val attachmentError: String? = null,
    val updatedAtMs: Long = 0L,
    val bannerDismissed: Boolean = false,
    val sessionSnapshot: ChatSessionSnapshot = ChatSessionSnapshot(),
    val pendingNodes: List<UiStateNode> = emptyList(),
    val lastSubmission: String = "",
    val lastAttachments: List<ChatAttachment> = emptyList(),
    /** Stable `/api/session/prompt` identity for the original submission being retried. */
    val lastPromptRequestId: String? = null,
    /** New requests accepted locally but not yet matched to the durable inbox projection. */
    val localQueueItems: List<com.phonedsh.ui.state.ManagedQueueItemState> = emptyList(),
    val imeVisible: Boolean = false,
    val pendingExpanded: Boolean = false,
    val expandedRowIds: Set<String> = emptySet(),
    val commandExecution: com.phonedsh.ui.page.ChatCommandExecution? = null,
) {
    /** True when this state names a conversation the session list can show. */
    val hasConversation: Boolean get() = chatId.isNotEmpty()
}

/**
 * Folds [ui] into the page's `ChatScreenState`.
 *
 * @param engineStarted the host is up, so an empty conversation is READY rather
 *   than 引擎还没启动.
 * @param providerNeedsKey no usable model service is configured.
 * @param canSend the host can accept a message right now.
 */
internal fun chatScreenState(
    ui: ChatUiState,
    engineStarted: Boolean,
    providerNeedsKey: Boolean,
    canSend: Boolean,
    canSteer: Boolean = false,
    enterMode: com.phonedsh.ui.state.EnterMode = com.phonedsh.ui.state.EnterMode.QUEUE,
    lifecycle: com.phonedsh.ui.state.EngineLifecycleFacts? = null,
): ChatScreenState {
    val chatId = ui.chatId.ifEmpty { PENDING_CHAT_ID }
    val nodes = ArrayList<UiStateNode>(ui.nodes.size + 2)
    nodes += sessionNode(chatId)
    // A stored row that repeats a nodeID would be a duplicate list key in the page;
    // the first occurrence is the row the person saw.
    nodes += ui.nodes.distinctBy { it.nodeID }
    nodes += ui.pendingNodes
    if (ui.running && ui.reasoningStreaming.isNotEmpty()) {
        val turn = ui.activeTurnNodeId ?: "live"
        val thinking = UiStateNode(
            kind = UiStateKind.THINKING_PILL,
            nodeID = "session/$chatId/turn/$turn/event/0/thinking-live",
            parentID = "session/$chatId/turn/$turn", sessionID = chatId, turnID = turn, seq = 0,
            text = ui.reasoningStreaming, surface = UiStateSurface.THINKING_PILL,
            props = JsonObject(mapOf("streaming" to JsonBool(true))),
        )
        val insertion = nodes.indexOfFirst { it.kind == UiStateKind.TOOL_ROW && it.turnID == turn }
        if (insertion >= 0) nodes.add(insertion, thinking) else nodes += thinking
    }
    ui.activeTurnNodeId?.let { active ->
        val turn = turnNode(chatId, active)
        if (nodes.none { it.nodeID == turn.nodeID }) nodes += turn
    }
    if (ui.error != null && !ui.bannerDismissed) {
        nodes += bannerNode(
            chatId,
            ui.error,
            retryable = !ui.lastPromptRequestId.isNullOrBlank() &&
                (ui.lastSubmission.isNotBlank() || ui.lastAttachments.isNotEmpty()),
        )
    }
    val local = ChatLocalState(
        draft = ui.draft,
        attachments = ui.attachments,
        attachmentError = ui.attachmentError,
        fileImportNotice = ui.notice,
        dismissedBannerIds = if (ui.bannerDismissed) setOf(bannerId(chatId)) else emptySet(),
        imeVisible = ui.imeVisible,
        pendingSlotExpanded = ui.pendingExpanded,
        expandedRowIds = ui.expandedRowIds,
    )
    val host = ChatHostFacts(
        engineStarted = engineStarted,
        // Queue and steer are host-owned session/prompt modes. The typed action
        // table controls which one the page can submit while the turn is live.
        canSend = canSend,
        canSteer = canSteer,
        enterMode = enterMode,
        providerNeedsKey = providerNeedsKey,
        lifecycle = lifecycle,
        todoItems = ui.sessionSnapshot.todos,
        accessModes = ui.sessionSnapshot.accessModes,
        currentAccessMode = ui.sessionSnapshot.currentAccessMode,
        commands = ui.sessionSnapshot.commands,
        workerSessionID = ui.workerSessionId,
        accessModeError = ui.sessionSnapshot.accessError,
        commandsError = ui.sessionSnapshot.commandsError,
        models = ui.sessionSnapshot.modelRoutes.map { it.model }.distinct(),
        currentModel = ui.sessionSnapshot.currentModel,
        currentEffort = ui.sessionSnapshot.currentEffort,
        efforts = ui.sessionSnapshot.modelRoutes.firstOrNull { it.model == ui.sessionSnapshot.currentModel }?.efforts.orEmpty(),
        effortTitle = ui.sessionSnapshot.currentEffort?.let { effortTitle(it) },
        defaultEffortTitle = ui.sessionSnapshot.modelRoutes.firstOrNull { it.model == ui.sessionSnapshot.currentModel }
            ?.defaultEffort?.let { "默认强度 · ${effortTitle(it)}" } ?: com.phonedsh.ui.UiStrings.MODEL_DEFAULT_EFFORT,
        queueItems = ui.sessionSnapshot.queueItems?.map { item -> item.copy(canSteer = ui.running) }
            ?.let { durable -> com.phonedsh.ui.state.reconcileQueueRows(durable, ui.localQueueItems) }
            ?: ui.localQueueItems.takeIf { it.isNotEmpty() },
    )
    val base = ChatScreenState.from(nodes = nodes, local = local, host = host, goal = ui.sessionSnapshot.goal)
    val rows = if (ui.running && ui.streaming.isNotEmpty()) {
        base.rows + ChatRow.Assistant(
            id = "streaming/$chatId",
            text = ui.streaming,
            streaming = true,
        )
    } else {
        base.rows
    }
    return base.copy(
        rows = rows,
        // The transcript's empty-state choice is a host fact, not a projection: the
        // page shows A/B/C from these two readings.
        empty = when {
            !engineStarted -> ChatEmpty.ENGINE_NOT_STARTED
            providerNeedsKey -> ChatEmpty.PROVIDER_SETUP
            else -> ChatEmpty.READY
        },
        runStatus = if (engineStarted) RunStatus.STARTED else RunStatus.STOPPED,
        // `ChatScreenState.from` derives the phase from open turn nodes; an empty
        // conversation has none, and the host is the only layer that knows whether a
        // turn is running.
        phase = when {
            ui.running -> ChatPhase.RUNNING
            base.phase == ChatPhase.WAITING && engineStarted -> ChatPhase.IDLE
            else -> base.phase
        },
    )
}

/** The screen state of one stored record: what a cold start reopens. */
internal fun ChatRecord.toUiState(): ChatUiState = ChatUiState(
    chatId = id,
    title = title,
    nodes = nodes,
    workerSessionId = workerSessionIds.lastOrNull(),
    updatedAtMs = updatedAtMs,
)

/** This send's node turn id for one Worker turn number. */
internal fun ChatUiState.turnIdOf(workerTurn: String?): String {
    val base = workerTurn ?: "unknown"
    return if (turnToken.isEmpty()) base else "$turnToken-$base"
}

/** The structural `session` node of one conversation (`trajectory.md` §7). */
internal fun sessionNode(chatId: String): UiStateNode = UiStateNode(
    kind = UiStateKind.SESSION,
    nodeID = "session/$chatId",
    parentID = "root",
    sessionID = chatId,
    turnID = SESSION_SCOPE_TURN_ID,
    seq = 0,
)

/** The structural `turn` node of the running turn. */
internal fun turnNode(chatId: String, turnId: String): UiStateNode = UiStateNode(
    kind = UiStateKind.TURN,
    nodeID = "session/$chatId/turn/$turnId",
    parentID = "session/$chatId",
    sessionID = chatId,
    turnID = turnId,
    seq = 0,
)

/** The transient fail-loud banner of a send failure; never persisted. */
private fun bannerNode(chatId: String, message: String, retryable: Boolean): UiStateNode = UiStateNode(
    kind = UiStateKind.FAIL_LOUD_BANNER,
    nodeID = bannerId(chatId),
    parentID = "session/$chatId",
    sessionID = chatId,
    turnID = SESSION_SCOPE_TURN_ID,
    seq = 0,
    surface = UiStateSurface.FAIL_LOUD_BANNER,
    text = message,
    props = JsonObject(
        linkedMapOf(
            "reason" to JsonString("error"),
            "retryable" to JsonBool(retryable),
            "dismissible" to JsonBool(true),
        ),
    ),
)

private fun bannerId(chatId: String): String = "session/$chatId/banner/send"

/**
 * Projects one Worker event into the node(s) the screen shows for it.
 *
 * @return the node, or `null` when this event has no row (a turn start, a usage
 *   fold, a completed turn end).
 */
internal fun chatNodeOf(
    chatId: String,
    turnToken: String,
    event: HostEvent,
    ordinal: Int,
): UiStateNode? {
    val workerTurn = event.turnId ?: FALLBACK_TURN_ID
    val turnId = if (turnToken.isEmpty()) workerTurn else "$turnToken-$workerTurn"
    val seq = event.seq?.toInt() ?: ordinal
    fun nodeID(slot: String): String = "session/$chatId/turn/$turnId/event/$seq/$slot"
    return when (event.kind) {
        HostEventKind.ASSISTANT_MESSAGE -> {
            val text = assistantTextOf(event)
            if (text.isBlank()) return null
            // The reasoning row is a real Worker payload too. Keep the complete
            // value when it fits the ui-state text bound, and say explicitly
            // when it does not; summary-only rendering loses the source and
            // makes a clipped value look complete.
            val reasoning = assistantReasoningOf(event)
            UiStateNode(
                kind = UiStateKind.BUBBLE,
                nodeID = nodeID("assistant-message"),
                parentID = "session/$chatId/turn/$turnId",
                sessionID = chatId,
                turnID = turnId,
                seq = seq,
                text = bounded(text),
                props = JsonObject(
                    linkedMapOf(
                        "role" to JsonString("assistant"),
                        "truncated" to JsonBool(text.length > TEXT_LIMIT),
                        "usage" to (event.data?.get("usage")
                            ?: (event.data?.get("message") as? JsonObject)?.get("usage")
                            ?: com.phonedsh.agentcore.JsonNull),
                        "fullReasoning" to JsonString(bounded(reasoning)),
                        "fullReasoningTruncated" to JsonBool(reasoning.length > TEXT_LIMIT),
                    ),
                ),
            )
        }

        HostEventKind.TOOL_CALL -> {
            val data = event.data ?: return null
            val name = (data["name"] as? JsonString)?.value.orEmpty()
            val callId = (data["callId"] as? JsonString)?.value
                ?: (data["call_id"] as? JsonString)?.value
                ?: nodeID("tool-call")
            UiStateNode(
                kind = UiStateKind.TOOL_ROW,
                nodeID = nodeID("tool-call"),
                parentID = "session/$chatId/turn/$turnId",
                sessionID = chatId,
                turnID = turnId,
                seq = seq,
                surface = UiStateSurface.TOOL_ROW,
                text = name.ifEmpty { null },
                props = JsonObject(
                    linkedMapOf(
                        "arguments" to (data["arguments"] ?: data["input"] ?: JsonString("")),
                        "callId" to JsonString(callId),
                        "name" to JsonString(name),
                        "phase" to JsonString("call"),
                        "state" to JsonString("running"),
                    ),
                ),
            )
        }

        HostEventKind.TOOL_RESULT -> {
            val data = event.data ?: return null
            val message = data["message"] as? JsonObject
            val callId = (data["callId"] as? JsonString)?.value
                ?: (data["call_id"] as? JsonString)?.value
                ?: (message?.get("toolCallId") as? JsonString)?.value
                ?: ((message?.get("source") as? JsonObject)?.get("callId") as? JsonString)?.value
                ?: nodeID("tool-result")
            val isError = (data["isError"] as? JsonBool)?.value
                ?: (message?.get("isError") as? JsonBool)?.value
                ?: (data["error"] != null && data["error"] != com.phonedsh.agentcore.JsonNull)
            val summary = toolResultTextOf(event)
            UiStateNode(
                kind = UiStateKind.TOOL_ROW,
                nodeID = nodeID("tool-result"),
                parentID = "session/$chatId/turn/$turnId",
                sessionID = chatId,
                turnID = turnId,
                seq = seq,
                surface = UiStateSurface.TOOL_ROW,
                text = bounded(summary.lineSequence().firstOrNull()?.trim().orEmpty())
                    .ifEmpty { null },
                props = JsonObject(
                    linkedMapOf(
                        "callId" to JsonString(callId),
                        "isError" to JsonBool(isError),
                        "phase" to JsonString("result"),
                        "state" to JsonString(if (isError) "error" else "ok"),
                        "body" to JsonString(bounded(summary)),
                        "meta" to (data["meta"] ?: com.phonedsh.agentcore.JsonNull),
                    ),
                ),
            )
        }

        HostEventKind.TURN_ENDED -> {
            val reason = event.turnEndReason ?: return null
            UiStateNode(
                kind = UiStateKind.STATUS,
                nodeID = nodeID("turn-end"),
                parentID = "session/$chatId/turn/$turnId",
                sessionID = chatId,
                turnID = turnId,
                seq = seq,
                text = reason,
                props = JsonObject(
                    linkedMapOf(
                        "reason" to JsonString(reason),
                        "detail" to JsonString(turnEndDetail(event).orEmpty()),
                        "endedAtMs" to (event.raw["time"] ?: com.phonedsh.agentcore.JsonNull),
                    ),
                ),
            )
        }

        else -> null
    }
}

/** Full reasoning text carried by this Worker's durable assistant payload. */
internal fun assistantReasoningOf(event: HostEvent): String =
    ((event.data?.get("message") as? JsonObject)?.get("content") as? JsonArray)?.values.orEmpty()
        .mapNotNull { raw ->
            (raw as? JsonObject)?.let { block ->
                if ((block["type"] as? JsonString)?.value == "reasoning") {
                    (block["text"] as? JsonString)?.value
                } else null
            }
        }
        .joinToString("\n")

/**
 * The durable row that says this conversation's earlier model context is gone.
 *
 * Written when the Worker answers `session/not-found` for the conversation's
 * stored session and the send has to open a new one. It is a `status` node, not
 * a banner: a transcript that still shows the earlier bubbles must say, in the
 * transcript itself, that the model can no longer see them — otherwise reopening
 * the chat presents two different histories as one (`ChatTurnRunner`).
 *
 * @param chatId this App's conversation id.
 * @param turnToken the send's turn prefix, so the row's id is unique per send.
 * @param message the user-facing sentence; also the row's `detail`.
 */
internal fun contextLostNode(chatId: String, turnToken: String, message: String): UiStateNode {
    val turnId = "${turnToken.ifEmpty { "context" }}-context-lost"
    return UiStateNode(
        kind = UiStateKind.STATUS,
        nodeID = "session/$chatId/turn/$turnId/event/0/context-lost",
        parentID = "session/$chatId/turn/$turnId",
        sessionID = chatId,
        turnID = turnId,
        seq = 0,
        text = message,
        props = JsonObject(
            linkedMapOf(
                "reason" to JsonString("context_lost"),
                "detail" to JsonString(message),
            ),
        ),
    )
}

/** The bubble the person's own message becomes, created before the Worker echoes it. */
internal fun userNodeOf(chatId: String, turnToken: String, text: String, ordinal: Int): UiStateNode {
    val turnId = if (turnToken.isEmpty()) "local-$ordinal" else "$turnToken-local"
    return UiStateNode(
        kind = UiStateKind.BUBBLE,
        nodeID = "session/$chatId/turn/$turnId/event/$ordinal/user-message",
        parentID = "session/$chatId/turn/$turnId",
        sessionID = chatId,
        turnID = turnId,
        seq = ordinal,
        text = bounded(text),
        props = JsonObject(
            linkedMapOf(
                "role" to JsonString("user"),
                "source" to JsonString("user"),
                "truncated" to JsonBool(text.length > TEXT_LIMIT),
            ),
        ),
    )
}

/** The text of one `tool_result` event: the Worker's own result, never a guess. */
internal fun toolResultTextOf(event: HostEvent): String {
    val data = event.data ?: return ""
    val message = data["message"] as? JsonObject
    val content = (message?.get("content") as? JsonArray)?.values.orEmpty()
        .mapNotNull { block -> (block as? JsonObject)?.let { entry ->
            if ((entry["type"] as? JsonString)?.value == "text") (entry["text"] as? JsonString)?.value else null
        } }.joinToString("\n")
    if (content.isNotEmpty()) return content
    ((data["error"] as? JsonObject)?.get("reason") as? JsonString)?.value?.let { return it }
    for (member in listOf("text", "result", "output", "summary")) {
        when (val value = data[member]) {
            is JsonString -> return value.value
            is JsonObject -> {
                (value["text"] as? JsonString)?.value?.let { return it }
                (value["content"] as? JsonArray)?.values?.forEach { block ->
                    (block as? JsonObject)?.let { entry ->
                        if ((entry["type"] as? JsonString)?.value == "text") {
                            (entry["text"] as? JsonString)?.value?.let { return it }
                        }
                    }
                }
            }

            is JsonArray -> {
                value.values.forEach { block ->
                    (block as? JsonObject)?.let { entry ->
                        if ((entry["type"] as? JsonString)?.value == "text") {
                            (entry["text"] as? JsonString)?.value?.let { return it }
                        }
                    }
                }
            }

            else -> Unit
        }
    }
    return ""
}

/** `turn/end.data.reason.cause` rendered as one token-free detail line. */
private fun turnEndDetail(event: HostEvent): String? {
    val reason = event.data?.get("reason") as? JsonObject ?: return null
    val cause = reason["cause"] as? JsonObject ?: return null
    val kind = (cause["kind"] as? JsonString)?.value
    val message = (cause["message"] as? JsonString)?.value
    return listOfNotNull(kind, message?.substringBefore('\n')?.take(240))
        .joinToString(": ")
        .ifBlank { null }
}

private fun bounded(text: String): String =
    if (text.length <= TEXT_LIMIT) text else text.take(TEXT_LIMIT)

/** The schema's `text.maxLength` (`ui-state-sequence.schema.json`). */
private const val TEXT_LIMIT: Int = 20_000

/** The session-scope turn id (`event-kinds.md` §1). */
private const val SESSION_SCOPE_TURN_ID: String = "-"

/** A turn id for an event the Worker did not tag with one. */
private const val FALLBACK_TURN_ID: String = "turn-0000"

/** The conversation id a never-started chat renders under. */
internal const val PENDING_CHAT_ID: String = "pending"

/** The one turn-end reason that closes a turn without a visible row. */
private const val COMPLETED: String = "completed"
