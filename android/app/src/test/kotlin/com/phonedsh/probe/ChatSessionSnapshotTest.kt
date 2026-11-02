// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import com.phonedsh.agentcore.*
import com.phonedsh.dsh.turn.PendingRemoteRequest
import com.phonedsh.hostevents.HostEvent
import com.phonedsh.ui.state.*
import com.phonedsh.domain.DomainStoreCore
import com.phonedsh.domain.QuotaPolicy
import com.phonedsh.domain.testing.InMemoryConnectionFactory
import com.phonedsh.platform.Clock
import org.junit.Assert.*
import org.junit.Test

class ChatSessionSnapshotTest {
    private fun json(text: String): JsonValue = JsonValue.parse(text)

    private fun domainStore() = DomainStoreCore(
        InMemoryConnectionFactory(), QuotaPolicy.uniform(4L * 1024 * 1024), object : Clock {
            override fun nowMillis() = 1L
            override fun monotonicMillis() = 1L
        },
    )

    private fun chatStore(): ChatSessionStore = ChatSessionStore(domainStore())

    @Test fun `chat write receipt follows atomic persisted archive and rename round trip`() {
        val store = chatStore()
        store.save(ChatRecord("chat-a", "First", 10, listOf("worker-a"), emptyList()))

        val archived = store.setArchived("chat-a", true, StateRequestId("archive-1"))
        assertEquals(StateRequestId("archive-1"), archived.requestId)
        assertEquals("chat-a", archived.sessionId)
        assertEquals(StateRevision(2), archived.revision)
        assertEquals(ChatWriteOutcome.Persisted, archived.outcome)

        val stale = store.rename("chat-a", "stale title", StateRequestId("rename-stale"), StateRevision(1))
        assertEquals(ChatWriteOutcome.Stale, stale.outcome)
        assertEquals("First", store.load("chat-a")?.title)

        val renamed = store.rename("chat-a", "  Second  ", StateRequestId("rename-1"))
        assertEquals(ChatWriteOutcome.Persisted, renamed.outcome)
        assertEquals("Second", store.load("chat-a")?.title)
        assertTrue(store.load("chat-a")?.archived == true)
        assertEquals(3L, store.load("chat-a")?.revision)
    }

