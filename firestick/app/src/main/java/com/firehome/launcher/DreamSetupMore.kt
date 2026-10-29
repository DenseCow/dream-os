package com.firehome.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * More of Dream OS's own setup pages, shared by the first-run setup ([SetupWizard]) and Settings:
 *
 *  - [PlaybackPrefs] / [PlaybackPages]: Playback Preferences in setup and Settings › Sources & Quality
 *    (Maximum Quality, Hide AV1, audio and subtitle languages, Dolby audio to a soundbar, Source Size
 *    Limits). Source filters live in [DreamFilters] ("quality.max", "size.movie.*", "size.episode.*",
 *    "subs.*", "hide.av1"), where the resolver reads them.
 *  - [SpeedTest] / [SpeedCard]: a short download test on the final check ("85 Mbps · Great for 4K").
 *  - [ProfilesPages]: Who's Watching (more profiles, Kids profiles with a maximum rating, a PIN to leave).
 *  - [SetupIntro]: Before You Start's "what goes where" list.
 *  - [DreamPanelPage]: any of these pages as a full Settings page.
 */
object PlaybackPrefs {
    private const val TAG = "FireHome"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dream_playback", Context.MODE_PRIVATE)

    /** Dolby / DTS sent untouched to a soundbar or receiver (on), or decoded by the TV itself (off). Read by [ExoEngine]. */
    fun passthrough(ctx: Context) = prefs(ctx).getBoolean("passthrough", true)
    fun setPassthrough(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("passthrough", on).apply()
        Log.w(TAG, "Setup: Dolby audio to a soundbar ${if (on) "on" else "off"}")
    }

    /** The last speed test (Mbps), 0 when none ran yet. */
    fun lastSpeed(ctx: Context) = prefs(ctx).getFloat("speed_mbps", 0f)
    fun setLastSpeed(ctx: Context, mbps: Float) = prefs(ctx).edit().putFloat("speed_mbps", mbps).putLong("speed_at", System.currentTimeMillis()).apply()

    /** The TV can show 4K (one of its display modes is 3840 wide). */
    fun screenIs4k(ctx: Context): Boolean = try {
        @Suppress("DEPRECATION")
        val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        d.supportedModes.any { maxOf(it.physicalWidth, it.physicalHeight) >= 3840 }
    } catch (e: Exception) { false }

    val QUALITIES = listOf("4k" to "4K", "1080p" to "1080p", "720p" to "720p")

    fun defaultQuality(ctx: Context) = if (DeviceVideo.plays4k(ctx)) "4k" else "1080p"

    /** The Maximum Quality choices this stick and TV can play. */
    fun qualities(ctx: Context) = if (DeviceVideo.plays4k(ctx)) QUALITIES else QUALITIES.filter { it.first != "4k" }

    /**
     * Maximum Quality: DreamFilters "quality.max" ("4k", "1080p", "720p", "sd"); the screen's best until chosen.
     * A 4K choice on a stick that can't play 4K reads as 1080p, which is what the resolver applies.
     */
    fun quality(ctx: Context): String {
        val q = DreamFilters.stored(ctx)["quality.max"]?.takeIf { it.isNotEmpty() } ?: defaultQuality(ctx)
        return if (q == "4k" && !DeviceVideo.plays4k(ctx)) "1080p" else q
    }

    fun qualityLabel(q: String) = QUALITIES.firstOrNull { it.first == q }?.second ?: if (q == "sd") "SD" else q

    fun setQuality(ctx: Context, q: String) {
        DreamFilters.put(ctx, "quality.max", q)
        Log.w(TAG, "Setup: Maximum Quality $q")
    }

    /** Saves the good defaults once (Maximum Quality from the screen), so the resolver sees a real choice. */
    fun ensureDefaults(ctx: Context) {
        if (DreamFilters.stored(ctx)["quality.max"].isNullOrEmpty()) setQuality(ctx, defaultQuality(ctx))
    }

    // ---- languages ----

