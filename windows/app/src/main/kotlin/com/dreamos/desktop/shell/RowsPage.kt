package com.dreamos.desktop.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.dreamos.desktop.shell.data.CardItem
import com.dreamos.desktop.shell.data.Catalog
import com.dreamos.desktop.shell.data.HeroItem
import com.dreamos.desktop.shell.data.PersonalRow
import com.dreamos.desktop.shell.data.Removal
import com.dreamos.desktop.shell.data.Row
import com.dreamos.desktop.shell.data.RowSlot
import com.dreamos.desktop.ui.kit.DOT
import com.dreamos.desktop.ui.kit.DataRowView
import com.dreamos.desktop.ui.kit.EndCap
import com.dreamos.desktop.ui.kit.Images
import com.dreamos.desktop.ui.kit.KeepClear
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.Metrics
import com.dreamos.desktop.ui.kit.RowError
import com.dreamos.desktop.ui.kit.SkeletonRow
import com.dreamos.desktop.ui.kit.firstArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed interface SlotState {
    data object Loading : SlotState
    data class Shown(val row: Row) : SlotState
    data object Hidden : SlotState
    data object Failed : SlotState
}

/**
 * A page of rows (Home, a hub): its hero, its slots and each slot's state, kept by the shell while the viewer is on
 * other tabs so coming back finds the page as it was, scrolled where it was. Rows load eight at a time in page order;
 * each shows once its first twelve cards' art is in (or 1.5 s passed), so a row arrives whole, and the rest of its
 * art downloads right behind it.
 */
class RowsModel(
    private val scope: CoroutineScope,
    private var slotsFn: suspend () -> List<RowSlot>,
    private val heroFn: (suspend () -> List<HeroItem>)?,
) {
    val list = LazyListState()
    var slots by mutableStateOf(emptyList<RowSlot>())
        private set
    val states = mutableStateMapOf<String, SlotState>()
    /** null while loading; empty when there's no hero. */
    var hero by mutableStateOf<List<HeroItem>?>(null)
        private set
    private var warm: suspend (Row, Int) -> Row = { r, _ -> r }
    private var warmHero: suspend (List<HeroItem>) -> Unit = {}
    private var started = false
    private val gate = Semaphore(8)

    /**
     * [warmArt] readies a loaded row before it shows and returns the row to show (its cards' key art filled in, its
     * first cards' art and pills ready; the slot's place on the page given), [warmHeroArt] the hero's first slide
     * (backdrop and logo), so neither appears piece by piece.
     */
    fun start(warmArt: suspend (Row, Int) -> Row, warmHeroArt: suspend (List<HeroItem>) -> Unit = {}) {
        warm = warmArt
        warmHero = warmHeroArt
        if (started) return
        started = true
        reloadAll()
    }

    /** null while the page's row list itself is loading; true when that failed. */
    var listFailed by mutableStateOf(false)
        private set

    fun reloadAll() {
        heroFn?.let { f ->
            scope.launch { hero = runCatching { f() }.getOrDefault(emptyList()).also { runCatching { warmHero(it) } } }
        } ?: run { hero = emptyList() }
        fillRows()
    }

    private var job: kotlinx.coroutines.Job? = null

    /** [quiet]: rows already showing stay on screen until their new cards are in (no skeleton flash). */
    private fun fillRows(quiet: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            listFailed = false
            val list = runCatching { slotsFn() }.getOrElse { listFailed = true; emptyList() }
            // Quiet: a hidden row stays hidden until its answer too (a Continue Watching row whose last card was
            // removed flashed its skeleton).
            val kept = if (quiet) states.filterValues { it is SlotState.Shown || it is SlotState.Hidden } else emptyMap()
            // Replaced in place, not cleared first: a frame between the two saw kept rows with no state (a skeleton).
            val ids = list.mapTo(HashSet()) { it.id }
            states.keys.filter { it !in ids }.forEach { states.remove(it) }
            list.forEach { s -> states[s.id] = kept[s.id]?.let { if (it is SlotState.Shown) without(it.row) else it } ?: SlotState.Loading }
            slots = list
            for ((i, s) in list.withIndex()) launch { load(s, i, quiet = s.id in kept) }
        }
    }

    /** New rows under the same hero (a category chip): the hero and the scroll position stay. */
    fun switchRows(fn: suspend () -> List<RowSlot>) {
        slotsFn = fn
        fillRows()
    }

    /** Rows again under the same hero (the profile, history or My List changed). */
    fun refreshRows() = fillRows(quiet = true)

    /** The [Catalog.changeCount] these rows were built at; -1 before the page first followed it. */
    private var heard = -1L
    /** Cards removed since the last change: a row answer read before core saved the removal leaves them out. */
    private val dropped = java.util.concurrent.ConcurrentHashMap<String, PersonalRow>()

    /**
     * While the page shows: a removed card leaves its rows at once, and the rows reload on every change, including
     * the ones made while the page was kept away (the profile, history, My List, settings, a new day).
     */
    suspend fun follow(catalog: Catalog): Unit = kotlinx.coroutines.coroutineScope {
        launch { catalog.removals.collect { dropCard(it) } }
        catalog.changeCount.collect { n ->
            val was = heard
            heard = n
            if (was >= 0 && n != was) {
                dropped.clear()
                refreshRows()
            }
        }
    }

    /** Takes [r]'s card off the rows it names now; a row left with nothing leaves the page. */
    fun dropCard(r: Removal) {
        dropped[r.key] = r.from
        for ((id, st) in states.toMap()) if (st is SlotState.Shown && r.from.holds(id)) states[id] = without(st.row)
    }

    private fun without(row: Row): SlotState {
        val left = row.items.filter { dropped[it.title.key]?.holds(row.id) != true }
        return when {
            left.size == row.items.size -> SlotState.Shown(row)
            left.isEmpty() && row.hubs.isEmpty() -> SlotState.Hidden
            else -> SlotState.Shown(row.copy(items = left))
        }
    }

    /** Loads one slot again (a personal row whose data changed, or Try Again). */
    fun reload(id: String) {
        val s = slots.firstOrNull { it.id == id } ?: return
        scope.launch { load(s, slots.indexOf(s), quiet = states[id] is SlotState.Shown) }
    }

    private suspend fun load(s: RowSlot, index: Int, quiet: Boolean = false) {
        if (!quiet) states[s.id] = SlotState.Loading
        val r = gate.withPermit { runCatching { s.load() } }
        states[s.id] = r.fold(
            // A row of hub tiles alone (More to Celebrate; a hub row whose titles didn't load) still shows.
            onSuccess = { got ->
                val row = got?.let { (without(it) as? SlotState.Shown)?.row }
                if (row == null || (row.items.isEmpty() && row.hubs.isEmpty())) SlotState.Hidden else SlotState.Shown(warm(row, index))
            },
            onFailure = { SlotState.Failed },
        )
    }

    /** Every slot settled and none has anything: the page's own empty or error view takes over. */
    val nothing: Boolean get() = (listFailed || (slots.isNotEmpty() && states.values.none { it is SlotState.Loading || it is SlotState.Shown })) && hero?.isEmpty() == true
}

