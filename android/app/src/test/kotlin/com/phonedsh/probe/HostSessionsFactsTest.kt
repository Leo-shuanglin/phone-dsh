// The sessions page only gets workspace membership the host actually measured.
// SPDX-License-Identifier: MIT

package com.phonedsh.probe

import com.phonedsh.platform.EngineHostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSessionsFactsTest {
    @Test
    fun `one measured workspace groups only app chats with that recorded cwd`() {
        val snapshot = HostSessionSnapshot(
            waiting = false,
            sessions = listOf(
                HostSessionRecord(
                    id = "chat-1",
                    lastActivityMs = 10L,
                    title = "app chat",
                    workspaceID = DEFAULT_WORKSPACE_ID,
                    pinned = true,
                ),
                HostSessionRecord(
                    id = "journal-only",
                    lastActivityMs = 5L,
                    title = "legacy session",
                ),
            ),
            currentSessionID = "chat-1",
            archivedSessions = listOf(
                HostSessionRecord(
                    id = "chat-archived",
                    lastActivityMs = 2L,
                    title = "archived",
                    workspaceID = DEFAULT_WORKSPACE_ID,
                ),
            ),
            pinnedSessionIDs = setOf("chat-1"),
        )

        val facts = buildSessionsFacts(
            state = EngineHostState.Stopped,
            turn = AppTurnState.Idle,
            snapshot = snapshot,
            workspacePresent = true,
            workspacePath = "/dsh/workspace",
        )

        val workspace = facts.workspaces!!.single()
        assertEquals(DEFAULT_WORKSPACE_ID, workspace.id)
        assertEquals("/dsh/workspace", workspace.title)
        assertEquals(listOf("chat-1"), workspace.sessionIDs)
        assertEquals(listOf("journal-only"), facts.sessions.filter { it.id !in workspace.sessionIDs }.map { it.id })
        assertEquals(listOf("chat-archived"), facts.archivedSessions!!.map { it.id })
        assertEquals(listOf("chat-1"), facts.pinnedSessionIDs)
    }

    @Test
    fun `a missing workspace directory does not claim an empty folder catalog`() {
        val facts = buildSessionsFacts(
            state = EngineHostState.Stopped,
            turn = AppTurnState.Idle,
            snapshot = HostSessionSnapshot(waiting = false),
            workspacePresent = false,
            workspacePath = "/dsh/workspace",
        )

        assertNull(facts.workspaces)
        assertTrue(facts.sessions.isEmpty())
    }
}
