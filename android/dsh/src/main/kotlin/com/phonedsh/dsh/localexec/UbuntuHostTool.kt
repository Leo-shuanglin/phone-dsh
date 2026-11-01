// phone-dsh — :dsh / com.phonedsh.dsh.localexec: the `ubuntu` host tool.
// SPDX-License-Identifier: MIT
//
// ---- what this is ----
// The host half of the Agent-facing `ubuntu` command. The Worker sends
// `phone ubuntu <op> ...` through the exec bridge (the same host-tool
// dispatcher `phone info` uses), and this class turns each op into one
// `ubuntu.exec` / `ubuntu.status` call on the L47 mock lane:
//
//   probe            is a local environment attached? (the boot probe)
//   run … -- …       ubuntu.exec: admit a job, then read its record
//   status <jobId>   ubuntu.status
//   close            stop the environment
//
// ---- why the answer is JSON on stdout ----
// `bridge.md` §12.8's status payload is an object, and the Worker command is the
// only place that decides how to render it for the model. Keeping the host side
// structured means the Agent's text is produced once, in `shell-ext`, and the
// host never grows a second wording for the same facts.
//
// ---- what this does NOT do ----
// It does not seal, snapshot, fork a process or read a workspace byte. It runs
// the mock adapter, so every job is `backend = mock`; the answer says so and
// the seal member is flagged `sealMock`. A real environment replaces the
// session, not this dispatch surface.

package com.phonedsh.dsh.localexec

import com.phonedsh.controller.ControllerBudgets
import com.phonedsh.dsh.exec.ExecRequest
import com.phonedsh.dsh.exec.ExecRequestException
import com.phonedsh.dsh.host.HostLog
import com.phonedsh.dsh.tools.PhoneTool
import com.phonedsh.dsh.tools.ToolInvocation
import com.phonedsh.dsh.tools.ToolOutcome
import com.phonedsh.platform.Clock
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `ubuntu` host tool over the in-process mock environment.
 *
 * @param clock wall time for the lane, the adapter and the wait bound.
 * @param logger token-free host log.
 * @param budgets the tier numbers the JobRef publishes. The mock has no device
 *   measurement, so the caller passes its own tier when it has one; the default
 *   is budgets.md's 8 GiB row.
 * @param admissionOpen the host's admission state, read at job start; the exec
 *   bridge already refused the dispatch when admission was closed.
 */
