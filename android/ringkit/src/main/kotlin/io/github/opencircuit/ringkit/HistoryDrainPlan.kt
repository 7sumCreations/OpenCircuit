package io.github.opencircuit.ringkit

// Which history channels one drain pass opens, and in what order. Port of upstream
// ios/OpenCircuitKit/Sources/OpenCircuitKit/HistoryDrainPlan.swift:48-288 (@ b1c2fdd).
//
// Upstream extracted this from three inline booleans so the order — in particular the invariant
// that the workout prime never touches the sleep channel (and so never walks the overnight resume
// pointer) — is locked by tests instead of prose.
//
// WHAT IS ESTABLISHED. Upstream saw the all-day channel report no-ack on 27 of 32 foreground
// attempts. Two mechanisms were candidates — a ring-side limit of one data-returning sync-open per
// connection (🟡, from captures not in the repo) and link flakiness (🟢) — and upstream acted only
// on the second: the link-awareness fix and the LINK_DOWN outcome, not a reorder. A reorder
// heuristic was prototyped and DELIBERATELY NOT SHIPPED; do not reintroduce ordering logic without
// measurement.
//
// ONE reorder input is admitted: the RESUME HINT — the channel this app's own session teardown cut
// off mid-wait. It may reorder ONLY the plain foreground plan; four guards keep it clean:
//   1. ORDERING. [steps] applies it only to the foreground plan; the all-day-only prime and both
//      background orderings (all-day first, and the morning catch-up's sleep first) are unreachable.
//   2. CAPTURE SITE. Only a genuine reconnect captures one ([TeardownReason.capturesResumeHint]).
//   3. IDENTITY + TTL. A hint names the ring it came from and when; [ResumeHint.step] refuses it on
//      any other ring, after [ResumeHint.TIME_TO_LIVE], or when the clock has moved backwards.
//   4. ONE-SHOT. It is consumed once per replacement session, win or lose.

import java.time.Duration
import java.time.Instant

object HistoryDrainPlan {

    /** One channel to open in this pass. [channel] is the wire selector byte, 0–255. */
    data class Step(val channel: Int, val label: String) {
        init {
            require(channel in 0..0xFF) { "channel must be a byte 0–255: $channel" }
        }
    }

    val SLEEP_STEP: Step = Step(Command.SYNC_CHANNEL_SLEEP, "sleep")
    val ALL_DAY_STEP: Step = Step(Command.SYNC_CHANNEL_ALL_DAY, "all-day")
    val SPORT_STEP: Step = Step(Command.SYNC_CHANNEL_SPORT, "sport")

    /**
     * Default width of the post-wake window in which the overnight sleep backlog outranks everything
     * else: 3 h.
     *
     * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:67`).
     */
    val DEFAULT_MORNING_CATCH_UP_WINDOW: Duration = Duration.ofHours(3)

    /**
     * The ordered channel opens for one drain pass.
     *
     * - [inBackground]: a bounded background wake — all-day goes first so today's vitals land.
     * - [allDayOnly]: the workout prime. Touches ONLY all-day — never sleep, never sport.
     * - [nightWindowEnd] / [now]: in the BACKGROUND, within [morningCatchUpWindow] after wake (both
     *   ends inclusive), the night's backlog is the point of the pass, so sleep goes first.
     * - [sportEnabled]: sport is foreground-only and always last.
     * - [resumeHint]: moved to the front of the plain FOREGROUND plan only (see the file header).
     */
    fun steps(
        inBackground: Boolean,
        allDayOnly: Boolean,
        sportEnabled: Boolean,
        now: Instant,
        nightWindowEnd: Instant?,
        morningCatchUpWindow: Duration = DEFAULT_MORNING_CATCH_UP_WINDOW,
        resumeHint: Step? = null,
    ): List<Step> {
        // The workout prime is all-day and nothing else — no sleep pointer, no sport.
        if (allDayOnly) return listOf(ALL_DAY_STEP)

        // Includes the `inBackground` requirement: the foreground plan is unconditionally sleep-first.
        val morningCatchUp = if (!inBackground || nightWindowEnd == null) {
            false
        } else {
            val sinceWake = Duration.between(nightWindowEnd, now)
            !sinceWake.isNegative && sinceWake <= morningCatchUpWindow
        }
        val sleepFirst = !inBackground || morningCatchUp

        var plan = if (sleepFirst) listOf(SLEEP_STEP, ALL_DAY_STEP) else listOf(ALL_DAY_STEP, SLEEP_STEP)
        if (!inBackground && sportEnabled) plan = plan + SPORT_STEP
        // FOREGROUND ONLY: both background orderings are device-observed decisions, and a hint that
        // reordered them would be a measurement-free reorder.
        if (resumeHint != null && !inBackground) plan = resuming(resumeHint, plan)
        return plan
    }

