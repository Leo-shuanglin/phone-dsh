// phone-dsh — :dsh unit test: the `ubuntu` host tool over the L47 mock lane.
// SPDX-License-Identifier: MIT
//
// What these tests pin: the four ops the Worker command drives, and the two
// honesty flags that keep a mock answer from reading as a real result
// (`backend = mock`, `sealMock = true`). The job machine itself is covered by
// UbuntuJobLaneTest / UbuntuJobRecoveryTest; this file covers the wiring the
// Agent's `ubuntu` command actually reaches.

package com.phonedsh.dsh.localexec

import com.phonedsh.dsh.exec.ExecFixtures
import com.phonedsh.dsh.exec.ExecToolResult
import com.phonedsh.dsh.tools.PhoneTool
import com.phonedsh.dsh.tools.ToolInvocation
import com.phonedsh.dsh.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuHostToolTest {
    private val clock = TestClock()

    private fun tool(): UbuntuHostTool = UbuntuHostTool(clock = clock)

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
            listOf("run", "status", "close"),
            reply.getJSONArray("commands").let { array -> (0 until array.length()).map { array.getString(it) } },
        )
    }

    @Test
    fun run_admits_one_job_and_answers_the_terminal_record() {
        val startedAt = clock.nowMillis()
        val reply = payload(
            invoke(
                tool(),
                listOf("phone", "ubuntu", "run", "--env", MockUbuntuAdapter.DEFAULT_ENV, "--label", "fib", "--", "gcc", "-o", "a", "a.c"),
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
        // The record is the §12.8 payload, not an argv echo: the audit names the
        // command by digest only.
        assertEquals(64, reply.getString("argvDigest").length)
        assertFalse(reply.toString().contains("gcc"))
        assertTrue(clock.nowMillis() >= startedAt)
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
