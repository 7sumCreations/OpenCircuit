package io.github.opencircuit.ringkit

// SPLIT A NIGHT INTO WHAT WE MEASURED AND WHAT THE USER TOLD US — and decide which numbers we are
// entitled to publish. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/SleepProvenanceBreakdown.swift (@ b1c2fdd).
//
// This is the enforcement point for clause 3 of the provenance rule ([SleepProvenance]):
// asserted-UNMEASURED time never enters a derived number. `SleepStaging.Summary` stays
// provenance-BLIND — it is the DISPLAY headline, because an assertion wins for display. Everything a
// third party could mistake for a measurement is computed here instead.
//
// WHAT COUNTS AS MEASURED. Measured and asserted-over-measured both do (`hasMeasurement`): the second is
// a user LABEL on real recorded ground, so it belongs in numerator and denominator. The one narrower
// field is [SleepProvenanceBreakdown.measuredAwake] — measured ONLY, because it is read as "the ring said
// awake"; the wearer's own awake paint over recorded ground has its own bucket. The asleep side keeps
// its totals (they feed efficiency and scorability) and names the relabelled part as an informational
// SUBSET instead.
//
// THREE BUCKETS. Asserted is a claim over ground we can PROVE holds no records, and it is the only thing
// excluded from a derived number. Ground our retained records cannot reach is coverage-UNKNOWN and lands
// in its own bucket: counted in the display total, published unlabelled, never quoted as either
// measurement or hole. Collapsing it into asserted is how retention got read as absence upstream.
//
// "EXCLUDED FROM A DERIVED NUMBER" IS NOT "KEPT OUT OF THE HEALTH STORE": asserted sleep reaches the
// store tagged as user-entered ([SleepHealthPublication], at the bottom of this file).
//
// NOTHING HERE FIRES ON AN UNEDITED NIGHT: staging emits only measured segments, so its breakdown is
// all-measured, coverage is 1 and every number is published as before.
//
// Shape notes: totals are `Double` seconds summed in input order, exactly as upstream's `TimeInterval`
// arithmetic (so no total can overflow), each segment's seconds the difference of its two dates' doubles
// as Swift's `duration` computes it — the export prints these totals with 17 significant digits.
// Whole minutes are 64-bit; a total whose rounded minute count leaves 64 bits — about 10^13 years of
// segments — saturates there, where upstream traps.

import java.util.Collections

/** Measured-versus-asserted totals for one night, plus the publish/withhold verdicts. */
class SleepProvenanceBreakdown(segments: List<SleepSegment>, private val tuning: Tuning = Tuning.DEFAULT) {

    /**
     * Thresholds for withholding a derived number. Provisional and deliberately round.
     *
     * ⚠️ THESE ARE NOT FITTED, AND MUST NOT BE QUOTED AS IF THEY WERE: round values that behave
     * correctly on the two device-proven nights and are inert on a fully covered one.
     */
    data class Tuning(
        /**
         * Least covered in-bed time (seconds) over which an efficiency RATIO is worth publishing; below
         * this the number says more about the gap than the sleep.
         */
        val minCoveredInBedForEfficiency: Double = 3.0 * 3600,
        /**
         * Coverage at or above which a single summary verdict (the sleep score) may be assembled — its
         * dominant factor is time asleep, precisely what an uncovered night cannot answer.
         */
        val minCoverageForScore: Double = 0.75,
        /** `false` restores pre-provenance behaviour: nothing is ever withheld. The kill switch for this layer. */
        val withholdingEnabled: Boolean = true,
    ) {
        companion object {
            val DEFAULT = Tuning()

            /** Publish everything, exactly as before provenance existed. */
            val NEVER_WITHHOLD = Tuning(withholdingEnabled = false)
        }
    }

    // MARK: totals (seconds)

    /** In-bed span the user is claiming, measured or not — the honest "time in bed". */
    val totalInBed: Double

    /** The part of [totalInBed] the ring actually recorded across. */
    val coveredInBed: Double