    /** Audio languages offered (code, name). */
    val AUDIO = listOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
        "nl" to "Dutch", "pl" to "Polish", "ru" to "Russian", "uk" to "Ukrainian", "tr" to "Turkish", "ar" to "Arabic", "he" to "Hebrew",
        "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian",
        "fi" to "Finnish", "el" to "Greek", "cs" to "Czech", "hu" to "Hungarian", "ro" to "Romanian")

    /** Subtitle languages as Dream OS's subtitle search names them (English names). */
    val SUBS = AUDIO.map { it.second }.let { it.take(6) + "Portuguese(Brazil)" + it.drop(6) }

    fun subLabel(name: String) = if (name.replace(" ", "").equals("Portuguese(Brazil)", true)) "Portuguese (Brazil)" else name

    fun audioLang(ctx: Context): String = PlayerPrefs.audioLang(ctx) ?: Locale.getDefault().language.ifEmpty { "en" }
    fun audioLabel(ctx: Context) = audioLang(ctx).let { c -> AUDIO.firstOrNull { it.first == c }?.second ?: PlayerPrefs.displayLang(c) }
    fun setAudioLang(ctx: Context, code: String) { PlayerPrefs.setAudioLang(ctx, code); Log.w(TAG, "Setup: audio language $code") }

    fun subLang(ctx: Context, second: Boolean = false): String? =
        DreamFilters.stored(ctx)[if (second) "subs.lang2" else "subs.lang1"]?.takeIf { it.isNotBlank() && !it.equals("none", true) }
            ?: if (second) null else "English"

    fun setSubLang(ctx: Context, name: String, second: Boolean = false) {
        DreamFilters.put(ctx, if (second) "subs.lang2" else "subs.lang1", name)
        Log.w(TAG, "Setup: subtitle language ${if (second) "2" else "1"} $name")
    }

    /** Subtitles show when something starts: the setting on, and not turned off in the player. */
    fun subsOn(ctx: Context) = DreamFilters.stored(ctx)["subs.enabled"] == "true" && PlayerPrefs.subLang(ctx) != PlayerPrefs.OFF

    fun setSubsOn(ctx: Context, on: Boolean) {
        DreamFilters.put(ctx, "subs.enabled", on.toString())
        val player = PlayerPrefs.subLang(ctx)
        // The player's own choice wins over the setting, so it follows the switch.
        if (on && player == PlayerPrefs.OFF) {
            val code = Locale.getISOLanguages().firstOrNull { Locale(it).getDisplayLanguage(Locale.ENGLISH).equals(subLang(ctx), true) } ?: "en"
            PlayerPrefs.setSubLang(ctx, code)
        } else if (!on && player != null && player != PlayerPrefs.OFF) PlayerPrefs.setSubLang(ctx, PlayerPrefs.OFF)
        Log.w(TAG, "Setup: subtitles ${if (on) "on" else "off"}")
    }

    fun hideAv1(ctx: Context) = DreamFilters.stored(ctx)["hide.av1"].let { if (it.isNullOrEmpty()) true else it == "true" }
    fun setHideAv1(ctx: Context, on: Boolean) = DreamFilters.put(ctx, "hide.av1", on.toString())

    // ---- source size limits (DreamFilters "size.movie.*" / "size.episode.*") ----

    val MODES = listOf("off" to "No Limit", "range" to "Size Range", "speed" to "Match My Connection")
    val MIN_GB = listOf("0", "0.5", "1", "1.5", "2", "3", "4", "5", "8", "10").map { it to if (it == "0") "None" else "$it GB" }
    val MAX_GB = listOf("1", "1.5", "2", "3", "4", "5", "6", "8", "10", "15", "20", "25", "30", "40", "50", "60", "80", "100").map { it to "$it GB" }
    val MBPS = listOf("5", "10", "15", "20", "25", "30", "40", "50", "75", "100", "150", "200").map { it to "$it Mbps" }

    private fun p(ep: Boolean) = if (ep) "size.episode" else "size.movie"
    fun sizeValue(ctx: Context, ep: Boolean, part: String): String? = DreamFilters.stored(ctx)["${p(ep)}.$part"]?.takeIf { it.isNotEmpty() }
    fun sizeMode(ctx: Context, ep: Boolean) = sizeValue(ctx, ep, "limit")?.takeIf { m -> MODES.any { it.first == m } } ?: "off"
    fun minGb(ctx: Context, ep: Boolean) = sizeValue(ctx, ep, "min_gb") ?: "0"
    fun maxGb(ctx: Context, ep: Boolean) = sizeValue(ctx, ep, "max_gb") ?: if (ep) "5" else "20"
    fun speedMbps(ctx: Context, ep: Boolean) = sizeValue(ctx, ep, "speed_mbps") ?: sizeValue(ctx, false, "speed_mbps") ?: suggestedMbps(ctx)

    /** The speed list's step at or under the last speed test (or 25 Mbps without one). */
    fun suggestedMbps(ctx: Context): String {
        val s = lastSpeed(ctx)
        if (s <= 0f) return "25"
        return MBPS.lastOrNull { it.first.toFloat() <= s * 0.9f }?.first ?: MBPS.first().first
    }

    fun setSize(ctx: Context, ep: Boolean, part: String, value: String) {
        val e = DreamFilters
        e.put(ctx, "${p(ep)}.$part", value)
        // A range always stays a range: the smallest never above the largest.
        val min = minGb(ctx, ep).toDoubleOrNull() ?: 0.0
        val max = maxGb(ctx, ep).toDoubleOrNull() ?: 0.0
        if (part == "min_gb" && min > max) MAX_GB.firstOrNull { it.first.toDouble() >= min }?.let { e.put(ctx, "${p(ep)}.max_gb", it.first) }
        if (part == "max_gb" && max < min) MIN_GB.lastOrNull { it.first.toDouble() <= max }?.let { e.put(ctx, "${p(ep)}.min_gb", it.first) }
        if (part == "limit") {
            // A mode's values are saved when it's chosen, so the resolver never reads a half-made limit.
            if (value == "range") { e.put(ctx, "${p(ep)}.min_gb", minGb(ctx, ep)); e.put(ctx, "${p(ep)}.max_gb", maxGb(ctx, ep)) }
            if (value == "speed") e.put(ctx, "${p(ep)}.speed_mbps", speedMbps(ctx, ep))
        }
        Log.w(TAG, "Setup: size limit ${p(ep)}.$part = $value")
    }

    fun gb(v: String) = (MIN_GB + MAX_GB).firstOrNull { it.first == v }?.second ?: "$v GB"

    fun sizeSummary(ctx: Context, ep: Boolean): String = when (sizeMode(ctx, ep)) {
        "range" -> "${minGb(ctx, ep).let { if (it == "0") "Up to" else gb(it) + " to" }} ${gb(maxGb(ctx, ep))}"
        "speed" -> "Up to ${speedMbps(ctx, ep)} Mbps"
        else -> "No Limit"
    }

    /** The Settings row's value: "4K · No Size Limit". */
    fun summary(ctx: Context): String {
        val m = sizeMode(ctx, false)
        val e = sizeMode(ctx, true)
        return qualityLabel(quality(ctx)) + if (m == "off" && e == "off") " · No Size Limit" else " · Size Limits On"
    }
}

