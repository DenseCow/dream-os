package com.dreamos.desktop.core.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The title page's language pills (fire-home LangPills.kt, iPhone LanguagePills.swift; owner 2026-10-29). They show
 * only when the title's original language isn't the viewer's audio language.
 *  - Their language available, green with the check: any good copy (no CAM, playable here) carries it, by its release
 *    name or because Dream Player heard it in that copy or title before. "English Dub Available" when it's a dub (a name
 *    says dub, or English isn't among TMDB's spoken languages), else "English Audio Available".
 *  - Otherwise amber with a speaker: "Korean Audio Only", "Hindi and Tamil Audio" or "Multi Audio", beside "English
 *    Subtitles" with the check when a release name or the subtitle index confirms them; "No English Subtitles" only
 *    when the index answered without them.
 * Nothing shows until something is known: never a guess that later flips. Kept: [KEEP] titles for a week, what the
 * player heard for two months (worst case ~200 KB on disk). Also the dub summary anime episode rows read.
 */
class LanguagePillsStore(private val store: KeyValueStore, private val clock: Clock = Clock.SYSTEM) {
    @Serializable
    data class Entry(val want: String, val total: Int, val match: Int, val unnamed: Int, val top: List<String>, val topCount: Int,
                     val subs: List<String>, val index: List<String>? = null, val orig: String? = null, val at: Long,
                     val dubSaid: Int = 0, val spoken: List<String>? = null, val dub: Int = 0, val original: Int = 0)

    @Serializable data class Heard(val langs: List<String>, val at: Long)

    @Serializable
    private data class Saved(val entries: Map<String, Entry> = emptyMap(), val viewerSubs: List<String>? = null,
                             val heardKeys: Map<String, Heard> = emptyMap(), val heardHashes: Map<String, Heard> = emptyMap())

    companion object {
        private const val STORE_KEY = "languagePills.v1"
        const val KEEP = 400
        private const val MAX_AGE = 7 * 86_400_000L
        private const val HEARD_AGE = 60 * 86_400_000L
        /** A title page asks the scrapers again once its answer is older than this. */
        const val FRESH = 6 * 3_600_000L
        private val JSON = Json { ignoreUnknownKeys = true }

        /** "French" for "fr"; null for a code with no known name. */
        fun name(code: String): String? = when (code) {
            "pob" -> "Brazilian Portuguese"
            "cn" -> "Cantonese"
            else -> Locale(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotEmpty() && !it.equals(code, true) }
                ?.replaceFirstChar { it.titlecase(Locale.US) }
        }

        fun original(lang: String?): String? = lang?.lowercase()?.takeIf { it.isNotEmpty() && name(it) != null }
        private fun plain(code: String) = if (code == "pob") "pt" else code
        private val SUB_LANG = mapOf("ESUB" to "en", "ESUBS" to "en", "ENGSUB" to "en", "ENGSUBS" to "en", "VOSTFR" to "fr", "VOSE" to "es")
    }

    private val lock = Any()
    private val found = HashMap<String, Entry>()
    private val heardKeys = HashMap<String, Heard>()
    private val heardHashes = HashMap<String, Heard>()
    private var viewerSubs: List<String>? = null

    init {
        try {
            store.get(STORE_KEY)?.let { JSON.decodeFromString(Saved.serializer(), it) }?.let { s ->
                val now = clock.wallMs()
                s.entries.filterTo(found) { now - it.value.at < MAX_AGE }
                s.heardKeys.filterTo(heardKeys) { now - it.value.at < HEARD_AGE }
                s.heardHashes.filterTo(heardHashes) { now - it.value.at < HEARD_AGE }
                viewerSubs = s.viewerSubs
            }
        } catch (e: Exception) { PlaybackLog.w("Language pills: kept answers unreadable (${e.javaClass.simpleName}); starting over") }
    }

    private fun <T> newest(m: MutableMap<String, T>, at: (T) -> Long) {
        if (m.size <= KEEP + KEEP / 4) return
        val keep = m.entries.sortedByDescending { at(it.value) }.take(KEEP).map { it.key }.toSet()
        m.keys.retainAll(keep)
    }

    @Volatile private var saving = false

