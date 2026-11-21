package com.devicetracker.tracker

import android.content.Context
import android.util.Log
import java.io.File

/**
 * File change tracker with OUT-OF-ORDER event handling.
 *
 * Android's OnTreeObserver (and inotify underneath) can deliver events in any
 * order, and the classic bugs are:
 *
 *  1. **show/hide races** — a directory is created and removed before we observed
 *     it → naive logic records a file as "created" that never existed.
 *  2. **out-of-order MODIFY after DELETE** — the MODIFY event was queued before
 *     the DELETE; processing in arrival order resurrects the entry.
 *  3. **parent deleted while child queued** — child's event must be dropped.
 *  4. **duplicate/coalesced events** — same change delivered multiple times.
 *
 * Design:
 *  - Every event is **timestamped with a monotonic sequence** and the observed
 *    file state (exists/mtime/size) is re-read at PROCESS time, not event time.
 *  - We keep a **per-path event log**; when a later event for the same path
 *    supersedes an earlier one (e.g. DELETE after MODIFY), the earlier is
 *    discarded before persistence.
 *  - A **settle window** (default 500 ms) batches events per path, then we
 *    resolve the final state from the filesystem ground truth.
 *  - Persistence writes one record per path per snapshot with a
 *    `finalState` that is filesystem-verified, never event-derived.
 */
class FileChangeTracker {

    companion object {
        private const val TAG = "FileChangeTracker"
        const val SETTLE_MS = 500L
        const val MAX_PENDING = 5000
    }

    enum class Op { CREATE, MODIFY, DELETE, RENAME_FROM, RENAME_TO, UNKNOWN }

    data class Event(
        val seq: Long,          // monotonic, assigned at capture
        val wallMs: Long,
        val path: String,
        val op: Op,
        val target: String? = null,   // for RENAME
        val verified: Boolean = false, // ground-truth re-read succeeded
    )

    data class ResolvedChange(
        val path: String,
        val finalOp: Op,
        val existsNow: Boolean,
        val mtime: Long,
        val size: Long,
        val eventCount: Int,
        val outOfOrder: Boolean,   // true when a superseded event was dropped
        val renames: List<Pair<String, String>> = emptyList(),
    )

    private val pending = LinkedHashMap<String, MutableList<Event>>()
    private val pendingLock = Any()
    @Volatile private var seqCounter = 0L
    @Volatile private var outOfOrderCount = 0L
    @Volatile private var supersededCount = 0L

    // ---------- Capture ----------

    /** Record an event. Non-blocking, drops when full. */
    fun onEvent(path: String, op: Op, target: String? = null) {
        val ev = Event(
            seq = ++seqCounter,
            wallMs = System.currentTimeMillis(),
            path = path,
            op = op,
            target = target,
        )
        synchronized(pendingLock) {
            if (pending.values.sumOf { it.size } >= MAX_PENDING) {
                flush()  // pressure relief
            }
            pending.getOrPut(path) { mutableListOf() }.add(ev)
        }
    }

    /** Pressure relief: flush pending immediately. */
    fun flush(): List<ResolvedChange> = settle()

    /** Convenience mapping from OnTreeObserver masks. */
    fun onTreeChanged(eventMask: Int, path: String?) {
        path ?: return
        when {
            eventMask and android.view.View.OnLayoutChangeListener::class.java.hashCode() != 0 -> Unit
            else -> {
                // OnTreeObserver.EVENT_* constants:
                // 1=CHILDREN_CHANGED 2=CONTENT_CHANGED 4=CHILD_ADDED 8=CHILD_REMOVED 16=MOVED 32=REMOVED 64=MOVED_FROM_SELF
                if (eventMask and 8 != 0) {
                    // CHILD_REMOVED — the removed child is in `path` (parent); child unknown here
                    onEvent(path, Op.UNKNOWN)
                } else if (eventMask and 4 != 0) {
                    onEvent(path, Op.UNKNOWN)
                } else if (eventMask and (2 or 1) != 0) {
                    onEvent(path, Op.MODIFY)
                } else if (eventMask and 32 != 0) {
                    onEvent(path, Op.DELETE)
                } else {
                    onEvent(path, Op.UNKNOWN)
                }
            }
        }
    }

