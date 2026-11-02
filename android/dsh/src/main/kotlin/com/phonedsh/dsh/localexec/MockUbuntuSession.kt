// phone-dsh — :dsh / com.phonedsh.dsh.localexec: the mock environment's host runtime.
// SPDX-License-Identifier: MIT
//
// ---- what this file is ----
// The wiring half of the L47 mock: it builds the `ubuntu.exec` lane over
// [MockUbuntuAdapter] so the Agent-facing `ubuntu` command has a host tool to
// call. It is deliberately NOT the real environment (no proot, no rootfs, no
// chmod seal); everything it stands in for is named "mock" here and rendered as
// `backend=mock` / `sealMock=true` in the answer, so a synthetic exit 0 can
// never be read as a real compile.
//
// ---- why the job store lives under a temp directory ----
// The mock adapter dies with the process, so a job persisted anywhere else could
// never be resumed: §12.3's `remote` row would have no adapter to resume with.
// A per-process temp directory is the honest store for an in-process mock; the
// record format and the atomic replace are the production ones, so the lane's
// durability path is still exercised.
//
// ---- the seal is a mock, and says so ----
// The real `BuildSeal` lives in `:platform-android` and `:dsh` does not depend on
// it. [MockUbuntuSeal] opens and closes nothing; its tripwire observes nothing.
// The lane therefore records `seal: sealed/restored` because the state machine
// ran, and the host tool adds `sealMock: true` so neither the Worker nor the
// Agent can read that as a protected window. This is the one place a mock
// answer could be mistaken for an audit fact, which is why it is flagged twice.

package com.phonedsh.dsh.localexec

import com.phonedsh.controller.ControllerBudgets
import com.phonedsh.dsh.host.HostLog
import com.phonedsh.platform.Clock
import com.phonedsh.platform.Durability
import com.phonedsh.platform.DurabilityLevel
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The `Durability` SPI over real files, for the mock's job store.
 *
 * `atomicReplace` is the production order the WAL and `HostStateStore` use:
 * write a temp sibling, fsync it, rename over the target, fsync the directory.
 * The mock has no power-loss promise to keep, but a half-written record would
 * still break `ubuntu.status`, so the order is not simplified away.
 */
class MockRecordDurability : Durability {
    override suspend fun fsyncPath(path: Path) {
        if (Files.isDirectory(path)) {
            fsyncDirectory(path)
            return
        }
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    override suspend fun atomicReplace(target: Path, write: (Path) -> Unit) {
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        try {
            write(temporary)
            fsyncPath(temporary)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            fsyncDirectory(target.parent)
        } catch (failure: IOException) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }

    override fun level(): DurabilityLevel = DurabilityLevel.LEVEL_1_PROCESS_KILL

    private fun fsyncDirectory(directory: Path) {
        // Directory fsync is not portable to every Android filesystem; a
        // refusal here must not fail a save that the rename already completed.
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (ignored: IOException) {
            // Best effort; the record itself is already fsynced.
        }
    }
}

/**
 * The mock's seal: it opens nothing and observes nothing.
 *
 * Never use this for a real environment — [UbuntuSeal.unwired] is the
 * fail-closed seam for that case, and this class exists only because the mock
 * adapter reads no workspace byte, so there is no window to protect. The host
 * tool marks every answer `sealMock: true`.
 */
class MockUbuntuSeal : UbuntuSeal {
    override suspend fun seal(jobId: String): UbuntuSealResult = UbuntuSealResult.Sealed

    override suspend fun restore(jobId: String): UbuntuSealResult = UbuntuSealResult.Sealed

    override fun observed(jobId: String): Boolean = false