    /** Writes 3 s after the first change of a burst (one write for a burst of surveys). */
    private fun save() {
        synchronized(lock) { if (saving) return; saving = true }
        Background.after(3_000) {
            val snap = synchronized(lock) { saving = false; Saved(HashMap(found), viewerSubs, HashMap(heardKeys), HashMap(heardHashes)) }
            store.put(STORE_KEY, JSON.encodeToString(Saved.serializer(), snap))
        }
    }

    /**
     * Dream Core read a scrape's good copies (release name, info hash) for [key]: what each says about the viewer's
     * language [want] (iPhone AnimeDubs.recordLanguages + the anime dub summary).
     */
    fun record(key: String, copies: List<Pair<String, String>>, want: String) {
        if (copies.isEmpty()) return
        var match = 0; var unnamed = 0; var dubSaid = 0; var dub = 0; var orig = 0
        val named = HashMap<Set<String>, Int>()
        val subLangs = HashSet<String>()
        for ((n, hash) in copies) {
            val t = Dubs.tokens(n)
            fun subNear(i: Int) = t.getOrNull(i + 1)?.let { it in Dubs.SUBS } == true || t.getOrNull(i - 1)?.let { it in Dubs.SUBS } == true
            val langs = HashSet<String>(); val said = HashSet<String>()
            var multi = false
            for ((i, w) in t.withIndex()) {
                SUB_LANG[w]?.let { said += it }
                if (w in Dubs.SUB_GROUPS) said += "en"
                if (w in setOf("MULTI", "MULTIAUDIO", "MULTILANG")) { if (!subNear(i)) multi = true; continue }
                val l = ReleaseLanguage.tag(w) ?: continue
                if (subNear(i)) said += l else langs += l
            }
            subLangs += said
            val dual = "DUAL" in t || "DUALAUDIO" in t
            val saysDub = t.any { it in Dubs.DUB_WORDS } || dual || "ENGDUB" in t || "ENGDUBBED" in t
            val audio = Dubs.audio(n, want)
            when (audio) { Dubs.Audio.DUB -> dub++; Dubs.Audio.ORIGINAL -> orig++; else -> {} }
            val byName = if (want == "ja") "ja" in langs else audio == Dubs.Audio.DUB
            if (byName || heardIn(hash, want)) { match++; if (byName && saysDub) dubSaid++ }
            else if (langs.isEmpty() && !multi && !dual && t.none { it in Dubs.DUB_WORDS }) unnamed++
            else if (langs.isNotEmpty()) named[langs] = (named[langs] ?: 0) + 1
        }
        val top = named.maxByOrNull { it.value }
        synchronized(lock) {
            val was = found[key]?.takeIf { it.want == want }
            found[key] = Entry(want, copies.size, match, unnamed, top?.key.orEmpty().sorted(), top?.value ?: 0, subLangs.sorted(),
                was?.index, was?.orig, clock.wallMs(), dubSaid, was?.spoken, dub, orig)
            newest(found) { it.at }
        }
        save()
    }

    /** Dream Player heard [lang] in the copy with info hash [hash] before (Dream Core ranks it as the dub). */
    fun heardIn(hash: String, lang: String): Boolean = synchronized(lock) { heardHashes[hash.lowercase()]?.langs?.contains(lang) == true }

    /** Dream Player's first frame for [key]: the audio languages its tracks carry, for the title and the copy. */
    fun heard(key: String, hash: String?, langs: Set<String>) {
        if (langs.isEmpty()) return
        synchronized(lock) {
            val now = clock.wallMs()
            heardKeys[key] = Heard((langs + heardKeys[key]?.langs.orEmpty()).sorted(), now)
            hash?.lowercase()?.takeIf { it.isNotEmpty() }?.let { heardHashes[it] = Heard(langs.sorted(), now) }
            newest(heardKeys) { it.at }; newest(heardHashes) { it.at }
        }
        save()
    }

    private fun heardKey(key: String, want: String) = synchronized(lock) { heardKeys[key]?.langs?.contains(want) == true }
    private fun entry(key: String, want: String) = synchronized(lock) { found[key]?.takeIf { it.want == want && clock.wallMs() - it.at < MAX_AGE } }

    /** Ask the scrapers again: nothing known for [key], or the answer is older than [FRESH]. */
    fun needsRefresh(key: String, want: String) = entry(key, want)?.let { clock.wallMs() - it.at > FRESH } ?: true

