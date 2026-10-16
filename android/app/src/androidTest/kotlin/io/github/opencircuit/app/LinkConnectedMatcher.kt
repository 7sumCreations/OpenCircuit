package io.github.opencircuit.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import io.github.opencircuit.app.health.HEALTH_CONNECT_CARD_TAG

/**
 * The connection card's "Connected" (the ring's link is up), not the Health Connect card's
 * "Connected" (the app may write to Health Connect), which stays on screen once access is granted
 * — and a grant made by one instrumented test stays for the next.
 */
internal fun linkConnected(): SemanticsMatcher = hasText("Connected") and !hasAnyAncestor(hasTestTag(HEALTH_CONNECT_CARD_TAG))