    /** In-bed time our retained records cannot speak about — neither covered nor a proven hole. */
    val unknownInBed: Double

    /** Asleep (core + deep + REM) over ground the ring recorded across, whoever chose the label. */
    val measuredAsleep: Double

    /**
     * The part of [measuredAsleep] the wearer's edit labelled asleep over recorded ground. ⚠️ A SUBSET,
     * NOT A FOURTH BUCKET: adding it to the others double-counts.
     */
    val assertedOverMeasuredAsleep: Double

    /** Asleep the user asserted over ground we can PROVE holds no records. */
    val assertedAsleep: Double

    /** Asleep asserted over ground our retained records cannot speak about at all. */
    val unknownAsleep: Double

    /** Awake the RING's own staging called awake — measured ONLY (narrower than `hasMeasurement` on purpose). */
    val measuredAwake: Double

    /** Awake the WEARER labelled over recorded ground: `measuredAwake + assertedOverMeasuredAwake` is the old value. */
    val assertedOverMeasuredAwake: Double

    /** Awake the user asserted over ground we can PROVE holds no records. */
    val assertedAwake: Double

    /** Awake over ground our retained records cannot speak about. */
    val unknownAwake: Double

    /** Per-stage seconds over covered ground only. Unmeasured time has no defensible stage. */
    val measuredLight: Double
    val measuredDeep: Double
    val measuredREM: Double

    /**
     * Per-stage seconds the wearer asserted over ground we can PROVE holds no records — the same
     * predicate as [hasAssertedTime], so a surface that renders these and one that decides whether to
     * say anything at all can never disagree. Coverage-unknown ground is deliberately not included.
     */
    val assertedLight: Double
    val assertedDeep: Double
    val assertedREM: Double

    /** Longest single PROVEN unmeasured run inside the in-bed window (stage and in-bed views merged). */
    val longestUnmeasuredGap: Double

    init {
        fun sum(predicate: (SleepSegment) -> Boolean): Double =
            segments.filter(predicate).fold(0.0) { acc, s -> acc + swiftMax(0.0, dateSecondsBetween(s.start, s.end)) }
        val asleepStages = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)

        // In-bed: the in-bed layer when there is one (a stitched night carries one per fragment, and the
        // gaps between must not count); the staged total otherwise, so stage-only input has a denominator.
        if (segments.none { it.stage == SleepStage.IN_BED }) {
            totalInBed = sum { it.stage != SleepStage.IN_BED }
            coveredInBed = sum { it.stage != SleepStage.IN_BED && it.provenance.hasMeasurement }
            unknownInBed = sum { it.stage != SleepStage.IN_BED && it.provenance.isCoverageUnknown }
        } else {
            totalInBed = sum { it.stage == SleepStage.IN_BED }
            coveredInBed = sum { it.stage == SleepStage.IN_BED && it.provenance.hasMeasurement }
            unknownInBed = sum { it.stage == SleepStage.IN_BED && it.provenance.isCoverageUnknown }
        }

        measuredAsleep = sum { it.stage in asleepStages && it.provenance.hasMeasurement }
        assertedOverMeasuredAsleep = sum { it.stage in asleepStages && it.provenance == SleepProvenance.ASSERTED_OVER_MEASURED }
        assertedAsleep = sum { it.stage in asleepStages && it.provenance.isProvenUnmeasured }
        unknownAsleep = sum { it.stage in asleepStages && it.provenance.isCoverageUnknown }
        // MEASURED exactly — the one asymmetry; the relabelled part moves one line down, it is not dropped.
        measuredAwake = sum { it.stage == SleepStage.AWAKE && it.provenance == SleepProvenance.MEASURED }
        assertedOverMeasuredAwake = sum { it.stage == SleepStage.AWAKE && it.provenance == SleepProvenance.ASSERTED_OVER_MEASURED }
        assertedAwake = sum { it.stage == SleepStage.AWAKE && it.provenance.isProvenUnmeasured }
        unknownAwake = sum { it.stage == SleepStage.AWAKE && it.provenance.isCoverageUnknown }
        measuredLight = sum { it.stage == SleepStage.ASLEEP_CORE && it.provenance.hasMeasurement }
        measuredDeep = sum { it.stage == SleepStage.ASLEEP_DEEP && it.provenance.hasMeasurement }
        measuredREM = sum { it.stage == SleepStage.ASLEEP_REM && it.provenance.hasMeasurement }
        assertedLight = sum { it.stage == SleepStage.ASLEEP_CORE && it.provenance.isProvenUnmeasured }
        assertedDeep = sum { it.stage == SleepStage.ASLEEP_DEEP && it.provenance.isProvenUnmeasured }
        assertedREM = sum { it.stage == SleepStage.ASLEEP_REM && it.provenance.isProvenUnmeasured }

