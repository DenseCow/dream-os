package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.KidsLevel
import com.dreamos.desktop.core.model.Profile
import com.dreamos.desktop.core.store.DreamStore
import java.util.Locale

/**
 * The per-profile settings content reads (fire-home Theme.ANIME_APART, PlayerPrefs.audioLang, KidsProfiles), in the
 * same files and keys the Fire Stick uses, so Profiles, Settings and the rows agree without calling each other.
 *
 * - `theme[_<id>]`: `animeApart` (1 / 0, default 1).
 * - `player[_<id>]`: `audioLang` (normalized ISO 639-1), `subLang`.
 * - `profiles`: `kids_<id>` (Boolean), `maxlevel_<id>` (index into [KidsLevel.ALL]), `kidsexitpin_<id>`.
 */
object ContentPrefs {
    private fun store() = DreamStore.shared
    private val _version = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Bumps when a setting rows depend on changes (the anime rule, a Kids level): shown rows rebuild. */
    val version: kotlinx.coroutines.flow.StateFlow<Long> get() = _version
    fun bump() { _version.value++ }
    val profileId: String get() = DreamStore.currentProfile

    // ---- Keep Anime in Anime Sections ----

    /** Settings › Home & Apps › Keep Anime in Anime Sections (per profile, on by default). */
    fun animeApart(): Boolean = store().profileFile("theme").getInt("animeApart", 1) == 1
    fun setAnimeApart(on: Boolean) {
        store().profileFile("theme").putInt("animeApart", if (on) 1 else 0)
        Tmdb.clearLists()
        bump()
    }

    // ---- languages ----

    /** The player's audio language for this profile (normalized "en"), null when never chosen. */
    fun audioLang(): String? = store().profileFile("player").getString("audioLang")
    fun setAudioLang(lang: String?) { if (lang != null) store().profileFile("player").putString("audioLang", normLang(lang)) }

    /** The viewer's language: their audio language, else the PC's, else English. */
    fun viewerLang(): String = audioLang() ?: normLang(Locale.getDefault().language) ?: "en"

    private val BIBLIOGRAPHIC = mapOf(
        "fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "gre" to "el", "cze" to "cs", "rum" to "ro",
        "slo" to "sk", "ice" to "is", "per" to "fa", "wel" to "cy", "arm" to "hy", "baq" to "eu", "geo" to "ka",
        "mac" to "mk", "mao" to "mi", "may" to "ms", "alb" to "sq", "bur" to "my", "tib" to "bo",
    )
    private val iso3: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (c in Locale.getISOLanguages()) try { m[Locale(c).isO3Language] = c } catch (e: Exception) {}
        m
    }

    /** "eng" / "en-US" / "EN" -> "en"; null when unknown ("und", empty). */
    fun normLang(l: String?): String? {
        val p = l?.trim()?.lowercase(Locale.US)?.substringBefore('-')?.substringBefore('_') ?: return null
        if (p.isEmpty() || p == "und" || p == "unknown" || p == "zxx" || p == "mis") return null
        if (p.length == 3) return BIBLIOGRAPHIC[p] ?: iso3[p] ?: p
        return p
    }

    // ---- Kids ----

    private val KIDS_NAME = Regex("(?i)\\b(kids?|kiddos?|children|child|junior|juniors)\\b")

    /** A Kids profile: set by hand, else guessed from its name ("Kids", "Junior") as on the Fire Stick. */
    fun isKids(id: String = profileId, name: String? = null): Boolean {
        val p = store().file("profiles")
        if (p.contains("kids_$id")) return p.getBoolean("kids_$id")
        return KIDS_NAME.containsMatchIn(name ?: profileName(id))
    }

    private fun profileName(id: String): String =
        store().file("profiles").get("list", kotlinx.serialization.builtins.ListSerializer(Profile.serializer()))
            ?.firstOrNull { it.id == id }?.name.orEmpty()

    /** The profile's rating level (index into [KidsLevel.ALL]); Kids profiles default to Kids, everyone else to no limit. */
    fun kidsLevel(id: String = profileId): Int =
        store().file("profiles").getInt("maxlevel_$id", if (isKids(id)) KidsLevel.DEFAULT_KIDS else KidsLevel.ALL.size - 1)
            .coerceIn(0, KidsLevel.ALL.size - 1)

    private val MOVIE_ORDER = listOf("G", "PG", "PG-13", "R", "NC-17")

    /** Whether US certification [cert] is allowed at [level]. Unknown or unrated: only for Everyone. */
    fun allows(level: Int, cert: String?, tv: Boolean): Boolean {
        if (level >= KidsLevel.ALL.size - 1) return true
        val c = cert?.trim()?.uppercase().orEmpty()
        if (c.isEmpty() || c == "NR" || c == "UNRATED") return false
        val l = KidsLevel.ALL[level]
        return if (tv) c in l.tv else MOVIE_ORDER.indexOf(c).let { it in 0..MOVIE_ORDER.indexOf(l.movie) }
    }

    /** Movie certifications up to [level]'s ("G|PG"), for TMDB's discover filter. */
    fun movieCerts(level: Int): List<String> = MOVIE_ORDER.take(MOVIE_ORDER.indexOf(KidsLevel.ALL[level].movie) + 1)
}
