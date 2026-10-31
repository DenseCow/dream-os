package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.playback.Quality
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.store.Prefs
import com.dreamos.desktop.core.time.DreamTime
import java.util.Locale

/**
 * The settings the pages save (Settings, Setup) as core reads them: the per-profile `pages` file, with the pages'
 * defaults. Values are the words the pages store ("Automatic", "On, Muted", "English"); the typed helpers turn them into
 * what the player and Dream Core use. `home.animeApart` and `audio.lang` go through [ContentPrefs] (the Fire Stick's
 * files), as the pages already do.
 *
 * The clock settings (`time.zone`, `time.clock`) drive [DreamTime] for every date and time core words: [applyTime] runs
 * at launch, on every profile switch, and whenever the pages change them.
 */
object ViewerSettings {
    const val FILE = "pages"

    private fun f(): Prefs = DreamStore.shared.profileFile(FILE)
    private fun bool(key: String, def: Boolean) = f().getBoolean(key, def)
    private fun str(key: String, def: String) = f().string(key, def)

    // ---- Sources & Quality ----

    /** "Automatic", "4K", "1080p", "720p", "SD". */
    fun maxQualityWords() = str("quality.max", "Automatic")
    /** The highest grade allowed; null = Automatic (the screen and Dream Core decide). */
    fun maxQuality(): Quality? = Quality.ofLabel(maxQualityWords())
    fun preferHdr() = bool("prefer.hdr", false)
    fun hdrFirst() = bool("prefer.hdrFirst", false)
    fun hideAv1() = bool("hide.av1", true)
    fun hideHevc() = bool("hide.hevc", false)
    fun hideHdr() = bool("hide.hdr", false)
    fun hideDolbyVision() = bool("hide.dv", false)
    fun hideCam() = bool("hide.cam", true)
    /** "No Limit", "Size Range", "Match My Connection". */
    fun movieSize() = str("size.movieSize", "No Limit")
    fun episodeSize() = str("size.episodeSize", "No Limit")

    // ---- sound and subtitles ----

    fun preferDubs() = bool("audio.preferDubs", true)
    fun passthrough() = bool("audio.passthrough", false)
    fun subtitlesOn() = bool("subs.enabled", false)
    /** The subtitle languages in order as ISO 639-1 codes ("en"); "None" and unknown names left out. */
    fun subtitleLangs(): List<String> = listOf(str("subs.lang1", "English"), str("subs.lang2", "None")).mapNotNull { langCode(it) }.distinct()
    fun subtitlesInVideoFirst() = bool("subs.embedded", true)
    /** "Small", "Medium", "Large", "Extra Large". */
    fun subtitleSize() = str("subs.size", "Medium")
    /** "Classic", "Boxed", "Yellow". */
    fun subtitleStyle() = str("subs.style", "Classic")
    /** "Low", "Standard", "Raised". */
    fun subtitlePosition() = str("subs.position", "Standard")

    // ---- playback ----

    fun autoplay() = bool("play.autoplay", true)
    fun stillWatching() = bool("play.stillWatching", true)
    fun skipIntros() = bool("play.skipIntros", false)
    fun instantPlay() = bool("play.instant", true)

    // ---- look, sounds, notices ----

    /** "On with Sound", "On, Muted", "Off". */
    fun trailers() = str("look.trailers", "On, Muted")
    fun navSounds() = bool("look.navSounds", true)
    fun introSound() = bool("look.introSound", true)
    fun noticeSound() = bool("look.noticeSound", true)
    fun episodeChime() = bool("home.episodeChime", false)
    fun accountSummary() = bool("notices.summary", true)
    /** Notices may also go out as Windows notifications while Dream OS is in the background. */
    fun osAlerts() = bool("notices.osAlerts", true)

    // ---- time ----

    /** An IANA zone id, "" = Automatic. */
    fun timeZone() = str("time.zone", "")
    /** "Automatic", "12-Hour", "24-Hour". */
    fun clock() = str("time.clock", "Automatic")

    /** Points [DreamTime] at this profile's zone and clock format (only when they differ). */
    fun applyTime() {
        val zone = timeZone()
        val clock = clock()
        val wantZone = zone.ifBlank { null }
        if (DreamTime.isAutomatic != (wantZone == null) || (wantZone != null && DreamTime.zone.id != wantZone)) DreamTime.setZone(wantZone)
        val want24 = when (clock) { "24-Hour" -> true; "12-Hour" -> false; else -> null }
        if (DreamTime.override24h != want24) DreamTime.set24h(want24)
    }

    /** The current profile's file, for watching its changes. */
    fun file(): Prefs = f()

    /** "English" (the pages' names, in English) -> "en"; null for "None" or a name no locale has. */
    fun langCode(name: String): String? {
        if (name.isBlank() || name.equals("None", true)) return null
        return NAMES[name.lowercase(Locale.US)]
    }

    private val NAMES: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (c in Locale.getISOLanguages()) m.putIfAbsent(Locale(c).getDisplayLanguage(Locale.US).lowercase(Locale.US), c)
        m["brazilian portuguese"] = "pt"
        m["portuguese (brazil)"] = "pt"
        m["chinese (simplified)"] = "zh"
        m["chinese (traditional)"] = "zh"
        m["latin american spanish"] = "es"
        m["spanish (latin america)"] = "es"
        m
    }
}