    override suspend fun repair(): Boolean = true
}

/**
 * One mock environment session: the lane, its adapter and its volatile store.
 *
 * @param clock wall time for the lane and the adapter's delay.
 * @param budgets the tier numbers the JobRef publishes; the host passes its own
 *   tier when it has one, and the default row otherwise.
 * @param logger token-free host log.
 * @param adapter the environment; a fresh mock is built when this is omitted so
 *   a session that outlives one job still has an adapter.
 */
class MockUbuntuSession(
    private val clock: Clock,
    private val budgets: ControllerBudgets = ControllerBudgets.forTier(ControllerBudgets.DEFAULT_TIER),
    private val logger: HostLog = HostLog.None,
    // The mock's own manifest sits on both sides of the 15 MiB mutation bound
    // by design (`tiny.txt`, `mid.bin`, `huge.bin`). The Agent-facing session
    // declares a 20 MiB artifact limit so the ordinary answer is `succeeded`
    // with the manifest visible; the ingest-refusal path stays covered by the
    // lane's own tests, which build their own adapter.
    private val adapter: UbuntuAdapter = MockUbuntuAdapter(
        clock = clock,
        fileLimitBytes = MockUbuntuAdapter.HUGE_BYTES,
    ),
    private val admissionOpen: () -> Boolean = { true },
    private val storeRoot: Path? = null,
) {
    /** The mock's declared environment id; also the adapter registry key. */
    val env: String get() = adapter.env

    /** The one backend name every answer carries. */
    val backend: String = BACKEND

    private val jobsDir: Path = storeRoot ?: Files.createTempDirectory("pdsh-ubuntu-jobs")

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started: UbuntuJobLane? = null

    /** True once `close` ran; a later `run` opens a fresh session lazily. */
    var closed: Boolean = false
        private set

    /**
     * The lane, recovering the volatile store on first use.
     *
     * Recovery is idempotent and cheap on an empty directory. A store this
     * process wrote in an earlier session is not reused: the mock adapter that
     * owned those jobs is gone, so the boot table's `remote → failed` row is
     * the honest outcome and the lane applies it.
     */
    suspend fun lane(): UbuntuJobLane {
        started?.let { return it }
        val lane = UbuntuJobLane(
            scope = scope,
            clock = clock,
            store = UbuntuJobStore(jobsDir, MockRecordDurability(), logger),
            adapters = mapOf(adapter.env to adapter),
            seal = MockUbuntuSeal(),
            budgets = budgets,
            vfsRoot = jobsDir,
            sink = { _, _, _ -> Unit },
            admissionOpen = admissionOpen,
            logger = logger,
        )
        lane.recover()
        started = lane
        closed = false
        return lane
    }

    /**
     * Submit one job (bridge.md §12.2).
     *
     * @param approval the session decision that admitted it, written into the
     *   record's audit line; `null` for a caller with no gate (tests).
     */
    suspend fun submit(
        request: UbuntuExecRequest,
        approval: UbuntuJobApproval? = null,
    ): UbuntuSubmit = lane().submit(request, approval)

    /** The current record, or null for an id this process never knew. */
    suspend fun status(jobId: String): UbuntuJobRecord? = lane().status(jobId)

    /**
     * Wait for a job to become terminal, bounded.
     *
     * @param jobId the job.
     * @param timeoutMs how long to wait; the caller passes the request deadline.
     * @returns the last record seen, terminal or not.
     */
    suspend fun awaitTerminal(jobId: String, timeoutMs: Long): UbuntuJobRecord? {
        val lane = lane()
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            while (true) {
                val record = lane.status(jobId) ?: return@withTimeoutOrNull null
                if (record.terminal) return@withTimeoutOrNull record
                delay(POLL_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } ?: lane.status(jobId)
    }

    /**
     * Close the environment (the Agent's `ubuntu close`).
     *
     * Every non-terminal job is cancelled first, so a close never leaves a job
     * in flight that the next answer would still call `remote`.
     * @returns the ids that were stopped.
     */
    suspend fun close(): List<String> {
        val lane = started ?: return emptyList()
        val stopped = lane.jobs().filter { !it.terminal }.map { it.jobId }
        for (jobId in stopped) lane.cancel(jobId)
        closed = true
        return stopped
    }

    companion object {
        /** The backend name reported in every answer. */
        const val BACKEND: String = "mock"

        /** How often the host tool polls a running mock job. */
        private const val POLL_INTERVAL_MS: Long = 20L
    }
}
