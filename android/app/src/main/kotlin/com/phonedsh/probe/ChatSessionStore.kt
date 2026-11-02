// The chat transcript cache: what a cold start reopens.
// SPDX-License-Identifier: MIT
//
// ---- why this exists, and why it is not the journal -------------------------
// The authoritative record of a session is still the host WAL / journal, owned by
// `:dsh`. The journal names a session but does not expose its event text to this
// module (`JournalStore.records` is `internal`), and the app-level `SessionArchive`
// keeps only the settled tail's *kinds*, not the words. A cold start therefore had
// nothing to paint for the conversation the user just had: the turn state came back
// but the transcript did not.
//
// This store is the Android half of the iOS baseline's
// `transcript-snapshots/<sessionID>.json` (`ChatModel.swift:2512-2599`): the
// rendered node sequence of one conversation plus its title, so the screen can be
// painted before the Worker is up. It stores **the nodes the screen already drew**
// (`ui-state/1` via `:lib`'s canonical codec), not a second copy of the events, so
// restoring cannot disagree with what was on screen.
//
// The rows live in the same `session` domain as `SessionArchive`, under a `chat/`
// key prefix, so the one `DomainStore` remains the one product store and the
// existing quota applies unchanged. The `turn/` and `current` rows of
// `SessionArchive` are never touched: the two writers own disjoint key ranges.
//
// ---- failure model ---------------------------------------------------------
// A row that cannot be decoded is reported as absent, never as a half transcript;
// it is not deleted either (silent loss is what `budgets.md` §5 forbids).
package com.phonedsh.probe

import com.phonedsh.agentcore.JsonArray
import com.phonedsh.agentcore.JsonNumber
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.JsonValue
import com.phonedsh.agentcore.toJsonString
import com.phonedsh.domain.DomainNames
import com.phonedsh.domain.DomainStore
import com.phonedsh.ui.projection.UiStateNode
import com.phonedsh.ui.projection.decodeCanonicalUiState
import com.phonedsh.ui.projection.encodeCanonical

/**
 * One conversation as this App knows it.
 *
 * @param id the App's stable chat id (`chat-<millis>-<n>`). It survives every
 *   process restart; the Worker's session id does not, which is why they are
 *   different values.
 * @param title the user's name for it; empty means 新会话 (the page's untitled word).
 * @param updatedAtMs the wall-clock instant of its last change, for the list order.
 * @param workerSessionIds every Worker `session/create` id this conversation has
 *   used, oldest first. Each one is a journal row; keeping the list lets the
 *   session list claim them all so one conversation never lists twice.
 * @param nodes the rendered `ui-state/1` sequence, in order.
 */
internal data class ChatRecord(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val workerSessionIds: List<String>,
    val nodes: List<UiStateNode>,
) {
    /** The Worker session the next turn should try first, or `null` before the first. */
    val workerSessionId: String? get() = workerSessionIds.lastOrNull()
}

/**
 * The two-way store over [DomainStore]'s `session` domain.
 *
 * Every method is synchronous and touches disk: callers must not run them on the
 * Android main thread (`DomainStore`'s threading rule).
 */
internal class ChatSessionStore(private val store: DomainStore) {
    /** Writes [record], replacing the row of the same id. */
    fun save(record: ChatRecord) {
        store.put(DOMAIN, key(record.id), encode(record).toByteArray(Charsets.UTF_8))
    }

    /** Loads one record, or `null` when it is absent or does not decode. */
    fun load(id: String): ChatRecord? = decodeOrNull(
        store.get(DOMAIN, key(id))?.toString(Charsets.UTF_8),
    )

    /** Every decodable record, newest first. */
    fun loadAll(): List<ChatRecord> {
        if (store.usageBytes(DOMAIN) == 0L) return emptyList()
        return store.list(DOMAIN, PREFIX, MAXIMUM_RECORDS)
            .mapNotNull { entry -> decodeOrNull(entry.value.toString(Charsets.UTF_8)) }
            .sortedByDescending { it.updatedAtMs }
    }

    /** Removes one record; `true` when a row existed. */
    fun delete(id: String): Boolean = store.delete(DOMAIN, key(id))

    private fun key(id: String): String = PREFIX + id

    private fun encode(record: ChatRecord): String = JsonObject(
        linkedMapOf(
            "schema" to JsonString(SCHEMA),
            "id" to JsonString(record.id),
            "title" to JsonString(record.title),
            "updatedAtMs" to JsonNumber(record.updatedAtMs.toString()),
            "workerSessionIds" to JsonArray(
                record.workerSessionIds.map { JsonString(it) },
            ),
            // The node sequence is the `ui-state/1` canonical JSONL `:lib` writes,
            // carried as one string member so this envelope never re-implements the
            // node schema.
            "nodes" to JsonString(record.nodes.encodeCanonical()),
        ),
    ).toJsonString()

    private fun decodeOrNull(text: String?): ChatRecord? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = JsonValue.parse(text) as? JsonObject ?: return null
            if ((root["schema"] as? JsonString)?.value != SCHEMA) return null
            val id = (root["id"] as? JsonString)?.value ?: return null
            if (id.isBlank()) return null
            val nodes = decodeCanonicalUiState((root["nodes"] as? JsonString)?.value.orEmpty())
            ChatRecord(
                id = id,
                title = (root["title"] as? JsonString)?.value.orEmpty(),
                updatedAtMs = (root["updatedAtMs"] as? JsonNumber)?.asLongOrNull() ?: 0L,
                workerSessionIds = (root["workerSessionIds"] as? JsonArray)
                    ?.values
                    ?.mapNotNull { (it as? JsonString)?.value }
                    .orEmpty(),
                nodes = nodes,
            )
        } catch (_: Exception) {
            // A damaged row is reported as absent; the next save for that chat
            // replaces it. Nothing is deleted on this path.
            null
        }
    }

    companion object {
        /** The domain `SessionArchive` also uses; this store owns the `chat/` range. */
        const val DOMAIN: String = DomainNames.SESSION

        /** Key prefix of one chat row. */
        const val PREFIX: String = "chat/"

        /** The envelope version this reader accepts. */
        const val SCHEMA: String = "pdsh-chat-session/1"

        /** A read bound, not a retention policy (the domain quota is the bound). */
        const val MAXIMUM_RECORDS: Int = 1000
    }
}
