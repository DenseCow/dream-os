package com.dreamos.desktop.startup

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import com.dreamos.desktop.shell.RowsModel
import com.dreamos.desktop.shell.ShellState
import com.dreamos.desktop.shell.SlotState
import com.dreamos.desktop.ui.kit.Images
import com.dreamos.desktop.ui.kit.LocalMetrics
import com.dreamos.desktop.ui.kit.Tmdb

/**
 * Home's first screen is in: the hero with its first backdrop in memory, and the first two rows settled (shown, left
 * out or failed). The launch intro holds for this and no longer.
 */
object HomeReady {
    @Composable
    fun remember(state: ShellState): () -> Boolean {
        val m = LocalMetrics.current
        val density = LocalDensity.current.density
        return { check(state, m.widthDp * density) }
    }

    private fun check(state: ShellState, heroPx: Float): Boolean {
        // No TMDB key: Home is its welcome page, already drawn.
        if (!state.catalog.ready) return true
        val model = state.peek<RowsModel>("home") ?: return false
        val ready = model.nothing || run {
            val hero = model.hero ?: return@run false
            val art = hero.firstOrNull()?.title?.backdropPath?.let { Tmdb.backdrop(it, heroPx) }
            if (art != null && Images.cached(art) == null) return@run false
            val slots = model.slots
            if (slots.isEmpty()) model.listFailed
            else slots.take(2).all { s -> model.states[s.id].let { it != null && it != SlotState.Loading } }
        }
        if (ready) StartupTrace.mark("home: ready")
        return ready
    }
}