    @Test fun `stale out of order chat write is rejected without rolling back transcript`() {
        val store = chatStore()
        store.save(ChatRecord("chat-a", "new", 20, emptyList(), emptyList()))
        try {
            store.save(ChatRecord("chat-a", "stale", 10, emptyList(), emptyList()))
            fail("stale write should be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals("new", store.load("chat-a")?.title)
        assertEquals(20L, store.load("chat-a")?.updatedAtMs)
    }

    @Test fun `workspace group rename and delete preserve assigned conversation`() {
        val store = chatStore()
        store.save(ChatRecord("chat-a", "A", 10, emptyList(), emptyList(), workspaceGroupId = "daily"))
        val created = store.putWorkspaceGroup(ChatWorkspaceGroup("daily", "日常"), StateRequestId("group-save"))
        assertEquals(ChatWriteOutcome.Persisted, created.outcome)
        val renamed = store.putWorkspaceGroup(ChatWorkspaceGroup("daily", "项目"), StateRequestId("group-rename"))
        assertEquals(ChatWriteOutcome.Persisted, renamed.outcome)
        assertEquals(StateRevision(2), renamed.revision)
        assertEquals("项目", store.loadWorkspaceGroups().single().name)
        val stale = store.deleteWorkspaceGroup("daily", StateRequestId("group-delete-stale"), StateRevision(1))
        assertEquals(ChatWriteOutcome.Stale, stale.outcome)
        assertEquals("项目", store.loadWorkspaceGroups().single().name)
        val deleted = store.deleteWorkspaceGroup("daily", StateRequestId("group-delete"))
        assertEquals(ChatWriteOutcome.Persisted, deleted.outcome)
        assertEquals(StateRevision(3), deleted.revision)
        assertEquals("daily", store.load("chat-a")?.workspaceGroupId)
        assertEquals("chat-a", store.loadAll().single().id)
        assertEquals("未分配", workspaceGroupTitle(store.load("chat-a")!!, store.loadWorkspaceGroups()))
        val unknown = ChatRecord("chat-b", "B", 11, emptyList(), emptyList(), workspaceGroupId = null)
        assertEquals("未分配", workspaceGroupTitle(unknown, store.loadWorkspaceGroups()))
    }

    @Test fun `日常 group is seeded once without overwriting an existing name`() {
        val store = chatStore()
        assertEquals(ChatWriteOutcome.Persisted, store.ensureDefaultWorkspaceGroup(StateRequestId("seed")).outcome)
        assertEquals("日常", store.loadWorkspaceGroups().single().name)
        store.putWorkspaceGroup(ChatWorkspaceGroup("daily", "My daily"), StateRequestId("rename"))
        assertEquals(ChatWriteOutcome.Persisted, store.ensureDefaultWorkspaceGroup(StateRequestId("seed-again")).outcome)
        assertEquals("My daily", store.loadWorkspaceGroups().single().name)
    }

    @Test fun `cold store restores pending and queued state as persisted facts`() {
        val store = chatStore()
        val pending = pendingRequestNode("chat-a", PendingRemoteRequest(
            "request-a", "client-a", json("""{"reason":"confirm"}"""), "approval/request",
        ))
        val lost = contextLostNode("chat-a", "turn-a", "上一段上下文已丢失")
        val record = ChatRecord("chat-a", "A", 10, listOf("worker-a"), listOf(lost),
            pendingNodes = listOf(pending), queuedPrompts = listOf("second prompt"))
        store.save(record)
        val restored = store.load("chat-a")!!
        assertEquals(listOf("request-a"), restored.pendingNodes?.map { (it.props["requestId"] as JsonString).value })
        assertEquals(listOf("second prompt"), restored.queuedPrompts)
        assertEquals("上一段上下文已丢失", restored.nodes.single().text)
    }

    @Test fun `queue projection preserves durable item ids lane and edit capability`() {
        val snapshot = sessionSnapshotOf(
            projections = json("""{"values":{"inbox":{
                "next-turn":[{"id":"q-next","content":[{"type":"text","text":"queued"}]}],
                "next-step":[{"id":"q-step","content":[{"type":"text","text":"steered"}]}]
            }}}"""),
            catalog = null,
            commandCatalog = null,
            sessionId = "worker-session",
        )
        val rows = snapshot.queueItems!!
        assertEquals(2, rows.size)
        assertEquals(QueueItemId.Durable("q-next"), rows[0].id)
        assertEquals("worker-session", rows[0].sessionId)
        assertEquals("next-turn", rows[0].lane)
        assertEquals(true, rows[0].canEdit)
        assertEquals("next-step", rows[1].lane)
    }

    @Test fun `legacy chat row keeps unknown envelope fields and reports missing queue as unknown`() {
        val rawStore = domainStore()
        rawStore.put(ChatSessionStore.DOMAIN, "chat/legacy", """{
            "schema":"pdsh-chat-session/1","id":"legacy","title":"Old title",
            "updatedAtMs":9,"workerSessionIds":["worker-old"],"workspaceID":"/dsh/workspace",
            "nodes":"","futureOwnerField":{"keep":true}
        }""".trimIndent().toByteArray(Charsets.UTF_8))
        val store = ChatSessionStore(rawStore)
        val loaded = store.load("legacy")!!
        assertEquals("Old title", loaded.title)
        assertNull(loaded.pendingNodes)
        assertNull(loaded.queuedPrompts)
        assertEquals("/dsh/workspace", loaded.workspaceID)
        assertTrue(store.setArchived("legacy", true))
        val persisted = JsonValue.parse(rawStore.get(ChatSessionStore.DOMAIN, "chat/legacy")!!.toString(Charsets.UTF_8)) as JsonObject
        assertEquals(JsonObject(mapOf("keep" to JsonBool(true))), persisted["futureOwnerField"])
        assertEquals("Old title", store.load("legacy")?.title)
    }

    @Test fun `dock reads nested goal and filters unknown todo statuses`() {
        val snapshot = sessionSnapshotOf(json("""{"values":{
          "goal":{"goal":{"objective":"Ship the report","phase":"paused"}},
          "todos":[{"content":"Read CSV","status":"completed"},
                   {"content":"Write report","status":"in_progress"},
                   {"content":"Unknown","status":"invented"}],
          "permissions":{"currentValue":"read-only"}}}"""),
            json("""{"options":[{"value":"read-only","name":"Read Only"}]}"""),
            json("""[{"name":"goal","description":"Set goal","input":{}},
                      {"name":"clear","description":"Clear"}]"""))
        assertEquals(GoalBarState.Visible("paused", "Ship the report"), snapshot.goal)
        assertEquals(listOf("completed", "in_progress"), snapshot.todos.map { it.status })
        assertEquals("read-only", snapshot.currentAccessMode?.id)
        assertEquals(listOf(true, false), snapshot.commands.map { it.hasInput })
        assertNull(snapshot.accessError)
    }

    @Test fun `rich live question reaches plan review carrier without losing metadata`() {
        val request = PendingRemoteRequest("ask-1", "client-1", json("""{
          "questions":[{"id":"plan","header":"Review","question":"Proceed?",
          "detail":"# Plan\nRead then write.","multiSelect":false,
          "options":[{"label":"Approve","description":"Start work"}],
          "intent":{"kind":"plan-review","approve":"Approve"}}]}"""), "user-questions/request")
        val node = pendingRequestNode("chat-1", request)
        val state = ChatScreenState.from(listOf(sessionNode("chat-1"), node))
        val question = state.pending.primary!!
        assertEquals("ask-1", question.requestId)
        assertEquals(true, question.channelAvailable)
        assertEquals("Review", question.questionItems.single().header)
        assertEquals("Start work", question.questionItems.single().options.single().description)
        assertEquals("Approve", question.questionItems.single().approveWireLabel)
    }

    @Test fun `answer wire preserves each question and separates custom input from selections`() {
        val request = PendingRemoteRequest("q", "client", json("""{"questions":[
          {"id":"one","options":[{"label":"A"}]},
          {"id":"two","options":[{"label":"B"}]}]}"""), "user-questions/request")
        val outcome = questionOutcome(request, """{"one":["A","Custom"],"two":[]}""")
        val value = outcome["value"] as JsonObject
        val answers = (value["answers"] as JsonArray).values.map { it as JsonObject }
        assertEquals(JsonArray(listOf(JsonString("A"))), answers[0]["selected"])
        assertEquals(JsonString("Custom"), answers[0]["custom"])
        assertEquals(JsonArray(emptyList()), answers[1]["selected"])
        assertFalse(answers[1].members.containsKey("custom"))
    }

    @Test fun `real worker result envelope matches call and retains every text block`() {
        val event = HostEvent.project(json("""{"type":"tool/result","seq":9,"time":3000,
          "data":{"turn":1,"message":{"toolCallId":"call-7","isError":false,
          "content":[{"type":"text","text":"first"},{"type":"text","text":"last"}]}}}""") as JsonObject)!!
        val node = chatNodeOf("chat", "t", event, 9)!!
        assertEquals(JsonString("call-7"), node.props["callId"])
        assertEquals(JsonString("first\nlast"), node.props["body"])
        assertEquals(JsonString("ok"), node.props["state"])
    }

    @Test fun `fold and host dock facts survive real app projection`() {
        val state = chatScreenState(ChatUiState(chatId = "chat", imeVisible = true,
            expandedRowIds = setOf("tool-1"), sessionSnapshot = ChatSessionSnapshot(
                todos = listOf(TodoDockItem("one", "Read CSV", "pending")),
                goal = GoalBarState.Visible("active", "Report"))), true, false, true)
        assertEquals(setOf("tool-1"), state.expandedRowIds)
        assertEquals(1, state.todoItems.size)
        assertEquals(GoalBarState.Visible("active", "Report"), state.goal)
    }
}
