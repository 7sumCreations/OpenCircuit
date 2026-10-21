package io.github.opencircuit.app

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.opencircuit.app.onboarding.Destination
import io.github.opencircuit.app.ui.tabTag

/** Taps [tab] in the bottom bar: the app opens on Today, so a test of another screen goes there first. */
internal fun SemanticsNodeInteractionsProvider.openTab(tab: Destination) {
    onNodeWithTag(tabTag(tab)).performClick()
}