class UbuntuHostTool(
    private val clock: Clock,
    private val logger: HostLog = HostLog.None,
    private val budgets: ControllerBudgets = ControllerBudgets.forTier(ControllerBudgets.DEFAULT_TIER),
    private val admissionOpen: () -> Boolean = { true },
) : PhoneTool {

    private val lock = Any()

    private var created: MockUbuntuSession? = null

    /** The lazily created session; construction is cheap and probe touches it. */
    private fun session(): MockUbuntuSession = synchronized(lock) {
        created ?: MockUbuntuSession(
            clock = clock,
            budgets = budgets,
            logger = logger,
            admissionOpen = admissionOpen,
        ).also { created = it }
    }

    override suspend fun invoke(invocation: ToolInvocation): ToolOutcome {
        val request = try {
            ExecRequest.parse(invocation.argumentsJson)
        } catch (invalid: ExecRequestException) {
            return ToolOutcome.Failure(CODE_UNSUPPORTED, "the dispatch request is unreadable")
        }
        val argv = request.argv
        if (argv.size < 3 || argv[0] != PHONE_TOOL || argv[1] != UBUNTU_TOOL) {
            return ToolOutcome.Failure(CODE_UNSUPPORTED, "only `phone ubuntu <probe|run|status|close>` is implemented")
        }
        return when (argv[2]) {
            OP_PROBE -> probe()
            OP_RUN -> run(request, argv.subList(3, argv.size))
            OP_STATUS -> status(argv.subList(3, argv.size))
            OP_CLOSE -> close(argv.subList(3, argv.size))
            else -> ToolOutcome.Failure(CODE_UNSUPPORTED, "only `phone ubuntu <probe|run|status|close>` is implemented")
        }
    }

    /** The boot probe: available only when the session really opened. */
    private suspend fun probe(): ToolOutcome {
        val session = try {
            session().also { it.lane() }
        } catch (failure: Exception) {
            logger.warn("ubuntu probe failed (${failure.javaClass.simpleName}); the command stays unregistered")
            return ToolOutcome.Success(
                resultJson(
                    JSONObject()
                        .put("available", false)
                        .put("backend", MockUbuntuSession.BACKEND)
                        .put("reason", "the mock session could not be created"),
                ),
            )
        }
        return ToolOutcome.Success(
            resultJson(
                JSONObject()
                    .put("available", true)
                    .put("backend", session.backend)
                    .put("env", session.env)
                    .put("commands", JSONArray(listOf(OP_RUN, OP_STATUS, OP_CLOSE))),
            ),
        )
    }

    /** `ubuntu.exec` plus the terminal wait; the answer is the §12.8 record. */
    private suspend fun run(request: ExecRequest, args: List<String>): ToolOutcome {
        val parsed = parseRun(args)
            ?: return ToolOutcome.Failure(CODE_UNSUPPORTED, "run takes [--env V] [--cwd P] [--label L] [--timeout-ms N] -- <command…>")
        val job = try {
            UbuntuExecRequest(
                env = parsed.env ?: session().env,
                argv = parsed.command,
                cwd = parsed.cwd ?: DEFAULT_GUEST_ROOT,
                workspace = DEFAULT_GUEST_ROOT,
                label = parsed.label,
                jobTimeoutMs = parsed.jobTimeoutMs,
            )
        } catch (invalid: IllegalArgumentException) {
            // The Worker command validates its own flags; a request this side
            // cannot represent is a host defect, not a job. Never echo argv.
            logger.warn("ubuntu run refused a malformed request (${invalid.javaClass.simpleName})")
            return ToolOutcome.Failure(CODE_UNSUPPORTED, "the run request is not representable")
        }
        return when (val submitted = session().submit(job)) {
            is UbuntuSubmit.Refused -> ToolOutcome.Success(
                resultJson(failurePayload(submitted.bridgeCode, submitted.message)),
            )

            is UbuntuSubmit.Accepted -> {
                val jobId = submitted.job.jobId
                val waitMs = waitBudgetMs(request, job.jobTimeoutMs)
                val record = session().awaitTerminal(jobId, waitMs) ?: submitted.job
                ToolOutcome.Success(resultJson(jobPayload(record)))
            }
        }
    }

    /** `ubuntu.status`: one record, or a truthful "unknown job". */
    private suspend fun status(args: List<String>): ToolOutcome {
        if (args.size != 1 || args[0].isEmpty()) {
            return ToolOutcome.Failure(CODE_UNSUPPORTED, "status takes exactly one job id")
        }
        val jobId = args[0]
        val record = session().status(jobId)
            ?: return ToolOutcome.Success(
                resultJson(
                    JSONObject()
                        .put("backend", MockUbuntuSession.BACKEND)
                        .put("jobId", jobId)
                        .put("state", "unknown")
                        .put("exitCode", JSONObject.NULL)
                        .put("logTail", "")
                        .put("artifacts", JSONArray())
                        .put("failure", failureObject("provider_unavailable", "unknown", "adapter_gone", "no job $jobId on this host"))
                        .put("internalAccessObserved", JSONObject.NULL)
                        .put("seal", JSONObject().put("state", "pending").put("reason", JSONObject.NULL))
                        .put("sealMock", true)
                        .put("egress", false)
                        .put("authorityExited", JSONObject.NULL),
                ),
            )
        return ToolOutcome.Success(resultJson(jobPayload(record)))
    }

    /** `ubuntu close`: stop every non-terminal job, then report the ids. */
    private suspend fun close(args: List<String>): ToolOutcome {
        if (args.isNotEmpty()) return ToolOutcome.Failure(CODE_UNSUPPORTED, "close takes no arguments")
        val cancelled = try {
            session().close()
        } catch (failure: Exception) {
            logger.warn("ubuntu close failed (${failure.javaClass.simpleName})")
            return ToolOutcome.Failure(CODE_TOOL_FAILED, "the environment could not be closed")
        }
        return ToolOutcome.Success(
            resultJson(
                JSONObject()
                    .put("closed", true)
                    .put("backend", MockUbuntuSession.BACKEND)
                    .put("cancelled", JSONArray(cancelled)),
            ),
        )
    }

    /** The one `ExecToolResult` envelope: exit 0, stdout is the JSON payload. */
    private fun resultJson(payload: JSONObject, exitCode: Int = 0): String {
        val stdout = Base64.getEncoder().encodeToString(
            (payload.toString() + "\n").toByteArray(Charsets.UTF_8),
        )
        return JSONObject()
            .put("status", "exited")
            .put("code", exitCode)
            .put("signal", JSONObject.NULL)
            .put("quiescent", true)
            .put("stdout", stdout)
            .put("stderr", "")
            .toString()
    }

    /** The §12.8 record as JSON (never argv, never artifact bytes). */
    private fun jobPayload(record: UbuntuJobRecord): JSONObject {
        val artifacts = JSONArray()
        for (artifact in record.artifacts) {
            artifacts.put(
                JSONObject()
                    .put("name", artifact.name)
                    .put("bytes", artifact.bytes)
                    .put("state", artifact.state.wire)
                    .put("sha256", artifact.sha256 ?: JSONObject.NULL),
            )
        }
        val failure = record.failure?.let { failure ->
            failureObject(
                code = failure.code,
                outcome = failure.outcome,
                cause = failure.cause,
                message = failure.message,
            ).put("retryable", failure.retryable)
                .put("foregroundRequired", failure.foregroundRequired)
        }
        return JSONObject()
            .put("backend", MockUbuntuSession.BACKEND)
            .put("env", record.env)
            .put("jobId", record.jobId)
            .put("state", record.state.wire)
            .put("seq", record.seq)
            .put("exitCode", record.exitCode ?: JSONObject.NULL)
            .put("logTail", record.logTail)
            .put("artifacts", artifacts)
            .put("failure", failure ?: JSONObject.NULL)
            .put("internalAccessObserved", record.internalAccessObserved ?: JSONObject.NULL)
            .put("seal", JSONObject().put("state", record.seal.wire).put("reason", record.sealReason ?: JSONObject.NULL))
            // The lane's seal state is the mock's; this flag is what keeps a
            // `sealed`/`restored` pair from reading as a protected window.
            .put("sealMock", true)
            .put("egress", record.egress)
            .put("authorityExited", record.authorityExited ?: JSONObject.NULL)
            .put("argvDigest", record.argvDigest)
            .put("label", record.label ?: JSONObject.NULL)
            .put(
                "limits",
                JSONObject()
                    .put("fileLimitBytes", record.limits.fileLimitBytes)
                    .put("workspaceLimitBytes", record.limits.workspaceLimitBytes)
                    .put("rssLimitBytes", record.limits.rssLimitBytes ?: JSONObject.NULL),
            )
    }

    /** A refusal that created no record, in the status shape the renderer reads. */
    private fun failurePayload(code: String, message: String): JSONObject = JSONObject()
        .put("backend", MockUbuntuSession.BACKEND)
        .put("state", "failed")
        .put("exitCode", JSONObject.NULL)
        .put("logTail", "")
        .put("artifacts", JSONArray())
        .put("failure", failureObject("provider_unavailable", UbuntuOutcome.NOT_STARTED, null, "$code: $message"))
        .put("internalAccessObserved", JSONObject.NULL)
        .put("seal", JSONObject().put("state", "pending").put("reason", JSONObject.NULL))
        .put("sealMock", true)
        .put("egress", false)
        .put("authorityExited", JSONObject.NULL)

    private fun failureObject(code: String, outcome: String, cause: String?, message: String): JSONObject =
        JSONObject()
            .put("code", code)
            .put("message", message)
            .put("outcome", outcome)
            .put("retryable", false)
            .put("cause", cause ?: JSONObject.NULL)

    /** How long `run` waits for the record; never past the dispatch deadline. */
    private fun waitBudgetMs(request: ExecRequest, jobTimeoutMs: Long): Long {
        val remaining = request.deadline?.let { (it - clock.nowMillis()).coerceAtLeast(1L) }
            ?: DEFAULT_WAIT_MS
        return remaining.coerceAtMost(jobTimeoutMs + WAIT_MARGIN_MS).coerceAtLeast(1L)
    }

    /** One parsed `run`. */
    private class RunArgs(
        val env: String?,
        val cwd: String?,
        val label: String?,
        val jobTimeoutMs: Long,
        val command: List<String>,
    )

    /** Parse the run flags; null for any shape this tool cannot honour. */
    private fun parseRun(args: List<String>): RunArgs? {
        var env: String? = null
        var cwd: String? = null
        var label: String? = null
        var timeoutMs = UbuntuExecRequest.DEFAULT_JOB_TIMEOUT_MS
        var index = 0
        while (index < args.size) {
            val word = args[index]
            if (word == "--") {
                index += 1
                break
            }
            if (word != "--env" && word != "--cwd" && word != "--label" && word != "--timeout-ms") return null
            val value = args.getOrNull(index + 1) ?: return null
            index += 2
            when (word) {
                "--env" -> env = value
                "--cwd" -> cwd = value
                "--label" -> label = value
                "--timeout-ms" -> timeoutMs = value.toLongOrNull() ?: return null
            }
        }
        val command = args.subList(index, args.size)
        if (command.isEmpty()) return null
        return RunArgs(env, cwd, label, timeoutMs, command)
    }

    companion object {
        /** The `phone` tool name; `argv[0]` of every dispatch. */
        const val PHONE_TOOL: String = "phone"

        /** The sub-tool this class owns. */
        const val UBUNTU_TOOL: String = "ubuntu"

        /** The four ops. */
        const val OP_PROBE: String = "probe"
        const val OP_RUN: String = "run"
        const val OP_STATUS: String = "status"
        const val OP_CLOSE: String = "close"

        /** The one guest root the mock advertises (bridge.md §12.2's default). */
        const val DEFAULT_GUEST_ROOT: String = "/dsh/workspace"

        /** Fallback wait when the dispatch carried no deadline. */
        const val DEFAULT_WAIT_MS: Long = 60_000L

        /** Slack over the job deadline before `run` stops waiting. */
        const val WAIT_MARGIN_MS: Long = 5_000L

        /** Stable refusal: the request shape is not this tool's. */
        const val CODE_UNSUPPORTED: String = "unsupported"

        /** Stable refusal: the environment could not be stopped. */
        const val CODE_TOOL_FAILED: String = "tool_failed"
    }
}
