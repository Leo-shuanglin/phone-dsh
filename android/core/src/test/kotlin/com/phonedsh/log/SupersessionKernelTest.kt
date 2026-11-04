// The JNI projection of the Rust retained-sequence supersession predicate.
// SPDX-License-Identifier: MIT

package com.phonedsh.log

import com.phonedsh.agentcore.JsonArray
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.JsonValue
import com.phonedsh.wire.NativeWal
import com.phonedsh.wire.WalException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupersessionKernelTest {
    @Test
    fun aLaterCompleteWriteNamesTheCoverMseq() {
        val reply = NativeWal.retainedSupersession(
            """[
              {"mseq":1,"op":1,"appended":true,"path":"/dsh/workspace/notes.txt"},
              {"mseq":2,"op":1,"appended":false,"path":"/dsh/workspace/notes.txt"}
            ]""",
        )
        val verdict = JsonValue.parse(reply).asArray().first()
        assertEquals("settled-superseded", verdict.str("verdict"))
        assertEquals(2L, verdict.long("superseded_by_mseq"))
        assertEquals("complete-write", verdict.str("cover"))
    }

    @Test
    fun anAncestorRemoveIsItsOwnCoverKind() {
        val reply = NativeWal.retainedSupersession(
            """[
              {"mseq":1,"op":1,"appended":true,"path":"/dsh/workspace/dir/a.txt"},
              {"mseq":2,"op":3,"appended":false,"path":"/dsh/workspace/dir"}
            ]""",
        )
        val verdict = JsonValue.parse(reply).asArray().first()
        assertEquals("settled-superseded", verdict.str("verdict"))
        assertEquals("ancestor-remove", verdict.str("cover"))
    }

    @Test
    fun aCoverOfAnotherPathDoesNotSettleTheAppend() {
        val reply = NativeWal.retainedSupersession(
            """[
              {"mseq":1,"op":1,"appended":true,"path":"/dsh/workspace/notes.txt"},
              {"mseq":2,"op":1,"appended":false,"path":"/dsh/workspace/other.txt"}
            ]""",
        )
        val verdict = JsonValue.parse(reply).asArray().first()
        assertEquals("not-superseded", verdict.str("verdict"))
    }

    @Test
    fun theBaseCheckEntryPointIsStillTheThreeWayDecision() {
        // One byte already present: the retained-sequence verdict does not
        // replace `appended_replay`, it is checked before it.
        val reply = JsonValue.parse(
            NativeWal.appendedReplay(1, 0, byteArrayOf(65), byteArrayOf(65)),
        ) as JsonObject
        assertEquals("skip", reply.str("verdict"))
    }

    @Test
    fun aNonAscendingSequenceIsInputInvalid() {
        val failure = assertThrows(WalException::class.java) {
            NativeWal.retainedSupersession(
                """[
                  {"mseq":2,"op":1,"appended":true,"path":"/dsh/workspace/a"},
                  {"mseq":1,"op":1,"appended":false,"path":"/dsh/workspace/a"}
                ]""",
            )
        }
        assertEquals("WAL_INPUT_INVALID", failure.code)
    }

    private fun JsonValue.asArray(): List<JsonValue> = (this as JsonArray).values

    private fun JsonValue.str(name: String): String =
        ((this as JsonObject).members[name] as JsonString).value

    private fun JsonValue.long(name: String): Long =
        (this as JsonObject).members[name]!!.let { (it as com.phonedsh.agentcore.JsonNumber).asLongOrNull()!! }
}
