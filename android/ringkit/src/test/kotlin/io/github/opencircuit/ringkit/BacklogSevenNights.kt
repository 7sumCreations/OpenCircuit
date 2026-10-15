package io.github.opencircuit.ringkit

import java.time.Instant
import java.time.ZoneId

/**
 * The kept seven-night backlog (`backlog-7-nights/backlog.txt`): seven synthetic nights of
 * `sleep-differential/inputs.txt`, stitched into one counter-continuous run with idle records
 * between them. Guarded byte for byte against its source by [BacklogSevenNightsFixtureTest].
 */
internal object BacklogSevenNights {

    /** A night of the backlog: its source id and the counters of its first and last record. */
    data class Night(val id: String, val first: Long, val last: Long)

    class Backlog(
        val zone: ZoneId,
        val nights: List<Night>,
        val records: List<BulkRecord>,
        val temps: List<TemperatureSample>,
    ) {
        /** The records of night [index] (0 = oldest) alone. */
        fun nightRecords(index: Int): List<BulkRecord> = nights[index].let { n -> records.filter { it.counter in n.first..n.last } }
    }

    fun load(): Backlog {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("backlog-7-nights/backlog.txt"))
        val lines = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.filter { !it.startsWith("#") }
        var zone = ""
        val nights = mutableListOf<Night>()
        val records = mutableListOf<BulkRecord>()
        val temps = mutableListOf<TemperatureSample>()
        for (line in lines) {
            val f = line.split(' ')
            when (f[0]) {
                "zone" -> zone = f[1]
                "night" -> nights += Night(f[2], f[3].toLong(16), f[4].toLong(16))
                "r" -> records += checkNotNull(BulkRecord.of(hex(f[1]))) { line }
                "t" -> temps += TemperatureSample(Instant.ofEpochSecond(f[1].toLong()), SleepDifferentialFixtures.bitsToDouble(f[2]))
                else -> error("unexpected line: $line")
            }
        }
        return Backlog(ZoneId.of(zone), nights, records, temps)
    }
}
