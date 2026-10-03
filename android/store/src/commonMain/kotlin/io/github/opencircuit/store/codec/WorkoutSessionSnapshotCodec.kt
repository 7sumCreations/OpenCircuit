package io.github.opencircuit.store.codec

import io.github.opencircuit.ringkit.WorkoutSessionSnapshot
import io.github.opencircuit.ringkit.WorkoutSportType
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored form of the live-workout snapshot:
 * `{"activeKcal":…,"avgHR":…,"hrSampleCount":…,"lastAliveAt":<ms>,"maxHR":…,"sport":"<raw>","startDate":<ms>}`,
 * absent values left out.
 *
 * Upstream's `WorkoutSessionSnapshot.encoded()` / `decoded(from:)` (`WorkoutSessionRecovery.swift:76`,
 * `:81`): a snapshot whose energy is NaN or infinite is not written, and a stored value this build
 * cannot read is no snapshot at all — never a half-read one. The sign of a -0 kcal energy is kept.
 * A reading count or heart rate past 32 bits makes the snapshot unreadable.
 */
object WorkoutSessionSnapshotCodec {

    /** The stored text, or null when the energy is NaN or infinite (upstream's `encoded()` is nil then). */
    fun encode(snapshot: WorkoutSessionSnapshot): String? {
        val kcal = snapshot.activeKcal
        val kcalJson = if (kcal == null) null else kcal.finiteJsonOrNull() ?: return null
        return jsonObjectOf(
            "activeKcal" to kcalJson,
            "avgHR" to snapshot.avgHR?.let(::JsonPrimitive),
            "hrSampleCount" to JsonPrimitive(snapshot.hrSampleCount),
            "lastAliveAt" to snapshot.lastAliveAt.json(),
            "maxHR" to snapshot.maxHR?.let(::JsonPrimitive),
            "sport" to JsonPrimitive(snapshot.sport.rawValue),
            "startDate" to snapshot.startDate.json(),
        ).toString()
    }

    fun decode(text: String): Decoded<WorkoutSessionSnapshot> = readStored(text) { root ->
        val o = root.obj()
        WorkoutSessionSnapshot(
            sport = o.required("sport").enumOf(WorkoutSportType.entries) { it.rawValue },
            startDate = o.required("startDate").instant(),
            lastAliveAt = o.required("lastAliveAt").instant(),
            hrSampleCount = o.required("hrSampleCount").int(),
            activeKcal = o.optional("activeKcal")?.double(),
            avgHR = o.optional("avgHR")?.int(),
            maxHR = o.optional("maxHR")?.int(),
        )
    }

    /** Upstream's `decoded(from: Data?)`: no stored text, or text this build cannot read, is no snapshot. */
    fun decodeOrNull(text: String?): WorkoutSessionSnapshot? = text?.let { decode(it).valueOrNull() }
}