// =====================================================================================
// Playback Preferences / Settings › Sources & Quality
// =====================================================================================

internal class PlaybackPages(private val ctx: Context, private val ui: SetupUi, private val panel: SetupPanel) {
    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    private fun app() = ctx.applicationContext
    private val P = PlaybackPrefs

    /**
     * Setup's Change Settings: four rows (Picture, Sound, Subtitles, Source Size), each opening a short page of its
     * own, so no page has to scroll on the TV. Settings shows the whole list at once ([overview]).
     */
    fun hub(): SetupScreen {
        P.ensureDefaults(app())
        val s = SetupScreen(ui, "Playback Settings", null)
        val rows = ScreenRows(s)
        fun paint() {
            rows.row("pic", 0) { parts.row(null, chevron = true) { panel.push(overview("pic")) } }
                .set("Picture", "Maximum quality and AV1 videos", P.qualityLabel(P.quality(app())))
            rows.row("sound", 8) { parts.row(null, chevron = true) { panel.push(overview("sound")) } }
                .set("Sound", "Audio language, dubs and Dolby audio", P.audioLabel(app()))
            rows.row("subs", 8) { parts.row(null, chevron = true) { panel.push(overview("subs")) } }
                .set("Subtitles", "When they turn on and which language", if (P.subsOn(app())) P.subLabel(P.subLang(app()) ?: "English") else "Off")
            rows.row("size", 8) { parts.row(null, chevron = true) { panel.push(sizes()) } }
                .set("Source Size", "Skip files that are too large or too small")
        }
        s.onShow = { paint() }
        s.focus = { rows.get("pic") }
        return s
    }

    /** The playback settings: every group ([only] null, Settings), or one group as its own page ("pic", "sound", "subs"). */
    fun overview(only: String? = null): SetupScreen {
        P.ensureDefaults(app())
        val s = SetupScreen(ui, when (only) { "pic" -> "Picture"; "sound" -> "Sound"; "subs" -> "Subtitles"; else -> null }, null)
        fun on(group: String) = only == null || only == group
        val all = only == null
        val rows = ScreenRows(s)
        lateinit var paint: () -> Unit
        fun switchRow(key: String, top: Int, value: () -> Boolean, flip: () -> Unit): BrandRow = rows.row(key, top) {
            lateinit var r: BrandRow
            r = parts.row(null, switch = value()) { flip(); r.sw?.set(value()); paint() }
            r
        }.also { it.sw?.set(value()) }
        paint = {
            if (all) s.view("k:pic", 0) { parts.section("Picture", null) }
            if (on("pic")) rows.row("quality", if (all) 10 else 0) { parts.row(null, chevron = true) { panel.push(qualityChooser()) } }
                .set("Maximum Quality", if (DeviceVideo.plays4k(app())) "Your Fire Stick and TV can play 4K" else "Your Fire Stick and TV play up to 1080p", P.qualityLabel(P.quality(app())))
            if (on("pic")) switchRow("av1", 8, { P.hideAv1(app()) }) { P.setHideAv1(app(), !P.hideAv1(app())) }
                .set("Hide AV1 Videos", "Some AV1 videos can restart this Fire Stick")
            if (all) s.view("k:sound", 24) { parts.section("Sound", null) }
            if (on("sound")) rows.row("audio", if (all) 10 else 0) { parts.row(null, chevron = true) { panel.push(audioChooser()) } }
                .set("Audio Language", "Preferred when a video has several", P.audioLabel(app()))
            // AnimeDubs.kt: a title in another language plays the viewer's dub when a copy has it. Nothing to dub when that language is Japanese.
            if (!on("sound") || AnimeDubs.want(app()) == "ja") s.hide("dubs")
            else switchRow("dubs", 8, { AnimeDubs.preferDubs(app()) }) { AnimeDubs.setPreferDubs(app(), !AnimeDubs.preferDubs(app())) }
                .set(AnimeDubs.settingLabel(app()), "Plays the ${AnimeDubs.langName(AnimeDubs.want(app()))} dub when a copy has one.")
            if (on("sound")) switchRow("dolby", 8, { P.passthrough(app()) }) { P.setPassthrough(app(), !P.passthrough(app())) }
                .set("Dolby Audio to Your Soundbar", "Sends Dolby and DTS sound untouched to your soundbar or receiver")
            if (all) s.view("k:subs", 24) { parts.section("Subtitles", null) }
            if (on("subs")) {
                switchRow("subs", if (all) 10 else 0, { P.subsOn(app()) }) { P.setSubsOn(app(), !P.subsOn(app())) }
                    .set("Subtitles", "Turns subtitles on when a movie or show starts")
                rows.row("sublang", 8) { parts.row(null, chevron = true) { panel.push(subChooser(false)) } }
                    .set("Subtitle Language", null, P.subLabel(P.subLang(app()) ?: "English"))
                rows.row("sublang2", 8) { parts.row(null, chevron = true) { panel.push(subChooser(true)) } }
                    .set("Second Choice", "Used when the first isn't available", P.subLang(app(), true)?.let { P.subLabel(it) } ?: "None")
            }
            if (all) {
                s.view("k:size", 24) { parts.section("Source Size", null) }
                rows.row("size", 10) { parts.row(null, chevron = true) { panel.push(sizes()) } }
                    .set("Source Size Limits", "Movies: ${P.sizeSummary(app(), false)} · Episodes: ${P.sizeSummary(app(), true)}")
            }
        }
        s.onShow = { paint() }
        s.onResume = { paint() }
        s.focus = { rows.get("quality") ?: rows.get("audio") ?: rows.get("subs") }
        return s
    }

