// PC-20261104: live D9 supersession and the conservative watermark.
// SPDX-License-Identifier: MIT
//
// CCR-1's fourth verdict is not replay-only. A base mismatch that a later
// complete write/remove covers before the Worker's repair arrives is settled by
// that cover: no Failed, no repair needed, and the watermark may pass the pair
// only when the cover's mseq is inside the same contiguous prefix. These tests
// drive the real WorkspaceLog write path and the real Rust predicate.

package com.phonedsh.log

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveSupersessionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun aLaterCompleteWriteSettlesTheHeldAppendWithoutARepair() = runBlocking {
        val root = temporary.newFolder("live-full-write").toPath()
        val paths = WalPaths(root)
        val log = newLog(root, RecordingDurability(root))
        assertTrue(log.recover() is RecoveryOutcome.Recovered)

        assertNull(log.appendMutation(writeMutation(F, "abc")).resync)
        val held = log.appendMutation(appendedMutation(F, 10, "xyz"))
        assertEquals(F, held.resync?.path)
        assertEquals(2L, held.resync?.mseq)
        assertEquals("the watermark stops below the mismatch", 1L, log.materializedUptoMseq)

        val cover = log.appendMutation(writeMutation(F, "worker-fix"))
        assertNull("the cover settles the held append", cover.resync)
        assertEquals(3L, cover.materializedUptoMseq)
        assertEquals("worker-fix", materializedText(paths, F))
        assertNull(log.outstandingResync())
        assertEquals(3L, log.closeEpoch().durableUpto)
    }

    @Test
    fun anAncestorRemoveSettlesTheDescendantAppend() = runBlocking {
        val root = temporary.newFolder("live-ancestor-remove").toPath()
        val paths = WalPaths(root)
        val log = newLog(root, RecordingDurability(root))
        assertTrue(log.recover() is RecoveryOutcome.Recovered)

        val file = "/dsh/workspace/dir/a.txt"
        assertNull(log.appendMutation(writeMutation(file, "HELLO")).resync)
        assertEquals(file, log.appendMutation(appendedMutation(file, 6, " world")).resync?.path)
        assertEquals(1L, log.materializedUptoMseq)

        val remove = log.appendMutation(removeMutation("/dsh/workspace/dir"))
        assertNull("the ancestor remove is the cover", remove.resync)
        assertEquals(3L, remove.materializedUptoMseq)
        assertNull(materializedText(paths, file))
        assertNull(log.outstandingResync())
        assertEquals(3L, log.closeEpoch().durableUpto)
    }

    @Test
    fun theWatermarkStaysBelowASettledRecordUntilItsCoverIsApplied() = runBlocking {
        val root = temporary.newFolder("live-conservative-watermark").toPath()
        val paths = WalPaths(root)
        val log = newLog(root, RecordingDurability(root))
        assertTrue(log.recover() is RecoveryOutcome.Recovered)
        val a = "/dsh/workspace/a.txt"
        val b = "/dsh/workspace/b.txt"

        assertEquals(1L, log.appendMutation(writeMutation(a, "HELLO")).mseq)
        val held = log.appendMutation(appendedMutation(a, 6, " world"))
        assertEquals(2L, held.resync?.mseq)
        assertEquals(
            "another path may still be applied",
            3L,
            log.appendMutation(writeMutation(b, "B")).mseq,
        )
        assertEquals("the held/settled pair blocks the prefix", 1L, log.materializedUptoMseq)

        val cover = log.appendMutation(writeMutation(a, "fixed"))
        assertNull(cover.resync)
        assertEquals(
            "the settled record and its cover pass together when the cover is applied",
            4L,
            cover.materializedUptoMseq,
        )
        assertEquals("fixed", materializedText(paths, a))
        assertEquals("B", materializedText(paths, b))
        assertEquals(4L, log.closeEpoch().durableUpto)
    }

    @Test
    fun aRecoveryHeldAppendIsSettledByATruncatingCompleteWrite() = runBlocking {
        val root = temporary.newFolder("recovery-held-truncate").toPath()
        val paths = WalPaths(root)
        val first = newLog(root, RecordingDurability(root))
        assertTrue(first.recover() is RecoveryOutcome.Recovered)
        assertEquals(1L, first.appendMutation(writeMutation(F, "abc")).mseq)
        first.closeEpoch()
        assertEquals(2L, first.appendMutation(appendedMutation(F, 6, "ghi")).mseq)

        val reopened = newLog(root, RecordingDurability(root), bootId = BootId.random())
        val outcome = reopened.recover()
        assertTrue("$outcome", outcome is RecoveryOutcome.Recovered)
        outcome as RecoveryOutcome.Recovered
        assertEquals(listOf(F), outcome.resyncRequired)
        assertEquals(1L, outcome.materializedUptoMseq)

        // The Worker's next write is a complete write ("z"), not the exact
        // repair bytes. It covers the held append; the deferred record must not
        // be applied on top of the new base.
        val ack = reopened.appendWorkerMutation(writeMutation(F, "z"), 3L)
        assertTrue("$ack", ack is WorkerLaneAppend.Appended)
        ack as WorkerLaneAppend.Appended
        assertNull("the complete write settles the held append", ack.ack.resync)
        assertEquals(3L, ack.ack.materializedUptoMseq)
        assertEquals("z", materializedText(paths, F))
        assertNull(reopened.outstandingResync())
        assertEquals(3L, reopened.closeEpoch().durableUpto)
    }

    private companion object {
        const val F = "/dsh/workspace/held.txt"
    }
}
