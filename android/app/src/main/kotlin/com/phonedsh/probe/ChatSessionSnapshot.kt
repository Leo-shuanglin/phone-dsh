// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import com.phonedsh.agentcore.*
import com.phonedsh.dsh.turn.PendingRemoteRequest
import com.phonedsh.ui.projection.*
import com.phonedsh.ui.state.*

/** The same authoritative session projection the iOS input dock consumes. */
internal data class ChatSessionSnapshot(
    val goal: GoalBarState = GoalBarState.Hidden,
    val todos: List<TodoDockItem> = emptyList(),
    val accessModes: List<AccessModeOption> = emptyList(),
    val currentAccessMode: AccessModeOption? = null,
    val commands: List<ComposerCommand> = emptyList(),
    val accessError: String? = null,
    val commandsError: String? = null,
    val modelRoutes: List<ChatModelRoute> = emptyList(),
    val currentModel: String? = null,
    val currentProvider: String? = null,
    val currentEffort: String? = null,
)

internal data class ChatModelRoute(val provider: String, val model: String,
    val efforts: List<EffortOption>, val defaultEffort: String?)

internal fun sessionSnapshotOf(
    projections: JsonValue,
    catalog: JsonValue?,
    commandCatalog: JsonValue?,
    modelCatalog: JsonValue? = null,
): ChatSessionSnapshot {
    val values = (projections as? JsonObject)?.get("values") as? JsonObject
    val goal = ((values?.get("goal") as? JsonObject)?.get("goal") as? JsonObject)
    val objective = goal?.string("objective")?.takeIf { it.isNotBlank() }
    val modes = ((catalog as? JsonObject)?.get("options") as? JsonArray)?.values.orEmpty()
        .mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val id = row.string("value") ?: return@mapNotNull null
            val name = accessModeTitle(id) ?: return@mapNotNull null
            AccessModeOption(id = id, title = name)
        }
    val current = (values?.get("permissions") as? JsonObject)?.string("currentValue")
    val selection = values?.get("modelSelection") as? JsonObject
    val selected = ((selection?.get("next") as? JsonObject) ?: (selection?.get("lastUsed") as? JsonObject))
    val routes = ((modelCatalog as? JsonObject)?.get("groups") as? JsonArray)?.values.orEmpty().flatMap { raw ->
        val group = raw as? JsonObject ?: return@flatMap emptyList()
        val provider = group.string("id") ?: return@flatMap emptyList()
        (group["models"] as? JsonArray)?.values.orEmpty().mapNotNull { modelRaw ->
            val model = modelRaw as? JsonObject ?: return@mapNotNull null
            val id = model.string("id") ?: return@mapNotNull null
            val reasoning = model["reasoning"] as? JsonObject
            ChatModelRoute(provider, id, (reasoning?.get("efforts") as? JsonArray)?.values.orEmpty().mapNotNull { effortRaw ->
                val effort = effortRaw as? JsonObject ?: return@mapNotNull null
                val effortId = effort.string("id") ?: return@mapNotNull null
                EffortOption(effortId, effortTitle(effortId, effort.string("name") ?: effortId))
            }, reasoning?.string("defaultEffort"))
        }
    }
    val commands = when (commandCatalog) {
        is JsonArray -> commandCatalog
        is JsonObject -> (commandCatalog["commands"] ?: commandCatalog["value"]) as? JsonArray
        else -> null
    }?.values.orEmpty().mapNotNull { raw ->
        val row = raw as? JsonObject ?: return@mapNotNull null
        val name = row.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val description = row.string("description") ?: return@mapNotNull null
        ComposerCommand(name, description, hasInput = row["input"] != null && row["input"] != JsonNull)
    }
    return ChatSessionSnapshot(
        goal = if (objective == null) GoalBarState.Hidden else GoalBarState.Visible(
            phase = goal.string("phase") ?: "active", objective = objective,
        ),
        todos = (values?.get("todos") as? JsonArray)?.values.orEmpty().mapIndexedNotNull { index, raw ->
            val row = raw as? JsonObject ?: return@mapIndexedNotNull null
            val content = row.string("content") ?: return@mapIndexedNotNull null
            val status = row.string("status")?.takeIf {
                it in setOf("pending", "in_progress", "completed", "cancelled")
            } ?: return@mapIndexedNotNull null
            TodoDockItem(row.string("id") ?: "todo-$index", content, status)
        },
        accessModes = modes,
        currentAccessMode = modes.firstOrNull { it.id == current },
        commands = commands,
        accessError = if (catalog == null || current == null) "没读到权限,再试一次。" else null,
        commandsError = if (commandCatalog == null) "没读到命令,再试一次。" else null,
        modelRoutes = routes,
        currentModel = selected?.string("model"),
        currentProvider = selected?.string("provider"),
        currentEffort = selected?.string("reasoningEffort"),
    )
}