    private fun qualityChooser(): SetupScreen {
        // 4K is only offered when this stick and TV can play it (the Fire TV Stick HD can't).
        return choose("Maximum Quality", "Dream OS plays the best video up to this quality. Choose a lower one if videos pause to load.",
            listOfNotNull(
                Triple("4k", "4K", "Sharpest picture · needs about 25 Mbps").takeIf { DeviceVideo.plays4k(app()) },
                Triple("1080p", "1080p", "Full HD · needs about 8 Mbps"),
                Triple("720p", "720p", "HD · best for slower connections"),
            ), { P.quality(app()) }) { P.setQuality(app(), it) }
    }

    private fun audioChooser() = choose("Audio Language", null,
        P.AUDIO.map { Triple(it.first, it.second, null) }, { P.audioLang(app()) }) { P.setAudioLang(app(), it) }

    private fun subChooser(second: Boolean) = choose(if (second) "Second Subtitle Language" else "Subtitle Language", null,
        (if (second) listOf(Triple("None", "None", null as String?)) else emptyList()) + P.SUBS.map { Triple(it, P.subLabel(it), null as String?) },
        { P.subLang(app(), second) ?: "None" }) { P.setSubLang(app(), it, second) }

    private fun sizes(): SetupScreen {
        val s = SetupScreen(ui, "Source Size Limits", null)
        val rows = ScreenRows(s)
        s.view("n", 0) { parts.note("Skip files that are too large or too small. Match My Connection skips files too large to play smoothly on your connection.", 14f) }
        lateinit var paint: () -> Unit
        paint = {
            for (ep in listOf(false, true)) {
                val tag = if (ep) "e" else "m"
                s.view("k:$tag", if (ep) 24 else 18) { parts.section(if (ep) "Episodes" else "Movies", null) }
                val mode = P.sizeMode(app(), ep)
                rows.row("$tag:mode", 10) { parts.row(null, chevron = true) {
                    panel.push(choose(if (ep) "Episode Size Limit" else "Movie Size Limit", null, P.MODES.map { m ->
                        Triple(m.first, m.second, when (m.first) {
                            "range" -> "Set the smallest and largest file"
                            "speed" -> "Skips files too large for your connection"
                            else -> "Any file size"
                        })
                    }, { P.sizeMode(app(), ep) }) { P.setSize(app(), ep, "limit", it) })
                } }.set("Limit", null, P.MODES.first { it.first == mode }.second)
                if (mode == "range") {
                    rows.row("$tag:min", 8) { parts.row(null, chevron = true) {
                        panel.push(choose("Smallest File", null, P.MIN_GB.map { Triple(it.first, it.second, null as String?) }, { P.minGb(app(), ep) }) { P.setSize(app(), ep, "min_gb", it) })
                    } }.set("Smallest File", null, P.gb(P.minGb(app(), ep)).let { if (it == "0 GB") "None" else it })
                    rows.row("$tag:max", 8) { parts.row(null, chevron = true) {
                        panel.push(choose("Largest File", null, P.MAX_GB.map { Triple(it.first, it.second, null as String?) }, { P.maxGb(app(), ep) }) { P.setSize(app(), ep, "max_gb", it) })
                    } }.set("Largest File", null, P.gb(P.maxGb(app(), ep)))
                } else { s.hide("$tag:min"); s.hide("$tag:max") }
                if (mode == "speed") {
                    val measured = P.lastSpeed(app())
                    rows.row("$tag:speed", 8) { parts.row(null, chevron = true) {
                        panel.push(choose("Your Internet Speed", if (measured > 0f) "Your last speed test: ${measured.toInt()} Mbps." else null,
                            P.MBPS.map { Triple(it.first, it.second, null as String?) }, { P.speedMbps(app(), ep) }) { P.setSize(app(), ep, "speed_mbps", it) })
                    } }.set("Your Internet Speed", if (measured > 0f) "Last speed test: ${measured.toInt()} Mbps" else null, "${P.speedMbps(app(), ep)} Mbps")
                } else s.hide("$tag:speed")
            }
        }
        s.onShow = { paint() }
        s.focus = { rows.get("m:mode") }
        return s
    }

    /** A list to pick one from: the chosen one checked; picking saves at once and goes back. */
    private fun choose(title: String, note: String?, options: List<Triple<String, String, String?>>, current: () -> String, pick: (String) -> Unit): SetupScreen =
        SetupChoices.screen(ui, panel, parts, title, note, options, current, pick)
}

/** A setup list to pick one option from (shared by the pages in this file). */
internal object SetupChoices {
    fun screen(ui: SetupUi, panel: SetupPanel, parts: SetupParts, title: String, note: String?, options: List<Triple<String, String, String?>>,
               current: () -> String, pick: (String) -> Unit): SetupScreen {
        val s = SetupScreen(ui, title, null)
        if (note != null) s.add(parts.note(note, 14f), 0)
        val rows = ScreenRows(s)
        var leaving = false
        lateinit var paint: () -> Unit
        paint = {
            val now = current()
            for ((i, o) in options.withIndex()) {
                rows.row("o:${o.first}", if (i == 0) (if (note != null) 16 else 0) else 6) {
                    parts.row(null) {
                        if (leaving) return@row
                        leaving = true
                        try { pick(o.first) } catch (e: Exception) { Log.w(SetupBrand.TAG, "Setup: choice $title failed: $e") }
                        paint()
                        ui.main.postDelayed({ if (s.alive) panel.pop() }, 220)
                    }
                }.set(o.second, o.third, mark = if (o.first == now) BrandRow.Mark.PICK else BrandRow.Mark.OFF) // a choice: neutral pick, the rest a ring (aligned)
            }
        }
        s.onShow = { leaving = false; paint() }
        s.focus = { rows.get("o:${current()}") ?: SetupScreen.firstFocusable(s.body) }
        return s
    }
}

