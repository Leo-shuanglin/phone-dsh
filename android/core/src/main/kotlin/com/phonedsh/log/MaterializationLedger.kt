// D9 supersession, settlement and the conservative contiguous watermark.
// SPDX-License-Identifier: MIT

package com.phonedsh.log

import com.phonedsh.agentcore.JsonArray
import com.phonedsh.agentcore.JsonBool
import com.phonedsh.agentcore.JsonNumber
import com.phonedsh.agentcore.JsonObject
import com.phonedsh.agentcore.JsonString
import com.phonedsh.agentcore.toJsonString
import com.phonedsh.wire.NativeWal

/**
 * The five D9 decisions a replay can record (`wal.md` §6.2).
 *
 * [SETTLED] and [HELD] are the two outcomes the conservative watermark must
 * tell apart: a settled record counts only together with its cover, while a
 * held record stops the prefix and is the §5.1 failure.
 */
internal enum class ReplayVerdict(val wireName: String) {
    /** The record was applied during this replay. */
    APPLIED("applied"),

    /** The bytes are already present (D9 skip). */
    SKIPPED("skipped-idempotent"),

    /** A later retained record covers the append; it was not applied. */
    SETTLED("settled-superseded"),

    /** The base did not match and no later retained record covers it. */
    HELD("mismatch-held"),

    /** The mseq is at or below the input manifest watermark. */
    ALREADY_MATERIALIZED("already-materialized"),
}

/** The cover categories of `wal.md` §6.2, as the Rust predicate reports them. */
internal enum class CoverKind(val wireName: String) {
    /** `op = 1`, not appended, same path (a truncating/repair write too). */
    COMPLETE_WRITE("complete-write"),

    /** `op = 3`, same path. */
    REMOVE("remove"),

    /** `op = 3`, proper ancestor of the path. */
    ANCESTOR_REMOVE("ancestor-remove"),
    ;

    companion object {
        /** The cover for a native label, or `null` for an unknown future label. */
        fun fromWire(label: String): CoverKind? = entries.firstOrNull { it.wireName == label }
    }
}

/** Which pass a trace belongs to. */
internal enum class ReplayPass {
    /** Replay of the given materialized copy. */
    COPY,

    /** The `wal.md` §7.2 rebuild from the retained WAL. */
    REBUILD,
}

/**
 * A test-only observation surface for the real replay.
 *
 * Production installs [NONE]. The shared vector runner installs a recorder to
 * assert each record's verdict, cover mseq and per-step watermark without a
 * second replay implementation.
 */
internal interface WalReplayTrace {
    /** A pass started at [startWatermark]. */
    fun passStarted(pass: ReplayPass, startWatermark: Long) = Unit

    /** One record at or below the input watermark. */
    fun alreadyMaterialized(mseq: Long) = Unit

    /** A record was decided as [verdict] (applied/skipped/held). */
    fun decided(mseq: Long, verdict: ReplayVerdict) = Unit

    /** A previously decided appended record is now settled by [coverMseq]. */
    fun settled(mseq: Long, coverMseq: Long, cover: CoverKind) = Unit

    /** The watermark after [mseq] was decided. */
    fun watermarkAfter(mseq: Long, watermark: Long) = Unit

    /** The pass ended with [watermark] and these outstanding repairs. */
    fun passFinished(pass: ReplayPass, watermark: Long, resync: List<ResyncRequest>) = Unit

    companion object {
        /** Discards every observation. */
        val NONE: WalReplayTrace = object : WalReplayTrace {}
    }
}

/**
 * The shared D9 settlement state of replay and live materialization.
 *
 * Records are fed in ascending `mseq` order. The ledger decides supersession
 * **before** the D9 base check by asking the Rust kernel
 * ([NativeWal.retainedSupersession]); it applies only records that are not
 * settled. Its watermark is the conservative contiguous one of `wal.md` §6.2:
 * a settled append is passed only when its covering record's mseq is inside the
 * same prefix, and a held append stops the prefix without by itself becoming
 * `Failed`.
 *
 * @param materializer the real §6.1 applier. Replay points it at the staging
 *   tree on a rebuild; live writes point it at `materialized/`.
 * @param trace a test-only observation surface; [WalReplayTrace.NONE] in
 *   production.
 * @param startWatermark the manifest watermark this pass starts from.
 */