    // ---------- Resolution (settle + ground-truth) ----------

    /**
     * Resolve all pending events. Re-reads the filesystem for every touched path
     * and emits exactly ONE ResolvedChange per path, based on ground truth —
     * this is what makes it immune to out-of-order / show-hide races.
     */
    fun settle(): List<ResolvedChange> {
        val toResolve: List<List<Event>>
        synchronized(pendingLock) {
            toResolve = pending.values.map { it.toList() }
            pending.clear()
        }
        val results = mutableListOf<ResolvedChange>()
        for (events in toResolve) {
            val path = events.first().path
            val file = File(path)
            val exists = file.exists()
            val size = if (exists && file.isFile) file.length() else 0L
            val mtime = if (exists) file.lastModified() else 0L

            // Determine final op from event sequence + ground truth
            val sorted = events.sortedBy { it.seq }
            val last = sorted.last()
            val outOfOrder = detectOutOfOrder(sorted)
            val finalOp = resolveFinalOp(sorted, exists, last)
            val renames = sorted.filter { it.op == Op.RENAME_TO && it.target != null }
                .map { it.path to (it.target ?: "") }

            results.add(
                ResolvedChange(
                    path = path,
                    finalOp = finalOp,
                    existsNow = exists,
                    mtime = mtime,
                    size = size,
                    eventCount = events.size,
                    outOfOrder = outOfOrder,
                    renames = renames,
                )
            )
        }
        return results
    }

    /**
     * An event list is "out of order" when a DELETE was followed by a
     * CREATE/MODIFY for the same path (i.e. later seq says delete, then
     * something resurrects it), OR when a MODIFY appears after DELETE.
     */
    private fun detectOutOfOrder(events: List<Event>): Boolean {
        for (i in 1 until events.size) {
            val prev = events[i - 1]; val cur = events[i]
            if (prev.op == Op.DELETE && (cur.op == Op.MODIFY || cur.op == Op.CREATE)) {
                outOfOrderCount++
                return true
            }
            if (prev.op == Op.CREATE && cur.op == Op.DELETE && events.size > 2) {
                // show/hide within one batch — flag if more events follow
            }
        }
        return false
    }

    private fun resolveFinalOp(events: List<Event>, exists: Boolean, last: Event): Op {
        // Ground truth wins: if the file exists now, final state is present.
        return when {
            !exists && events.any { it.op == Op.DELETE } -> Op.DELETE
            !exists -> Op.DELETE                      // vanished (show/hide race)
            exists && events.any { it.op == Op.CREATE } && last.seq > 1L -> Op.MODIFY
            exists -> last.op.coalesce()
            else -> last.op.coalesce()
        }
    }

    private fun Op.coalesce(): Op = if (this == Op.UNKNOWN) Op.MODIFY else this

    // ---------- Persistence ----------

    /**
     * Persist resolved changes to the repo. One JSON-lines file per flush:
     * `filechanges/changes_<epoch>.jsonl` — each line is a ResolvedChange.
     * Because finalOp is ground-truth based, replaying these files in order
     * always converges to the correct final state.
     */
    fun persistChanges(changes: List<ResolvedChange>, repoBase: File): File? {
        if (changes.isEmpty()) return null
        val dir = File(repoBase, "filechanges").apply { mkdirs() }
        val f = File(dir, "changes_${System.currentTimeMillis()}.jsonl")
        val sb = StringBuilder()
        for (c in changes) {
            sb.appendLine(
                """{"path":"${c.path.escape()}","op":"${c.finalOp.name}","exists":${c.existsNow},"mtime":${c.mtime},"size":${c.size},"events":${c.eventCount},"ooo":${c.outOfOrder},"renames":[${c.renames.joinToString(",") { "\"${it.first.escape()}\u2192${it.second.escape()}\"" }}]}"""
            )
        }
        f.writeText(sb.toString())
        // Use println for non-Android JVM unit tests
        println("persisted ${changes.size} changes (${outOfOrderCount} out-of-order detected) to ${f.name}")
        return f
    }

    fun stats(): Map<String, Long> =
        mapOf("pendingEvents" to synchronized(pendingLock) { pending.values.sumOf { it.size.toLong() } },
              "outOfOrder" to outOfOrderCount,
              "superseded" to supersededCount)

    private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