/** A live waterfall request is transient; it must never be restored as actionable history. */
internal fun pendingRequestNode(chatId: String, request: PendingRemoteRequest): UiStateNode {
    val body = request.request as? JsonObject ?: JsonObject(emptyMap())
    val isQuestion = request.eventKind == "user-questions/request"
    val questions = (body["questions"] as? JsonArray)?.values.orEmpty().mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val intent = item["intent"] as? JsonObject
        JsonObject(linkedMapOf<String, JsonValue>(
            "id" to (item["id"] ?: JsonString("")),
            "prompt" to (item["question"] ?: JsonString("")),
            "options" to (item["options"] ?: JsonArray(emptyList())),
            "multiple" to (item["multiSelect"] ?: JsonBool(false)),
        ).apply {
            for (name in listOf("header", "detail")) item[name]?.let { put(name, it) }
            intent?.get("kind")?.let { put("intentKind", it) }
            intent?.get("approve")?.let { put("approveWireLabel", it) }
        })
    }
    return UiStateNode(
        kind = UiStateKind.APPROVAL_CARD,
        nodeID = "session/$chatId/pending/${request.eventID}",
        parentID = "session/$chatId", sessionID = chatId, turnID = "-", seq = 0,
        surface = UiStateSurface.APPROVAL_CARD,
        text = if (isQuestion) "" else body.string("reason") ?: body.string("toolName").orEmpty(),
        props = JsonObject(linkedMapOf(
            "requestId" to JsonString(request.eventID),
            "kind" to JsonString(if (isQuestion) "question" else "permission"),
            "questionItems" to JsonArray(questions),
            "channelAvailable" to JsonBool(true),
            "submitting" to JsonBool(false),
        )),
    )
}

/** DSH's answer envelope: selections are option labels; custom text stays separate. */
internal fun questionOutcome(request: PendingRemoteRequest, answer: String): JsonObject {
    val body = request.request as? JsonObject
    val questions = (body?.get("questions") as? JsonArray)?.values.orEmpty()
    val perQuestion = runCatching { JsonValue.parse(answer) as? JsonObject }.getOrNull()
    return JsonObject(mapOf("kind" to JsonString("result"), "value" to JsonObject(mapOf(
        "answers" to JsonArray(questions.map { raw ->
            val item = raw as? JsonObject ?: JsonObject(emptyMap())
            val id = item.string("id").orEmpty()
            val labels = (item["options"] as? JsonArray)?.values.orEmpty().mapNotNull {
                (it as? JsonObject)?.string("label")
            }
            val value = perQuestion?.get(id)
            val answers = when (value) {
                is JsonArray -> value.values.mapNotNull { (it as? JsonString)?.value }
                is JsonString -> listOf(value.value)
                else -> if (perQuestion == null) listOf(answer) else emptyList()
            }
            JsonObject(linkedMapOf<String, JsonValue>(
                "id" to JsonString(id),
                "selected" to JsonArray(answers.filter { it in labels }.map(::JsonString)),
            ).apply {
                answers.filter { it !in labels }.joinToString("\n").takeIf { it.isNotEmpty() }
                    ?.let { put("custom", JsonString(it)) }
            })
        }),
    ))))
}

internal fun effortTitle(id: String, fallback: String = id): String = when (id.lowercase()) {
    "off", "none", "disabled" -> "关"
    "low", "minimal" -> "低"
    "medium", "mid" -> "中"
    "high" -> "高"
    "max", "maximum", "xhigh" -> "最高"
    else -> fallback
}

private fun accessModeTitle(id: String): String? = when (id) {
    "read-only", "phone-read-only" -> "仅可查看"
    "workspace-write", "phone" -> "工作区内修改"
    "danger-full-access", "bypassPermissions" -> "完全权限"
    "auto" -> "自动判断"
    "plan" -> "只出方案"
    "acceptEdits" -> "改前不问"
    else -> null
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonString)?.value
