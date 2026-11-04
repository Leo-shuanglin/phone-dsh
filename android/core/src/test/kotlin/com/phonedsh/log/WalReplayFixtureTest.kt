// The 14 shared WAL replay vectors drive the real WorkspaceLog recovery.
// SPDX-License-Identifier: MIT

package com.phonedsh.log

import com.phonedsh.agentcore.JsonBool
import com.phonedsh.agentcore.JsonNumber
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.JsonValue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The fixture is the oracle, the runner is not.
 *
 * Every case under `spec/v1/golden/wal/replay/` is loaded from disk, its
 * `copy`, `materialized_upto_mseq` and `records` are turned into a real
 * materialized tree and a real segment, and [WorkspaceLog.recover] runs. The
 * test then asserts the replay's own decisions (through the internal trace) and
 * the observable result: per-record verdict, cover mseq, declared
 * `watermark_steps`, final watermark, tree, Failed outcome and
 * `rebuilt_from_wal`.
 *
 * The expected number of vectors is fixed: a fixture that disappears or a new
 * one that is not replayed fails here, not only in the Python checker.
 */
class WalReplayFixtureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun everySharedVectorRunsThroughRealRecovery() = runBlocking {
        val directory = replayDirectory()
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" }
            ?: error("cannot list ${directory.absolutePath}")
        assertEquals(
            "the shared replay directory must hold exactly the 14 normative vectors",
            14,
            files.size,
        )
        val seen = HashSet<String>()
        for (file in files.sortedBy { it.name }) {
            val fixture = JsonObject(
                JsonValue.parse(file.readText(Charsets.UTF_8)).asObject(file.name).members,
            )
            val id = fixture.str("id")
            assertTrue("duplicate fixture id $id", seen.add(id))
            replayOne(id, fixture)
        }
        assertEquals(
            "every shared vector must run",
            EXPECTED_IDS,
            seen,
        )
    }

    private suspend fun replayOne(id: String, fixture: JsonObject) {
        val root = temporary.newFolder(id).toPath()
        val paths = WalPaths(root)
        val inputWatermark = fixture.long("materialized_upto_mseq")
        writeCopy(paths, fixture.obj("copy").members)
        val records = fixture.arr("records").map { parseRecord(it.asObject("$id record")) }
        writeWal(paths, records, inputWatermark)

        val trace = RecordingReplayTrace()
        val log = newLog(root, RecordingDurability(root), bootId = TEST_BOOT_ID)
        log.replayTrace = trace
        val outcome = log.recover()

        val expected = fixture.obj("expected")
        val rebuilt = expected.optBool("rebuilt_from_wal") ?: false
        val selectedPass = if (rebuilt) ReplayPass.REBUILD else ReplayPass.COPY
        val pass = trace.pass(selectedPass)
        assertNotNull("$id: no $selectedPass replay trace", pass)
        pass!!

        val resultWatermark = when (outcome) {
            is RecoveryOutcome.Recovered -> outcome.materializedUptoMseq
            is RecoveryOutcome.Failed -> pass.watermark
        }
        assertEquals(
            "$id: final materialized_upto_mseq",
            expected.long("materialized_upto_mseq"),
            resultWatermark,
        )
        assertTreesEqual("$id: final tree", expectedTree(expected.obj("tree")), readTree(paths))

        when (outcome) {
            is RecoveryOutcome.Recovered -> {
                assertEquals(
                    "$id: rebuilt_from_wal",
                    rebuilt,
                    outcome.fullReconcile,
                )
                val failed = outcome.resyncRequired.isNotEmpty()
                assertEquals("$id: failed", expected.boolean("failed"), failed)
                if (!rebuilt) {
                    assertEquals(
                        "$id: the copy replay's failed flag",
                        expected.boolean("failed"),
                        pass.resync.isNotEmpty(),
                    )
                }
            }

            is RecoveryOutcome.Failed -> {
                assertTrue("$id: expected Failed, got ${outcome.code}", expected.boolean("failed"))
                assertFalse("$id: a refused recovery is not a rebuild", rebuilt)
                assertTrue(
                    "$id: the refusal names the held path: ${outcome.detail}",
                    expected.boolean("failed"),
                )
            }
        }

        assertVerdicts(id, expected, selectedPass, pass, inputWatermark)
        expected.opt("copy_replay")?.let { copyReplay ->
            val copyPass = trace.pass(ReplayPass.COPY)
            assertNotNull("$id: no copy replay trace", copyPass)
            val obj = copyReplay.asObject("$id copy_replay")
            assertEquals(
                "$id: copy_replay watermark",
                obj.long("materialized_upto_mseq"),
                copyPass!!.watermark,
            )
            assertEquals(
                "$id: copy_replay failed",
                obj.boolean("failed"),
                copyPass.resync.isNotEmpty(),
            )
            assertVerdicts(id, obj, ReplayPass.COPY, copyPass, inputWatermark)
        }
    }

    private fun assertVerdicts(
        id: String,
        expected: JsonObject,
        passKind: ReplayPass,
        pass: RecordedPass,
        inputWatermark: Long,
    ) {
        val expectedRecords = expected.arr("records")
        assertEquals(
            "$id: one declared verdict per retained record",
            expectedRecords.size,
            pass.records.size,
        )
        expectedRecords.forEachIndexed { index, value ->
            val declared = value.asObject("$id records[$index]")
            val mseq = declared.long("mseq")
            val actual = pass.records[mseq]
            assertNotNull("$id: no decision recorded for mseq $mseq ($passKind)", actual)
            actual!!
            assertEquals("$id: mseq order in the trace", mseq, actual.mseq)
            val verdict = declared.str("verdict")
            if (mseq > inputWatermark && verdict == "settled-superseded") {
                // The trace starts a settled record as held/applied and upgrades
                // it when the cover is applied; the final value is what counts.
                assertEquals("$id: mseq $mseq verdict", "settled-superseded", actual.verdict.wireName)
                assertEquals(
                    "$id: mseq $mseq cover mseq",
                    declared.long("superseded_by_mseq"),
                    actual.coverMseq,
                )
                assertTrue(
                    "$id: mseq $mseq cover kind ${declared.str("cover")} vs ${actual.cover}",
                    coverMatches(declared.str("cover"), actual.cover),
                )
            } else {
                assertEquals("$id: mseq $mseq verdict", verdict, actual.verdict.wireName)
            }
        }
        expected.opt("watermark_steps")?.let { steps ->
            for (stepValue in steps.asArray("$id watermark_steps")) {
                val step = stepValue.asObject("$id watermark step")
                val mseq = step.long("mseq")
                val recorded = pass.steps[mseq]
                assertNotNull("$id: no per-step watermark for mseq $mseq", recorded)
                assertEquals(
                    "$id: watermark after mseq $mseq",
                    step.long("materialized_upto_mseq"),
                    recorded,
                )
            }
        }
    }

    private fun coverMatches(declared: String, actual: CoverKind?): Boolean = when (declared) {
        "complete-write", "truncate", "repair-write" -> actual == CoverKind.COMPLETE_WRITE
        "remove" -> actual == CoverKind.REMOVE
        "ancestor-remove" -> actual == CoverKind.ANCESTOR_REMOVE
        else -> false
    }

    private data class ParsedRecord(
        val mseq: Long,
        val op: Int,
        val path: String,
        val appended: Boolean,
        val appendFrom: Long,
        val data: ByteArray,
    )

    private fun parseRecord(obj: JsonObject): ParsedRecord {
        val op = obj.long("op").toInt()
        val appended = obj.boolean("appended")
        val data = obj.opt("data")?.let { content(it.asObject("record data")) } ?: ByteArray(0)
        return ParsedRecord(
            mseq = obj.long("mseq"),
            op = op,
            path = obj.str("path"),
            appended = appended,
            appendFrom = obj.optLong("append_from") ?: 0L,
            data = data,
        )
    }

    private fun writeWal(paths: WalPaths, records: List<ParsedRecord>, inputWatermark: Long) {
        require(records.isNotEmpty()) { "a replay vector has at least one record" }
        val firstMseq = records.first().mseq
        for (pair in records.zipWithNext()) {
            assertEquals("fixture records are contiguous", pair.first.mseq + 1, pair.second.mseq)
        }
        val name = WalSegmentName(1, 0)
        val frames = records.map { record ->
            val body = when (record.op) {
                1 -> MutationBody(
                    op = VfsOp.WRITE,
                    mode = 420,
                    path = record.path,
                    data = record.data,
                    appended = record.appended,
                    appendFrom = record.appendFrom,
                    entryChanged = !record.appended,
                )
                3 -> MutationBody(op = VfsOp.REMOVE, mode = 0, path = record.path)
                else -> error("replay vectors admit write and remove only")
            }
            WalRecordCodec.encodeMutation(record.mseq, body)
        }
        Files.createDirectories(paths.walDirectory)
        Files.write(
            paths.segment(name),
            WalSegmentHeader(firstMseq, TEST_BOOT_ID).encode() + frames.fold(ByteArray(0)) { acc, f -> acc + f },
        )
        val manifest = Manifest(
            lastMseq = records.last().mseq,
            segments = listOf(ManifestSegment.of(name, firstMseq, records.last().mseq)),
            lastDurableEpoch = 0,
            bootId = TEST_BOOT_ID,
            materializedUptoMseq = inputWatermark,
        )
        Files.write(paths.manifestFile, manifest.encode())
    }

    private fun writeCopy(paths: WalPaths, copy: Map<String, JsonValue>) {
        for ((path, value) in copy) {
            val target = paths.materialized(path)
            target.parent?.let { Files.createDirectories(it) }
            Files.write(target, content(value.asObject("copy $path")))
        }
    }

    private fun content(obj: JsonObject): ByteArray = when {
        obj.opt("content_utf8") != null -> obj.str("content_utf8").toByteArray(Charsets.UTF_8)
        obj.opt("content_hex") != null -> hex(obj.str("content_hex"))
        else -> error("content is neither content_utf8 nor content_hex")
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex content has an odd length" }
        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun assertTreesEqual(context: String, expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals("$context paths", expected.keys.sorted(), actual.keys.sorted())
        for ((path, bytes) in expected) {
            assertTrue(
                "$context $path content",
                bytes.contentEquals(actual.getValue(path)),
            )
        }
    }

    private fun expectedTree(obj: JsonObject): Map<String, ByteArray> =
        obj.members.mapValues { (_, value) ->
            content(value.asObject("tree member"))
        }

    private fun readTree(paths: WalPaths): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        for ((root, prefix) in listOf(
            paths.materializedHome to "/dsh/home",
            paths.materializedWorkspace to "/dsh/workspace",
        )) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val relative = root.relativize(file).joinToString("/")
                    result["$prefix/$relative"] = Files.readAllBytes(file)
                }
            }
        }
        return result
    }

    private fun replayDirectory(): File {
        val root = System.getProperty("pdsh.repo.root")
            ?: error("pdsh.repo.root is not set; run the WAL suite through Gradle")
        return File(root, "spec/v1/golden/wal/replay")
    }

    /** The internal trace recorder; it observes, it does not replay. */
    private class RecordedPass(val startWatermark: Long) {
        data class Record(
            val mseq: Long,
            var verdict: ReplayVerdict,
            var coverMseq: Long? = null,
            var cover: CoverKind? = null,
        )

        val records = LinkedHashMap<Long, Record>()
        val steps = LinkedHashMap<Long, Long>()
        var watermark: Long = startWatermark
        var resync: List<ResyncRequest> = emptyList()
    }

    private class RecordingReplayTrace : WalReplayTrace {
        private val passes = LinkedHashMap<ReplayPass, RecordedPass>()

        fun pass(kind: ReplayPass): RecordedPass? = passes[kind]

        override fun passStarted(pass: ReplayPass, startWatermark: Long) {
            passes[pass] = RecordedPass(startWatermark)
        }

        override fun alreadyMaterialized(mseq: Long) {
            current().records[mseq] = RecordedPass.Record(mseq, ReplayVerdict.ALREADY_MATERIALIZED)
        }

        override fun decided(mseq: Long, verdict: ReplayVerdict) {
            current().records[mseq] = RecordedPass.Record(mseq, verdict)
        }

        override fun settled(mseq: Long, coverMseq: Long, cover: CoverKind) {
            val record = current().records.getOrPut(mseq) {
                RecordedPass.Record(mseq, ReplayVerdict.SETTLED)
            }
            record.verdict = ReplayVerdict.SETTLED
            record.coverMseq = coverMseq
            record.cover = cover
        }

        override fun watermarkAfter(mseq: Long, watermark: Long) {
            current().watermark = watermark
            current().steps[mseq] = watermark
        }

        override fun passFinished(pass: ReplayPass, watermark: Long, resync: List<ResyncRequest>) {
            current().watermark = watermark
            current().resync = resync
        }

        private fun current(): RecordedPass = passes.values.lastOrNull()
            ?: error("a trace event arrived before passStarted")
    }

    private companion object {
        val EXPECTED_IDS: Set<String> = setOf(
            "append-already-applied-skips",
            "append-ancestor-remove-settles",
            "append-full-write-settles",
            "append-only-stays-held",
            "append-remove-settles",
            "append-truncate-settles",
            "cover-before-append-stays-held",
            "cover-other-path-stays-held",
            "dependent-append-not-covered-stays-held",
            "intervening-append-covered-settles",
            "live-repair-write-settles",
            "recovery-rebuilds-from-wal",
            "retained-not-from-one-stays-failed",
            "settled-watermark-waits-for-cover",
        )
    }
}

