package io.github.opencircuit.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The step, energy and daily-record side of the writer, in memory, with the store's own rules
 * (`:store` `pendingStepSampleRows` / `markStepSamplesWritten` / `todaySteps` and the `store_kv`
 * marks): pending is every unwritten row, oldest first (ties in row order); each added row gets the
 * next row id; a day's total is the sum of the rows whose end falls on that local day in [zone].
 */
class InMemoryHealthActivity(private val zone: ZoneId) : HealthActivityStore {
    private val lock = Any()
    private val rows = mutableListOf<StepRow>()
    private val written = mutableSetOf<Long>()
    private val dayMarks = mutableMapOf<RecordType, LocalDate>()
    private val energy = mutableMapOf<LocalDate, Map<Instant, Double>>()
    private var nextId = 1L

    /** Adds a step row; returns its id. */
    fun addSteps(start: Instant, end: Instant, count: Long): Long = synchronized(lock) {
        val id = nextId++
        rows += StepRow(id, start, end, count)
        id
    }

    fun isWritten(id: Long): Boolean = synchronized(lock) { id in written }

    fun energyDays(): Set<LocalDate> = synchronized(lock) { energy.keys.toSet() }

    override suspend fun pendingSteps(): List<StepRow> = synchronized(lock) {
        rows.filter { it.id !in written }.sortedWith(compareBy({ it.start }, { it.id }))
    }

    override suspend fun steps(from: Instant, until: Instant): List<StepRow> = synchronized(lock) {
        rows.filter { !it.start.isBefore(from) && it.start.isBefore(until) }.sortedWith(compareBy({ it.start }, { it.id }))
    }

    override suspend fun markStepsWritten(ids: List<Long>): Unit = synchronized(lock) { written += ids }

    override suspend fun daySteps(day: LocalDate): Long = synchronized(lock) {
        rows.filter { it.end.atZone(zone).toLocalDate() == day }.sumOf { it.count }
    }

    override suspend fun dayMark(type: RecordType): LocalDate? = synchronized(lock) { dayMarks[type] }

    override suspend fun setDayMark(type: RecordType, day: LocalDate): Unit = synchronized(lock) { dayMarks[type] = day }

    override suspend fun energyWritten(day: LocalDate): Map<Instant, Double> = synchronized(lock) { energy[day].orEmpty() }

    override suspend fun setEnergyWritten(day: LocalDate, written: Map<Instant, Double>): Unit = synchronized(lock) {
        if (written.isEmpty()) energy.remove(day) else energy[day] = written.toMap()
    }

    override suspend fun forgetEnergyBefore(day: LocalDate): Unit = synchronized(lock) { energy.keys.removeAll { it.isBefore(day) } }
}
