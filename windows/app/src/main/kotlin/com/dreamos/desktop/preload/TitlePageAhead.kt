package com.dreamos.desktop.preload

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.dreamos.desktop.details.TitleDetails
import com.dreamos.desktop.details.TitleSource
import com.dreamos.desktop.settings.kit.TitleRef
import com.dreamos.desktop.ui.kit.Images
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A title page readied before it shows. With the details in hand, the hero's backdrop and logo (what fills the window
 * first) are loaded before the page shows, up to [HERO_WAIT_MS]; the opening season (episodes and their first stills,
 * mostly below the hero) starts at the same moment and is waited for only [SEASON_WAIT_MS], enough when it is kept on
 * disk, so a first visit never holds the page for it; it is in long before the viewer scrolls to it. Behind it: More
 * Like This pills asked, the first cast photos and More Like This posters fetched. A season tab under the pointer
 * loads that season.
 */
object TitlePageAhead {
    /** The longest the page waits for its hero art (the details are already in). */
    const val HERO_WAIT_MS = 900L
    /** The longest the page waits for its opening season (it keeps loading behind). */
    const val SEASON_WAIT_MS = 250L
    /** Episode stills, cast photos and More Like This posters fetched ahead: about a window's width of each. */
    const val STILLS = 8
    const val CAST = 10
    const val SIMILAR = 10

    /** The season the episode lane opens on (EpisodesSection's rule). */
    fun openingSeason(d: TitleDetails): Int? = if (!d.ref.isShow || d.seasons.isEmpty()) null else
        d.progress?.season?.takeIf { s -> d.seasons.any { it.number == s && it.comingWords == null } } ?: d.defaultSeason ?: d.seasons.first().number

    suspend fun ready(source: TitleSource, d: TitleDetails) {
        val s = openingSeason(d)
        if (s != null) season(source, d.ref, s)
        coroutineScope {
            launch { Images.warm(listOf(d.backdropUrl, d.logoUrl), HERO_WAIT_MS) }
            if (s != null) launch { withTimeoutOrNull(SEASON_WAIT_MS) { while (source.seasonNow(d.ref, s) == null) delay(20) } }
        }
        behind(source, d)
    }

    /**
     * What the page shows further down, behind the viewer: More Like This pills asked, the first cast photos and More
     * Like This posters fetched, the opening season if it isn't ready yet. Alone when the page was already showing
     * (readied by a hover), so nothing waits on it.
     */
    fun behind(source: TitleSource, d: TitleDetails) {
        source.lookAhead(d)
        Images.prefetch(d.cast.take(CAST).map { it.photoUrl } + d.similar.take(SIMILAR).map { it.posterUrl })
        openingSeason(d)?.let { s -> season(source, d.ref, s) }
    }

    /** A season tab under the pointer: its episodes and first stills, behind the viewer. */
    fun season(source: TitleSource, ref: TitleRef, season: Int) {
        if (source.seasonNow(ref, season) == null) ahead { seasonAndStills(source, ref, season) }
    }

    private suspend fun seasonAndStills(source: TitleSource, ref: TitleRef, season: Int) {
        val eps = source.seasonNow(ref, season) ?: runCatching { source.season(ref, season) }.getOrNull() ?: return
        Images.prefetch(eps.take(STILLS).map { it.stillUrl })
    }
}

/**
 * [content] fades in when it arrives later than [since] (its card's or band's first frame), and is simply there when
 * it came with it: a pill answered after its card showed lands softly instead of blinking in.
 */
@Composable
fun Arriving(since: Long, content: @Composable () -> Unit) {
    val late = remember { System.currentTimeMillis() - since > LATE_MS }
    val a = remember { Animatable(if (late) 0f else 1f) }
    LaunchedEffect(Unit) { if (late) a.animateTo(1f, tween(FADE_MS)) }
    Box(Modifier.graphicsLayer { alpha = a.value }) { content() }
}

private const val LATE_MS = 150L
private const val FADE_MS = 240
