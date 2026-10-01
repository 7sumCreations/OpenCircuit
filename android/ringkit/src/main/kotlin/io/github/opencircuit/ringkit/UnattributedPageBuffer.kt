package io.github.opencircuit.ringkit

// Retention buffer for `0x4c` history pages that arrive with NO drain open. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/UnattributedPageBuffer.swift:25-64 (@ b1c2fdd).
//
// WHY THIS EXISTS. The BLE layer ACKs every `0x4c` page unconditionally — the ack (`cc 00 00`)
// is what makes the ring send the next page — and that same ack advances the ring's single resume
// pointer. So an acked page is GONE from the ring whether or not we kept it. Retention that
// depends on app state while the ack does not is a silent, permanent data-loss bug. Upstream
// measured it on two testers' rings on 2026-08-04 (a Gen 2 and a Gen 2 Air): whole nights of
// records acked outside a drain and dropped.
//
// The contract in one line: **if we ACK a page, we KEEP it.**
//
// OWNERSHIP. Upstream is a Swift `mutating struct` (every assignment copies). Here it is a mutable
// class owned by one BLE session: sharing the reference shares the buffer. Take [copy] for an
// independent snapshot. [records] hands out a fresh list on every read.

class UnattributedPageBuffer(val cap: Int = DEFAULT_CAP) {

    companion object {
        /**
         * Leak bound only — reaching it means "bank NOW", never "drop". 4 000 records ≈ 92 KB,
         * well past the 30 h the [EpochArchive] retains anyway.
         */
        const val DEFAULT_CAP = 4_000
    }

    private val held = ArrayList<BulkRecord>()

    /** The retained records, oldest page first. A fresh list on every read. */
    val records: List<BulkRecord> get() = held.toList()

    /**
     * How many pages contributed to the current buffer (diagnostics — distinguishes "one big
     * handoff we missed" from "a trickle of stragglers").
     */
    var pages: Int = 0
        private set

    val isEmpty: Boolean get() = held.isEmpty()
    val count: Int get() = held.size

    /**
     * Retain one page's records. Returns true when the buffer has reached [cap] and the caller
     * must bank immediately instead of waiting out its debounce. An empty page is a no-op and
     * never trips the cap.
     */
    fun retain(incoming: List<BulkRecord>): Boolean {
        if (incoming.isEmpty()) return false
        held += incoming
        pages += 1
        return held.size >= cap
    }

    /**
     * Take everything and reset. The caller is responsible for durably banking the result — this
     * type never drops records on its own.
     */
    fun drain(): List<BulkRecord> {
        val out = held.toList()
        held.clear()
        pages = 0
        return out
    }

    /** An independent copy: retaining into or draining either buffer never changes the other. */
    fun copy(): UnattributedPageBuffer {
        val c = UnattributedPageBuffer(cap)
        c.held += held
        c.pages = pages
        return c
    }

    override fun equals(other: Any?): Boolean =
        other is UnattributedPageBuffer && cap == other.cap && pages == other.pages && held == other.held

    override fun hashCode(): Int = (31 * (31 * cap + pages)) + held.hashCode()

    override fun toString(): String = "UnattributedPageBuffer(cap=$cap, pages=$pages, count=${held.size})"
}
