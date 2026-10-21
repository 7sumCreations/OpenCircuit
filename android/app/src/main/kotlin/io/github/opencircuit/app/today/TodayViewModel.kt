package io.github.opencircuit.app.today

import androidx.lifecycle.ViewModel
import io.github.opencircuit.app.ring.everyMinute
import io.github.opencircuit.app.session.SessionHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Hosts the Today screen's state: what is stored (reloaded on each [TodaySource.revisions]), what
 * the ring session reports ([session]) and the clock, through [TodayPresenter]. [scope] becomes the
 * view model's scope (the app passes a main-thread scope; tests pass a virtual-time one).
 *
 * The state starts loading and leaves it after the first load; from then on a reload never blanks
 * the screen: while one runs, or when one fails, the last values stay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val source: TodaySource?,
    session: Flow<TodaySession> = flowOf(TodaySession()),
    scope: CoroutineScope,
    /** The phone's wall clock, for the readings' ages and "Last synced". */
    private val wallClock: () -> Instant = Instant::now,
    /** The phone's time zone, for "today" and the times shown. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Re-reads [wallClock]: ages move on while nothing else changes. */
    clockTicks: Flow<Unit> = everyMinute(),
    private val log: (String) -> Unit = {},
) : ViewModel(scope) {

    /** The Today screen's state. */
    val uiState: StateFlow<TodayUiState>

    init {
        val stored: Flow<TodayStored?> = (source?.revisions() ?: flowOf(0L))
            .transformLatest<Long, TodayStored?> {
                if (source == null) {
                    emit(TodayStored())
                } else {
                    try {
                        val z = zone()
                        emit(source.load(LocalDate.ofInstant(wallClock(), z), z))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The last values stay on screen; the next revision tries again.
                        log("Today could not read the store: ${e::class.java.simpleName}")
                    }
                }
            }
            .catch { e -> log("Today stopped watching the store: ${e::class.java.simpleName}") }
            .onStart { emit(null) }
        uiState = combine(stored, session, clockTicks) { s, parts, _ -> TodayPresenter.present(TodayInput(s, parts, wallClock(), zone())) }
            .stateIn(scope, SharingStarted.Eagerly, TodayUiState())
    }
}

/** What Today shows of the ring session: the current session's link, name, sync log and running sync. */
@OptIn(ExperimentalCoroutinesApi::class)
fun todaySession(sessions: SessionHost?): Flow<TodaySession> =
    (sessions?.current ?: flowOf(null)).flatMapLatest { session ->
        if (session == null) {
            flowOf(TodaySession())
        } else {
            combine(session.state, session.syncRecords.entries, session.sync.state) { link, entries, sync ->
                TodaySession(link = link, ringName = session.link.ring.name, log = entries, syncing = sync.syncing)
            }
        }
    }