        // Longest PROVEN unmeasured run: merge the asserted spans (the in-bed and stage layers overlap,
        // so a naive max over segments would report the shorter of two views of one hole). Unknown
        // ground is excluded — a gap we cannot vouch for is never "the ring recorded nothing".
        val assertedSpans = segments
            .filter { it.provenance.isProvenUnmeasured && it.end.isAfter(it.start) }
            .map { DateInterval(it.start, it.end) }
        longestUnmeasuredGap = MeasuredCoverage(assertedSpans).intervals.maxOfOrNull { dateSecondsBetween(it.start, it.end) } ?: 0.0
    }

    // MARK: verdicts

    /**
     * Share of the in-bed window the ring recorded across, 0…1 (0 with no in-bed time). Unknown ground is
     * NOT in the numerator: read [unknownAsleep] / [unknownInBed] before quoting this about the device.
     */
    val coverageFraction: Double get() = if (totalInBed > 0) coveredInBed / totalInBed else 0.0

    /** The headline the CARD shows: everything, however we came by it (clause 1). */
    val displayedAsleep: Double get() = measuredAsleep + assertedAsleep + unknownAsleep

    /** Four terms, because [measuredAwake] no longer carries the relabelled part. */
    val displayedAwake: Double get() = measuredAwake + assertedOverMeasuredAwake + assertedAwake + unknownAwake

    /**
     * True when any displayed sleep is a claim over ground we can prove holds no records. Deliberately
     * NOT true for unknown ground — that would caveat every night older than the archive.
     */
    val hasAssertedTime: Boolean get() = assertedAsleep > 0 || assertedAwake > 0

    /**
     * Sleep efficiency over COVERED GROUND ONLY — null when there is not enough covered in-bed time for
     * the ratio to mean anything.
     *
     * ⚠️ NULL MEANS WITHHELD AND MUST BE RENDERED AS "—". **Never persist 0 for it**: upstream's store
     * reads a stored zero as "reconstruct in-bed some other way", a live sentinel for every reader.
     */
    val efficiency: Double?
        get() {
            if (!(coveredInBed > 0)) return null
            if (tuning.withholdingEnabled && coveredInBed < tuning.minCoveredInBedForEfficiency) return null
            return measuredAsleep / coveredInBed
        }

    /** Whether a single summary verdict (the sleep score) can be honestly assembled for this night. */
    val isScorable: Boolean
        get() {
            if (!tuning.withholdingEnabled) return true
            if (!(totalInBed > 0)) return false
            return coverageFraction >= tuning.minCoverageForScore
        }

    /**
     * In-bed time we can PROVE holds no records — [totalInBed] less the covered part AND less the part
     * our retained records cannot speak about. The only quantity entitled to be called "holds no ring data".
     */
    val provenUnmeasuredInBed: Double get() = swiftMax(0.0, totalInBed - coveredInBed - unknownInBed)

    /**
     * A one-line reason a number was withheld, for the export and the diagnostics bundle; null when
     * nothing is withheld. ⚠️ IT COUNTS ONLY PROVEN GROUND — folding in the unknown bucket would state
     * as fact that the ring recorded nothing across minutes we simply no longer hold records for.
     */
    val withheldReason: String?
        get() {
            if (!(tuning.withholdingEnabled && hasAssertedTime)) return null
            val mins = wholeMinutes(provenUnmeasuredInBed)
            return "$mins min of this night's ${wholeMinutes(totalInBed)} min in-bed window holds no ring data"
        }

    /** Whole minutes (half away from zero), for cards, exports and the store. [efficiency] stays a `Double?` on purpose. */
    data class Minutes(
        val inBed: Long,
        val coveredInBed: Long,
        val measuredAsleep: Long,
        val assertedAsleep: Long,
        val measuredAwake: Long,
        val assertedOverMeasuredAwake: Long,
        val assertedAwake: Long,
        val light: Long,
        val deep: Long,
        val rem: Long,
    )

    val minutes: Minutes
        get() = Minutes(
            wholeMinutes(totalInBed), wholeMinutes(coveredInBed),
            wholeMinutes(measuredAsleep), wholeMinutes(assertedAsleep),
            wholeMinutes(measuredAwake), wholeMinutes(assertedOverMeasuredAwake), wholeMinutes(assertedAwake),
            wholeMinutes(measuredLight), wholeMinutes(measuredDeep), wholeMinutes(measuredREM),
        )

    /** Value equality over every stored total and the tuning, as upstream's synthesized `Equatable`. */
    override fun equals(other: Any?): Boolean = other is SleepProvenanceBreakdown && stored() == other.stored() && tuning == other.tuning

    override fun hashCode(): Int = 31 * stored().hashCode() + tuning.hashCode()

    override fun toString(): String =
        "SleepProvenanceBreakdown(totalInBed=$totalInBed, coveredInBed=$coveredInBed, unknownInBed=$unknownInBed, " +
            "measuredAsleep=$measuredAsleep, assertedAsleep=$assertedAsleep, unknownAsleep=$unknownAsleep, " +
            "measuredAwake=$measuredAwake, assertedAwake=$assertedAwake, longestUnmeasuredGap=$longestUnmeasuredGap, tuning=$tuning)"

    private fun stored(): List<Double> = listOf(
        totalInBed, coveredInBed, unknownInBed,
        measuredAsleep, assertedOverMeasuredAsleep, assertedAsleep, unknownAsleep,
        measuredAwake, assertedOverMeasuredAwake, assertedAwake, unknownAwake,
        measuredLight, measuredDeep, measuredREM, assertedLight, assertedDeep, assertedREM,
        longestUnmeasuredGap,
    )

    private companion object {
        /** Swift `Int((t / 60).rounded())`, saturating at 64 bits where Swift traps. Totals are never NaN. */
        fun wholeMinutes(seconds: Double): Long = roundHalfAwayFromZero(seconds / 60).toLong()
    }
}