// =====================================================================================
// Speed test (the final check)
// =====================================================================================

object SpeedTest {
    private const val TAG = "FireHome"
    /** A neutral HTTPS speed-test file (Cloudflare's own speed test); nothing about the viewer is sent. */
    private const val URL_DOWN = "https://speed.cloudflare.com/__down?bytes=40000000"
    private const val STREAMS = 3

    /**
     * Downloads over [STREAMS] connections for about [seconds] (after a short warm-up) and returns the
     * speed in Mbps, or null when it couldn't run. [live] gets the running speed (a background thread).
     * [cancel] stops it early. Blocking: call off the main thread.
     */
    fun measure(seconds: Float = 4f, cancel: AtomicBoolean = AtomicBoolean(false), live: (Float) -> Unit = {}): Float? {
        val total = AtomicLong(0)
        val running = AtomicInteger(STREAMS)
        val first = AtomicLong(0)
        val threads = (0 until STREAMS).map {
            Thread {
                var c: HttpURLConnection? = null
                try {
                    c = (URL(URL_DOWN).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000; readTimeout = 5000; useCaches = false
                        setRequestProperty("Accept-Encoding", "identity")
                    }
                    c.inputStream.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (!cancel.get()) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            first.compareAndSet(0, SystemClock.elapsedRealtime())
                            total.addAndGet(n.toLong())
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    try { c?.disconnect() } catch (e: Exception) {}
                    running.decrementAndGet()
                }
            }.apply { isDaemon = true; name = "speed-test"; start() }
        }
        try {
            // Wait for the first byte (at most 6 s), then a short warm-up so connection setup doesn't count.
            val waitUntil = SystemClock.elapsedRealtime() + 6000
            while (first.get() == 0L && running.get() > 0 && !cancel.get() && SystemClock.elapsedRealtime() < waitUntil) Thread.sleep(50)
            if (first.get() == 0L) { Log.w(TAG, "Setup: speed test couldn't reach the test server"); return null }
            Thread.sleep(600)
            val t0 = SystemClock.elapsedRealtime()
            val b0 = total.get()
            val end = t0 + (seconds * 1000).toLong()
            var mbps = 0f
            while (!cancel.get() && running.get() > 0 && SystemClock.elapsedRealtime() < end) {
                Thread.sleep(250)
                val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
                mbps = ((total.get() - b0) * 8f / dt / 1000f)
                live(mbps)
            }
            val dt = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
            mbps = ((total.get() - b0) * 8f / dt / 1000f)
            if (cancel.get() || dt < 500) return null
            Log.w(TAG, "Setup: speed test ${"%.1f".format(Locale.US, mbps)} Mbps (${total.get() / 1_000_000} MB in ${dt + 600} ms)")
            return mbps
        } finally {
            cancel.set(true)
            for (t in threads) t.interrupt()
        }
    }

    /** What the speed is good for, in plain words. */
    fun verdict(mbps: Float): String = when {
        mbps >= 40f -> "Great for 4K"
        mbps >= 25f -> "Good for 4K"
        mbps >= 8f -> "Great for 1080p"
        mbps >= 4f -> "Good for 720p"
        else -> "Slower Connection"
    }

    /** A lower Maximum Quality that suits [mbps] when [quality] is too much for it, else null. */
    fun suggestion(quality: String, mbps: Float): String? = when {
        quality == "4k" && mbps < 25f -> if (mbps >= 8f) "1080p" else "720p"
        quality == "1080p" && mbps < 8f -> "720p"
        else -> null
    }
}

/**
 * The final check's connection card: the gauge, "Your Connection · 85 Mbps · Great for 4K", and a
 * one-press "Use 1080p" when the speed is too slow for the chosen Maximum Quality. [run] starts it;
 * [release] stops a test still running. [settled] runs after a suggestion was taken (focus moves on).
 */
