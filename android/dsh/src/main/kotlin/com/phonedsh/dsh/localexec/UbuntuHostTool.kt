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
//   run … -- …       ubuntu.exec: approve this session, admit a job, read its record
//   status <jobId>   ubuntu.status
//   fetch <jobId> <name> [--offset N] [--path P]   ubuntu.fetch: one artifact chunk
//   close            stop the environment
//
// ---- the approval gate is before the lane, not inside it --------------------
// `run` asks [UbuntuApprovalGate] first (design §3 layer 1: one approval per
// session). A refusal never reaches `UbuntuJobLane.submit`; it writes a
// `failed` record through `recordRefusal`, so the audit keeps the user's
// decision and `ubuntu.status` can answer for the attempt.
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
 * @param approval the session gate. The default is the process-wide gate the app
 *   installs its dialog into; a test passes its own so no global state leaks.
 */
class UbuntuHostTool(
    private val clock: Clock,
    private val logger: HostLog = HostLog.None,
    private val budgets: ControllerBudgets = ControllerBudgets.forTier(ControllerBudgets.DEFAULT_TIER),
    private val admissionOpen: () -> Boolean = { true },
    private val approval: UbuntuApprovalGate = UbuntuApproval.gate,
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
            return ToolOutcome.Failure(CODE_UNSUPPORTED, REFUSED_OPS)
        }
        return when (argv[2]) {
            OP_PROBE -> probe()
            OP_RUN -> run(request, argv.subList(3, argv.size))
            OP_STATUS -> status(argv.subList(3, argv.size))
            OP_FETCH -> fetch(argv.subList(3, argv.size))
            OP_CLOSE -> close(argv.subList(3, argv.size))
            else -> ToolOutcome.Failure(CODE_UNSUPPORTED, REFUSED_OPS)
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
                    .put("commands", JSONArray(listOf(OP_RUN, OP_STATUS, OP_FETCH, OP_CLOSE))),
            ),
        )
    }

    /**
     * `ubuntu.exec` behind the session approval gate, then the terminal wait.
     *
     * Ordering is the point: no record is admitted and no adapter is contacted
     * until the gate answers [UbuntuApprovalState.GRANTED]. A refusal is a
     * terminal `failed` record with `outcome = not_started` — a job that never
     * ran, kept in the audit because the user's decision is testimony.
     */
    private suspend fun run(request: ExecRequest, args: List<String>): ToolOutcome {
        val parsed = parseRun(args)
            ?: return ToolOutcome.Failure(CODE_UNSUPPORTED, RUN_USAGE)
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
        val decision = approval.authorize(
            UbuntuApprovalRequest(
                sessionId = parsed.session,
                env = job.env,
                backend = MockUbuntuSession.BACKEND,
                label = job.label,
                argvDigest = UbuntuJobLane.digest(job.argv),
                argvLength = job.argv.size,
            ),
        )
        // One audit line per decision, on the host's own logger. The gate's
        // logger is the process-wide default (`None`); this is the log the app
        // actually collects, and the record carries the same decision durably.
        logger.info(
            "ubuntu approval=${decision.state.wire} env=${job.env} " +
                "session=${decision.sessionId?.let(UbuntuApprovalGate::digest) ?: "-"} " +
                "prompted=${decision.prompted}",
        )
        if (decision.state != UbuntuApprovalState.GRANTED) {
            val failure = when (decision.state) {
                UbuntuApprovalState.DENIED -> UbuntuFailures.approvalDenied()
                else -> UbuntuFailures.approvalUnavailable(decision.reason ?: "unknown")
            }
            logger.info("ubuntu run refused before admission: cause=${failure.cause}")
            val record = session().lane().recordRefusal(job, failure, decision.record())
            return if (record != null) {
                ToolOutcome.Success(resultJson(jobPayload(record)))
            } else {
                // The store refused the audit line; answer the same failure
                // without inventing a job id.
                ToolOutcome.Success(resultJson(refusalPayload(failure, decision.record())))
            }
        }
        return when (val submitted = session().submit(job, decision.record())) {
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

    /**
     * `ubuntu.fetch`: one artifact chunk at bridge.md §4's 512 KiB bound.
     *
     * The bytes are base64 in the payload; the Worker writes them through the
     * standard VFS mutation path (it is the only writer of VFS_MUT). The host
     * stages nothing: a chunk is read from the adapter at fetch time.
     */
    private suspend fun fetch(args: List<String>): ToolOutcome {
        val parsed = parseFetch(args)
            ?: return ToolOutcome.Failure(CODE_UNSUPPORTED, FETCH_USAGE)
        if (parsed.path != null && !UbuntuArtifactIngest.isMutationPath(parsed.path)) {
            return ToolOutcome.Failure(CODE_UNSUPPORTED, "the fetch path is not a channel.md §6 mutation path")
        }
        val answer = session().lane().fetchChunk(parsed.jobId, parsed.name, parsed.offset)
        return ToolOutcome.Success(resultJson(fetchPayload(answer)))
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
                        .put("authorityExited", JSONObject.NULL)
                        .put("approval", JSONObject.NULL),
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
            .put("approval", approvalObject(record.approval))
            .put(
                "limits",
                JSONObject()
                    .put("fileLimitBytes", record.limits.fileLimitBytes)
                    .put("workspaceLimitBytes", record.limits.workspaceLimitBytes)
                    .put("rssLimitBytes", record.limits.rssLimitBytes ?: JSONObject.NULL),
            )
    }

    /**
     * The `approval` member of §12.2.
     *
     * `null` means "this record cannot say" — an old record, or a job admitted
     * before the gate existed. It is never a stand-in for a grant.
     */
    private fun approvalObject(approval: UbuntuJobApproval?): Any = if (approval == null) {
        JSONObject.NULL
    } else {
        JSONObject()
            .put("state", approval.state.wire)
            .put("sessionId", approval.sessionId ?: JSONObject.NULL)
            .put("atMs", approval.atMs)
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
        .put("approval", JSONObject.NULL)

    /**
     * A gate refusal whose record could not be persisted: the same failure,
     * answered without a job id. The decision is still carried, because the
     * Worker's renderer reads it and the caller may show it.
     */
    private fun refusalPayload(failure: UbuntuJobFailure, approval: UbuntuJobApproval): JSONObject = JSONObject()
        .put("backend", MockUbuntuSession.BACKEND)
        .put("state", UbuntuJobState.FAILED.wire)
        .put("exitCode", JSONObject.NULL)
        .put("logTail", "")
        .put("artifacts", JSONArray())
        .put(
            "failure",
            failureObject(failure.code, failure.outcome, failure.cause, failure.message)
                .put("retryable", failure.retryable)
                .put("foregroundRequired", failure.foregroundRequired),
        )
        .put("internalAccessObserved", JSONObject.NULL)
        .put("seal", JSONObject().put("state", UbuntuSealState.PENDING.wire).put("reason", JSONObject.NULL))
        .put("sealMock", true)
        .put("egress", false)
        .put("authorityExited", JSONObject.NULL)
        .put("approval", approvalObject(approval))

    /** One `ubuntu.fetch` answer (bridge.md §12.8): chunk, refusal or error. */
    private fun fetchPayload(answer: UbuntuFetch): JSONObject = when (answer) {
        is UbuntuFetch.Chunk -> JSONObject()
            .put("backend", MockUbuntuSession.BACKEND)
            .put("jobId", answer.jobId)
            .put("name", answer.name)
            .put("offset", answer.offset)
            .put("bytes", answer.bytes)
            .put("eof", answer.eof)
            .put("chunk", Base64.getEncoder().encodeToString(answer.chunk))
            .put("refusal", JSONObject.NULL)
            .put("failure", JSONObject.NULL)
            .put("limits", limitsObject(answer.limits))

        is UbuntuFetch.Refused -> JSONObject()
            .put("backend", MockUbuntuSession.BACKEND)
            .put("jobId", answer.jobId)
            .put("name", answer.name)
            .put("offset", 0L)
            .put("bytes", answer.refusal.bytes)
            .put("eof", false)
            .put("chunk", JSONObject.NULL)
            .put(
                "refusal",
                JSONObject()
                    .put("code", answer.refusal.code)
                    .put("reason", answer.refusal.reason)
                    .put("limit", answer.refusal.limit)
                    .put("bytes", answer.refusal.bytes),
            )
            .put("failure", JSONObject.NULL)
            .put("limits", limitsObject(answer.limits))

        is UbuntuFetch.Unavailable -> JSONObject()
            .put("backend", MockUbuntuSession.BACKEND)
            .put("jobId", answer.jobId)
            .put("name", answer.name)
            .put("offset", 0L)
            .put("bytes", JSONObject.NULL)
            .put("eof", false)
            .put("chunk", JSONObject.NULL)
            .put("refusal", JSONObject.NULL)
            .put(
                "failure",
                failureObject(answer.bridgeCode, UbuntuOutcome.NOT_STARTED, null, answer.message),
            )
            .put("limits", JSONObject.NULL)
    }

    /** The JobRef `limits` block, as every answer that carries one renders it. */
    private fun limitsObject(limits: UbuntuJobLimits): JSONObject = JSONObject()
        .put("fileLimitBytes", limits.fileLimitBytes)
        .put("workspaceLimitBytes", limits.workspaceLimitBytes)
        .put("rssLimitBytes", limits.rssLimitBytes ?: JSONObject.NULL)

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
        /**
         * The DSH session the Worker forwards from its trusted tool-call
         * context, or `null` when it had none. The host never invents one: a
         * missing session means the gate cannot scope a grant (it asks every
         * time), which is the honest failure mode.
         */
        val session: String?,
        val command: List<String>,
    )

    /** Parse the run flags; null for any shape this tool cannot honour. */
    private fun parseRun(args: List<String>): RunArgs? {
        var env: String? = null
        var cwd: String? = null
        var label: String? = null
        var timeoutMs = UbuntuExecRequest.DEFAULT_JOB_TIMEOUT_MS
        var session: String? = null
        var index = 0
        while (index < args.size) {
            val word = args[index]
            if (word == "--") {
                index += 1
                break
            }
            if (
                word != "--env" && word != "--cwd" && word != "--label" &&
                word != "--timeout-ms" && word != "--session"
            ) {
                return null
            }
            val value = args.getOrNull(index + 1) ?: return null
            index += 2
            when (word) {
                "--env" -> env = value
                "--cwd" -> cwd = value
                "--label" -> label = value
                "--timeout-ms" -> timeoutMs = value.toLongOrNull() ?: return null
                // A malformed session id is not a session: drop it rather than
                // letting an id the gate cannot validate scope a grant.
                "--session" -> session = value.takeIf { UbuntuApprovalGate.SESSION_ID.matches(it) }
            }
        }
        val command = args.subList(index, args.size)
        if (command.isEmpty()) return null
        return RunArgs(env, cwd, label, timeoutMs, session, command)
    }

    /** One parsed `fetch`. */
    private class FetchArgs(
        val jobId: String,
        val name: String,
        val offset: Long,
        val path: String?,
    )

    /** Parse `fetch <jobId> <name> [--offset N] [--path P]`; null for a bad shape. */
    private fun parseFetch(args: List<String>): FetchArgs? {
        val positional = mutableListOf<String>()
        var offset = 0L
        var path: String? = null
        var index = 0
        while (index < args.size) {
            val word = args[index]
            if (word == "--offset" || word == "--path") {
                val value = args.getOrNull(index + 1) ?: return null
                index += 2
                if (word == "--offset") {
                    offset = value.toLongOrNull()?.takeIf { it >= 0L } ?: return null
                } else {
                    if (value.isEmpty()) return null
                    path = value
                }
                continue
            }
            if (word.startsWith("--")) return null
            positional += word
            index += 1
        }
        if (positional.size != 2) return null
        if (!UbuntuJobRecord.JOB_ID.matches(positional[0])) return null
        if (!UbuntuArtifactRecord.NAME.matches(positional[1])) return null
        return FetchArgs(positional[0], positional[1], offset, path)
    }

    companion object {
        /** The `phone` tool name; `argv[0]` of every dispatch. */
        const val PHONE_TOOL: String = "phone"

        /** The sub-tool this class owns. */
        const val UBUNTU_TOOL: String = "ubuntu"

        /** The five ops. */
        const val OP_PROBE: String = "probe"
        const val OP_RUN: String = "run"
        const val OP_STATUS: String = "status"
        const val OP_FETCH: String = "fetch"
        const val OP_CLOSE: String = "close"

        /** The refusal message for an op outside the five. */
        const val REFUSED_OPS: String = "only `phone ubuntu <probe|run|status|fetch|close>` is implemented"

        /** The `run` and `fetch` shapes, for a malformed dispatch. */
        const val RUN_USAGE: String = "run takes [--env V] [--cwd P] [--label L] [--timeout-ms N] [--session S] -- <command…>"
        const val FETCH_USAGE: String = "fetch takes <jobId> <name> [--offset N] [--path P]"

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
