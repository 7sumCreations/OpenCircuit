package io.github.opencircuit.app.today

// Port of upstream ios/OpenCircuit/Dashboard/DashboardSectionOrder.swift (@ 2d07e550), whole.
//
// Port notes:
//  • Upstream's Today has eight sections (readiness, metrics, vitalsStatus, calories, goals, cycle,
//    headache, sync); this Today has four of them, keeping upstream's ids and their relative
//    canonical order. Every other upstream id decodes as an id this build does not have: dropped in
//    place, as upstream drops its retired `vitals`.
//  • `reordered` takes one moved index (upstream: an `IndexSet` from a drag, here one up / down
//    step or a drag) with Swift's `move(fromOffsets:toOffset:)` meaning of [to]: the position in the
//    list BEFORE the move (so moving to the end passes the list's size).

/**
 * The reorderable Today sections. [rawValue] is the id written to the saved order, so it stays
 * stable across releases; declaration order is the default (first-run) layout.
 */
enum class DashboardSection(val rawValue: String) {
    READINESS("readiness"),
    METRICS("metrics"),
    CALORIES("calories"),
    GOALS("goals"),
}

/** The saved Today layout: the saved order's string ⇄ `List<DashboardSection>`. */
object DashboardSectionOrder {

    /**
     * The full canonical-or-saved order. Decodes the stored string, dropping unknown and duplicate
     * ids, then appends any section not yet present (a new feature) in its canonical order — so a
     * saved order keeps working across updates that add or retire cards. The metric tiles, when
     * missing, go straight under readiness rather than at the bottom (upstream's rule).
     */
    fun decode(raw: String): List<DashboardSection> {
        val result = mutableListOf<DashboardSection>()
        for (id in raw.split(",")) {
            val section = DashboardSection.entries.firstOrNull { it.rawValue == id } ?: continue
            if (section !in result) result += section
        }
        for (section in DashboardSection.entries) {
            if (section in result) continue
            val readiness = result.indexOf(DashboardSection.READINESS)
            if (section == DashboardSection.METRICS && readiness >= 0) result.add(readiness + 1, section) else result += section
        }
        return result.toList()
    }

    /** The string the saved order is written as. Only live ids are ever written, so a retired id cannot survive a reorder. */
    fun encode(sections: List<DashboardSection>): String = sections.joinToString(",") { it.rawValue }

    /**
     * Applies one move in [visible]'s index space — the section at [from] to position [to] counted
     * before the move — then merges every section of [full] not in [visible] back at its prior
     * absolute position (so a section shown again later returns roughly where it was).
     */
    fun reordered(full: List<DashboardSection>, visible: List<DashboardSection>, from: Int, to: Int): List<DashboardSection> {
        require(from in visible.indices && to in 0..visible.size) { "a move inside the visible sections" }
        val moved = visible.toMutableList()
        val section = moved.removeAt(from)
        moved.add(if (to > from) to - 1 else to, section)
        val merged = moved.toMutableList()
        for (s in full) {
            if (s in moved) continue
            merged.add(minOf(full.indexOf(s), merged.size), s)
        }
        return merged.toList()
    }
}
