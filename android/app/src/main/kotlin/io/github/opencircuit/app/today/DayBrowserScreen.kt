package io.github.opencircuit.app.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.opencircuit.app.ui.LocalTones
import io.github.opencircuit.app.ui.Motion
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The tag of the day pager. */
const val TODAY_PAGER_TAG = "today.pager"

/** The tag of the date strip. */
const val TODAY_DAY_STRIP_TAG = "today.days"

/** The tag of the previous-day button. */
const val TODAY_PREVIOUS_DAY_TAG = "today.day.previous"

/** The tag of the next-day button. */
const val TODAY_NEXT_DAY_TAG = "today.day.next"

/** The tag of Today's top bar (the heading, the chip and Pick a day). */
const val TODAY_TOP_BAR_TAG = "today.topBar"

/** The tag of the "Today" chip. */
const val TODAY_CHIP_TAG = "today.chip"

/** The tag of the date picker's button. */
const val TODAY_PICK_DAY_TAG = "today.day.pick"

/** The tag of a past day's heart-rate card. */
const val TODAY_PAST_DAY_TAG = "today.pastDay"

/** The tag of one date in the strip. */
fun todayDayTag(day: LocalDate): String = "today.day.$day"

/** The heading (Today, or the day's weekday with its date), the "Today" chip on a past day, and the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayTopBar(browser: DayBrowserUi, onAction: (TodayAction) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    TopAppBar(
        modifier = Modifier.testTag(TODAY_TOP_BAR_TAG),
        title = {
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(browser.title)
                if (browser.subtitle.isNotEmpty()) Text(browser.subtitle, style = MaterialTheme.typography.bodySmall)
            }
        },
        actions = {
            if (!browser.isToday) {
                AssistChip(onClick = { onAction(TodayAction.ShowToday) }, label = { Text(TODAY) }, modifier = Modifier.testTag(TODAY_CHIP_TAG))
            }
            if (browser.days != null) {
                TextButton(onClick = { picking = true }, modifier = Modifier.testTag(TODAY_PICK_DAY_TAG)) { Text("Pick a day") }
            }
        },
    )
    val days = browser.days
    val selected = browser.selected
    if (picking && days != null && selected != null) DayPicker(days, selected, onAction) { picking = false }
}

/** Material's date picker, offering only the stored days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(days: BrowsableDays, selected: LocalDate, onAction: (TodayAction) -> Unit, onDismiss: () -> Unit) {
    val pickable = remember(days) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = DayBrowser.pickable(utcTimeMillis, days)

            override fun isSelectableYear(year: Int): Boolean = year in days.first.year..days.last.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = DayBrowser.pickerMillis(selected),
        yearRange = days.first.year..days.last.year,
        selectableDates = pickable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onAction(TodayAction.SelectDay(DayBrowser.pickerDay(it))) }
                onDismiss()
            }) { Text("Show this day") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}

/**
 * The date strip: previous, every stored day (lazily — years of days cost nothing until shown),
 * next. The picked day is filled; a day where data just landed carries a dot (and says so).
 */
@Composable
internal fun DayStrip(browser: DayBrowserUi, days: BrowsableDays, onAction: (TodayAction) -> Unit) {
    val selected = browser.selected ?: days.last
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (days.indexOf(selected) - 3).coerceAtLeast(0))
    // Keep the picked day in view as it changes (or as days are added before it).
    LaunchedEffect(selected, days) {
        val i = days.indexOf(selected)
        if (i >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == i }) list.scrollToItem((i - 3).coerceAtLeast(0))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val previous = selected.minusDays(1).takeIf { it in days }
        val next = selected.plusDays(1).takeIf { it in days }
        IconButton(
            onClick = { previous?.let { onAction(TodayAction.SelectDay(it)) } },
            enabled = previous != null,
            modifier = Modifier.testTag(TODAY_PREVIOUS_DAY_TAG).semantics { contentDescription = "Previous day" },
        ) { Text("‹", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { }) }
        LazyRow(
            state = list,
            modifier = Modifier.weight(1f).testTag(TODAY_DAY_STRIP_TAG),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(days.size, key = { days.dateAt(it).toEpochDay() }) { i ->
                val day = days.dateAt(i)
                DayCell(DayBrowser.cell(day, days.last, day in browser.marked), day == selected, todayDayTag(day)) { onAction(TodayAction.SelectDay(day)) }
            }
        }
        IconButton(
            onClick = { next?.let { onAction(TodayAction.SelectDay(it)) } },
            enabled = next != null,
            modifier = Modifier.testTag(TODAY_NEXT_DAY_TAG).semantics { contentDescription = "Next day" },
        ) { Text("›", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { }) }
    }
}