/**
 * One night's segments, split by what the health store must be told about each of them.
 *
 * Three buckets, and the third is kept EMPTY on purpose. [withheld] is what this app declines to write
 * at all; today's rule withholds nothing, but its one consumer is a DELETE-EXCLUSION predicate, and a
 * rule that ever starts withholding again must not also start deleting across the ground it withheld.
 * Deriving that predicate's input from this partition keeps the two answers married.
 *
 * Every list is copied in and read-only out (upstream's arrays are values).
 */
class SleepHealthPublication(
    measured: List<SleepSegment>,
    userEntered: List<SleepSegment>,
    withheld: List<SleepSegment>,
    published: List<SleepSegment>,
) {
    /** Written as ordinary samples: the ring measured this ground, or we cannot prove it did not. */
    val measured: List<SleepSegment> = readOnlyCopy(measured)

    /** Written marked as user-entered — the wearer's own account of ground we can PROVE holds no records. */
    val userEntered: List<SleepSegment> = readOnlyCopy(userEntered)

    /** Not written at all. Empty under today's rule. */
    val withheld: List<SleepSegment> = readOnlyCopy(withheld)

    /** Everything that reaches the store, in the caller's original order. */
    val published: List<SleepSegment> = readOnlyCopy(published)

    override fun equals(other: Any?): Boolean =
        other is SleepHealthPublication && measured == other.measured && userEntered == other.userEntered &&
            withheld == other.withheld && published == other.published

    override fun hashCode(): Int = listOf(measured, userEntered, withheld, published).hashCode()

    override fun toString(): String =
        "SleepHealthPublication(measured=$measured, userEntered=$userEntered, withheld=$withheld, published=$published)"

    private companion object {
        fun readOnlyCopy(segments: List<SleepSegment>): List<SleepSegment> = Collections.unmodifiableList(ArrayList(segments))
    }
}

