// phone-dsh — :dsh unit test: the `ubuntu` host tool over the L47 mock lane.
// SPDX-License-Identifier: MIT
//
// What these tests pin: the five ops the Worker command drives, the per-session
// approval gate in front of `run`, the two honesty flags that keep a mock answer
// from reading as a real result (`backend = mock`, `sealMock = true`), and the
// `fetch` answer shape. The job machine itself is covered by
// UbuntuJobLaneTest / UbuntuJobRecoveryTest; this file covers the wiring the
// Agent's `ubuntu` command actually reaches.

package com.phonedsh.dsh.localexec

import com.phonedsh.controller.ControllerBudgets
import com.phonedsh.dsh.exec.ExecFixtures
import com.phonedsh.dsh.host.HostLog
import com.phonedsh.dsh.exec.ExecToolResult
import com.phonedsh.dsh.tools.PhoneTool
import com.phonedsh.dsh.tools.ToolInvocation
import com.phonedsh.dsh.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuHostToolTest {
    private val clock = TestClock()

    private fun tool(
        gate: UbuntuApprovalGate = approvingGate(clock),
        budgets: ControllerBudgets = ControllerBudgets.forTier(ControllerBudgets.DEFAULT_TIER),
    ): UbuntuHostTool = UbuntuHostTool(clock = clock, budgets = budgets, approval = gate)

    private fun invoke(
        tool: PhoneTool,
        argv: List<String>,
        idem: String = ExecFixtures.IDEM_A,
        deadline: Long? = null,
    ): ToolOutcome = runBlocking {
        val json = ExecFixtures.baseRequest(argv = argv, idemKey = idem)
        if (deadline != null) json.put("deadline", deadline)
        tool.invoke(ToolInvocation(idem, UbuntuHostTool.PHONE_TOOL, json.toString()))
    }

    /** The payload every op answers on stdout (the `ExecToolResult` envelope). */
    private fun payload(outcome: ToolOutcome): JSONObject {
        assertTrue("expected a success outcome, was $outcome", outcome is ToolOutcome.Success)
        val result = ExecToolResult.parse((outcome as ToolOutcome.Success).resultJson)
        assertEquals(0, result.code)
        return JSONObject(String(result.stdout, Charsets.UTF_8))
    }

    @Test
    fun probe_answers_the_environment_the_mock_backend_serves() {
        val reply = payload(invoke(tool(), listOf("phone", "ubuntu", "probe")))
        assertTrue(reply.getBoolean("available"))
        assertEquals("mock", reply.getString("backend"))
        assertEquals(MockUbuntuAdapter.DEFAULT_ENV, reply.getString("env"))
        assertEquals(
            listOf("run", "status", "fetch", "close"),
            reply.getJSONArray("commands").let { array -> (0 until array.length()).map { array.getString(it) } },
        )
    }

    @Test
    fun run_admits_one_job_and_answers_the_terminal_record() {
        val startedAt = clock.nowMillis()
        val reply = payload(
            invoke(
                tool(),
                listOf(
                    "phone", "ubuntu", "run",
                    "--env", MockUbuntuAdapter.DEFAULT_ENV,
                    "--label", "fib",
                    "--session", "sess-a",
                    "--", "gcc", "-o", "a", "a.c",
                ),
            ),
        )
        assertEquals("mock", reply.getString("backend"))
        assertEquals("succeeded", reply.getString("state"))
        assertEquals(0, reply.getInt("exitCode"))
        assertTrue(reply.getBoolean("sealMock"))
        assertFalse(reply.getBoolean("egress"))
        assertEquals(UbuntuJobRecord.AUTHORITY_WORKER_VFS, reply.getString("authorityExited"))
        assertEquals("restored", reply.getJSONObject("seal").getString("state"))
        assertFalse(reply.isNull("internalAccessObserved"))
        assertFalse(reply.getBoolean("internalAccessObserved"))
        assertEquals("fib", reply.getString("label"))
        assertTrue(reply.getString("logTail").contains("mock ${MockUbuntuAdapter.DEFAULT_ENV}"))
        assertEquals(3, reply.getJSONArray("artifacts").length())
        assertTrue(reply.getString("jobId").startsWith("uj-"))
        // The approval gate admitted the job and the audit says so.
        val approval = reply.getJSONObject("approval")
        assertEquals("granted", approval.getString("state"))
        assertEquals("sess-a", approval.getString("sessionId"))
        assertTrue(approval.getLong("atMs") >= startedAt)
        // The record is the §12.8 payload, not an argv echo: the audit names the
        // command by digest only.
        assertEquals(64, reply.getString("argvDigest").length)
        assertFalse(reply.toString().contains("gcc"))
        assertTrue(clock.nowMillis() >= startedAt)
    }

    @Test
    fun the_first_run_in_a_session_asks_and_later_runs_do_not() {
        val (gate, prompt) = approvalGate(clock = clock)
        val tool = tool(gate)
        val first = payload(invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "true")))
        assertEquals("succeeded", first.getString("state"))
        assertEquals(1, prompt.requests.size)
        assertEquals("sess-a", prompt.requests[0].sessionId)
        assertEquals("sess-a", gate.boundSession())

        // The second run in the same session: no dialog, and the audit says the
        // grant (not a fresh prompt) admitted it.
        val second = payload(
            invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "true"), idem = ExecFixtures.IDEM_B),
        )
        assertEquals("succeeded", second.getString("state"))
        assertEquals(1, prompt.requests.size)
        assertEquals("granted", second.getJSONObject("approval").getString("state"))
        assertEquals("sess-a", second.getJSONObject("approval").getString("sessionId"))
    }

    @Test
    fun a_denial_is_a_failed_record_with_the_denial_cause_and_nothing_ran() {
        val (gate, prompt) = approvalGate(answer = UbuntuApprovalDecision.Denied, clock = clock)
        val tool = tool(gate)
        val reply = payload(invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "gcc", "x.c")))
        assertEquals("failed", reply.getString("state"))
        assertTrue(reply.isNull("exitCode"))
        val failure = reply.getJSONObject("failure")
        assertEquals(UbuntuJobFailure.CANCELLED, failure.getString("code"))
        assertEquals(UbuntuOutcome.NOT_STARTED, failure.getString("outcome"))
        assertEquals(UbuntuCause.APPROVAL_DENIED, failure.getString("cause"))
        assertFalse(failure.getBoolean("retryable"))
        assertTrue(failure.getString("message").contains("declined"))
        assertEquals("denied", reply.getJSONObject("approval").getString("state"))
        assertEquals("sess-a", reply.getJSONObject("approval").getString("sessionId"))
        assertEquals(1, prompt.requests.size)
        // Nothing ran: the record has no exit code, no artifacts and no seal
        // window (the seal is still `pending`, not `sealed`).
        assertEquals(0, reply.getJSONArray("artifacts").length())
        assertEquals("pending", reply.getJSONObject("seal").getString("state"))
        assertTrue(reply.isNull("internalAccessObserved"))
        // The denial is remembered for the session: a retry asks nobody and
        // fails the same way, instead of nagging.
        val again = payload(
            invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "true"), idem = ExecFixtures.IDEM_B),
        )
        assertEquals("failed", again.getString("state"))
        assertEquals(UbuntuCause.APPROVAL_DENIED, again.getJSONObject("failure").getString("cause"))
        assertEquals(1, prompt.requests.size)
    }

    @Test
    fun a_denial_does_not_cross_a_session_boundary() {
        val (gate, prompt) = approvalGate(answer = UbuntuApprovalDecision.Denied, clock = clock)
        val tool = tool(gate)
        payload(invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "true")))
        assertEquals(1, prompt.requests.size)
        // A different session never inherits the denial: it gets its own dialog.
        prompt.answers(UbuntuApprovalDecision.Approved)
        val next = payload(
            invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-b", "--", "true"), idem = ExecFixtures.IDEM_B),
        )
        assertEquals("succeeded", next.getString("state"))
        assertEquals(2, prompt.requests.size)
        assertEquals("sess-b", prompt.requests[1].sessionId)
    }

    @Test
    fun a_run_without_a_session_context_asks_every_time() {
        val (gate, prompt) = approvalGate(clock = clock)
        val tool = tool(gate)
        payload(invoke(tool, listOf("phone", "ubuntu", "run", "--", "true")))
        payload(invoke(tool, listOf("phone", "ubuntu", "run", "--", "true"), idem = ExecFixtures.IDEM_B))
        assertEquals(2, prompt.requests.size)
        assertEquals(null, prompt.requests[0].sessionId)
        assertNull(gate.boundSession())
    }

    @Test
    fun a_host_with_no_prompt_fails_closed() {
        val tool = tool(headlessGate(clock))
        val reply = payload(invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "true")))
        assertEquals("failed", reply.getString("state"))
        val failure = reply.getJSONObject("failure")
        assertEquals(UbuntuCause.APPROVAL_UNAVAILABLE, failure.getString("cause"))
        assertEquals(UbuntuOutcome.NOT_STARTED, failure.getString("outcome"))
        assertEquals("unavailable", reply.getJSONObject("approval").getString("state"))
    }

    @Test
    fun a_run_with_a_malformed_session_flag_does_not_bind_a_grant() {
        val (gate, prompt) = approvalGate(clock = clock)
        val tool = tool(gate)
        payload(invoke(tool, listOf("phone", "ubuntu", "run", "--session", "not a session", "--", "true")))
        assertNull(gate.boundSession())
        assertEquals(null, prompt.requests.single().sessionId)
    }

    @Test
    fun every_approval_decision_leaves_one_host_log_line() {
        val lines = mutableListOf<String>()
        val log = object : HostLog {
            override fun debug(message: String) = Unit
            override fun info(message: String) {
                lines += message
            }

            override fun warn(message: String) = Unit
            override fun error(message: String) = Unit
        }
        val (gate, _) = approvalGate(clock = clock)
        val tool = UbuntuHostTool(clock = clock, logger = log, approval = gate)
        payload(invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "true")))
        val granted = lines.single { it.contains("ubuntu approval=granted") }
        assertTrue(granted.contains("env=ubuntu-24.04"))
        assertTrue(granted.contains("prompted=true"))
        assertFalse("the raw session id is never logged", granted.contains("sess-a"))
    }

    @Test
    fun fetch_answers_one_chunk_and_refuses_an_artifact_over_the_file_limit() {
        // The 3 GiB row's single-file budget is 16 MiB, so the mock's 20 MiB
        // `huge.bin` crosses it; `tiny.txt` still fetches. This pins the
        // refusal shape `channel.md` §4.8 already defines.
        val tool = tool(budgets = BUDGETS_3_GIB)
        val run = payload(invoke(tool, listOf("phone", "ubuntu", "run", "--session", "sess-a", "--", "true")))
        assertEquals("ingestRefused", run.getString("state"))
        val jobId = run.getString("jobId")

        val tiny = payload(invoke(tool, listOf("phone", "ubuntu", "fetch", jobId, "tiny.txt"), idem = ExecFixtures.IDEM_B))
        assertEquals(jobId, tiny.getString("jobId"))
        assertEquals("tiny.txt", tiny.getString("name"))
        assertEquals(0L, tiny.getLong("offset"))
        assertEquals(12L, tiny.getLong("bytes"))
        assertTrue(tiny.getBoolean("eof"))
        assertTrue(tiny.isNull("refusal"))
        assertTrue(tiny.isNull("failure"))
        assertTrue(
            String(java.util.Base64.getDecoder().decode(tiny.getString("chunk")), Charsets.UTF_8)
                .isNotEmpty(),
        )

        val huge = payload(
            invoke(tool, listOf("phone", "ubuntu", "fetch", jobId, "huge.bin"), idem = "c".repeat(64)),
        )
        assertEquals("VFS_REFUSED", huge.getJSONObject("refusal").getString("code"))
        assertEquals(UbuntuVfsRefusal.FILE_TOO_LARGE, huge.getJSONObject("refusal").getString("reason"))
        assertEquals(SIXTEEN_MIB, huge.getJSONObject("refusal").getLong("limit"))
        assertEquals(MockUbuntuAdapter.HUGE_BYTES, huge.getJSONObject("refusal").getLong("bytes"))
        assertTrue(huge.isNull("chunk"))
    }

    @Test
    fun fetch_refuses_a_malformed_shape_without_echoing_it() {
        val outcome = invoke(tool(), listOf("phone", "ubuntu", "fetch", "uj-x"))
        assertTrue(outcome is ToolOutcome.Failure)
        assertEquals(UbuntuHostTool.CODE_UNSUPPORTED, (outcome as ToolOutcome.Failure).code)
        assertEquals(
            UbuntuHostTool.CODE_UNSUPPORTED,
            (invoke(tool(), listOf("phone", "ubuntu", "fetch", "uj-x", "n.txt", "--offset", "-1")) as ToolOutcome.Failure).code,
        )
    }

    @Test
    fun status_of_an_unknown_job_is_not_a_fabricated_record() {
        val reply = payload(invoke(tool(), listOf("phone", "ubuntu", "status", "uj-does-not-exist")))
        assertEquals("unknown", reply.getString("state"))
        assertEquals("adapter_gone", reply.getJSONObject("failure").getString("cause"))
        assertTrue(reply.isNull("exitCode"))
    }

    @Test
    fun status_reads_back_the_job_run_admitted() {
        val tool = tool()
        val run = payload(invoke(tool, listOf("phone", "ubuntu", "run", "--", "true")))
        val status = payload(invoke(tool, listOf("phone", "ubuntu", "status", run.getString("jobId")), idem = ExecFixtures.IDEM_B))
        assertEquals(run.getString("jobId"), status.getString("jobId"))
        assertEquals("succeeded", status.getString("state"))
        assertEquals(0, status.getInt("exitCode"))
    }

    @Test
    fun close_reports_the_environment_closed() {
        val reply = payload(invoke(tool(), listOf("phone", "ubuntu", "close")))
        assertTrue(reply.getBoolean("closed"))
        assertEquals(0, reply.getJSONArray("cancelled").length())
    }

    @Test
    fun an_unknown_op_is_unsupported_and_runs_nothing() {
        val outcome = invoke(tool(), listOf("phone", "ubuntu", "explode"))
        assertTrue(outcome is ToolOutcome.Failure)
        assertEquals(UbuntuHostTool.CODE_UNSUPPORTED, (outcome as ToolOutcome.Failure).code)
    }

    @Test
    fun run_refuses_a_malformed_flag_shape_without_echoing_argv() {
        val outcome = invoke(tool(), listOf("phone", "ubuntu", "run", "--nope", "gcc"))
        assertTrue(outcome is ToolOutcome.Failure)
        assertEquals(UbuntuHostTool.CODE_UNSUPPORTED, (outcome as ToolOutcome.Failure).code)
        assertFalse(outcome.message.contains("gcc"))
    }

    @Test
    fun the_phone_catalog_routes_info_and_ubuntu_and_refuses_the_rest() {
        val info = PhoneTool { ToolOutcome.Success("{\"info\":true}") }
        val ubuntu = PhoneTool { ToolOutcome.Success("{\"ubuntu\":true}") }
        val catalog = UbuntuPhoneCatalog(info = info, ubuntu = ubuntu)
        val infoReply = runBlocking {
            catalog.invoke(ToolInvocation(ExecFixtures.IDEM_A, "phone", ExecFixtures.baseRequest(argv = listOf("phone", "info")).toString()))
        }
        assertEquals(ToolOutcome.Success("{\"info\":true}"), infoReply)
        val ubuntuReply = runBlocking {
            catalog.invoke(
                ToolInvocation(
                    ExecFixtures.IDEM_B,
                    "phone",
                    ExecFixtures.baseRequest(argv = listOf("phone", "ubuntu", "probe"), idemKey = ExecFixtures.IDEM_B).toString(),
                ),
            )
        }
        assertEquals(ToolOutcome.Success("{\"ubuntu\":true}"), ubuntuReply)
        val other = runBlocking {
            val json = ExecFixtures.baseRequest(argv = listOf("phone", "camera"))
            catalog.invoke(ToolInvocation(ExecFixtures.IDEM_A, "phone", json.toString()))
        }
        assertTrue(other is ToolOutcome.Failure)
        assertEquals("unsupported", (other as ToolOutcome.Failure).code)
        assertTrue(other.message.contains("phone ubuntu"))
    }

    @Test
    fun the_catalog_without_an_environment_keeps_the_single_tool_refusal() {
        val catalog = UbuntuPhoneCatalog(info = PhoneTool { ToolOutcome.Success("{}") }, ubuntu = null)
        val outcome = runBlocking {
            catalog.invoke(
                ToolInvocation(
                    ExecFixtures.IDEM_A,
                    "phone",
                    ExecFixtures.baseRequest(argv = listOf("phone", "ubuntu", "probe")).toString(),
                ),
            )
        }
        assertTrue(outcome is ToolOutcome.Failure)
        assertEquals("unsupported", (outcome as ToolOutcome.Failure).code)
    }
}
