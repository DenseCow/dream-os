package com.firehome.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The title page's language pills (owner, 2026-10-29), for every language, movies, shows and anime alike. They show
 * only when the title's original language isn't the viewer's audio language (their own setting, per profile).
 *  - Their language available, green with the check: when any good copy (no CAM, playable on this stick) carries it, by
 *    its release name (DUB, DUAL AUDIO, ENG / ENGLISH as audio, "Kor-Eng", MULTI with ENG) or because Dream Player heard
 *    it in that copy or this title before ([heard]). "English Dub Available" when it's a dub (a name says dub, or English
 *    isn't among TMDB's spoken languages), else "English Audio Available" (a co-production's English may be original).
 *    Play picks that copy ([AnimeDubs.rank], Prefer Dubs).
 *  - Audio, amber: "Korean Audio Only", "Hindi and Tamil Audio" or "Multi Audio" when no good copy carries it.
 *  - Subtitles, beside the audio pill: "English Subtitles" with the check when subtitles in the viewer's subtitle
 *    language (else audio language) are confirmed, by a release name or Dream Subtitles' index; plain "No English
 *    Subtitles" only when the index answered without them; not confirmed either way, no subtitles pill.
 * Nothing shows until something is known: never a guess that later flips. The answers are kept in a small file
 * ([FILE], [KEEP] titles) with the availability checker's surveys feeding it, so a title page shows them on its first
 * frame ([saved]) and only refreshes behind them ([lookup]). Drawn by [Availability.Pill.language].
 */
object LangPills {
    private const val TAG = "FireHome"
    private const val FILE = "lang-pills.json"
    private const val KEEP = 400
    /** A saved answer shows this long; a title page asks again once it's older than [FRESH]. */
    private const val MAX_AGE = 7 * 24 * 3600_000L
    private const val FRESH = 6 * 3600_000L
    /** What Dream Player heard is kept longer: a copy's tracks don't change. */
    private const val HEARD_AGE = 60 * 24 * 3600_000L

    enum class Kind { AUDIO, DUB, SUBS, NO_SUBS }

    /** One pill: its words, longest first (a tight line takes the shorter form). */
    data class Pill(val kind: Kind, val labels: List<String>)

    // ---- what release names and the player say, kept ----

    /**
     * One title's (or episode's) good copies read for the viewer language [want]: [match] carry it ([dubSaid] of them
     * by a name that says dub), [unnamed] name no audio language (the original), [top] is the audio-language set named
     * most often ([topCount] copies), [subs] the subtitle languages named. [index]: Dream Subtitles' languages once asked;
     * [orig] / [spoken]: TMDB's original and spoken languages.
     */
    private class Entry(val want: String, val total: Int, val match: Int, val unnamed: Int, val top: Set<String>, val topCount: Int,
                        val subs: Set<String>, @Volatile var index: Set<String>?, @Volatile var orig: String?,
                        val dubSaid: Int = 0, @Volatile var spoken: Set<String>? = null,
                        val at: Long = System.currentTimeMillis())

    /** Audio languages Dream Player saw in a stream ([heard]), by title / episode key and by the copy's info hash. */
    private class Heard(val langs: Set<String>, val at: Long = System.currentTimeMillis())

    private val found = ConcurrentHashMap<String, Entry>()
    private val heardKeys = ConcurrentHashMap<String, Heard>()
    private val heardHashes = ConcurrentHashMap<String, Heard>()
    /** The viewer's subtitle languages as last read ([DreamSubtitles.viewerLanguages]), so a first frame needs no disk. */
    @Volatile private var viewerSubs: List<String>? = null
    @Volatile private var loaded = false
    @Volatile private var app: Context? = null
    private val saving = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** Subtitle words that name their language themselves. */
    private val SUB_LANG = mapOf("ESUB" to "en", "ESUBS" to "en", "ENGSUB" to "en", "ENGSUBS" to "en", "VOSTFR" to "fr", "VOSE" to "es")

    /** Reads the saved answers in the background (app start, a title page opening). */
    fun warm(ctx: Context) { if (!loaded) Net.run { load(ctx) } }

    @Synchronized private fun load(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.optJSONArray("subs")?.let { a -> viewerSubs = (0 until a.length()).map { a.getString(it) } }
                val a = o.optJSONArray("e") ?: JSONArray()
                val now = System.currentTimeMillis()
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    val at = e.optLong("at")
                    if (at < now - MAX_AGE) continue
                    fun set(k: String) = e.optJSONArray(k)?.let { s -> (0 until s.length()).map { s.getString(it) }.toSet() }
                    found.putIfAbsent(e.getString("k"), Entry(e.getString("w"), e.optInt("t"), e.optInt("m"), e.optInt("u"),
                        set("top").orEmpty(), e.optInt("tc"), set("s").orEmpty(), set("i"), e.optString("o").ifEmpty { null },
                        e.optInt("d"), set("sp"), at))
                }
                for ((name, map) in listOf("hk" to heardKeys, "hh" to heardHashes)) {
                    val h = o.optJSONObject(name) ?: continue
                    for (k in h.keys()) {
                        val x = h.optJSONObject(k) ?: continue
                        val l = x.optJSONArray("l") ?: continue
                        if (x.optLong("at") < now - HEARD_AGE) continue
                        map.putIfAbsent(k, Heard((0 until l.length()).map { l.getString(it) }.toSet(), x.optLong("at")))
                    }
                }
            }
        } catch (x: Exception) {
            Log.w(TAG, "Language pills: saved answers unreadable (${x.javaClass.simpleName})")
        }
        loaded = true
    }

    /** Writes the answers a few seconds after the last change (one write for a burst of surveys). */
    private fun save() {
        val ctx = app ?: return
        if (!saving.compareAndSet(false, true)) return
        main.postDelayed({
            Net.run {
                saving.set(false)
                try {
                    val now = System.currentTimeMillis()
                    val a = JSONArray()
                    for ((k, e) in found.entries.filter { it.value.at >= now - MAX_AGE }.sortedByDescending { it.value.at }.take(KEEP)) {
                        a.put(JSONObject().put("k", k).put("w", e.want).put("t", e.total).put("m", e.match).put("u", e.unnamed)
                            .put("top", JSONArray(e.top.toList())).put("tc", e.topCount).put("s", JSONArray(e.subs.toList()))
                            .put("i", e.index?.let { JSONArray(it.toList()) }).put("o", e.orig).put("at", e.at)
                            .put("d", e.dubSaid).put("sp", e.spoken?.let { JSONArray(it.toList()) }))
                    }
                    fun heard(m: Map<String, Heard>) = JSONObject().apply {
                        for ((k, h) in m.entries.filter { it.value.at >= now - HEARD_AGE }.sortedByDescending { it.value.at }.take(KEEP))
                            put(k, JSONObject().put("l", JSONArray(h.langs.toList())).put("at", h.at))
                    }
                    val o = JSONObject().put("e", a).put("subs", viewerSubs?.let { JSONArray(it) })
                        .put("hk", heard(heardKeys)).put("hh", heard(heardHashes))
                    val dir = ctx.filesDir
                    val tmp = File(dir, "$FILE.part")
                    tmp.writeText(o.toString())
                    if (!tmp.renameTo(File(dir, FILE))) tmp.delete()
                } catch (x: Exception) {
                    Log.w(TAG, "Language pills: couldn't save (${x.javaClass.simpleName})")
                }
            }
        }, 3_000)
    }

    private fun <T> trim(m: ConcurrentHashMap<String, T>, at: (T) -> Long) {
        if (m.size > KEEP + KEEP / 4) m.entries.sortedBy { at(it.value) }.take(m.size - KEEP).forEach { m.remove(it.key) }
    }

    /**
     * Dream Core read [copies] (good ones only: release name, info hash) for [key] ([AnimeDubs.record]: a survey, a
     * lookup, the source list). A copy carries the viewer's language when its name says so or Dream Player heard it.
     */
    fun record(ctx: Context, key: String, copies: List<Pair<String, String>>, want: String) {
        load(ctx)
        if (copies.isEmpty()) return
        var match = 0; var unnamed = 0; var dubSaid = 0
        val named = HashMap<Set<String>, Int>()
        val subs = HashSet<String>()
        for ((n, hash) in copies) {
            val t = AnimeDubs.tokens(n)
            fun subNear(i: Int) = t.getOrNull(i + 1) in AnimeDubs.SUBS || t.getOrNull(i - 1) in AnimeDubs.SUBS
            val langs = HashSet<String>()
            var multi = false
            for ((i, w) in t.withIndex()) {
                SUB_LANG[w]?.let { subs += it }
                if (w in AnimeDubs.SUB_GROUPS) subs += "en"
                if (w == "MULTI" || w == "MULTIAUDIO" || w == "MULTILANG") { if (!subNear(i)) multi = true; continue }
                val l = AudioLanguages.tagLanguage(w) ?: continue
                if (subNear(i)) subs += l else langs += l
            }
            val dual = "DUAL" in t || "DUALAUDIO" in t
            val saysDub = t.any { it in AnimeDubs.DUBS } || dual || "ENGDUB" in t || "ENGDUBBED" in t
            val byName = if (want == "ja") "ja" in langs else AnimeDubs.audio(n, want) == AnimeDubs.Audio.DUB
            when {
                byName || heardIn(hash, want) -> { match++; if (byName && saysDub) dubSaid++ }
                langs.isEmpty() && !multi && !dual && t.none { it in AnimeDubs.DUBS } -> unnamed++
                langs.isNotEmpty() -> named.merge(langs, 1, Int::plus)
            }
        }
        val top = named.entries.maxByOrNull { it.value }
        val was = found[key]?.takeIf { it.want == want }
        found[key] = Entry(want, copies.size, match, unnamed, top?.key.orEmpty(), top?.value ?: 0, subs, was?.index, was?.orig,
            dubSaid, was?.spoken)
        trim(found) { it.at }
        save()
    }

    /** Dream Player heard [lang] in the copy with info hash [hash] before. */
    fun heardIn(hash: String, lang: String): Boolean = heardHashes[hash.lowercase(Locale.US)]?.langs?.contains(lang) == true

    /**
     * Dream Player started [req] (its first frame): the audio languages its tracks carry are kept for the title / episode
     * and, when it's Dream Core's link ([url]), for that copy's info hash, so the next page open and ranking know them.
     */
    fun heard(ctx: Context, req: PlaybackRequest, url: String?, tracks: List<Track>) {
        if (req.trailer || req.tmdbId <= 0) return
        val langs = tracks.filter { it.supported }
            .mapNotNull { PlayerPrefs.normLang(it.language) ?: AudioLanguages.fromTrackName(it.label) }.map { plain(it) }.toSet()
        if (langs.isEmpty()) return
        val clean = url?.substringBefore('|')
        val hash = clean?.let { u -> NativeResolver.peek(req)?.takeIf { it.url == u }?.hash?.lowercase(Locale.US) }
        val key = AnimeDubs.key(req)
        Net.run {
            load(ctx.applicationContext)
            heardKeys[key] = Heard(langs + heardKeys[key]?.langs.orEmpty())
            hash?.let { heardHashes[it] = Heard(langs) }
            trim(heardKeys) { it.at }
            trim(heardHashes) { it.at }
            save()
            Log.w(TAG, "Language pills: heard ${langs.joinToString("/")} in ${req.title}${hash?.let { " (${it.take(8)})" } ?: ""}")
        }
    }

    private fun entry(key: String, want: String): Entry? =
        found[key]?.takeIf { it.want == want && System.currentTimeMillis() - it.at < MAX_AGE }

    private fun heardKey(key: String, want: String) = heardKeys[key]?.langs?.contains(want) == true

    // ---- the rule ----

    /** "French" for "fr", in English like the rest of Dream OS; null for a code with no known name. */
    fun name(code: String): String? = when (code) {
        "pob" -> "Brazilian Portuguese"
        "cn" -> "Cantonese"
        else -> (PlaybackPrefs.AUDIO.firstOrNull { it.first == code }?.second ?: Locale(code).getDisplayLanguage(Locale.ENGLISH))
            .takeIf { it.isNotBlank() && !it.equals(code, true) }?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
    }

    private fun usable(code: String?) = PlayerPrefs.normLang(code)?.takeIf { name(it) != null }

    /** "pob" plays as Portuguese. */
    private fun plain(code: String) = if (code == "pob") "pt" else code

    /** The audio languages the copies carry when none carries the viewer's: the set named most, else the original. */
    private fun audioLangs(e: Entry, orig: String, want: String): List<String> {
        val langs = if (e.topCount > e.unnamed && e.top.isNotEmpty()) e.top - want else setOf(orig)
        return langs.filter { name(it) != null }.sortedWith(compareBy({ it != orig }, { name(it) }))
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

    /** The subtitles language: the viewer's first one not already playing, else their audio language. */
    private fun subLanguage(prefs: List<String>, langs: List<String>, want: String) = prefs.firstOrNull { plain(it) !in langs } ?: want

    /** A dub, not the original: a name says dub, or the viewer's language isn't among TMDB's spoken languages. */
    private fun isDub(e: Entry?, want: String) = (e?.dubSaid ?: 0) > 0 || e?.spoken?.let { want !in it } == true

    /** [e] may be null when only Dream Player's ears know (the title played before, its copies not read since). */
    private fun verdict(key: String, e: Entry?, orig: String, want: String): List<Pill> {
        val mine = name(want) ?: return emptyList()
        if ((e?.match ?: 0) > 0 || heardKey(key, want)) {
            val what = if (isDub(e, want)) "Dub" else "Audio"
            return listOf(Pill(Kind.DUB, listOf("$mine $what Available", "$mine $what")))
        }
        if (e == null || e.total == 0) return emptyList() // no copies read: no guess at their audio
        val langs = audioLangs(e, orig, want)
        val audio = Pill(Kind.AUDIO, audioWords(langs, only = true).ifEmpty { return emptyList() })
        val prefs = viewerSubs ?: return listOf(audio)
        val sub = subLanguage(prefs, langs, want)
        val n = name(sub) ?: return listOf(audio)
        val subsPill = when {
            plain(sub) in e.subs || e.index?.contains(sub) == true -> Pill(Kind.SUBS, listOf("$n Subtitles"))
            e.index != null -> Pill(Kind.NO_SUBS, listOf("No $n Subtitles"))
            else -> null
        }
        return listOfNotNull(audio, subsPill)
    }

    /**
     * The saved answer for [req], main thread safe (no network, no disk): empty when the title is in the viewer's own
     * language, null when nothing is known yet (show nothing; [lookup] answers).
     */
    fun saved(ctx: Context, req: PlaybackRequest, original: String?): List<Pill>? {
        if (!loaded) { warm(ctx); return null }
        val want = AnimeDubs.want(ctx)
        val key = AnimeDubs.key(req)
        val e = entry(key, want)
        val orig = usable(original) ?: e?.orig ?: return null
        if (orig == want) return emptyList()
        if (e == null && !heardKey(key, want)) return null
        return verdict(key, e, orig, want)
    }

    /**
     * Blocking. [req]'s pills (what Play plays), refreshed: the scrapers' quick check ([NativeResolver.survey], which
     * records the names) when the saved answer is missing or older than [FRESH] and [scrape] allows it, then Dream
     * Subtitles' index when the subtitles pill needs it. Empty when nothing is known.
     */
    fun lookup(ctx: Context, req: PlaybackRequest, original: String?, scrape: Boolean): List<Pill> {
        val app = ctx.applicationContext
        load(app)
        val want = AnimeDubs.want(app)
        val orig = usable(original) ?: AnimeDubs.originalLanguage(app, req)?.let { usable(it) } ?: return emptyList()
        if (orig == want) return emptyList()
        val key = AnimeDubs.key(req)
        var e = entry(key, want)
        if (scrape && (e == null || System.currentTimeMillis() - e.at > FRESH)) {
            NativeResolver.survey(app, req)
            e = entry(key, want) ?: e
        }
        if (e == null && !heardKey(key, want)) return emptyList()
        // Only Dream Player's ears know (no copies read): an empty entry keeps the languages for the first frame.
        val known = e ?: Entry(want, 0, 0, 0, emptySet(), 0, emptySet(), null, orig).also { found[key] = it }
        known.orig = orig
        AnimeDubs.spokenLanguages(app, req)?.let { known.spoken = it }
        viewerSubs = DreamSubtitles.viewerLanguages(app)
        if (known.total > 0 && known.match == 0 && !heardKey(key, want)) {
            val sub = subLanguage(viewerSubs.orEmpty(), audioLangs(known, orig, want), want)
            if (plain(sub) !in known.subs) DreamSubtitles.indexLanguages(app, req)?.let { known.index = it }
        }
        save()
        val pills = verdict(key, known, orig, want)
        Log.w(TAG, "Language pills: ${req.title}: ${pills.joinToString { it.labels.first() }.ifEmpty { "none" }}" +
            " (viewer $want, original $orig, ${known.match} of ${known.total} good copies carry $want, heard ${heardKeys[key]?.langs.orEmpty()})")
        return pills
    }

    /**
     * An episode row's compact words ("English Dub", "English Audio", "Japanese Audio"), the same rule, once that
     * episode's copies were read or it played; null otherwise. Main thread safe.
     */
    fun episodeWords(ctx: Context, req: PlaybackRequest, original: String?): String? {
        val want = AnimeDubs.want(ctx)
        val key = AnimeDubs.key(req)
        val e = entry(key, want)
        if (e == null && !heardKey(key, want)) return null
        val orig = usable(original) ?: e?.orig ?: return null
        if (orig == want) return null
        if ((e?.match ?: 0) > 0 || heardKey(key, want)) return name(want)?.let { "$it ${if (isDub(e, want)) "Dub" else "Audio"}" }
        return e?.takeIf { it.total > 0 }?.let { audioWords(audioLangs(it, orig, want), only = false).firstOrNull() }
    }

    /**
     * The title page's status band under the actions: the availability status ([Availability.TitleStatus]) and the
     * language pills, left to right; a pill that doesn't fit starts a second line, whole. No height while all are gone.
     */
    class Band(ctx: Context) : ViewGroup(ctx) {
        private val gapX = Ui.dp(ctx, 12)
        private val gapY = Ui.dp(ctx, 10)

        init {
            clipChildren = false
            clipToPadding = false
            setPadding(0, Ui.dp(ctx, 14), 0, 0)
        }

        /** Each shown child's line, from the last measure. */
        private val lineOf = IntArray(8)
        private val lineH = IntArray(8)

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val max = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            var x = 0; var line = 0; var any = false
            lineH.fill(0)
            for (i in 0 until minOf(childCount, lineOf.size)) {
                val v = getChildAt(i)
                lineOf[i] = -1
                if (v.visibility == View.GONE) continue
                v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (v.measuredWidth == 0) continue
                if (x > 0 && x + v.measuredWidth > max) { line++; x = 0 }
                lineOf[i] = line
                lineH[line] = maxOf(lineH[line], v.measuredHeight)
                x += v.measuredWidth + gapX
                any = true
            }
            val h = if (!any) 0 else paddingTop + paddingBottom + (0..line).sumOf { lineH[it] } + line * gapY
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var x = paddingLeft; var y = paddingTop; var line = 0
            for (i in 0 until minOf(childCount, lineOf.size)) {
                val v = getChildAt(i)
                val at = lineOf[i]
                if (at < 0 || v.visibility == View.GONE) continue
                while (line < at) { y += lineH[line] + gapY; line++; x = paddingLeft }
                val top = y + (lineH[line] - v.measuredHeight) / 2
                v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
                x += v.measuredWidth + gapX
            }
        }
    }
}
