// PC-20261104 CCR-2: the two shared drain fixtures on the real host path.
// SPDX-License-Identifier: MIT
//
// The fixtures (`spec/v1/golden/perturbations/drain-outstanding-resync-*.json`)
// are read from disk; their ids, precondition mseq and expected terminal shape
// drive the real `ControllerStateMachine`, the real `DrainLane`, the real
// `VfsSink` and a real `WorkspaceLog`. The first case answers the VFS_RESYNC
// before the deadline and parks with a closed epoch; the second never answers,
// so the deadline runs the failing close and the recorded detail names the held
// path and mseq.

package com.phonedsh.dsh.host

import com.phonedsh.controller.ControllerFailure
import com.phonedsh.controller.ControllerSignal
import com.phonedsh.controller.ControllerStateMachine
import com.phonedsh.controller.HostState
import com.phonedsh.controller.ScenePhase
import com.phonedsh.log.BootId as LogBootId
import com.phonedsh.log.Manifest
import com.phonedsh.log.MutationBody
import com.phonedsh.log.RecoveryOutcome
import com.phonedsh.log.VfsOp
import com.phonedsh.log.WalPaths
import com.phonedsh.log.WorkspaceLog
import com.phonedsh.loopback.ChannelCloseCause
import com.phonedsh.platform.EngineHostState
import com.phonedsh.platform.ScenePhase as PlatformScenePhase
import com.phonedsh.platform.testing.FakePlatform
import com.phonedsh.wire.WireCodec
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrainResyncFixtureTest {
    @Test
    fun drainOutstandingResyncAnsweredParksAfterTheRepair() {
        val fixture = fixture("drain-outstanding-resync-answered")
        val preconditions = fixture.getJSONObject("preconditions")
        assertEquals("Running", preconditions.getString("host_state"))
        assertEquals("open", preconditions.getString("admission"))
        assertEquals("open", preconditions.getString("worker_channel"))
        val heldMseq = 2L
        assertEquals(heldMseq, preconditions.getLong("acked_mseq"))
        val expected = fixture.getJSONObject("expected")
        assertEquals("level_2", expected.getString("durability_level"))
        assertEquals("closed_epoch", expected.getString("wal_tail"))

        FakePlatform().use { platform ->
            val paths = WalPaths(platform.tempDir)
            val log = WorkspaceLog(
                paths = paths,
                durability = platform,
                clock = platform,
                bootId = LogBootId.random(),
            )
            val controller = ControllerStateMachine.boot(persistedState = null)
            val host = DefaultPhoneDshHost(
                engine = platform.engine,
                controller = controller,
                clock = platform,
                signals = platform,
                notifier = platform,
                wal = log.asHostWal(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                logger = RecordingHostLog(),
                turnWindow = { TurnWindow.Absent },
            )
            try {
                startRunning(host, platform)
                val connection = connect(host)
                send(host, connection, writeFrame(1, HELD, "abc", seq = 2))
                send(host, connection, appendFrame(heldMseq, HELD, 10, "xyz", seq = 3))
                assertEquals("the fixture precondition is a held repair", heldMseq, log.outstandingResync()?.mseq)
                assertEquals(1L, log.materializedUptoMseq)
                assertEquals(1, resyncFrames(connection))

                // scenePhase background: the drain starts and must wait for the
                // connected Worker's answer instead of closing immediately.
                platform.emit(PlatformScenePhase(PlatformScenePhase.Phase.BACKGROUND))
                runBlocking { withTimeout(5_000) { host.state.first { it == HostState.DRAINING } } }
                assertEquals("no close while the repair is outstanding", 0, platform.engine.stopCount)
                assertEquals("no close while the repair is outstanding", 1, resyncFrames(connection))

                // The Worker answers with a complete write at the next mseq.
                send(host, connection, writeFrame(3, HELD, "worker-fix", seq = 4))
                runBlocking { withTimeout(5_000) { host.state.first { it == HostState.PARKED } } }

                assertEquals(expected.getString("state"), host.state.value.wireName)
                assertEquals(expected.getString("admission"), controller.admission.wireName)
                assertSubsequence(
                    expected.getJSONArray("state_path").toList().map { it.toString() },
                    controller.transitions.map { it.to.wireName },
                )
                assertEquals(3L, log.materializedUptoMseq)
                assertNull(log.outstandingResync())
                val manifest = readManifest(paths)
                assertEquals(
                    "the drain closed a level-2 epoch (wal.md §5.2)",
                    1,
                    manifest.lastDurableEpoch,
                )
                assertEquals(3L, manifest.materializedUptoMseq)
                assertTrue("the worker was stopped", platform.engine.stopCount >= 1)
                assertEquals(1, resyncFrames(connection))
            } finally {
                host.stop()
            }
        }
    }

    @Test
    fun drainOutstandingResyncDeadlineFailedEntersFailedWithPathAndMseq() {
        val fixture = fixture("drain-outstanding-resync-deadline-failed")
        val preconditions = fixture.getJSONObject("preconditions")
        val heldMseq = 2L
        assertEquals(heldMseq, preconditions.getLong("acked_mseq"))
        assertEquals("open", preconditions.getString("worker_channel"))
        val expected = fixture.getJSONObject("expected")
        val detail = expected.getJSONObject("failure_detail")
        assertEquals("Failed", expected.getString("state"))
        assertEquals("level_1", expected.getString("durability_level"))
        assertEquals("intact_open_epoch", expected.getString("wal_tail"))

        FakePlatform().use { platform ->
            val paths = WalPaths(platform.tempDir)
            // A real retained WAL with an unrepaired base mismatch, recovered
            // cold so the sink is seeded from the recovery request.
            val seed = WorkspaceLog(
                paths = paths,
                durability = platform,
                clock = platform,
                bootId = LogBootId.random(),
            )
            runBlocking {
                assertTrue(seed.recover() is RecoveryOutcome.Recovered)
                seed.appendMutation(writeMutation(HELD, "abc"))
                seed.closeEpoch()
                seed.appendMutation(appendedMutation(HELD, 6, "ghi"))
            }

            val log = WorkspaceLog(
                paths = paths,
                durability = platform,
                clock = platform,
                bootId = LogBootId.random(),
            )
            val hostWal = log.asHostWal()
            val recovered = runBlocking { hostWal.recover() }
            assertTrue("$recovered", recovered is WalRecovery.Recovered)
            recovered as WalRecovery.Recovered
            assertEquals(heldMseq, recovered.ackedMseq)
            assertEquals(heldMseq, recovered.resync.single().mseq)

            val controller = ControllerStateMachine.boot(persistedState = null)
            val failures = WriteFailureSink { reason, text ->
                controller.handle(ControllerSignal.FailureRaised(reason, text), 20_005L)
            }
            val sink = VfsSink(wal = hostWal, failures = failures, logger = RecordingHostLog())
            runBlocking {
                sink.open(recovered)
                sink.attach(VfsSender { })
                sink.onHelloAnswered()
            }
            // The controller is the real D14 machine. Start it at Running (the
            // boot mapping is Recovering), then background closes admission and
            // arms its 20 s deadline. The lane below is driven with a 60 ms test
            // deadline so the same deadline path runs without a 20 s wait.
            controller.handle(ControllerSignal.LeaseCheckResult(verified = true), 0L)
            assertEquals(HostState.RUNNING, controller.state)
            controller.handle(ControllerSignal.ScenePhaseChanged(ScenePhase.BACKGROUND), 0L)
            assertEquals(HostState.DRAINING, controller.state)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val outcome = java.util.concurrent.CompletableFuture<DrainLane.DrainOutcome>()
                val lane = DrainLane(
                    scope = scope,
                    clock = platform,
                    closeEpoch = { sink.closeEpoch() },
                    waitForResyncs = { remaining -> sink.awaitResyncsSettled(remaining) },
                    stopWorker = {},
                    awaitWorkerStopped = { true },
                    onDeadlineElapsed = {
                        controller.handle(ControllerSignal.DrainDeadlineElapsed, 20_001L)
                    },
                    onDrained = { drained ->
                        controller.handle(ControllerSignal.DrainCompleted, 20_002L)
                        outcome.complete(drained)
                    },
                    logger = RecordingHostLog(),
                )
                lane.arm(platform.monotonicMillis() + 60L)
                runBlocking {
                    withTimeout(5_000) {
                        while (controller.state != HostState.FAILED) {
                            kotlinx.coroutines.delay(5)
                        }
                    }
                }
                val drained = outcome.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(DrainLane.Reason.DEADLINE, drained.reason)
                assertEquals(expected.getString("state"), controller.state.wireName)
                assertEquals(ControllerFailure.UNREPAIRED_BASE_MISMATCH, controller.lastFailure)
                assertTrue(
                    "the failure detail names the path: ${controller.lastFailureDetail}",
                    controller.lastFailureDetail?.contains(detail.getString("path")) == true,
                )
                assertTrue(
                    "the failure detail names the mseq: ${controller.lastFailureDetail}",
                    controller.lastFailureDetail?.contains("mseq ${detail.getLong("mseq")}") == true,
                )
                assertEquals(HostState.FAILED.admission, controller.admission)
                // No epoch-close record was invented for the failed close; the
                // manifest still names the one closed epoch from the seed log.
                val manifest = readManifest(paths)
                assertEquals(1, manifest.lastDurableEpoch)
                assertEquals(1L, log.lastDurableEpoch.toLong())
                assertEquals(1L, manifest.materializedUptoMseq)
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * A disconnected Worker is not waited for (`budgets.md` §1/§2). The drain
     * goes straight to the close; the held mismatch still makes that close the
     * §5.2 failure with the path and mseq, but the state reaches `Failed` in
     * milliseconds instead of sitting in the 20 s wait.
     */
    @Test
    fun aDisconnectedWorkerIsNotWaitedForAtDrain() {
        FakePlatform().use { platform ->
            val paths = WalPaths(platform.tempDir)
            val log = WorkspaceLog(
                paths = paths,
                durability = platform,
                clock = platform,
                bootId = LogBootId.random(),
            )
            val controller = ControllerStateMachine.boot(persistedState = null)
            val host = DefaultPhoneDshHost(
                engine = platform.engine,
                controller = controller,
                clock = platform,
                signals = platform,
                notifier = platform,
                wal = log.asHostWal(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                logger = RecordingHostLog(),
                turnWindow = { TurnWindow.Absent },
            )
            try {
                startRunning(host, platform)
                val connection = connect(host)
                send(host, connection, writeFrame(1, HELD, "abc", seq = 2))
                send(host, connection, appendFrame(2, HELD, 10, "xyz", seq = 3))
                assertEquals(2L, log.outstandingResync()?.mseq)
                runBlocking {
                    host.channel.onClosed(connection, ChannelCloseCause.CLIENT_CLOSED)
                }

                platform.emit(PlatformScenePhase(PlatformScenePhase.Phase.BACKGROUND))
                runBlocking { withTimeout(5_000) { host.state.first { it == HostState.FAILED } } }
                assertEquals(ControllerFailure.UNREPAIRED_BASE_MISMATCH, controller.lastFailure)
                assertTrue(
                    "the failure names the held path: ${controller.lastFailureDetail}",
                    controller.lastFailureDetail?.contains(HELD) == true,
                )
                assertTrue(
                    "the failure names the held mseq: ${controller.lastFailureDetail}",
                    controller.lastFailureDetail?.contains("mseq 2") == true,
                )
            } finally {
                host.stop()
            }
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun startRunning(host: DefaultPhoneDshHost, platform: FakePlatform) {
        host.start()
        runBlocking { withTimeout(5_000) { host.ready.first { it } } }
        platform.engine.setState(EngineHostState.Running("http://127.0.0.1:41234", 41234))
        runBlocking { withTimeout(5_000) { host.state.first { it == HostState.RUNNING } } }
    }

    private fun connect(host: DefaultPhoneDshHost): RecordingLoopbackConnection {
        val connection = RecordingLoopbackConnection("drain-fixture")
        runBlocking {
            host.channel.onOpen(connection)
            host.channel.onBinary(connection, workerHelloFrame())
        }
        return connection
    }

    private fun send(
        host: DefaultPhoneDshHost,
        connection: RecordingLoopbackConnection,
        frame: ByteArray,
    ) {
        runBlocking { host.channel.onBinary(connection, frame) }
    }

    private fun resyncFrames(connection: RecordingLoopbackConnection): Int =
        connection.sent.count { HostFrame.typeOf(it) == 0x13 }

    private fun writeFrame(mseq: Long, path: String, text: String, seq: Long): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        val fields = """{"type":"VFS_MUT","fields":{""" +
            """"append_from":0,"data_hex":"${hex(data)}","data_len":${data.size},""" +
            """"mflags":1,"mode":420,"mseq":$mseq,"op":1,"path":"$path"}}"""
        return WireCodec.encode(fields, seq = seq)
    }

    private fun appendFrame(
        mseq: Long,
        path: String,
        appendFrom: Long,
        text: String,
        seq: Long,
    ): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        val fields = """{"type":"VFS_MUT","fields":{""" +
            """"append_from":$appendFrom,"data_hex":"${hex(data)}","data_len":${data.size},""" +
            """"mflags":2,"mode":420,"mseq":$mseq,"op":1,"path":"$path"}}"""
        return WireCodec.encode(fields, seq = seq)
    }

    private fun hex(bytes: ByteArray): String = buildString {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append("0123456789abcdef"[value ushr 4]).append("0123456789abcdef"[value and 0x0F])
        }
    }

    private fun writeMutation(path: String, text: String): MutationBody = MutationBody(
        op = VfsOp.WRITE,
        mode = 420,
        path = path,
        data = text.toByteArray(Charsets.UTF_8),
        entryChanged = true,
    )

    private fun appendedMutation(path: String, appendFrom: Long, text: String): MutationBody =
        MutationBody(
            op = VfsOp.WRITE,
            mode = 420,
            path = path,
            data = text.toByteArray(Charsets.UTF_8),
            appended = true,
            appendFrom = appendFrom,
        )

    private fun readManifest(paths: WalPaths): Manifest =
        Manifest.parse(Files.readAllBytes(paths.manifestFile))

    private fun assertSubsequence(needle: List<String>, haystack: List<String>) {
        var matched = 0
        haystack.forEach { value ->
            if (matched < needle.size && needle[matched] == value) matched++
        }
        assertEquals("$needle is not a subsequence of $haystack", needle.size, matched)
    }

    private fun fixture(id: String): JSONObject {
        val name = "$id.json"
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "spec/v1/golden/perturbations/$name")
            if (candidate.isFile) {
                return JSONObject(candidate.readText(Charsets.UTF_8))
            }
            directory = directory.parentFile
        }
        error("cannot locate spec/v1/golden/perturbations/$name")
    }

    private companion object {
        const val HELD = "/dsh/workspace/notes.txt"
    }
}
