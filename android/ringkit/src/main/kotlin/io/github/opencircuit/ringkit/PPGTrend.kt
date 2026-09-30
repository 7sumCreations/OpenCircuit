package io.github.opencircuit.ringkit

// Bulk PPG / optical-trend SAMPLE decode (../docs/PROTOCOL.md §5.2). Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/PPGTrend.swift:17-50 (@ b1c2fdd).
//
// `EpochRecord.parsePPGPage` already splits a `0x47` page into per-record timestamp + raw 38-byte
// payload; this decodes THAT payload into sample values. Settled offline upstream across 5
// captures: 10-bit big-endian samples, ONE smooth optical channel, and far too slow for a
// heartbeat — a sparse 15-min perfusion/optical-amplitude TREND, not a PPG waveform.
//
// Channel identity (which LED; AC vs DC) and physical units are unconfirmed, so this is
// DIAGNOSTIC ONLY — do NOT derive HR/HRV/SpO2 from it, and do NOT write it to Health Connect.

import java.time.Instant

object PPGTrend {

    /** Bits per sample (🟢 settled offline). */
    const val SAMPLE_BIT_WIDTH = 10

    /** Expected samples per 38-byte payload: 304 bits / 10 = 30, with 4 pad bits dropped. */
    const val EXPECTED_SAMPLES_PER_RECORD = 30

    /** One record's samples paired with its reconstructed timestamp (upstream's named tuple). */
    data class RecordSamples(val timestamp: Instant, val samples: List<Int>)

    /**
     * Unpack a `0x47` record's raw payload into consecutive 10-bit big-endian samples (a trailing
     * partial sample is dropped). DIAGNOSTIC ONLY — a relative optical trend, not a calibrated
     * reading. [rawPayload] is only read.
     */
    fun samples(rawPayload: ByteArray): List<Int> {
        val totalBits = rawPayload.size * 8
        val out = ArrayList<Int>(totalBits / SAMPLE_BIT_WIDTH)
        var i = 0
        while (i + SAMPLE_BIT_WIDTH <= totalBits) {
            var v = 0
            for (j in 0 until SAMPLE_BIT_WIDTH) {
                val bit = i + j
                v = (v shl 1) or ((rawPayload.u8(bit / 8) ushr (7 - bit % 8)) and 1)
            }
            out += v
            i += SAMPLE_BIT_WIDTH
        }
        return out
    }

    /** Decode every record's samples, paired with its already-reconstructed timestamp. */
    fun samples(records: List<EpochRecord.PPGRecord>): List<RecordSamples> =
        records.map { RecordSamples(it.timestamp, samples(it.rawPayload)) }
}
