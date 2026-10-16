package io.github.opencircuit.health

import io.github.opencircuit.ringkit.BulkRecord
import io.github.opencircuit.ringkit.BulkSleep
import io.github.opencircuit.ringkit.NightStager
import io.github.opencircuit.ringkit.SleepSegment
import io.github.opencircuit.ringkit.TemperatureSample
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The kept seven-night backlog (`ringkit/src/test/resources/backlog-7-nights/backlog.txt`, guarded
 * byte for byte in `:ringkit` by `BacklogSevenNightsFixtureTest`), read on the raw byte path and
 * staged by the same `:ringkit` calls the app's history commit makes: each night's staged hypnogram
 * ([NightStager.stage], what the store keeps as the night's timeline) and its coarse pair
 * ([BulkSleep.sleepSegments]).
 */
object SevenNights {
    class Night(val staged: List<SleepSegment>, val coarse: List<SleepSegment>)

    val zone: ZoneId
    val nights: List<Night>

    init {
        val root = checkNotNull(System.getProperty("opencircuit.androidRoot")) { "the test task names the source tree" }
        val file = File(root, "ringkit/src/test/resources/backlog-7-nights/backlog.txt")
        check(file.isFile) { "the kept backlog fixture is missing: ${file.absolutePath}" }
        var zoneId = ""
        val ranges = mutableListOf<LongRange>()
        val records = mutableListOf<BulkRecord>()
        val temps = mutableListOf<TemperatureSample>()
        for (line in file.readLines(Charsets.UTF_8)) {
            if (line.startsWith("#")) continue
            val f = line.split(' ')
            when (f[0]) {
                "zone" -> zoneId = f[1]
                "night" -> ranges += f[3].toLong(16)..f[4].toLong(16)
                "r" -> records += checkNotNull(BulkRecord.of(hex(f[1]))) { line }
                "t" -> temps += TemperatureSample(Instant.ofEpochSecond(f[1].toLong()), java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(f[2], 16)))
                else -> error("unexpected line: $line")
            }
        }
        check(records.size == 3654) { "the backlog holds 3,654 records, read ${records.size}" }
        zone = ZoneId.of(zoneId)
        nights = ranges.map { r ->
            val night = records.filter { it.counter in r }
            Night(NightStager.stage(night, records, zone, temps), BulkSleep.sleepSegments(night, temperatures = temps))
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