@Composable
private fun DayCell(cell: DayCellUi, picked: Boolean, tag: String, onClick: () -> Unit) {
    val marked = cell.marked
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (picked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 56.dp).testTag(tag).clearAndSetSemantics {
            contentDescription = cell.spoken
            selected = picked
            role = Role.Button
            onClick { onClick(); true }
        },
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cell.top, style = MaterialTheme.typography.labelSmall)
            Text(cell.number, style = MaterialTheme.typography.titleMedium)
            val dot = if (picked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            Box(Modifier.size(6.dp)) { if (marked) Canvas(Modifier.fillMaxSize()) { drawCircle(dot) } }
        }
    }
}

/**
 * One page per stored day, keyed by its epoch day, oldest first. The picked date is the truth: when
 * it changes (or days are added before it, moving its page) the pager is put back on it; a swipe the
 * user finishes picks the page's date. A page moved by data alone is never taken as the user's pick.
 */
@Composable
internal fun DayPager(
    days: BrowsableDays,
    selected: LocalDate,
    onAction: (TodayAction) -> Unit,
    animate: Boolean,
    page: @Composable (LocalDate) -> Unit,
) {
    val pager = rememberPagerState(initialPage = days.indexOf(selected).coerceAtLeast(0)) { days.size }
    val currentDays by rememberUpdatedState(days)
    val currentSelected by rememberUpdatedState(selected)
    LaunchedEffect(selected, days) {
        val target = days.indexOf(selected)
        if (target >= 0 && pager.currentPage != target) {
            // A neighbour slides; a jump (the picker, the chip, a moved page) lands at once.
            if (animate && kotlin.math.abs(pager.currentPage - target) == 1) pager.animateScrollToPage(target, animationSpec = Motion.spatial<Float>(animate)) else pager.scrollToPage(target)
        }
    }
    LaunchedEffect(pager) {
        var dragged = false
        launch { pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
        snapshotFlow { pager.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && dragged) {
                dragged = false
                val day = currentDays.dateAt(pager.currentPage.coerceIn(0, currentDays.size - 1))
                if (day != currentSelected) onAction(TodayAction.SelectDay(day))
            }
        }
    }
    HorizontalPager(
        state = pager,
        key = { days.dateAt(it).toEpochDay() },
        modifier = Modifier.fillMaxSize().testTag(TODAY_PAGER_TAG),
    ) { i -> page(days.dateAt(i)) }
}

/**
 * A past day's heart rate in place of the live hero: its low–high, average and resting estimate,
 * and the range as a bar on the fixed scale — one node for a screen reader. Null while the day's
 * load has not landed: the card keeps its size and says nothing yet.
 */
@Composable
internal fun PastDayCard(hero: PastDayHeroUi?, animate: Boolean) {
    val tones = LocalTones.current
    // Grown with the font, as the tiles are, so the bar and its scale are never cut off.
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Card(
        modifier = Modifier.fillMaxWidth().height(PAST_DAY_HEIGHT * fontScale).testTag(TODAY_PAST_DAY_TAG)
            .clearAndSetSemantics { hero?.let { contentDescription = it.description } },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(hero?.title ?: "", style = MaterialTheme.typography.labelLarge)
            AnimatedContent(hero, transitionSpec = { fadeIn(Motion.effects<Float>(animate)) togetherWith fadeOut(Motion.effects<Float>(animate)) }, label = "pastDay") { h ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        h == null -> Unit
                        h.range == null -> Text(h.empty ?: "", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(h.range, style = MaterialTheme.typography.displaySmall)
                                Text(" bpm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            h.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            val track = Color(tones.tonal)
                            val fill = MaterialTheme.colorScheme.primary
                            val mark = Color(tones.heart)
                            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                                val y = size.height / 2
                                val stroke = size.height
                                drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                                drawLine(fill, Offset(h.low * size.width, y), Offset(h.high * size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                                drawCircle(mark, radius = stroke * 0.7f, center = Offset(h.average * size.width, y))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                for (v in listOf("40", "80", "120", "160 bpm")) {
                                    Text(v, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The past-day card's height at the default font: the same whether its load has landed or not. */
private val PAST_DAY_HEIGHT = 196.dp