// Minimal typed accessors over the shared fixture dialect.
private fun JsonValue.asObject(context: String): JsonObject =
    this as? JsonObject ?: error("$context is not an object")

private fun JsonValue.asArray(context: String): List<JsonValue> =
    (this as? com.phonedsh.agentcore.JsonArray)?.values ?: error("$context is not an array")

private fun JsonObject.opt(name: String): JsonValue? = members[name]

private fun JsonObject.obj(name: String): JsonObject =
    members[name] as? JsonObject ?: error("missing object member $name")

private fun JsonObject.arr(name: String): List<JsonValue> =
    (members[name] as? com.phonedsh.agentcore.JsonArray)?.values
        ?: error("missing array member $name")

private fun JsonObject.str(name: String): String =
    (members[name] as? JsonString)?.value ?: error("missing string member $name")

private fun JsonObject.long(name: String): Long =
    (members[name] as? JsonNumber)?.asLongOrNull() ?: error("missing integer member $name")

private fun JsonObject.optLong(name: String): Long? =
    (members[name] as? JsonNumber)?.asLongOrNull()

private fun JsonObject.boolean(name: String): Boolean =
    (members[name] as? JsonBool)?.value ?: error("missing boolean member $name")

private fun JsonObject.optBool(name: String): Boolean? = (members[name] as? JsonBool)?.value
