package io.github.opencircuit.ringkit

// PARTIAL port of upstream ios/OpenCircuitKit/Sources/OpenCircuitKit/HealthAlerts.swift (@ b1c2fdd):
// only `StepWindow` (`:182-189`), which the daily energy estimate takes. The health-alert policy
// itself — thresholds, de-dupe, quiet hours, the skin-temperature and fever notifications — is ported
// by the alerts epic, which grows this file; see PORTING.md.

import java.time.Instant

/**
 * One step-count snapshot's observation window and step delta, carrying the device's own timestamps.
 * A plain value, as upstream's struct: nothing here checks it (a window that ends before it starts
 * or a negative delta is accepted, and the energy estimate decides what it is worth).
 */
data class StepWindow(val start: Instant, val end: Instant, val delta: Int)
