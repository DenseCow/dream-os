package com.dreamos.desktop.ui.kit

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlin.math.abs
import kotlin.math.exp

/**
 * A row's cards by index, so the row can hand keyboard focus to a card that is in view after the mouse scrolled the
 * focused one away (focus never stays on a card the viewer can't see, and never on one that is about to go).
 */
class RowFocus {
    private val slots = HashMap<Int, FocusRequester>()
    /** The card index holding focus inside this row, or -1. */
    var focused = -1
        private set

    fun requester(i: Int): FocusRequester = slots.getOrPut(i) { FocusRequester() }

    /** The modifier for card [i]'s slot (on a box around the card: the request goes to the card inside). */
    fun slot(i: Int): Modifier = Modifier
        .onFocusChanged { if (it.hasFocus) focused = i else if (focused == i) focused = -1 }
        .focusRequester(requester(i))

    /** Keeps the cards that exist: indexes past [count] are dropped (a row that shrank). */
    fun trim(count: Int) { slots.keys.removeAll { it >= count }; if (focused >= count) focused = -1 }
}

val LocalRowFocus = staticCompositionLocalOf<RowFocus?> { null }

/** Card [i]'s slot in the row around it (nothing outside a row). */
@Composable
fun rowSlot(i: Int): Modifier = LocalRowFocus.current?.slot(i) ?: Modifier

/**
 * A smooth, momentum-like glide that several wheel notches add to: each notch adds its distance to what is still to
 * go, and the row eases toward it (a time constant of [TAU_MS]), so fast notches feel like one throw. Stops at the
 * row's ends with nothing left over.
 */
class RowGlide(private val state: LazyListState) {
    private var remaining = 0f
    private var running = false

    /** Adds [px] (positive: forward) and runs the glide until it has all been scrolled. */
    suspend fun add(px: Float) {
        // A notch the other way cancels what's left this way, so a reversal answers at once.
        remaining = if (remaining != 0f && (remaining > 0) != (px > 0)) px else remaining + px
        if (running) return
        running = true
        try {
            state.scroll(MutatePriority.UserInput) {
                var last = withFrameNanos { it }
                while (abs(remaining) > 0.5f) {
                    val now = withFrameNanos { it }
                    val dt = (now - last) / 1_000_000f
                    last = now
                    val step = remaining * (1f - exp(-dt / TAU_MS))
                    val used = scrollBy(step)
                    movedPx += abs(used)
                    remaining -= step
                    if (abs(used) < abs(step) - 0.5f) remaining = 0f // reached an end
                }
            }
        } finally {
            remaining = 0f
            running = false
        }
    }

    companion object {
        private const val TAU_MS = 70f
        /** Every row's glides added up (px): the offscreen checks read it to see a row moved. */
        @Volatile var movedPx = 0f
    }
}