internal class SpeedCard(ctx: Context, private val k: PremiumKit, private val settled: () -> Unit) : LinearLayout(ctx) {
    private val Int.dp get() = Ui.dp(context, this)
    private val art = SetupArt(ctx, SetupArt.Kind.SPEED)
    private val head = k.label("Your Connection", 17f, SetupBrand.TEXT, k.semibold, lines = 0)
    private val line = k.label("", 14f, SetupBrand.DIM, k.font, lines = 0).apply { setLineSpacing(3.dp.toFloat(), 1f) }
    private val use: TextView
    private var cancel: AtomicBoolean? = null
    private var anim: ValueAnimator? = null
    /** The test finished: true when it offers a lower quality to choose, false when nothing needs choosing. */
    var onResult: ((Boolean) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 4.dp, 4.dp, 4.dp)
        addView(art, LayoutParams(64.dp, 64.dp))
        val col = LinearLayout(ctx).apply { orientation = VERTICAL }
        col.addView(head)
        col.addView(line, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 4.dp })
        addView(col, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 16.dp })
        use = k.pill("Use 1080p", null, false, 40, dream = true) {}.apply { setPadding(22.dp, 0, 22.dp, 0); visibility = GONE }
        addView(use, LayoutParams(WRAP_CONTENT, 40.dp).apply { marginStart = 14.dp })
        visibility = GONE
    }

    private fun gaugeTo(mbps: Float, ms: Long) {
        // Log scale: 1 Mbps near the start, 200 Mbps at the end.
        val to = (Math.log(1.0 + mbps) / Math.log(201.0)).toFloat().coerceIn(0f, 1f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(art.gauge, to).apply {
            duration = ms
            interpolator = Ui.EASE
            addUpdateListener { art.gauge = it.animatedValue as Float }
            start()
        }
    }

    fun run() {
        if (cancel != null) return
        val c = AtomicBoolean(false)
        cancel = c
        visibility = VISIBLE
        head.text = "Your Connection"
        line.text = "Testing your connection…"
        use.visibility = GONE
        val app = context.applicationContext
        Net.run {
            val mbps = SpeedTest.measure(4f, c) { v -> post { if (!c.get()) { gaugeTo(v, 240); line.text = "Testing…  ${v.toInt()} Mbps" } } }
            post {
                if (cancel !== c) return@post
                if (mbps == null) {
                    if (!c.get() || isAttachedToWindow) { gaugeTo(0f, 400); line.text = "The speed test couldn't run right now. Everything else is ready." }
                    onResult?.invoke(false)
                    return@post
                }
                PlaybackPrefs.setLastSpeed(app, mbps)
                gaugeTo(mbps, 600)
                head.text = "Your Connection  ·  ${mbps.toInt()} Mbps  ·  ${SpeedTest.verdict(mbps)}"
                val q = PlaybackPrefs.quality(app)
                val better = SpeedTest.suggestion(q, mbps)
                if (better == null) {
                    line.text = "Fast enough for ${PlaybackPrefs.qualityLabel(q)}, your Maximum Quality."
                } else {
                    line.text = "Too slow for smooth ${PlaybackPrefs.qualityLabel(q)}. ${PlaybackPrefs.qualityLabel(better)} will play more smoothly."
                    use.text = "Use ${PlaybackPrefs.qualityLabel(better)}"
                    use.setOnClickListener {
                        PlaybackPrefs.setQuality(app, better)
                        line.text = "Maximum Quality is now ${PlaybackPrefs.qualityLabel(better)}. Change it anytime in Settings."
                        use.visibility = GONE
                        settled()
                    }
                    use.visibility = VISIBLE
                }
                onResult?.invoke(better != null)
            }
        }
    }

    fun release() {
        cancel?.set(true)
        anim?.cancel()
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
}

// =====================================================================================
// Who's Watching
// =====================================================================================

/**
 * Profiles for everyone at home. Each profile can be a Kids profile with a maximum rating
 * ([KidsProfiles]) and, optionally, a PIN to leave it (a grown-up profile's PIN). [pins] null = no
 * PIN controls (pages opened without a PIN prompt host).
 */
internal class ProfilesPages(private val ctx: Context, private val ui: SetupUi, private val panel: SetupPanel, private val pins: PinPrompt?,
                             /** Setup's stage already says what profiles are for: just the profiles and Add a Profile. */
                             private val compact: Boolean = false) {
    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    private val Int.dp get() = Ui.dp(ctx, this)
    private fun app() = ctx.applicationContext

    private fun avatar(p: Profiles.Profile) = k.label(p.emoji, 19f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color) }
    }

    private fun caption(p: Profiles.Profile): String {
        val bits = ArrayList<String>()
        if (KidsProfiles.isKids(app(), p.id)) bits += "Kids · Up to ${KidsProfiles.LEVELS[KidsProfiles.level(app(), p.id)].movie}"
        else if (KidsProfiles.level(app(), p.id) < KidsProfiles.LEVELS.size - 1) bits += "Up to ${KidsProfiles.LEVELS[KidsProfiles.level(app(), p.id)].movie}"
        else bits += "All Ratings"
        if (Profiles.hasPin(app(), p.id)) bits += "PIN"
        if (p.id == Profiles.current(app()).id) bits += "You"
        return bits.joinToString(" · ")
    }

    fun overview(): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val rows = ScreenRows(s)
        var shape = ""
        lateinit var paint: () -> Unit
        paint = {
            val all = Profiles.all(app())
            val now = all.joinToString { it.id + it.name + it.emoji + it.color }
            if (now != shape) { if (shape.isNotEmpty()) rows.clear(); shape = now }
            if (!compact) s.view("k:who", 0) { parts.section("Profiles", null) }
            for ((i, p) in all.withIndex()) {
                rows.row("p:${p.id}", if (i == 0) (if (compact) 0 else 10) else 8) { parts.row(avatar(p), chevron = true) { panel.push(profile(p.id)) } }
                    .set(p.name.ifBlank { "Profile" }, caption(p))
            }
            if (all.size < MAX) rows.row("add", 8) { parts.row(null, chevron = true) { panel.push(addProfile()) } }
                .set("Add a Profile", "For someone else at home, or a Kids profile")
            if (!compact) s.view("n", 18) { parts.note("Each profile keeps its own list, history and look. A Kids profile only shows movies and shows up to the rating you choose.", 14f) }
        }
        s.onShow = { paint() }
        s.focus = { rows.get("add") ?: SetupScreen.firstFocusable(s.body) }
        return s
    }

    private fun addProfile(): SetupScreen = setupEntry(ctx, ui, parts, "Add a Profile", null, "Name", "", emptyList(),
        "Enter a name, such as Kids or Sam. Add a picture anytime in Settings.", null) { typed ->
        val name = typed.trim().take(20)
        val p = Profiles.add(app(), name)
        Log.w(SetupBrand.TAG, "Setup: Who's Watching, added a profile${if (KidsProfiles.isKids(app(), p.id)) " (kids)" else ""}")
        panel.replace(profile(p.id))
    }

    /** The grown-up whose PIN leaves a kids profile: the first profile that isn't one. */
    private fun owner(): Profiles.Profile? = Profiles.all(app()).firstOrNull { !KidsProfiles.isKids(app(), it.id) }

    private fun profile(id: String): SetupScreen {
        // Compact (setup's stage): the profile's name is the page title and the groups have no headings, so the page
        // fits one screen.
        val s = SetupScreen(ui, if (compact) Profiles.all(app()).firstOrNull { it.id == id }?.name?.ifBlank { null } ?: "Profile" else null, null)
        val rows = ScreenRows(s)
        lateinit var paint: () -> Unit
        fun toggle(key: String, top: Int, value: () -> Boolean, flip: (BrandRow) -> Unit): BrandRow = rows.row(key, top) {
            lateinit var r: BrandRow
            r = parts.row(null, switch = value()) { flip(r); paint() }
            r
        }.also { it.sw?.set(value()) }
        paint = paint@{
            val p = Profiles.all(app()).firstOrNull { it.id == id } ?: run { panel.pop(); return@paint }
            val kids = KidsProfiles.isKids(app(), id)
            if (!compact) rows.row("head", 0) { parts.row(avatar(p)) }.set(p.name.ifBlank { "Profile" }, caption(p))
            if (!compact) s.view("k:kids", 22) { parts.section("Kids", null) }
            toggle("kids", 10, { KidsProfiles.isKids(app(), id) }) { _ ->
                val on = !KidsProfiles.isKids(app(), id)
                KidsProfiles.setKids(app(), id, on)
                KidsProfiles.setLevel(app(), id, if (on) 1 else KidsProfiles.LEVELS.size - 1)
                if (!on) KidsProfiles.setExitPin(app(), id, false)
                Log.w(SetupBrand.TAG, "Setup: Who's Watching, kids profile ${if (on) "on" else "off"}")
            }.also { it.set("Kids Profile", "Limits movies and shows to the rating you choose"); it.sw?.set(kids) }
            if (kids) {
                val lv = KidsProfiles.level(app(), id)
                rows.row("level", 8) { parts.row(null, chevron = true) {
                    panel.push(SetupChoices.screen(ui, panel, parts, "Maximum Rating", "Movies and shows rated above this don't appear on this profile.",
                        KidsProfiles.LEVELS.mapIndexed { i, l ->
                            Triple(i.toString(), l.name, if (i == KidsProfiles.LEVELS.size - 1) "No limit" else "Movies up to ${l.movie} · shows up to ${l.tv.last()}")
                        }, { KidsProfiles.level(app(), id).toString() }) { v ->
                        KidsProfiles.setLevel(app(), id, v.toInt())
                        Log.w(SetupBrand.TAG, "Setup: Who's Watching, maximum rating ${KidsProfiles.LEVELS[v.toInt()].name}")
                    })
                } }.set("Maximum Rating", null, "${KidsProfiles.LEVELS[lv].name} · ${KidsProfiles.LEVELS[lv].movie}")
                val o = owner()
                if (pins != null && o != null) {
                    toggle("exit", 8, { KidsProfiles.exitPin(app(), id) }) { r ->
                        if (KidsProfiles.exitPin(app(), id)) { KidsProfiles.setExitPin(app(), id, false); return@toggle }
                        if (Profiles.hasPin(app(), o.id)) { KidsProfiles.setExitPin(app(), id, true); return@toggle }
                        // The grown-up profile needs a PIN first; the switch turns on once it's saved.
                        pins.setNew(o.id) { saved ->
                            if (saved && Profiles.hasPin(app(), o.id)) KidsProfiles.setExitPin(app(), id, true)
                            ui.main.post { if (s.alive) { paint(); r.requestFocus() } }
                        }
                    }.also {
                        it.set("Ask for a PIN to Leave", if (Profiles.hasPin(app(), o.id)) "Uses ${o.name.ifBlank { "Your" }}'s PIN" else "Sets a PIN for ${o.name.ifBlank { "Your Profile" }}")
                        it.sw?.set(KidsProfiles.exitPin(app(), id))
                    }
                } else s.hide("exit")
            } else { s.hide("level"); s.hide("exit") }
            if (pins != null) {
                if (!compact) s.view("k:pin", 22) { parts.section("Profile", null) }
                toggle("pin", 10, { Profiles.hasPin(app(), id) }) { r ->
                    if (Profiles.hasPin(app(), id)) Profiles.setPin(app(), id, null)
                    else pins.setNew(id) { ui.main.post { if (s.alive) { paint(); r.requestFocus() } } }
                }.also { it.set("Lock With a PIN", "Asks for a 4-digit PIN when this profile is chosen"); it.sw?.set(Profiles.hasPin(app(), id)) }
            }
            if (id != Profiles.all(app()).first().id && id != Profiles.current(app()).id)
                rows.row("remove", 8) { parts.row(null, chevron = true) { panel.push(confirmRemove(id)) } }.set("Remove Profile", "Also removes its list and history")
            else s.hide("remove")
        }
        s.onShow = { paint() }
        s.focus = { rows.get("kids") }
        return s
    }

    private fun confirmRemove(id: String): SetupScreen {
        val name = Profiles.all(app()).firstOrNull { it.id == id }?.name?.ifBlank { null } ?: "This Profile"
        val s = SetupScreen(ui, "Remove $name?", null)
        s.add(parts.note("Its list, history and look are removed from this TV."), 0)
        val cancel = parts.pill("Cancel", false) { panel.pop() }
        val go = parts.pill("Remove", false) {
            Profiles.remove(app(), id)
            Log.w(SetupBrand.TAG, "Setup: Who's Watching, removed a profile")
            panel.pop(); panel.pop()
        }
        s.add(parts.pills(cancel, go), 22)
        s.focus = { cancel }
        return s
    }

    companion object { const val MAX = 8 }
}