internal class MaterializationLedger(
    private val materializer: Materializer,
    private val trace: WalReplayTrace = WalReplayTrace.NONE,
    startWatermark: Long,
) {
    /** The conservative contiguous materialized watermark. */
    var watermark: Long = startWatermark
        private set

    /** The pass's outstanding §5.1 repairs, one per held path, in mseq order. */
    val resyncRequests: List<ResyncRequest>
        get() = resolvedResync.entries
            .sortedBy { it.value }
            .map { ResyncRequest(it.key, it.value) }

    private sealed interface Resolution {
        /** Applied or idempotently skipped; no cover is needed. */
        data object Passable : Resolution

        /** A base mismatch with no covering record yet. */
        data object Held : Resolution

        /** Covered by a later retained record that has since been applied. */
        data class Settled(val coverMseq: Long) : Resolution
    }

    private val resolved = HashMap<Long, Resolution>()

    /**
     * Appended mseqs per path since that path's last covering record. All of
     * them are covered by the same later complete write/remove, so one native
     * query per path is enough. Entries are removed once the watermark passes
     * them or a cover settles them.
     */
    private val unsettledAppends = LinkedHashMap<String, ArrayList<Long>>()
    private val heldPaths = HashSet<String>()
    private val resolvedResync = LinkedHashMap<String, Long>()
    private var lastFed: Long = startWatermark

    /** The first path still waiting for a repair, or `null`. */
    fun firstResync(): ResyncRequest? = resolvedResync.entries
        .minByOrNull { it.value }
        ?.let { ResyncRequest(it.key, it.value) }

    /** Feeds one mutation in mseq order. */
    fun feed(record: WalRecord.Mutation) {
        if (record.mseq <= watermark) {
            trace.alreadyMaterialized(record.mseq)
            trace.watermarkAfter(record.mseq, watermark)
            return
        }
        if (record.mseq <= lastFed) return
        lastFed = record.mseq
        val body = record.body

        // §6.2: supersession is decided before the base check.
        if (isCovering(body)) {
            settleCoveredBy(record)
        }

        if (body.op == VfsOp.WRITE && body.appended) {
            if (body.path in heldPaths) {
                hold(record)
            } else {
                apply(record)
            }
        } else {
            apply(record)
        }
        advance()
        trace.watermarkAfter(record.mseq, watermark)
    }

    /** Records an input-watermark record for a trace; no state change. */
    fun alreadyMaterialized(mseq: Long) {
        trace.alreadyMaterialized(mseq)
    }

    /** Finishes the pass: one final advance, then the observed repair set. */
    fun finish(): Long {
        advance()
        return watermark
    }

    private fun apply(record: WalRecord.Mutation) {
        when (val outcome = materializer.apply(record.body)) {
            is MaterializeOutcome.Applied -> {
                resolved[record.mseq] = Resolution.Passable
                trace.decided(record.mseq, ReplayVerdict.APPLIED)
                rememberAppend(record)
            }

            is MaterializeOutcome.Skipped -> {
                resolved[record.mseq] = Resolution.Passable
                trace.decided(record.mseq, ReplayVerdict.SKIPPED)
                rememberAppend(record)
            }

            is MaterializeOutcome.Resync -> {
                if (record.body.op == VfsOp.WRITE && record.body.appended) {
                    hold(record)
                } else {
                    // Only an appended write can return Resync today; a complete
                    // record that did is a host defect the materializer reports
                    // as an I/O failure instead, never as a silent hold.
                    throw IllegalStateException(
                        "a complete ${record.body.op} record at ${record.body.path} " +
                            "reported a base mismatch",
                    )
                }
            }
        }
    }

    private fun rememberAppend(record: WalRecord.Mutation) {
        if (record.body.op == VfsOp.WRITE && record.body.appended) {
            unsettledAppends.getOrPut(record.body.path) { ArrayList() }.add(record.mseq)
        }
    }

    private fun hold(record: WalRecord.Mutation) {
        resolved[record.mseq] = Resolution.Held
        trace.decided(record.mseq, ReplayVerdict.HELD)
        rememberAppend(record)
        heldPaths.add(record.body.path)
        resolvedResync.putIfAbsent(record.body.path, record.mseq)
    }

    /**
     * Settles the appended records a later complete write/remove covers.
     *
     * The Rust kernel is the one authority. One query per covered path carries
     * that path's earliest unresolved append and the candidate cover; a cover
     * covers every earlier record of the path, so the whole list shares the
     * returned cover mseq.
     */
    private fun settleCoveredBy(cover: WalRecord.Mutation) {
        val body = cover.body
        if (heldPaths.isEmpty()) return
        val candidatePaths = if (body.op == VfsOp.REMOVE) {
            unsettledAppends.keys.filter { path ->
                path == body.path || path.startsWith(body.path + "/")
            }
        } else {
            listOf(body.path)
        }
        for (path in candidatePaths) {
            val mseqs = unsettledAppends[path] ?: continue
            if (mseqs.isEmpty()) continue
            val supersession = querySupersession(mseqs.first(), path, cover) ?: continue
            for (mseq in mseqs) {
                if (mseq >= cover.mseq) continue
                val previous = resolved[mseq]
                if (previous != null) {
                    resolved[mseq] = Resolution.Settled(supersession.coverMseq)
                }
                trace.settled(mseq, supersession.coverMseq, supersession.cover)
            }
            unsettledAppends.remove(path)
            heldPaths.remove(path)
            resolvedResync.remove(path)
        }
    }

    private data class NativeSupersession(val coverMseq: Long, val cover: CoverKind)

    private fun querySupersession(
        heldMseq: Long,
        heldPath: String,
        cover: WalRecord.Mutation,
    ): NativeSupersession? {
        val records = JsonArray(
            listOf(
                JsonObject(
                    linkedMapOf(
                        "mseq" to JsonNumber(heldMseq.toString()),
                        "op" to JsonNumber(VfsOp.WRITE.code.toString()),
                        "appended" to JsonBool(true),
                        "path" to JsonString(heldPath),
                    ),
                ),
                JsonObject(
                    linkedMapOf(
                        "mseq" to JsonNumber(cover.mseq.toString()),
                        "op" to JsonNumber(cover.body.op.code.toString()),
                        "appended" to JsonBool(false),
                        "path" to JsonString(cover.body.path),
                    ),
                ),
            ),
        )
        val reply = WalJson.parse(NativeWal.retainedSupersession(records.toJsonString()))
        // One verdict per input record; the held append is the first entry.
        val verdict = WalJson.objectOf(
            WalJson.arrayOf(reply, "D9 supersession reply").first(),
            "D9 supersession verdict",
        )
        if (WalJson.string(verdict, "verdict", "D9 supersession verdict") != "settled-superseded") {
            return null
        }
        val coverMseq = WalJson.long(verdict, "superseded_by_mseq", "D9 supersession verdict")
        val kind = CoverKind.fromWire(WalJson.string(verdict, "cover", "D9 supersession verdict"))
            ?: return null
        return NativeSupersession(coverMseq, kind)
    }

    /**
     * Advances the conservative watermark.
     *
     * The next record may be passed when it is applied/skipped, or when it is
     * settled and its cover's mseq can be included in the same prefix. A held
     * record or an unresolved gap stops the prefix. The jump may reveal another
     * settled record whose cover extends the target; the loop follows it.
     */
    private fun advance() {
        while (true) {
            val next = watermark + 1
            when (val resolution = resolved[next] ?: return) {
                Resolution.Passable -> {
                    resolved.remove(next)
                    watermark = next
                    pruneUnsettledAppends()
                }

                Resolution.Held -> return

                is Resolution.Settled -> {
                    val target = closeOver(next, resolution.coverMseq) ?: return
                    var mseq = next
                    while (mseq <= target) {
                        resolved.remove(mseq)
                        mseq += 1
                    }
                    watermark = target
                    pruneUnsettledAppends()
                }
            }
        }
    }

    /**
     * The smallest legal candidate at or above [initialTarget] that includes
     * every cover of the settled records in the same prefix, or `null` when a
     * held/unresolved record is inside it.
     */
    private fun closeOver(start: Long, initialTarget: Long): Long? {
        var target = initialTarget
        var mseq = start
        while (mseq <= target) {
            when (val resolution = resolved[mseq]) {
                null, Resolution.Held -> return null

                Resolution.Passable -> Unit

                is Resolution.Settled -> if (resolution.coverMseq > target) {
                    target = resolution.coverMseq
                }
            }
            mseq += 1
        }
        return target
    }

    /**
     * Forgets appended records the watermark has passed.
     *
     * A future cover cannot move a watermark backwards, and a record the prefix
     * already passed is applied/skipped in the manifest; keeping every mseq of a
     * long append-only tail on the heap is the recovery budget failure
     * `budgets.md` §4 forbids. A record that a later cover would have settled is
     * therefore only settled while it is still above the watermark, which is
     * exactly the conservative window the manifest rule cares about.
     */
    private fun pruneUnsettledAppends() {
        if (unsettledAppends.isEmpty()) return
        val iterator = unsettledAppends.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll { it <= watermark }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    /** Whether [body] can be the later retained record of §6.2. */
    private fun isCovering(body: MutationBody): Boolean =
        (body.op == VfsOp.WRITE && !body.appended) || body.op == VfsOp.REMOVE
}