/**
 * Everything except claims over nothing — the filter for a DERIVED STATISTIC. ⚠️ NOT the health-store
 * filter: it also drops the unmeasured part of the in-bed layer, right for a denominator and wrong for
 * the store (use [healthPublishable] there).
 */
val List<SleepSegment>.measuredOnly: List<SleepSegment> get() = filter { it.provenance.hasMeasurement }

/**
 * HOW THIS NIGHT IS SPLIT FOR THE HEALTH STORE — the single place that decides what reaches it and how
 * each sample is tagged; [healthPublishable] and [withheldSpans] both derive from it, so "what we write"
 * and "what we declined to write" can never disagree.
 *
 * ONE PREDICATE, EVERY STAGE: a proven-unmeasured segment (an asleep block over a hole, an awake block
 * over the same hole, the in-bed span across it — the same claim by the same person) is user-entered;
 * everything else, including coverage-unknown ground ("we cannot say" is not "she told us"), is
 * written as an ordinary sample. Nothing is withheld; [SleepHealthPublication.published] is this list.
 */
val List<SleepSegment>.healthPublication: SleepHealthPublication
    get() {
        val measured = ArrayList<SleepSegment>()
        val userEntered = ArrayList<SleepSegment>()
        for (segment in this) {
            if (segment.provenance.isProvenUnmeasured) userEntered += segment else measured += segment
        }
        return SleepHealthPublication(measured, userEntered, withheld = emptyList(), published = this)
    }

/** What may be written to the health store — every segment, since the partition withholds nothing. */
val List<SleepSegment>.healthPublishable: List<SleepSegment> get() = healthPublication.published

/** The subset of [healthPublishable] that must be marked as user-entered. */
val List<SleepSegment>.healthUserEntered: List<SleepSegment> get() = healthPublication.userEntered

/** True when any segment is a claim over ground we can PROVE holds no records. */
val List<SleepSegment>.containsAssertedTime: Boolean get() = any { it.provenance.isProvenUnmeasured }

/**
 * Asleep seconds that reach the store as the WEARER'S OWN ENTRY — marked user-entered, counted in the
 * store's time asleep, and named on the sleep card.
 */
val List<SleepSegment>.unmeasuredAsleepSeconds: Double
    get() {
        val asleep = setOf(SleepStage.ASLEEP_CORE, SleepStage.ASLEEP_DEEP, SleepStage.ASLEEP_REM)
        return filter { it.stage in asleep && it.provenance.isProvenUnmeasured }
            .fold(0.0) { acc, s -> acc + swiftMax(0.0, SleepStaging.seconds(s.duration)) }
    }

/**
 * The spans this app is DECLINING to publish, merged so overlapping stage / in-bed views of one hole
 * count once. ⚠️ ITS ONE CONSUMER IS A DELETE PREDICATE: it derives from
 * [SleepHealthPublication.withheld], so it is empty today — a free-standing "asserted spans" filter
 * would protect freshly written samples from the cleanup that removes the PREVIOUS write over the same
 * ground, leaving a duplicate night in the store on every re-edit.
 */
val List<SleepSegment>.withheldSpans: List<DateInterval>
    get() {
        val spans = healthPublication.withheld.filter { it.end.isAfter(it.start) }.map { DateInterval(it.start, it.end) }
        return MeasuredCoverage(spans).intervals
    }