// =====================================================================================
// Before You Start: what goes where
// =====================================================================================

internal object SetupIntro {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("dream_setup", Context.MODE_PRIVATE)
    /** "I Understand" was pressed on Before You Start. */
    fun accepted(ctx: Context) = prefs(ctx).getBoolean("intro_ok", false)
    fun accept(ctx: Context) {
        prefs(ctx).edit().putBoolean("intro_ok", true).putLong("intro_at", System.currentTimeMillis()).apply()
        Log.w(SetupBrand.TAG, "Setup: Before You Start accepted")
    }

    /** (who, what for) in the order shown. */
    val PLACES = listOf(
        "TMDB" to "Posters, artwork and details",
        "TVmaze" to "New-episode air times",
        "Your Debrid Service" to "Finds and plays what you choose",
        "Your Scrapers" to "Searches only the sites you add",
        "Trakt" to "Only if you link it",
    )

    /**
     * What You'll Need's rows: (name, what it does, "Free" / "Paid" / "Recommended" or ""). A plain list on the
     * page (no box): the name over its line, the cost on the right, every line wrapping in full.
     */
    fun needs(ctx: Context, k: PremiumKit, rows: List<Triple<String, String, String>>): LinearLayout {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
        for ((i, r) in rows.withIndex()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            text.addView(k.label(r.first, 15.5f, SetupBrand.TEXT, k.semibold, lines = 0))
            text.addView(k.label(r.second, 13.5f, SetupBrand.DIM, k.font, lines = 0).apply { setLineSpacing(dp(2).toFloat(), 1f) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(3) })
            row.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (r.third.isNotEmpty()) row.addView(k.label(r.third, 13.5f, Ui.alpha(Color.WHITE, 0.72f), k.medium, lines = 0),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(20); topMargin = dp(1) })
            box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = dp(14) })
        }
        return box
    }

    /** Where information goes: each name in a fixed column, what it's for beside it (lines up like a table). */
    fun list(ctx: Context, k: PremiumKit): LinearLayout {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
        for ((i, pair) in PLACES.withIndex()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            row.addView(k.label(pair.first, 15f, SetupBrand.TEXT, k.semibold, lines = 0), LinearLayout.LayoutParams(dp(170), WRAP_CONTENT))
            row.addView(k.label(pair.second, 15f, SetupBrand.DIM, k.font, lines = 0), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = dp(12) })
        }
        return box
    }
}