    /** TMDB's original and spoken languages, kept when only Dream Player's ears know. */
    fun noteTitle(key: String, want: String, original: String, spoken: List<String>) {
        if (!heardKey(key, want) && entry(key, want) == null) return
        synchronized(lock) {
            val e = found[key]?.takeIf { it.want == want } ?: Entry(want, 0, 0, 0, emptyList(), 0, emptyList(), at = clock.wallMs())
            found[key] = e.copy(orig = original, spoken = spoken.ifEmpty { e.spoken })
        }
        save()
    }

    fun noteViewerSubs(langs: List<String>) {
        val changed = synchronized(lock) { (viewerSubs != langs).also { viewerSubs = langs } }
        if (changed) save()
    }

    /** The subtitle index answered for [key]. */
    fun noteIndex(key: String, langs: Set<String>) {
        synchronized(lock) { found[key]?.let { found[key] = it.copy(index = langs.sorted()) } }
        save()
    }

    /** The anime summary: copies that say a dub in [want], and copies that say subtitles / original only. */
    fun dubSummary(key: String, want: String): Pair<Int, Int>? = entry(key, want)?.let { it.dub to it.original }

    private fun audioLangs(e: Entry, orig: String, want: String): List<String> {
        val langs: Set<String> = if (e.topCount > e.unnamed && e.top.isNotEmpty()) e.top.toSet() - want else setOf(orig)
        return langs.filter { name(it) != null }.sortedWith(compareBy<String>({ it != orig }, { name(it) ?: it }))
    }

    private fun audioWords(langs: List<String>, only: Boolean): List<String> {
        val names = langs.mapNotNull { name(it) }
        return when (names.size) {
            0 -> emptyList()
            1 -> if (only) listOf("${names[0]} Audio Only", "${names[0]} Audio") else listOf("${names[0]} Audio")
            2 -> listOf("${names[0]} and ${names[1]} Audio")
            else -> listOf("Multi Audio")
        }
    }

    private fun subLanguage(prefs: List<String>, langs: List<String>, want: String) = prefs.firstOrNull { plain(it) !in langs } ?: want
    private fun isDub(e: Entry?, want: String) = (e?.dubSaid ?: 0) > 0 || e?.spoken?.let { want !in it } == true

    /**
     * The pills for [key] from what's known, no network: empty when the title is in the viewer's own language or nothing
     * is known yet (show nothing until something is).
     */
    fun pills(key: String, original: String?, want: String): LanguagePills {
        val none = LanguagePills(null, null)
        val e = entry(key, want)
        val orig = original(original) ?: e?.orig ?: return none
        if (orig == want) return none
        if (e == null && !heardKey(key, want)) return none
        val mine = name(want) ?: return none
        if ((e?.match ?: 0) > 0 || heardKey(key, want)) {
            val what = if (isDub(e, want)) "Dub" else "Audio"
            return LanguagePills(LanguagePill("$mine $what Available", PillTone.GOOD, PillGlyph.CHECK), null)
        }
        if (e == null || e.total == 0) return none
        val langs = audioLangs(e, orig, want)
        val words = audioWords(langs, only = true).firstOrNull() ?: return none
        val audio = LanguagePill(words, PillTone.WARN, PillGlyph.SPEAKER)
        val prefs = synchronized(lock) { viewerSubs } ?: return LanguagePills(audio, null)
        val sub = subLanguage(prefs, langs, want)
        val n = name(sub) ?: return LanguagePills(audio, null)
        return when {
            plain(sub) in e.subs || e.index?.contains(sub) == true -> LanguagePills(audio, LanguagePill("$n Subtitles", PillTone.PLAIN, PillGlyph.CHECK))
            e.index != null -> LanguagePills(audio, LanguagePill("No $n Subtitles", PillTone.PLAIN, PillGlyph.NONE))
            else -> LanguagePills(audio, null)
        }
    }

    /** An episode row's compact words ("English Dub", "Japanese Audio") once that episode's copies were read or it played. */
    fun episodeWords(key: String, original: String?, want: String): String? {
        val e = entry(key, want)
        if (e == null && !heardKey(key, want)) return null
        val orig = original(original) ?: e?.orig ?: return null
        if (orig == want) return null
        if ((e?.match ?: 0) > 0 || heardKey(key, want)) return name(want)?.let { "$it ${if (isDub(e, want)) "Dub" else "Audio"}" }
        if (e == null || e.total == 0) return null
        return audioWords(audioLangs(e, orig, want), only = false).firstOrNull()
    }
}