    /**
     * Move [step] to the front of [plan] when present (a channel cut off by a BLE reconnect gets
     * first crack on the very next attempt). A no-op when it is already first or not in the plan,
     * so a hint can never inject a channel the plan excludes.
     */
    fun resuming(step: Step, plan: List<Step>): List<Step> {
        val index = plan.indexOf(step)
        if (index <= 0) return plan
        return listOf(step) + plan.filterIndexed { i, _ -> i != index }
    }

    /**
     * Why a BLE session is being torn down — and therefore whether the channel it had in flight is
     * worth resuming on whatever session replaces it. Only a link that actually dropped, or a
     * session actually replaced on the same ring, is evidence that a channel lost its turn.
     * [rawValue] is upstream's string.
     */
    enum class TeardownReason(val rawValue: String) {
        /** The link dropped, possibly mid-drain; auto-reconnect brings up a session on the same ring. */
        LINK_DROPPED("linkDropped"),

        /** A session is being replaced on the SAME ring. */
        SESSION_REPLACED("sessionReplaced"),

        /** The user switched to a DIFFERENT ring; the old ring's channel says nothing about the new one. */
        SWITCHING_RING("switchingRing"),

        /** A deliberate user stop (disconnect / forget ring), not an interruption. */
        USER_DISCONNECTED("userDisconnected"),

        /** A bounded background read reached its own end; nothing was cut off. */
        BACKGROUND_READ_ENDED("backgroundReadEnded");

        /** Whether a teardown for this reason should record the in-flight channel as a resume hint. */
        val capturesResumeHint: Boolean
            get() = when (this) {
                LINK_DROPPED, SESSION_REPLACED -> true
                SWITCHING_RING, USER_DISCONNECTED, BACKGROUND_READ_ENDED -> false
            }
    }

    /**
     * A captured resume hint plus the two facts that make it refusable: WHICH ring it came from and
     * WHEN it was taken.
     *
     * @param peripheralID the identity of the ring whose drain was interrupted. Upstream's is the
     *   iOS peripheral `UUID`; here it is the string the BLE layer identifies the ring by.
     */
    data class ResumeHint(val step: Step, val peripheralID: String, val capturedAt: Instant) {

        /**
         * The hinted step if this hint still applies to [forPeripheral] at [at], else null. A hint
         * from a different ring, one older than [TIME_TO_LIVE] (the boundary itself still applies),
         * or one from the future (the clock moved backwards) is refused.
         */
        fun step(forPeripheral: String, at: Instant): Step? {
            if (forPeripheral != peripheralID) return null
            val age = Duration.between(capturedAt, at)
            if (age.isNegative || age > TIME_TO_LIVE) return null
            return step
        }

        companion object {
            /**
             * How long a hint stays applicable: 120 s — the worst single reconnect backoff step
             * (30 s) plus connect, discovery and auth with ~3× margin, expiring long before the next
             * drain cadence.
             *
             * iOS-tuned — re-check in E9/E11. Ported verbatim from upstream (`:248`).
             */
            val TIME_TO_LIVE: Duration = Duration.ofSeconds(120)

            /**
             * The hint that should be standing after a session teardown — the whole capture-site
             * policy as one pure function.
             *
             * ⚠️ A capturing teardown with NOTHING in flight (or no ring to stamp) must LEAVE the
             * [standing] hint alone, not clear it: the ordinary reconnect is a link-dropped teardown
             * (which captures) followed by a session-replaced one with nothing left in flight.
             * Only a NON-capturing reason clears.
             */
            fun afterTeardown(
                reason: TeardownReason,
                inFlight: Step?,
                peripheralID: String?,
                at: Instant,
                standing: ResumeHint?,
            ): ResumeHint? {
                if (!reason.capturesResumeHint) return null
                if (inFlight == null || peripheralID == null) return standing
                return ResumeHint(step = inFlight, peripheralID = peripheralID, capturedAt = at)
            }
        }
    }
}
