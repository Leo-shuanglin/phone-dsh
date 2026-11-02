// SPDX-License-Identifier: MIT
package com.phonedsh.probe

import com.phonedsh.agentcore.*
import com.phonedsh.dsh.turn.PendingRemoteRequest
import com.phonedsh.hostevents.HostEvent
import com.phonedsh.ui.state.*
import org.junit.Assert.*
import org.junit.Test

class ChatSessionSnapshotTest {
    private fun json(text: String): JsonValue = JsonValue.parse(text)

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