/** A title's meta line: year, type, two genres. */
fun metaLine(c: Catalog, t: com.dreamos.desktop.shell.data.Title): String =
    (listOfNotNull(t.year.ifEmpty { null }, if (t.isShow) "Series" else "Movie") + c.genreNames(t)).joinToString(DOT)

/**
 * The scrolling page of rows: [top] (a hero or a header), then each slot (skeleton while loading, nothing when empty,
 * a sentence and Try Again when it failed), then the end cap and [bottom] (a service page's credit). Keyboard focus keeps
 * clear of the bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowsPage(model: RowsModel, catalog: Catalog, bottom: LazyListScope.() -> Unit = {}, top: LazyListScope.() -> Unit) {
    val m = LocalMetrics.current
    val dens = LocalDensity.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        model.start(
            { row, index -> RowReady.warm(row, index, catalog, m, dens.density) },
            { heroes -> RowReady.warmHero(heroes, m, dens.density) },
        )
    }
    // Rows follow the viewer: removed cards, history, My List, profile, settings, a new day (core says when).
    LaunchedEffect(model, catalog) { model.follow(catalog) }
    val clear = remember(m, dens) { with(dens) { KeepClear((m.barH + m.u(8)).toPx(), m.u(24).toPx(), Motion.reduced) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides clear) {
        LazyColumn(state = model.list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = m.u(8))) {
            top()
            for ((i, s) in model.slots.withIndex()) {
                item(key = "slot:$i:${s.id}") {
                    val st = model.states[s.id] ?: SlotState.Loading
                    com.dreamos.desktop.preload.PopProbe("row", st !is SlotState.Loading)
                    // A row lands with a short fade and rise over its skeleton (once; a refresh swaps in place).
                    AnimatedContent(
                        targetState = st,
                        contentKey = { it::class },
                        transitionSpec = {
                            if (Motion.reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            else (fadeIn(tween(320)) + slideInVertically(tween(380)) { it / 14 }) togetherWith fadeOut(tween(160))
                        },
                        label = "row",
                    ) { now ->
                        when (now) {
                            SlotState.Loading -> Box(Modifier.padding(bottom = gap(m))) { SkeletonRow(s.name, s.style) }
                            is SlotState.Shown -> Box(Modifier.padding(bottom = gap(m))) {
                                LaunchedEffect(now.row) {
                                    catalog.ask(now.row.items.map { it.title })
                                    // New Episodes for You: each episode's own pill (a show card has none).
                                    for (i in now.row.items) if (i.newEpisode) catalog.askEpisode(i.title, i.season!!, i.episode!!, "", i.airDate.orEmpty())
                                }
                                DataRowView(now.row, meta = { it: CardItem -> metaLine(catalog, it.title) }, logo = { catalog.logo(it) })
                            }
                            SlotState.Failed -> Box(Modifier.padding(bottom = gap(m))) { RowError(s.name) { model.reload(s.id) } }
                            SlotState.Hidden -> Box(Modifier)
                        }
                    }
                }
            }
            item(key = "end") { com.dreamos.desktop.notices.AllCaughtUp(onBackToTop = { scope.launch { model.list.animateScrollToItem(0) } }) }
            bottom()
        }
    }
}

private fun gap(m: Metrics) = m.rowGap - m.liftRoom
