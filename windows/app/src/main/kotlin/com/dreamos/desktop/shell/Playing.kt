package com.dreamos.desktop.shell

import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.model.TmdbImage
import com.dreamos.desktop.core.playback.PlayTarget
import com.dreamos.desktop.core.service.Services
import com.dreamos.desktop.scrapers.Scrapers
import com.dreamos.player.DreamPlayer
import com.dreamos.player.api.PlayerEvents
import com.dreamos.player.core.CorePlayerServices
import com.dreamos.desktop.core.playback.MediaKind as PKind

/**
 * What to play, as Dream Core wants it: a movie or one episode, with the IMDb id, year, languages, runtime and art
 * from the title's details (cached by core after the first look), so the scrapers and the curtain have everything.
 */
object Targets {
    suspend fun of(t: Title, season: Int? = null, episode: Int? = null, episodeName: String? = null): PlayTarget {
        val d = runCatching { Services.titles.details(t.kind, t.id) }.getOrNull()
        val title = d?.title ?: t
        var s = season
        var e = episode
        var name = episodeName
        // A show without an episode: where the watch history says (Resume / Up Next), else the first episode.
        if (t.isShow && (s == null || e == null)) {
            val next = runCatching { Services.history.nextFor(title) }.getOrNull()
            s = next?.season ?: 1
            e = next?.episode ?: 1
            name = next?.episodeName
        }
        return PlayTarget(
            kind = if (t.isShow) PKind.EPISODE else PKind.MOVIE,
            tmdbId = title.id, title = title.name, year = title.year.toIntOrNull(), imdb = d?.imdbId,
            season = if (t.isShow) s else null, episode = if (t.isShow) e else null, episodeTitle = name,
            originalLanguage = title.lang.ifBlank { null }, spokenLanguages = d?.info?.spoken ?: emptyList(),
            runtimeMin = d?.runtime ?: 0, airDate = title.date.ifBlank { null },
            backdrop = title.backdrop("w1280"), poster = title.poster(), logo = TmdbImage.url(d?.logoPath, "w500"),
        )
    }

    fun titleOf(tmdbId: Int, isShow: Boolean, name: String = "") = Title(tmdbId, if (isShow) MediaKind.TV else MediaKind.MOVIE, name)
}

/** Opens Dream Player. The window's app uses [WindowPlayer]; offscreen runs record what would play. */
fun interface PlayerLauncher {
    /** On the UI thread. [startMs] > 0 overrides where the history would start (1 = from the beginning). */
    fun play(target: PlayTarget, startMs: Long)
    /** A trailer in Dream Player's trailer mode (no history, no Change Source), on the UI thread. */
    fun trailer(playback: com.dreamos.desktop.core.trailer.TrailerPlayback, target: PlayTarget) {}
    /** A source picked in Choose a Source, already resolved ([PlaybackApi.playSource]), on the UI thread. */
    fun prepared(request: com.dreamos.desktop.core.playback.PlayRequest) = play(request.target, 0)
}

/**
 * Dream Player over the app's window: the curtain shows at once and Dream Core resolves behind it. While it plays the
 * scrapers trim their memory and notices hold their banners; when it closes, held notices come out.
 */
class WindowPlayer(private val window: () -> java.awt.Window?, private val openTitle: (Title) -> Unit) : PlayerLauncher {
    override fun play(target: PlayTarget, startMs: Long) {
        val playback = AppServices.playback ?: return
        AppServices.playerShowing = true
        Thread({ runCatching { Scrapers.shared.engine.trimNow() } }, "dream-trim").apply { isDaemon = true }.start()
        val services = CorePlayerServices(playback.core.langs) { id, isShow -> openTitle(Targets.titleOf(id, isShow)) }
        DreamPlayer.play(window(), target, playback, services, events, startMs = startMs)
    }

    /** The trailer plays through its own PlaybackApi (core.trailer), the curtain first, as on the title page. */
    override fun trailer(playback: com.dreamos.desktop.core.trailer.TrailerPlayback, target: PlayTarget) {
        AppServices.playerShowing = true
        val langs = AppServices.playback?.core?.langs
        val services = CorePlayerServices(langs) { id, isShow -> openTitle(Targets.titleOf(id, isShow)) }
        DreamPlayer.open(window(), playback.pending(target), playback, services, events, trailer = true)
    }

    /** The picked source plays as given (its spot from the watch history), with no new lookup. */
    override fun prepared(request: com.dreamos.desktop.core.playback.PlayRequest) {
        val playback = AppServices.playback ?: return
        AppServices.playerShowing = true
        Thread({ runCatching { Scrapers.shared.engine.trimNow() } }, "dream-trim").apply { isDaemon = true }.start()
        val services = CorePlayerServices(playback.core.langs) { id, isShow -> openTitle(Targets.titleOf(id, isShow)) }
        DreamPlayer.open(window(), request, playback, services, events)
    }

    private val events = object : PlayerEvents {
        override fun closed(target: PlayTarget, positionMs: Long) = AppServices.playerClosed()
    }
}
