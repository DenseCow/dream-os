package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dream Player's "Change source": a side sheet listing the sources found for this title, so a source
 * that streams the wrong cut, language or episode can be swapped by hand.
 *
 * The list is Dream Core's own: every torrent the user's scrapers found, filtered and ranked as Play
 * ranks them ([NativeResolver.sources]; Search All Sources: the filters off, what they'd hide marked),
 * with quality, size, HDR / codec / audio badges, seeders and a "Ready" mark for what the debrid
 * service has cached (one AllDebrid call, filled in when it answers). Picking one unlocks it first
 * while the current stream keeps playing ([NativeResolver.resolveCandidate]); only a working link
 * switches over (same spot, curtain up, the VIEW intent the play flow sends). One that isn't available
 * says so in its row; nothing stops. "Find Another Source" has the play flow pick again.
 *
 * Releases whose name is clearly for another episode are hidden (behind "Show N more").
 */
object SourcePicker {
    private const val TAG = "FireHome"
    private val main = Handler(Looper.getMainLooper())
    private val GREEN = Color.parseColor("#4ADE80")
    private val AMBER = Color.parseColor("#FBBF24")

    private var sheet: Sheet? = null

    /** The sheet is on screen (DreamPlayerActivity routes every key here first). */
    val isOpen get() = sheet != null

    /**
     * Opens the sheet for what's playing (or, from the curtain's error, what was asked for). [loose]:
     * Search All Sources (the user's filters set aside for this one list).
     */
    fun open(host: PlayerHost, loose: Boolean = false) {
        val req = host.request ?: run { Log.w(TAG, "Source picker: nothing playing to list sources for"); return }
        sheet?.dismiss()
        val s = Sheet(host, req, loose)
        sheet = s
        s.show()
        val app = host.overlayRoot.context.applicationContext
        Net.run { fetch(app, s) }
    }

    /** Keys while the sheet is open; true if consumed (everything but media keys). */
    fun onKey(event: KeyEvent): Boolean = sheet?.onKey(event) ?: false

    /** Closes the sheet. */
    fun close() { sheet?.dismiss() }

    // ---- sources ----

    /**
     * One of Dream Core's sources. [index] is its place in the list; [native] the torrent, with its
     * badges ([tags]), seeders, and whether the user's filters would hide it ([outside]).
     */
    class Source(
        val index: Int, val name: String, val quality: String, val sizeGb: Double, val match: Match, val hash: String?,
        val native: Candidate, val tags: List<String> = emptyList(), val seeders: Int = 0, val outside: Boolean = false,
    )

    enum class Match { OK, DOUBT, OTHER_EPISODE, OTHER_TITLE }

    /** Background: Dream Core's list (the "Ready" marks follow when the service answers). */
    private fun fetch(app: Context, s: Sheet) {
        val list = nativeSources(app, s.req, s.loose)
        if (s.closed) return
        main.post { s.showSources(list) }
        if (list.isEmpty()) return
        val ready = NativeResolver.readyHashes(app, list.map { it.native })
        if (ready != null) main.post { s.markReady(ready) }
    }

    private const val GB = 1024.0 * 1024 * 1024

    /** Dream Core's sources as rows: the file inside the torrent (packs) names it when known. Blocking. */
    private fun nativeSources(app: Context, req: PlaybackRequest, loose: Boolean): List<Source> {
        val t0 = SystemClock.elapsedRealtime()
        val list = NativeResolver.sources(app, req, loose).mapIndexed { i, l ->
            val c = l.c
            val name = c.fileName?.let { DebridFiles.base(it) } ?: c.torrentName
            // The index matched it by IMDb id: only another episode (a pack's wrong file) is worth a flag.
            val m = if (match(name, req) == Match.OTHER_EPISODE) Match.OTHER_EPISODE else Match.OK
            Source(i, name, c.quality, c.sizeBytes / GB, m, c.hash, native = c, tags = l.tags, seeders = c.seeders, outside = l.outside)
        }
        val unique = dedupe(list)
        Log.w(TAG, "Source picker: Dream Core listed ${unique.size}${if (loose) " (all sources)" else ""} in ${SystemClock.elapsedRealtime() - t0} ms")
        return unique
    }

    /**
     * A release name as people read it: no extension, dots and underscores between words as spaces
     * (kept inside numbers: 5.1, H.264), e.g. "Breaking Bad S01E01 1080p BluRay x264-ROVERS".
     */
    fun prettyName(name: String): String {
        val base = if (DebridFiles.isVideo(name)) name.substringBeforeLast('.') else name
        return base.replace(Regex("(?<![0-9])[._]|[._](?![0-9])"), " ").replace(Regex("\\s+"), " ").trim()
    }

    // ---- duplicates ----

    private fun qrank(q: String) = when (q.uppercase()) { "4K", "2160P" -> 0; "1080P" -> 1; "720P" -> 2; "SD", "480P" -> 3; else -> 4 }

    /** [a] beats [b]: higher quality, then the bigger file. */
    private fun better(a: Source, b: Source): Boolean = when {
        qrank(a.quality) != qrank(b.quality) -> qrank(a.quality) < qrank(b.quality)
        else -> a.sizeGb > b.sizeGb
    }

    /** Same release: the same torrent hash, or the same release name. */
    private fun same(a: Source, b: Source): Boolean {
        if (a.hash != null && a.hash == b.hash) return true
        val na = norm(a.name.substringBeforeLast('.')).replace(" ", "")
        return na.length >= 8 && na == norm(b.name.substringBeforeLast('.')).replace(" ", "")
    }

    /** One entry per release, the best copy kept (in the place the release first appeared). */
    private fun dedupe(list: List<Source>): List<Source> {
        val out = ArrayList<Source>()
        for (s in list) {
            val i = out.indexOfFirst { same(it, s) }
            if (i < 0) out += s else if (better(s, out[i])) out[i] = s
        }
        return out
    }

    // ---- name checks ----

    private val STOP = setOf("the", "a", "an", "and", "of", "in", "on", "to")
    private fun norm(s: String) = s.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() && it !in STOP }

    /** Whether release [name] looks like [req]: its S/E (or year) and its title words. */
    fun match(name: String, req: PlaybackRequest): Match {
        val title = words(req.title)
        val nameWords = words(name).toSet()
        val have = if (title.isEmpty()) 1f else title.count { it in nameWords } / title.size.toFloat()
        if (req.type == "episode" && req.season != null && req.episode != null) {
            // A second episode needs a dash or another "e" (S01E01-03, S01E01-E03, S01E01E02): "S01E02.1080p" is
            // episode 2, not episodes 2 to 108.
            val se = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?:(?:[ ._]*-[ ._]*e?|[ ._-]*e)(\d{1,3})(?![0-9]))?""").find(name)
                ?: Regex("""(?i)(?<![0-9])(\d{1,2})x(\d{2,3})(?![0-9])""").find(name)
            if (se != null) {
                val s = se.groupValues[1].toInt()
                val e1 = se.groupValues[2].toInt()
                val e2 = se.groupValues.getOrNull(3)?.toIntOrNull()?.takeIf { it >= e1 } ?: e1
                if (s != req.season || req.episode !in e1..e2) return Match.OTHER_EPISODE
            } else {
                val season = Regex("""(?i)(?<![a-z0-9])(?:s|season[ ._-]*)(\d{1,2})(?![0-9e])""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                if (season != null && season != req.season) return Match.OTHER_EPISODE
            }
        } else if (req.year != null && req.year !in yearsIn(req.title)) {
            val y = yearsIn(name).firstOrNull()
            if (y != null && kotlin.math.abs(y - req.year) > 1 && have < 1f) return Match.OTHER_TITLE
        }
        return when {
            have == 0f -> Match.OTHER_TITLE
            have < 1f -> Match.DOUBT
            else -> Match.OK
        }
    }

    private fun yearsIn(s: String) = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""").findAll(s).map { it.value.toInt() }.toList()

    /** Whether Dream Core's [src] is the torrent behind the stream that's playing (the resolver's link for this title). */
    private fun playingNative(src: Source, streamUrl: String?): Boolean {
        val req = sheet?.req ?: return false
        val f = NativeResolver.peek(req) ?: return false
        return f.hash == src.hash && streamUrl?.substringBefore('|') == f.url
    }

    fun qualityLabel(q: String) = when (q.uppercase()) {
        "4K", "2160P" -> "4K"
        "1080P" -> "1080p"
        "720P" -> "720p"
        "" -> ""
        else -> q.uppercase()
    }

    fun sizeLabel(gb: Double) = when {
        gb <= 0.0 -> ""
        gb < 1.0 -> "${(gb * 1024).toInt()} MB"
        else -> String.format(java.util.Locale.US, "%.1f GB", gb)
    }

    // ---- the sheet ----

    private class Sheet(val host: PlayerHost, val req: PlaybackRequest, val loose: Boolean) {
        private val ctx = host.overlayRoot.context
        private val kit = PremiumKit(ctx)
        private val density = ctx.resources.displayMetrics.density
        private val Int.dp get() = (this * density).toInt()
        @Volatile var closed = false
        private var layer: FrameLayout? = null
        private var body: LinearLayout? = null
        private var spinner: LoadingCurtain.Spinner? = null
        private var rows: List<View> = emptyList()
        private var actions: Map<View, () -> Unit> = emptyMap()
        private var focus = 0
        private var sources: List<Source> = emptyList()
        private var showAll = false
        /** Info hashes the debrid service has cached, once known ([markReady]). */
        private var ready: Set<String>? = null
        /** Per Dream Core row (by hash): its badge column (the "Ready" mark) and its detail line ("Getting Ready"). */
        private val badges = HashMap<String, LinearLayout>()
        private val details = HashMap<String, Pair<TextView, String>>()
        /** The hash of a pick being unlocked (OK waits for it). */
        private var busy: String? = null

        fun show() {
            host.setControlsVisible(false)
            val fit = ScreenFit.insets(ctx)
            val l = FrameLayout(ctx).apply { clipChildren = false; isClickable = true }
            // Same scrim and card as the player's other side sheets.
            l.addView(View(ctx).apply {
                background = PlayerLook.sideScrim(ctx)
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            val card = FlatFrame(ctx).apply {
                background = PlayerLook.sheet(ctx)
                setPadding(28.dp, 26.dp, 28.dp, 22.dp)
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
                clipChildren = false
                clipToPadding = false
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = true }
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(8.dp, 0, 8.dp, 0) }
            head.addView(kit.kicker(if (loose) "All Sources" else "Change Source").apply { setTextColor(Ui.alpha(kit.text, 0.62f)) })
            val sub = if (req.type == "episode" && req.season != null && req.episode != null)
                "${req.title}  ·  S${req.season} E${req.episode}" else req.title
            head.addView(kit.label(sub, Ui.T_HEADLINE, kit.text, kit.semibold, Ui.TRACK_TITLE).apply {
                setPadding(0, 6.dp, 0, 0); ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(head)
            val list = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(8.dp, 0, 8.dp, 0)
            }
            val sv = SmoothScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                margin = 16.dp
                clipChildren = false
                addView(list, MATCH_PARENT, WRAP_CONTENT)
            }
            col.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 14.dp })
            Ui.liftRoom(sv, 8, 6)
            card.addView(col, MATCH_PARENT, MATCH_PARENT)
            l.addView(card, FrameLayout.LayoutParams(560.dp, MATCH_PARENT, Gravity.END).apply {
                setMargins(0, 26.dp + fit[1], 30.dp + fit[0], 26.dp + fit[1])
            })
            l.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { if (!closed) dismiss(animate = false) }
            })
            host.overlayRoot.addView(l, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            layer = l; body = list
            card.alpha = 0f
            card.translationX = 40.dp.toFloat()
            card.animate().alpha(1f).translationX(0f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start()
            showLoading()
        }

        private fun showLoading() {
            val list = body ?: return
            list.removeAllViews()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp, 18.dp, 12.dp, 18.dp)
            }
            val sp = LoadingCurtain.Spinner(ctx, kit.select, kit.blush, 2.5f * density, rainbow = true)
            row.addView(sp, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = Ui.S3.dp })
            row.addView(kit.label("Looking for Sources", Ui.T_BODY, kit.dim, kit.medium))
            list.addView(row)
            spinner = sp
            sp.start()
            rows = emptyList(); actions = emptyMap()
        }

        fun showSources(list: List<Source>) {
            if (closed) return
            spinner?.stop(); spinner = null
            sources = list
            build()
        }

        /** The debrid service's answer: a "Ready" mark fades into each cached row (nothing moves under the focus). */
        fun markReady(hashes: Set<String>) {
            if (closed) return
            ready = hashes
            for ((h, col) in badges) if (h in hashes) addReady(col, animate = true)
            Log.w(TAG, "Source picker: ${sources.count { it.hash in hashes }} of ${sources.size} ready to stream")
        }

        private fun addReady(col: LinearLayout, animate: Boolean) {
            if (col.findViewWithTag<View>("ready") != null) return
            val b = pill("Ready", GREEN).apply { tag = "ready" }
            col.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.END
                if (col.childCount > 0) topMargin = 4.dp
            })
            if (animate) { b.alpha = 0f; b.animate().alpha(1f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start() }
        }

        private fun pill(text: String, color: Int) = kit.label(text, Ui.T_MICRO, color, kit.semibold, Ui.TRACK_CAPS * 0.4f).apply {
            setPadding(10.dp, 4.dp, 10.dp, 4.dp)
            // Cinema: a solid dark pill, so its green / amber word reads on the dark sheet and on a focused (pearl) row alike.
            background = Ui.rounded(ctx, Ui.alpha(PlayerLook.shade(ctx), 0.92f), Ui.R_PILL, 1f, Ui.alpha(kit.text, 0.14f))
        }

        private fun build() {
            val list = body ?: return
            list.removeAllViews()
            val url = (host as? DreamPlayerActivity)?.streamUrl
            badges.clear(); details.clear()
            val shown = if (showAll) sources else sources.filter { it.match == Match.OK || it.match == Match.DOUBT }
            val hidden = sources.size - shown.size
            val r = ArrayList<View>()
            val a = HashMap<View, () -> Unit>()
            if (shown.isEmpty()) {
                list.addView(kit.label(
                    if (sources.isEmpty()) "No other sources are available right now." else "Every source found is for a different title or episode.",
                    Ui.T_BODY, kit.dim, kit.medium, 0f, 3,
                ).apply { setPadding(12.dp, 12.dp, 12.dp, 14.dp) })
            }
            for (s in shown) {
                val row = sourceRow(s, playingNative(s, url))
                list.addView(row); r += row; a[row] = { pick(s) }
            }
            if (hidden > 0) {
                val more = plainRow("Show $hidden More", "For a Different Title or Episode")
                list.addView(more); r += more; a[more] = { showAll = true; build() }
            }
            if (!loose) {
                // Every source, with the usual filters set aside for this one search.
                val all = plainRow("Search All Sources", "Includes Ones Your Source Settings Usually Hide")
                list.addView(all); r += all; a[all] = {
                    val h = host
                    dismiss()
                    // Not onto a player that closed meanwhile: a sheet that never attaches would take its next keys.
                    main.postDelayed({ if (h.overlayRoot.isAttachedToWindow) open(h, loose = true) }, 400)
                }
            }
            val again = plainRow("Find Another Source", "Dream OS Picks the Best One, From Where You Are")
            list.addView(again); r += again; a[again] = { findAnother() }
            rows = r; actions = a
            focus = focus.coerceIn(0, (r.size - 1).coerceAtLeast(0))
            r.getOrNull(focus)?.let { v -> v.post { if (!closed) v.requestFocus() } }
        }

        private fun rowFrame() = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            minimumHeight = 60.dp
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 4.dp }
        }

        /** Focus look like the player's sheet rows: pearl with the fading rainbow border on focus; every line turns to dark ink while it is focused. */
        private fun styleFocus(row: View, texts: List<Pair<TextView, Int>>) {
            val inkColor = PlayerLook.ink(ctx)
            fun ink(on: Boolean) { for ((i, pair) in texts.withIndex()) pair.first.setTextColor(if (on) Ui.alpha(inkColor, if (i == 0) 1f else 0.72f) else pair.second) }
            PlayerLook.glowRow(row, Ui.R_MD) { has -> ink(has) }
            ink(false)
        }

        private fun sourceRow(s: Source, playing: Boolean): View {
            val row = rowFrame()
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val top = listOf(quality(s.quality), size(s.sizeGb)).filter { it.isNotEmpty() }.joinToString("  ·  ")
            val title = kit.label(top, Ui.T_BODY, kit.text, kit.semibold)
            val via = (s.tags + listOfNotNull(seedersLabel(s.seeders))).joinToString("  ·  ")
            val viaColor = Ui.alpha(kit.text, 0.66f)
            val nameColor = Ui.alpha(kit.text, 0.48f)
            val viaView = kit.label(via, Ui.T_CAPTION, viaColor, kit.medium).apply { setPadding(0, 3.dp, 0, 0) }
            val nameView = kit.label(prettyName(s.name), Ui.T_MICRO, nameColor, kit.font).apply {
                ellipsize = TextUtils.TruncateAt.MIDDLE; setPadding(0, 3.dp, 0, 0)
            }
            texts.addView(title)
            texts.addView(viaView.apply { if (via.isEmpty()) visibility = View.GONE })
            texts.addView(nameView)
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val badge = when {
                playing -> "Playing" to GREEN
                loose && s.outside -> "Outside Your Filters" to Ui.alpha(kit.text, 0.78f)
                s.match == Match.OTHER_EPISODE -> "Other Episode" to AMBER
                s.match == Match.OTHER_TITLE -> "Other Title" to AMBER
                s.match == Match.DOUBT -> "Check Name" to AMBER
                else -> null
            }
            // A column at the end: the status pill, and Dream Core's "Ready" under it once the service answered.
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            if (badge != null) col.addView(pill(badge.first, badge.second), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END })
            if (s.hash != null) {
                badges[s.hash] = col
                details[s.hash] = viaView to via
                if (!playing && ready?.contains(s.hash) == true) addReady(col, animate = false)
            }
            row.addView(col, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = Ui.S3.dp })
            styleFocus(row, listOf(title to kit.text, viaView to viaColor, nameView to nameColor))
            return row
        }

        private fun seedersLabel(n: Int) = when { n <= 0 -> null; n == 1 -> "1 Seeder"; else -> "$n Seeders" }

        private fun plainRow(label: String, sub: String): View {
            val row = rowFrame()
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val subColor = Ui.alpha(kit.text, 0.6f)
            val t = kit.label(label, Ui.T_BODY, kit.text, kit.medium)
            val st = kit.label(sub, Ui.T_CAPTION, subColor, kit.font).apply { setPadding(0, 3.dp, 0, 0) }
            texts.addView(t); texts.addView(st)
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            styleFocus(row, listOf(t to kit.text, st to subColor))
            return row
        }

        private fun quality(q: String) = qualityLabel(q)
        private fun size(gb: Double) = sizeLabel(gb)

        /**
         * A pick: unlocked first while the stream plays on (its row says "Getting Ready"), then the switch:
         * same spot, curtain up, the stream delivered like the play flow's. Not cached: the row says so and
         * nothing changes. The player's subtitles and "Try Another Source" know the new link.
         */
        private fun pick(s: Source) {
            val c = s.native
            val player = host as? DreamPlayerActivity ?: return
            if (busy != null) return
            val hash = c.hash
            if (playingNative(s, player.streamUrl)) { dismiss(); return } // already this one
            busy = hash
            setDetail(hash, "Getting Ready")
            val app = ctx.applicationContext
            Net.run {
                val f = NativeResolver.resolveCandidate(app, req, c)
                main.post {
                    busy = null
                    if (closed) return@post
                    if (f == null) {
                        setDetail(hash, "Not Available Right Now", restoreAfterMs = 2_600)
                        return@post
                    }
                    Log.w(TAG, "Source picker: playing ${f.service.uppercase()} ${f.quality} (${prettyName(s.name).take(60)})")
                    dismiss()
                    val again = player.beginSourceSwitch() ?: return@post
                    try { app.startActivity(PlayFlow.streamIntent(app, f.url, again)) } catch (e: Exception) {
                        Log.w(TAG, "Source picker: couldn't hand the stream to the player: $e")
                        player.tryAnotherSource()
                    }
                }
            }
        }

        /** A Dream Core row's detail line says [text] instead of its badges (back to them after [restoreAfterMs], if set). */
        private fun setDetail(hash: String, text: String, restoreAfterMs: Long = 0) {
            val (v, orig) = details[hash] ?: return
            v.text = text
            v.visibility = View.VISIBLE // a row with no badges had it hidden
            if (restoreAfterMs > 0) v.postDelayed({
                if (!closed && v.text == text) { v.text = orig; if (orig.isEmpty()) v.visibility = View.GONE }
            }, restoreAfterMs)
        }

        /** The play flow's own pick again (autoplay), resuming from here; the curtain shows meanwhile. */
        private fun findAnother() {
            val player = host as? DreamPlayerActivity ?: return
            dismiss()
            player.tryAnotherSource()
        }

        fun onKey(e: KeyEvent): Boolean {
            when (e.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> {
                    if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) dismiss()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> return false // the player still plays/pauses underneath
            }
            if (e.action != KeyEvent.ACTION_DOWN) return true
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> move(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (e.repeatCount == 0) rows.getOrNull(focus)?.let { actions[it]?.invoke() }
                else -> {}
            }
            return true // nothing under the sheet reacts (no seeking while choosing)
        }

        private fun move(d: Int) {
            if (rows.isEmpty()) return
            focus = (focus + d).coerceIn(0, rows.size - 1)
            rows[focus].requestFocus()
        }

        fun dismiss(animate: Boolean = true) {
            if (closed) return
            closed = true
            if (sheet === this) sheet = null
            spinner?.stop(); spinner = null
            val l = layer ?: return
            layer = null
            if (animate && l.isAttachedToWindow) {
                l.animate().alpha(0f).setDuration(Ui.SHEET_OUT).setInterpolator(Ui.EXIT).withEndAction {
                    (l.parent as? ViewGroup)?.removeView(l)
                }.start()
            } else (l.parent as? ViewGroup)?.removeView(l)
        }
    }
}
