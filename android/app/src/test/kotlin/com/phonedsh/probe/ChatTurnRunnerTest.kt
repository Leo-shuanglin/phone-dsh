// Which failures may discard a conversation's Worker session (L85 P0).
// SPDX-License-Identifier: MIT
//
// ---- the rule this test pins ----------------------------------------------
// A Worker session id is **resumable**: the Worker persists its log under
// `/dsh/home/sessions`, the host repacks `/dsh/home` into every boot overlay, and
// `session/prompt` resumes a non-live session from that log. Only the Worker's own
// `session/not-found` verdict says the log is really gone. Every other failure
// leaves the log intact, so opening a new session on it would change "the model
// lost this turn" into "the model lost the conversation" — the worst state for a
// user, because the transcript still shows the earlier messages.
package com.phonedsh.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnRunnerTest {
    @Test
    fun onlyTheWorkersNotFoundVerdictRetiresTheSession() {
        assertTrue(
            "session/not-found is the one answer that means the durable log is gone, " +
                "so it is the one answer that may open a new session",
            workerSessionGone("session/not-found"),
        )
        for (code in listOf(
            // The symptom the old code misread as "stale session": the stream
            // produced no turn. The prompt may have been deduplicated or the model
            // may have failed; neither is evidence about the log.
            "no_turn_started",
            "stream_error",
            "invalid_response",
            "session_create_unavailable",
            // The session exists but another writer owns it: opening a new one
            // would be a second conversation, not a repair.
            "session/writer-held",
            "gateway/internal",
            "HostApiException",
            "",
            null,
        )) {
            assertFalse(
                "a failure with code ${code ?: "<null>"} must not discard the stored " +
                    "Worker session: the durable history is still there and the person " +
                    "must be told, not given a fresh context",
                workerSessionGone(code),
            )
        }
    }
}
