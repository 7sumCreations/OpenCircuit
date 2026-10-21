package io.github.opencircuit.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.opencircuit.app.onboarding.AppNavigation
import io.github.opencircuit.app.onboarding.Destination
import java.util.Locale

/** The test tag of a tab in the bottom bar: `tab.today`, `tab.ring`, `tab.settings`. */
fun tabTag(tab: Destination): String = "tab." + tab.name.lowercase(Locale.ROOT)

/**
 * The app after onboarding: [content] above a bottom bar with the three tabs, Today first, each
 * with its label always shown. [content] gets a modifier that keeps it clear of the bar.
 */
@Composable
fun AppShell(current: Destination, onSelect: (Destination) -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                AppNavigation.tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { onSelect(tab) },
                        icon = { Icon(TabIcons.of(tab), contentDescription = null) },
                        label = { Text(AppNavigation.label(tab)) },
                        alwaysShowLabel = true,
                        modifier = Modifier.testTag(tabTag(tab)),
                    )
                }
            }
        },
        // Each screen draws its own top bar and handles the top inset; the shell owns the bottom.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        content(Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** The tabs' icons, drawn here: the app ships no icon library. */
private object TabIcons {
    fun of(tab: Destination): ImageVector = when (tab) {
        Destination.Today -> today
        Destination.Ring -> ring
        Destination.Settings, Destination.Onboarding -> settings
    }

    private fun icon(name: String, fillType: PathFillType, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black), pathFillType = fillType, pathBuilder = draw).build()

    /** A calendar page with today's square. */
    private val today = icon("today", PathFillType.EvenOdd) {
        moveTo(3f, 5f); horizontalLineTo(21f); verticalLineTo(21f); horizontalLineTo(3f); close()
        moveTo(5f, 10f); horizontalLineTo(19f); verticalLineTo(19f); horizontalLineTo(5f); close()
        moveTo(7f, 12f); horizontalLineTo(11f); verticalLineTo(16f); horizontalLineTo(7f); close()
        moveTo(7f, 2f); horizontalLineTo(9f); verticalLineTo(5f); horizontalLineTo(7f); close()
        moveTo(15f, 2f); horizontalLineTo(17f); verticalLineTo(5f); horizontalLineTo(15f); close()
    }

    /** A ring seen from above. */
    private val ring = icon("ring", PathFillType.EvenOdd) {
        moveTo(12f, 3f); arcToRelative(9f, 9f, 0f, true, true, 0f, 18f); arcToRelative(9f, 9f, 0f, true, true, 0f, -18f); close()
        moveTo(12f, 6f); arcToRelative(6f, 6f, 0f, true, true, 0f, 12f); arcToRelative(6f, 6f, 0f, true, true, 0f, -12f); close()
    }

    /** Three sliders. */
    private val settings = icon("settings", PathFillType.NonZero) {
        listOf(6f, 11f, 16f).forEach { y -> moveTo(3f, y); horizontalLineTo(21f); verticalLineTo(y + 2f); horizontalLineTo(3f); close() }
        listOf(6f to 4f, 14f to 9f, 9f to 14f).forEach { (x, y) -> moveTo(x, y); horizontalLineTo(x + 3f); verticalLineTo(y + 6f); horizontalLineTo(x); close() }
    }
}
