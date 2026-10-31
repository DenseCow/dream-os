package com.dreamos.desktop.settings.kit

import androidx.compose.runtime.staticCompositionLocalOf

/** Which title: TMDB id and kind. Stand-in for core's model type until win-content's lands. */
data class TitleRef(val tmdbId: Int, val isShow: Boolean, val name: String = "")

/** What Play asks for: a title, an episode for shows, and whether to start from the beginning. */
data class PlayRequest(val title: TitleRef, val season: Int? = null, val episode: Int? = null, val fromStart: Boolean = false, val sourceId: String? = null)

/**
 * How the pages leave themselves: back, other pages, the player, the browser. Stand-in for win-shell's `Nav`
 * (requests.md); the shell provides the real one through [LocalPagesNav].
 */
interface PagesNav {
    fun back()
    fun openTitle(ref: TitleRef)
    fun openPerson(personId: Int, name: String)
    /** A cast face: the person page says "As [asRole]" and leaves [from] (the title page it came from) out of Known For. */
    fun openPerson(personId: Int, name: String, asRole: String?, from: TitleRef?) = openPerson(personId, name)
    fun openSettings()
    fun play(request: PlayRequest)
    fun playTrailer(title: TitleRef, videoKey: String)
    /**
     * Opens Dream Player on a request the page already resolved (Choose a Source's pick, from
     * [com.dreamos.desktop.details.TitleSource.prepare]): `DreamPlayer.open(window, request, playback, services, events)`.
     * The shell implements it; without it the pick goes to [play] with its source id.
     */
    fun playPrepared(request: com.dreamos.desktop.core.playback.PlayRequest, fallback: PlayRequest) = play(fallback)
    /** Settings › About, where a new version downloads and installs (a notice's Install Now); the release page until the shell provides it. */
    fun openUpdate() = openUrl(com.dreamos.desktop.about.DreamLinks.RELEASES)
    /** Opens [url] in the viewer's browser (the PC's default). */
    fun openUrl(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
    }
}

/** Does nothing but log: used by the offscreen previews and until the shell provides the real one. */
object LoggingNav : PagesNav {
    override fun back() = println("Nav: back")
    override fun openTitle(ref: TitleRef) = println("Nav: title $ref")
    override fun openPerson(personId: Int, name: String) = println("Nav: person $personId $name")
    override fun openSettings() = println("Nav: settings")
    override fun play(request: PlayRequest) = println("Nav: play $request")
    override fun playTrailer(title: TitleRef, videoKey: String) = println("Nav: trailer ${title.name}")
    override fun playPrepared(request: com.dreamos.desktop.core.playback.PlayRequest, fallback: PlayRequest) = println("Nav: play prepared ${request.target.title} ${request.stream.fileName}")
}

val LocalPagesNav = staticCompositionLocalOf<PagesNav> { LoggingNav }
