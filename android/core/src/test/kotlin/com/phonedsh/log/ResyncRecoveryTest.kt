// WAL recovery held-resync: rebuild, settle and the incomplete-WAL refusal.
// SPDX-License-Identifier: MIT
//
// Device evidence (22:03:13, Android 14): a cold boot replayed an appended
// record onto a materialized file that was already ahead of the manifest
// watermark, the replay stopped at a base mismatch, and the process could never
// close an epoch or recover again. These tests drive the real `WorkspaceLog`:
//
//   1. a complete WAL from mseq 1 rebuilds the materialized tree under
//      `wal.md` §7.2, so the stale copy-ahead window no longer becomes Failed;
//   2. an incomplete WAL (first_mseq != 1) stays Failed and names the path and
//      mseq instead of deleting the WAL or skipping the D9 check;
//   3. a genuinely torn base still holds a resync, and the Worker's complete
//      write settles it while the deferred WAL records are read back in order;
//   4. `resetForRecovery()` makes a second recovery in the same process real,
//      clearing the cached outcome and the sticky close failure.

package com.phonedsh.log

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResyncRecoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun copyAheadCoveredAppendSettlesWithoutARebuild() = runBlocking {
        val root = temporary.newFolder("copy-ahead").toPath()
        val paths = WalPaths(root)
        val first = newLog(root, RecordingDurability(root))
        assertTrue(first.recover() is RecoveryOutcome.Recovered)
        assertEquals(1L, first.appendMutation(writeMutation(F, "abcdefghij")).mseq)
        first.closeEpoch()
        // The live copy runs ahead of the manifest watermark: an appended
        // record, then a complete rewrite of the same path, with no close.
        assertEquals(2L, first.appendMutation(appendedMutation(F, 10, "klmnop")).mseq)
        assertEquals(3L, first.appendMutation(writeMutation(F, "zz")).mseq)
        assertEquals("zz", materializedText(paths, F))

        val sinkEvents = mutableListOf<WalLogEvent>()
        val reopened = newLog(
            root,
            RecordingDurability(root),
            bootId = BootId.random(),
            sink = WalLogSink { sinkEvents += it },
        )
        val outcome = reopened.recover()
        assertTrue("copy-ahead must recover: $outcome", outcome is RecoveryOutcome.Recovered)
        outcome as RecoveryOutcome.Recovered

        // The later complete write is the cover CCR-1 adds: mseq 2 is settled
        // rather than a base mismatch, so no resync and no rebuild is needed.
        assertEquals("the cover settles every retained record", emptyList<String>(), outcome.resyncRequired)
        assertEquals(3L, outcome.effectiveTail)
        assertEquals(3L, outcome.materializedUptoMseq)
        assertFalse("a covered append must not trigger the §7.2 rebuild", outcome.fullReconcile)
        assertTrue(
            "a settled-superseded record is not a resync diagnostic",
            sinkEvents.filterIsInstance<WalLogEvent.ResyncRequired>().isEmpty(),
        )
        assertEquals("zz", materializedText(paths, F))
        assertEquals(3L, reopened.closeEpoch().durableUpto)
    }

    @Test
    fun divergedCopyRebuildsFromWalWhenTheRetainedTailStartsAtOne() = runBlocking {
        val root = temporary.newFolder("diverged-copy").toPath()
        val paths = WalPaths(root)
        val first = newLog(root, RecordingDurability(root))
        assertTrue(first.recover() is RecoveryOutcome.Recovered)
        assertEquals(1L, first.appendMutation(writeMutation(F, "hello")).mseq)
        first.closeEpoch()
        assertEquals(2L, first.appendMutation(appendedMutation(F, 5, " world")).mseq)
        // The copy diverges from the WAL-derived bytes the way a copy that ran
        // ahead of an old manifest watermark does. The retained WAL starts at
        // mseq 1 with no hole, so §7.2 permits the from-empty rebuild.
        java.nio.file.Files.writeString(paths.materialized(F), "HELLO WORLD!")

        val reopened = newLog(root, RecordingDurability(root), bootId = BootId.random())
        val outcome = reopened.recover()
        assertTrue("a mseq-1 divergence must rebuild: $outcome", outcome is RecoveryOutcome.Recovered)
        outcome as RecoveryOutcome.Recovered
        assertEquals(emptyList<String>(), outcome.resyncRequired)
        assertTrue("the from-empty rebuild ran", outcome.fullReconcile)
        assertEquals(2L, outcome.materializedUptoMseq)
        assertEquals("hello world", materializedText(paths, F))
        assertEquals(2L, reopened.closeEpoch().durableUpto)
    }

    @Test
    fun incompleteWalKeepsFailedAndNamesPathAndMseq() = runBlocking {
        val root = temporary.newFolder("incomplete").toPath()
        val paths = WalPaths(root)
        val first = newLog(root, RecordingDurability(root))
        assertTrue(first.recover() is RecoveryOutcome.Recovered)
        first.appendMutation(writeMutation(F, "abcdefghij"))
        first.closeEpoch()
        first.appendMutation(appendedMutation(F, 10, "klmnop"))
        first.appendMutation(writeMutation(F, "zz"))
        // The cover settles the first append, but this later append depends on
        // the rewritten base and is not covered by anything retained.
        first.appendMutation(appendedMutation(F, 6, "!"))

        // Drop the epoch-1 segment. Its only mutation is at/below the manifest
        // watermark, so the listed-missing rule drops the manifest entry; the
        // retained WAL now starts at mseq 2 (no first_mseq == 1).
        val segments = segmentFiles(paths)
        val removed = segments.minByOrNull { it.fileName.toString() }!!
        assertTrue(Files.deleteIfExists(removed))
        val kept = segmentFiles(paths)
        assertEquals(1, kept.size)

        val reopened = newLog(root, RecordingDurability(root), bootId = BootId.random())
        val outcome = reopened.recover()
        assertTrue("an incomplete WAL must not rebuild: $outcome", outcome is RecoveryOutcome.Failed)
        outcome as RecoveryOutcome.Failed
        assertEquals("WAL_RESYNC_UNREPAIRED", outcome.code)
        assertTrue("the detail names the held path: ${outcome.detail}", outcome.detail.contains(F))
        assertTrue("the detail names the held mseq: ${outcome.detail}", outcome.detail.contains("4"))
        assertTrue("the retained WAL is never deleted", Files.exists(kept.single()))
        assertEquals("the materialized copy is left as it was", "zz", materializedText(paths, F))
    }

    @Test
    fun recoveryHeldResyncSettlesOnRepairAndAdvancesWatermark() = runBlocking {
        val root = temporary.newFolder("settle").toPath()
        val paths = WalPaths(root)
        val first = newLog(root, RecordingDurability(root))
        assertTrue(first.recover() is RecoveryOutcome.Recovered)
        first.appendMutation(writeMutation(F, "abc"))
        first.closeEpoch()
        // A genuinely torn base: the record claims 6 bytes of base but the
        // WAL-derived file is 3 bytes. A from-empty rebuild hits the same D9
        // mismatch, so this is not the copy-ahead case.
        val held = first.appendMutation(appendedMutation(F, 6, "ghi"))
        assertEquals(F, held.resync?.path)
        assertEquals(2L, held.resync?.mseq)

        val reopened = newLog(root, RecordingDurability(root), bootId = BootId.random())
        val outcome = reopened.recover()
        assertTrue("$outcome", outcome is RecoveryOutcome.Recovered)
        outcome as RecoveryOutcome.Recovered
        assertEquals(listOf(F), outcome.resyncRequired)
        assertEquals(2L, outcome.resyncRequests.single().mseq)
        assertEquals("the watermark stops below the mismatch", 1L, outcome.materializedUptoMseq)

        val repair = reopened.appendWorkerMutation(writeMutation(F, "abcdefghi"), 3L)
        assertTrue("$repair", repair is WorkerLaneAppend.Appended)
        repair as WorkerLaneAppend.Appended
        assertNull("the repair settles the held resync", repair.ack.resync)
        assertEquals(3L, repair.ack.materializedUptoMseq)
        assertEquals(3L, reopened.materializedUptoMseq)
        assertEquals("abcdefghi", materializedText(paths, F))
        assertEquals("the epoch now closes", 3L, reopened.closeEpoch().durableUpto)
    }

    @Test
    fun resetForRecoveryClearsCachedOutcomeAndStickyCloseFailure() = runBlocking {
        val root = temporary.newFolder("reset").toPath()
        val paths = WalPaths(root)
        val first = newLog(root, RecordingDurability(root))
        assertTrue(first.recover() is RecoveryOutcome.Recovered)
        first.appendMutation(writeMutation(F, "abc"))
        first.closeEpoch()
        first.appendMutation(appendedMutation(F, 6, "ghi"))

        val reopened = newLog(root, RecordingDurability(root), bootId = BootId.random())
        val firstOutcome = reopened.recover()
        assertTrue("$firstOutcome", firstOutcome is RecoveryOutcome.Recovered)
        assertNotNull(reopened.outstandingResync())

        val closeFailure = runCatching { reopened.closeEpoch() }.exceptionOrNull()
        assertTrue("$closeFailure", closeFailure is LogException)
        assertEquals(
            LogFailure.RESYNC_UNREPAIRED,
            (closeFailure as LogException).failure,
        )
        assertTrue(
            "the close failure detail names the path and mseq",
            closeFailure.detail.contains(F) && closeFailure.detail.contains("2"),
        )

        // Without this the second recover() would return the first outcome and
        // ensureReady() would keep throwing the sticky failure forever.
        reopened.resetForRecovery()
        val secondOutcome = reopened.recover()
        assertTrue("$secondOutcome", secondOutcome is RecoveryOutcome.Recovered)
        secondOutcome as RecoveryOutcome.Recovered
        assertEquals(listOf(F), secondOutcome.resyncRequired)
        assertEquals(2L, secondOutcome.resyncRequests.single().mseq)

        val repair = reopened.appendWorkerMutation(writeMutation(F, "abcdefghi"), 3L)
        assertTrue("$repair", repair is WorkerLaneAppend.Appended)
        assertNull((repair as WorkerLaneAppend.Appended).ack.resync)
        assertEquals(3L, reopened.closeEpoch().durableUpto)
    }

    private companion object {
        const val F = "/dsh/workspace/held.txt"
    }
}