// =====================================================================================
// Any of these pages as a full Settings page
// =====================================================================================

object DreamPanelPage {
    private var openPage: View? = null

    fun isOpen() = openPage != null

    /** Settings › Sources & Quality. */
    fun openPlayback(a: Activity, onClose: (() -> Unit)? = null) = open(a, SetupArt.Kind.PLAYBACK, "Sources & Quality",
        "Choose how Dream OS plays your movies and shows: picture quality, languages, subtitles, sound and file sizes.", onClose) { ui, panel, _ ->
        PlaybackPages(a, ui, panel).overview()
    }

    /** Settings › TMDB Key / OMDb Key: the key's own page (KeyPages). */
    internal fun openKey(a: Activity, kind: KeyPages.Kind, onClose: (() -> Unit)? = null) = open(a, SetupArt.Kind.ACCOUNTS,
        if (kind == KeyPages.Kind.TMDB) "TMDB Key" else "OMDb Key",
        if (kind == KeyPages.Kind.TMDB) "Every poster, detail and search in Dream OS comes from TMDB, with your own free key."
        else "A free OMDb key adds Rotten Tomatoes and IMDb scores to every title.", onClose) { ui, panel, _ ->
        KeyPages(a, ui, panel).overview(kind)
    }

    /**
     * Opens a page over the current screen in the setup's look: the illustration, title and [note] on the
     * left, the page's screens ([first]) on the right. Back steps back inside it, then closes. The one shell
     * for every such Settings page (Accounts, Scrapers, Sources & Quality, keys, Unlock): [first] may add to
     * the left column below the note.
     */
    internal fun open(a: Activity, art: SetupArt.Kind, title: String, note: String, onClose: (() -> Unit)? = null,
                      first: (SetupUi, SetupPanel, LinearLayout) -> SetupScreen) {
        if (openPage != null) return
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val parts = SetupParts(a, k)
        val back = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        lateinit var root: OverlayRoot
        fun close() {
            val r = root
            openPage = null
            r.uncover()
            r.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction {
                panel.release()
                (r.parent as? ViewGroup)?.removeView(r)
            }.start()
            if (back != null && back.isAttachedToWindow) back.requestFocus()
            onClose?.invoke()
        }
        root = OverlayRoot(a) { if (!panel.pop()) close() }
        root.isClickable = true
        SetupBrand.backdrop(root)
        root.coverBelow()
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(PremiumKit.EDGE), dp(44), dp(56), dp(36))
        }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        left.addView(SetupArt(a, art), LinearLayout.LayoutParams(dp(132), dp(132)))
        left.addView(k.label("SETTINGS", 12f, Ui.alpha(Color.WHITE, 0.62f), k.semibold, Ui.TRACK_CAPS), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        left.addView(k.label(title, 32f, SetupBrand.TEXT, k.semibold, Ui.TRACK_TITLE, lines = 2), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        left.addView(parts.note(note, 16f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f))
        row.addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.64f).apply { marginStart = dp(56) })
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
        Ui.unclip(row)
        (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
        openPage = root
        Ui.onDetach(root) { if (openPage === root) openPage = null } // Activity recreated with it open: it can open again
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        Log.w(SetupBrand.TAG, "Setup: Settings › $title opened")
        panel.push(first(ui, panel, left), animate = false)
    }
}
