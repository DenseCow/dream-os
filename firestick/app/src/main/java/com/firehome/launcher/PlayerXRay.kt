package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * X-Ray data (Prime Video style): who is in what's playing, and one person's facts and best-known titles.
 * TMDB through [WatchHistory.tmdb]'s bounded cache, plus small LRUs of the parsed results. Blocking: call
 * off the main thread ([Net.background]).
 */
object XRayData {
    class Face(val id: Int, val name: String, val character: String, val profilePath: String?) {
        fun profile(size: String = "w185") = profilePath?.let { Tmdb.IMG + size + it }
    }

    class Person(val id: Int, val name: String, val profilePath: String?, val meta: String, val bio: String, val knownFor: List<Title>)

    private const val MAX_FACES = 24
    private const val MAX_TITLES = 14
    private val castCache = LruCache<String, List<Face>>(8)
    private val personCache = LruCache<Int, Person>(16)

    fun key(r: PlaybackRequest) = "${r.type}:${r.tmdbId}:${r.season}:${r.episode}"

    /** True when X-Ray can look this title up at all (a TMDB title, not a trailer). */
    fun possible(ctx: Context, r: PlaybackRequest?) = r != null && !r.trailer && r.tmdbId > 0 && Tmdb.hasKey(ctx)

    /** The cast already looked up for [r] (null when not asked yet, empty when TMDB lists nobody). */
    fun cached(r: PlaybackRequest): List<Face>? = castCache.get(key(r))

    /**
     * Who's in it, top billing first. An episode: its own cast, then its guest stars; when TMDB has fewer than
     * three for the episode, the show's whole cast (each actor's most frequent role). A movie: its cast.
     * Empty when nobody is listed; a failed lookup is not remembered (the next open asks again).
     */
    fun cast(ctx: Context, r: PlaybackRequest): List<Face> {
        castCache.get(key(r))?.let { return it }
        if (!possible(ctx, r)) return emptyList()
        val out = LinkedHashMap<Int, Face>()
        // Remembered only when every lookup made answered (a network hiccup isn't "nobody's in it").
        var failed = false
        if (r.type == "episode") {
            val s = r.season
            val e = r.episode
            if (s != null && e != null) {
                val c = WatchHistory.tmdb(ctx, "/tv/${r.tmdbId}/season/$s/episode/$e/credits")
                if (c == null) failed = true
                add(out, c?.optJSONArray("cast"), aggregate = false)
                add(out, c?.optJSONArray("guest_stars"), aggregate = false)
            }
            if (out.size < 3) {
                val c = WatchHistory.tmdb(ctx, "/tv/${r.tmdbId}/aggregate_credits")
                if (c == null) failed = true
                add(out, c?.optJSONArray("cast"), aggregate = true)
            }
        } else {
            val c = WatchHistory.tmdb(ctx, "/movie/${r.tmdbId}/credits")
            if (c == null) failed = true
            add(out, c?.optJSONArray("cast"), aggregate = false)
        }
        val list = out.values.take(MAX_FACES)
        if (!failed || list.size >= 3) castCache.put(key(r), list)
        return list
    }

    private fun add(out: LinkedHashMap<Int, Face>, a: JSONArray?, aggregate: Boolean) {
        a ?: return
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedBy { it.optInt("order", 999) }
        for (o in rows) {
            if (out.size >= MAX_FACES) return
            val id = o.optInt("id")
            val name = clean(o.optString("name")) ?: continue
            if (id <= 0 || out.containsKey(id)) continue
            val character = if (aggregate) {
                val roles = o.optJSONArray("roles")
                (0 until (roles?.length() ?: 0)).mapNotNull { roles?.optJSONObject(it) }
                    .maxByOrNull { it.optInt("episode_count") }?.optString("character")
            } else o.optString("character")
            out[id] = Face(id, name, clean(character).orEmpty(), clean(o.optString("profile_path")))
        }
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** One person: born / died and birthplace, the start of the biography, and their best-known titles (not [excludeKey]). */
    fun person(ctx: Context, id: Int, excludeKey: String?): Person? {
        personCache.get(id)?.let { return it.copyWithout(excludeKey) }
        val j = WatchHistory.tmdb(ctx, "/person/$id", "&append_to_response=combined_credits") ?: return null
        val born = clean(j.optString("birthday"))?.let { PlayerMeta.airDate(it) }
        val died = clean(j.optString("deathday"))?.let { PlayerMeta.airDate(it) }
        val life = when {
            born != null && died != null -> "$born – $died"
            born != null -> "Born $born"
            else -> null
        }
        val meta = listOfNotNull(life, clean(j.optString("place_of_birth"))).joinToString("  ·  ")
        val bio = clean(j.optString("biography")).orEmpty()
        val list = j.optJSONObject("combined_credits")?.optJSONArray("cast")
        val skipGenres = setOf(10767, 10763) // talk, news
        val self = Regex("(?i)\\b(self|himself|herself|narrator|host)\\b")
        val all = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val o = list?.optJSONObject(i) ?: return@mapNotNull null
            if (self.containsMatchIn(o.optString("character"))) return@mapNotNull null
            title(o)
        }.filter { t -> t.posterPath != null && !t.adult && t.genreIds.none { it in skipGenres } }
            .distinctBy { it.key }.sortedByDescending { it.votes }
        // Known titles first; a newcomer's handful of small ones still shows.
        val known = all.filter { it.votes >= 20 }.let { if (it.size >= 4) it else all }.take(MAX_TITLES + 1)
        val p = Person(id, clean(j.optString("name")).orEmpty(), clean(j.optString("profile_path")), meta, bio, known)
        personCache.put(id, p)
        return p.copyWithout(excludeKey)
    }

    private fun Person.copyWithout(key: String?): Person =
        Person(id, name, profilePath, meta, bio, knownFor.filter { it.key != key }.take(MAX_TITLES))

    private fun title(o: JSONObject): Title? {
        val type = o.optString("media_type")
        if (type != "movie" && type != "tv") return null
        val date = o.optString(if (type == "movie") "release_date" else "first_air_date")
        return Title(
            id = o.optInt("id").takeIf { it > 0 } ?: return null,
            type = type,
            name = o.optString(if (type == "movie") "title" else "name"),
            overview = o.optString("overview"),
            posterPath = clean(o.optString("poster_path")),
            backdropPath = clean(o.optString("backdrop_path")),
            year = date.take(4),
            rating = o.optDouble("vote_average", 0.0),
            date = date.takeIf { it.length >= 10 }.orEmpty(),
            votes = o.optInt("vote_count", 0),
            genreIds = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty(),
            lang = o.optString("original_language"),
            adult = o.optBoolean("adult", false),
        )
    }
}

/**
 * X-Ray in Dream Player (Prime Video style): the Cast & Characters button, or Up (from the timeline, or while
 * paused), brings in a strip of who's in this episode or movie along the bottom of the picture, which keeps
 * playing under a light dim: round headshots, the actor's name and the character's. Selecting a face opens that
 * person's card in place (facts, a few lines of biography, a row of their best-known titles) and pauses; a title
 * opens its Dream OS page, and Back from that page returns to the card, still paused at the same spot. Back
 * steps out: card (playback resumes if the card paused it), then strip (playback just continues).
 *
 * Rows: whole columns fill the page between the gutters (Home's rule), so the next one only peeks into the
 * right gutter; each row's content carries its own room for the focus lift and glow on every side (a clipping
 * scroller cuts its child at the child's bounds, so the room must be inside them).
 *
 * Kept cheap for the stick: built when opened and dropped when closed, small TMDB art (w185) loaded only for
 * the faces near focus (Net's caches), focus borders are [PlayerLook]'s fading rainbow (only the focused one
 * steps), and every motion is a short ViewPropertyAnimator that ends.
 */
class PlayerXRay(
    private val host: PlayerHost,
    private val kit: PremiumKit,
    private val root: FrameLayout,
    private val padH: Int,
    private val padBottom: Int,
    private val cb: Callbacks,
) {
    interface Callbacks {
        /** X-Ray closed by Back (not by a new stream or the end). */
        fun xrayClosed()
        /** A person card opened ([open] true: pause for it) or went back to the strip (resume if the card paused). */
        fun xrayCard(open: Boolean)
        fun toast(text: String)
    }

    private val ctx = root.context
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val Int.dp get() = (this * density).toInt()
    private val ink = PlayerLook.ink(ctx)
    private val fg = kit.text
    /** Column widths (px) for the face strip and the Known For row: whole columns exactly fill the page. */
    private val faceW = fit(ITEM_W, GAP)
    private val posterW = fit(POSTER_W, POSTER_GAP)
    private val posterH = posterW * 3 / 2

    private val layer = FlatFrame(ctx).apply { visibility = View.GONE; clipChildren = false; clipToPadding = false }
    private val scrim = object : View(ctx) { override fun hasOverlappingRendering() = false }
    private val strip = FlatLinear(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val card = FlatLinear(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; visibility = View.GONE }

    val layerView: View get() = layer
    var isOpen = false
        private set
    /** The person card is up (else the strip). */
    var onCard = false
        private set
    private var gen = 0
    private var request: PlaybackRequest? = null
    private var faces: List<XRayData.Face> = emptyList()
    private val faceItems = ArrayList<View>()
    private val faceImages = ArrayList<ImageView?>()
    private var faceRow: XRow? = null
    private var faceIndex = 0
    private var loadedUpTo = -1
    private var titles: List<Title> = emptyList()
    private val titleItems = ArrayList<View>()
    private var titleRow: XRow? = null
    private var titleIndex = 0
    private val images = ArrayList<ImageView>()

    init {
        scrim.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Ui.alpha(Color.BLACK, 0.86f), Ui.alpha(Color.BLACK, 0.58f), Color.TRANSPARENT))
        layer.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt(), Gravity.BOTTOM))
        layer.addView(strip, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = padBottom + 8.dp })
        layer.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = padBottom + 8.dp })
        root.addView(layer, MATCH_PARENT, MATCH_PARENT)
    }

    // ======== open / close ========

    /** Looks the cast up ahead (a few seconds into playback), so Up while paused shows faces at once. */
    fun prefetch(r: PlaybackRequest) {
        if (!XRayData.possible(ctx, r) || XRayData.cached(r) != null) return
        Net.background({ XRayData.cast(ctx, r) }) { list -> Log.w(TAG, "Player: X-Ray cast ready (${list?.size ?: 0} people)") }
    }

    /** False when there's no one to show (nothing opens, Up keeps its old meaning). */
    fun canOpen(r: PlaybackRequest?): Boolean = XRayData.possible(ctx, r) && r?.let { XRayData.cached(it)?.isEmpty() } != true

    fun open(r: PlaybackRequest) {
        if (isOpen) return
        isOpen = true
        onCard = false
        request = r
        val myGen = ++gen
        buildStrip(r)
        layer.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        val have = XRayData.cached(r)
        Log.w(TAG, "Player: X-Ray opened for '${r.title}'${if (r.type == "episode") " S${r.season}E${r.episode}" else ""}" +
            (have?.let { " (${it.size} people, cached)" } ?: " (looking up)"))
        if (have != null) fillFaces(have)
        else Net.background({ XRayData.cast(ctx, r) }) { list ->
            if (myGen != gen || !isOpen) return@background
            if (list.isNullOrEmpty()) {
                cb.toast("Cast details aren't available for this title")
                close()
                return@background
            }
            fillFaces(list)
        }
    }

    /** Back: the person card goes back to the strip; the strip closes X-Ray. */
    fun back() {
        if (!isOpen) return
        if (onCard) { showStrip(); cb.xrayCard(false) } else close()
    }

    fun close() {
        if (!isOpen) return
        dropAll()
        layer.animate().cancel()
        layer.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
            .withEndAction { if (!isOpen) { layer.visibility = View.GONE; clearViews() } }.start()
        Log.w(TAG, "Player: X-Ray closed")
        cb.xrayClosed()
    }

    /** Gone at once (playback resumed, a new stream, the overlay stopping). */
    fun closeNow() {
        if (!isOpen) return
        dropAll()
        layer.animate().cancel()
        layer.visibility = View.GONE
        clearViews()
        Log.w(TAG, "Player: X-Ray closed (playback moved on)")
    }

    private fun dropAll() {
        isOpen = false
        onCard = false
        gen++
        for (iv in images) { Net.cancel(iv); iv.setImageDrawable(null); iv.tag = null }
        images.clear()
        main.removeCallbacksAndMessages(null)
    }

    private fun clearViews() {
        strip.removeAllViews()
        card.removeAllViews()
        card.visibility = View.GONE
        card.isFocusable = false
        strip.visibility = View.VISIBLE
        strip.alpha = 1f
        faceItems.clear(); faceImages.clear(); titleItems.clear()
        faceRow = null; titleRow = null
        faces = emptyList(); titles = emptyList()
    }

    // ======== keys ========

    /** Keys while open (Back comes through [back]); true when consumed. */
    fun onKey(e: KeyEvent): Boolean {
        if (!isOpen) return false
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) move(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (first) select()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> return true
        }
        return false
    }

    private fun move(dir: Int) {
        if (onCard) {
            if (titleItems.isEmpty()) return
            val i = (titleIndex + dir).coerceIn(0, titleItems.size - 1)
            if (i == titleIndex && titleItems[i].isFocused) return
            titleIndex = i
            titleItems[i].requestFocus()
            titleRow?.reveal(titleItems[i], padH)
        } else {
            if (faceItems.isEmpty()) return
            val i = (faceIndex + dir).coerceIn(0, faceItems.size - 1)
            if (i == faceIndex && faceItems[i].isFocused) return
            faceIndex = i
            faceItems[i].requestFocus()
            faceRow?.reveal(faceItems[i], padH)
            loadFaces(i + LOAD_AHEAD)
        }
    }

    private fun select() {
        if (onCard) {
            val t = titles.getOrNull(titleIndex) ?: return
            val v = titleItems.getOrNull(titleIndex) ?: return
            Ui.press(v)
            main.postDelayed({
                Ui.release(v)
                if (!isOpen || !onCard) return@postDelayed
                Log.w(TAG, "Player: X-Ray opened title page '${t.name}' (${t.key}); Back returns to the paused player")
                PlayFlow.openDetails(ctx, t, backToPlayer = true)
            }, Ui.PRESS)
        } else {
            val f = faces.getOrNull(faceIndex) ?: return
            val v = faceItems.getOrNull(faceIndex) ?: return
            Ui.press(v)
            main.postDelayed({ Ui.release(v); if (isOpen && !onCard) showPerson(f) }, Ui.PRESS)
        }
    }

    // ======== the strip ========

    private fun buildStrip(r: PlaybackRequest) {
        strip.removeAllViews()
        strip.visibility = View.VISIBLE
        strip.alpha = 1f
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(padH, 0, padH, 0) }
        val context = if (r.type == "episode" && r.season != null && r.episode != null) "${r.title}  ·  S${r.season} · E${r.episode}" else r.title
        head.addView(kicker(context))
        head.addView(kit.label(if (r.type == "episode") "In This Episode" else "Cast", Ui.T_HEADLINE, fg, kit.semibold, Ui.TRACK_TITLE),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
        strip.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val (row, inner) = rowPair()
        strip.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp - LIFT_V.dp; bottomMargin = -LIFT_V.dp })
        faceRow = row
        // Placeholders while the cast is looked up: the same circles, empty, centered in their columns.
        val side = (faceW - HEAD.dp) / 2
        for (i in 0 until 8) inner.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.alpha(fg, 0.06f)) }
        }, LinearLayout.LayoutParams(HEAD.dp, HEAD.dp).apply { marginStart = side; marginEnd = side + GAP.dp; bottomMargin = 44.dp })
        Ui.rise(listOf(head, row), fromDp = 16, step = 60L, duration = 380L)
    }

    private fun fillFaces(list: List<XRayData.Face>) {
        val inner = (faceRow?.getChildAt(0) as? LinearLayout) ?: return
        faces = list
        faceItems.clear()
        faceImages.clear()
        loadedUpTo = -1
        inner.removeAllViews()
        for ((i, f) in list.withIndex()) {
            val item = faceItem(f, i)
            inner.addView(item, LinearLayout.LayoutParams(faceW, WRAP_CONTENT).apply { if (i < list.size - 1) marginEnd = GAP.dp })
            faceItems += item
        }
        faceIndex = 0
        loadFaces(LOAD_FIRST)
        faceItems.firstOrNull()?.let { v -> v.post { if (isOpen && !onCard) v.requestFocus() } }
    }

    /** Headshots for the faces up to [upTo] (the rest wait until focus nears them). */
    private fun loadFaces(upTo: Int) {
        val end = minOf(upTo, faces.size - 1)
        while (loadedUpTo < end) {
            loadedUpTo++
            val iv = faceImages.getOrNull(loadedUpTo) ?: continue
            Net.image(ctx, iv, faces[loadedUpTo].profile(), rank = loadedUpTo)
            images += iv
        }
    }

    private fun faceItem(f: XRayData.Face, i: Int): View {
        val ring = FlatFrame(ctx).apply { clipChildren = false; clipToPadding = false }
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = HEAD / 2, haloDp = CinemaStyle.GLOW_HALO_SMALL).apply { haloAlpha = 0.44f }
        ring.background = glow
        val face = headshot(f, HEAD)
        ring.addView(face.first, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        faceImages += face.second
        // Two lines each before an ellipsis: "Sergeant Andrew Dean" fits, longer ones wrap.
        val name = kit.label(f.name, Ui.T_LABEL, Ui.alpha(fg, 0.86f), kit.semibold, lines = 2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val role = kit.label(f.character, Ui.T_CAPTION, Ui.alpha(fg, 0.56f), kit.medium, lines = 2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = if (f.character.isEmpty()) View.INVISIBLE else View.VISIBLE
        }
        return FlatLinear(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            contentDescription = if (f.character.isEmpty()) f.name else "${f.name} as ${f.character}"
            addView(ring, LinearLayout.LayoutParams(HEAD.dp, HEAD.dp))
            addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
            addView(role, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 5.dp })
            setOnFocusChangeListener { _, has ->
                PlayerLook.border(ring, glow, has)
                val s = if (has) FOCUS_SCALE else 1f
                ring.animate().scaleX(s).scaleY(s).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
                name.setTextColor(if (has) fg else Ui.alpha(fg, 0.86f))
                role.setTextColor(Ui.alpha(fg, if (has) 0.74f else 0.56f))
                if (has) faceIndex = i
            }
        }
    }

    /** A round headshot of [sizeDp] (the initials on glass when TMDB has no photo); the image view, if any. */
    private fun headshot(f: XRayData.Face?, sizeDp: Int, name: String? = f?.name, profile: String? = f?.profile()): Pair<View, ImageView?> {
        val box = FlatFrame(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.mix(Color.BLACK, fg, 0.10f)) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) = o.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
        val initials = name.orEmpty().split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        box.addView(kit.label(initials, if (sizeDp >= 120) 34f else 26f, Ui.alpha(fg, 0.42f), kit.semibold).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (profile == null) return box to null
        val iv = FaceView(ctx)
        box.addView(iv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return box to iv
    }

    private fun showStrip() {
        onCard = false
        gen++ // a person still loading doesn't land on the strip
        card.animate().cancel()
        card.animate().alpha(0f).setDuration(Ui.FOCUS_OUT).setInterpolator(Ui.EXIT).withEndAction {
            if (!onCard) { card.visibility = View.GONE; card.removeAllViews(); titleItems.clear(); titleRow = null; titles = emptyList() }
        }.start()
        strip.animate().cancel()
        strip.visibility = View.VISIBLE
        strip.alpha = 0f
        strip.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        faceItems.getOrNull(faceIndex)?.let { it.requestFocus(); faceRow?.reveal(it, padH) }
        Log.w(TAG, "Player: X-Ray back to the cast")
    }

    // ======== the person card ========

    private fun showPerson(f: XRayData.Face) {
        onCard = true
        cb.xrayCard(true) // the card pauses (the strip doesn't)
        val myGen = ++gen
        titleIndex = 0
        titles = emptyList()
        titleItems.clear()
        card.animate().cancel()
        card.removeAllViews()
        card.alpha = 1f
        card.visibility = View.VISIBLE
        strip.animate().cancel()
        strip.animate().alpha(0f).setDuration(Ui.FOCUS_OUT).setInterpolator(Ui.EXIT).withEndAction { if (onCard) strip.visibility = View.INVISIBLE }.start()
        // Focus parks on the card itself until the titles arrive (the face it came from must not keep the border).
        card.isFocusable = true
        card.requestFocus()

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, 0, padH, 0)
        }
        val (photo, iv) = headshot(f, BIG)
        iv?.let { Net.image(ctx, it, f.profile(), rank = 0); images += it }
        top.addView(photo, LinearLayout.LayoutParams(BIG.dp, BIG.dp))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (f.character.isNotEmpty()) texts.addView(kicker("As ${f.character}"))
        texts.addView(kit.label(f.name, Ui.T_DISPLAY, fg, kit.semibold, Ui.TRACK_TITLE, lines = 1),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = if (f.character.isNotEmpty()) 8.dp else 0 })
        val meta = kit.label("", Ui.T_LABEL, Ui.alpha(fg, 0.64f), kit.medium).apply { visibility = View.GONE }
        val bio = kit.label("", Ui.T_BODY, Ui.alpha(fg, 0.80f), kit.font, lines = 3).apply { setLineSpacing(0f, 1.22f); visibility = View.GONE }
        texts.addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 10.dp })
        texts.addView(bio, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        bio.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.56f).toInt()
        top.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 32.dp })
        card.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val known = kit.label("Known For", 17f, Ui.alpha(fg, 0.94f), kit.semibold, 0.005f).apply { setPadding(padH, 0, padH, 0) }
        card.addView(known, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 28.dp })
        val (row, inner) = rowPair()
        card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp - LIFT_V.dp; bottomMargin = -LIFT_V.dp })
        titleRow = row
        for (i in 0 until 8) inner.addView(View(ctx).apply { background = Ui.rounded(ctx, Ui.alpha(fg, 0.06f), POSTER_R) },
            LinearLayout.LayoutParams(posterW, posterH).apply { marginEnd = POSTER_GAP.dp; bottomMargin = 46.dp })
        Ui.rise(listOf(top, known, row), fromDp = 16, step = 60L, duration = 380L)
        Log.w(TAG, "Player: X-Ray person '${f.name}' (${f.id})")

        val exclude = request?.let { r -> if (r.type == "episode") "tv:${r.tmdbId}" else "movie:${r.tmdbId}" }
        Net.background({ XRayData.person(ctx, f.id, exclude) }) { p ->
            if (myGen != gen || !isOpen || !onCard) return@background
            if (p == null) {
                inner.removeAllViews()
                inner.addView(kit.label("Couldn't load this person's titles right now", Ui.T_BODY, Ui.alpha(fg, 0.6f), kit.medium))
                return@background
            }
            if (p.meta.isNotEmpty()) { meta.text = p.meta; meta.visibility = View.VISIBLE }
            if (p.bio.isNotEmpty()) { bio.visibility = View.VISIBLE; fitBio(bio, p.bio) }
            fillTitles(inner, p.knownFor)
        }
    }

    private fun fillTitles(inner: LinearLayout, list: List<Title>) {
        inner.removeAllViews()
        titles = list
        titleItems.clear()
        if (list.isEmpty()) {
            inner.addView(kit.label("No other titles to show", Ui.T_BODY, Ui.alpha(fg, 0.6f), kit.medium),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            return
        }
        for ((i, t) in list.withIndex()) {
            val item = posterItem(t, i)
            inner.addView(item, LinearLayout.LayoutParams(posterW, WRAP_CONTENT).apply { if (i < list.size - 1) marginEnd = POSTER_GAP.dp })
            titleItems += item
        }
        titleIndex = 0
        titleItems.first().let { v -> v.post { if (isOpen && onCard) { card.isFocusable = false; v.requestFocus() } } }
    }

    private fun posterItem(t: Title, i: Int): View {
        val frame = FlatFrame(ctx).apply { clipChildren = false; clipToPadding = false }
        val glow = CinemaStyle.glowDrawable(ctx, radiusDp = POSTER_R, haloDp = CinemaStyle.GLOW_HALO_SMALL).apply { haloAlpha = 0.44f }
        frame.background = glow
        val art = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(ctx, Ui.mix(Color.BLACK, fg, 0.08f), POSTER_R)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        frame.addView(art, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        Net.image(ctx, art, t.poster("w185"), rank = i)
        images += art
        // Two lines before an ellipsis ("John Wick: Chapter 4" whole).
        val caption = kit.label(t.name, Ui.T_CAPTION, Ui.alpha(fg, 0.66f), kit.medium, lines = 2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        return FlatLinear(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            contentDescription = t.name
            addView(frame, LinearLayout.LayoutParams(posterW, posterH))
            addView(caption, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
            setOnFocusChangeListener { _, has ->
                PlayerLook.border(frame, glow, has)
                val s = if (has) FOCUS_SCALE else 1f
                frame.animate().scaleX(s).scaleY(s).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
                caption.setTextColor(if (has) fg else Ui.alpha(fg, 0.66f))
                if (has) titleIndex = i
            }
        }
    }

    /**
     * A sideways row and its content. The content carries the page margins and [LIFT_V] above and below as
     * its own padding: the scroller clips its child at the child's bounds, so the focus lift and the glow of
     * the first, last and every focused item need that room inside them (the ring was cut at the top before).
     */
    private fun rowPair(): Pair<XRow, LinearLayout> {
        val row = XRow(ctx)
        val inner = FlatLinear(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            clipChildren = false
            clipToPadding = false
            setPadding(padH, LIFT_V.dp, padH, LIFT_V.dp)
        }
        row.addView(inner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return row to inner
    }

    /** A column width (px) near [wDp] with [gapDp] between, such that a whole number of columns fills the page between the gutters. */
    private fun fit(wDp: Int, gapDp: Int): Int {
        val page = ctx.resources.displayMetrics.widthPixels - 2 * padH
        val gap = gapDp.dp
        val n = maxOf(1, Math.round((page + gap).toFloat() / (wDp.dp + gap)))
        return (page - (n - 1) * gap) / n
    }

    /**
     * The biography in three lines that end on a whole sentence (never mid-word): the longest run of full
     * sentences that fits; when even the first one is longer, it ends on a whole word with an ellipsis.
     */
    private fun fitBio(tv: TextView, full: String) {
        val text = full.replace(Regex("\\s*\\n+\\s*"), " ").trim()
        // Whole words only (no hyphenation), broken the simple way, so the measure below is what shows.
        tv.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        tv.text = text
        // Once laid out at its real width (it was GONE until now), measured once and cut to fit.
        tv.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                val w = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
                if (w <= 0) return
                tv.removeOnLayoutChangeListener(this)
                if (tv.text.toString() != text) return
                val sl = android.text.StaticLayout.Builder.obtain(text, 0, text.length, tv.paint, w)
                    .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier).setIncludePad(false)
                    .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE).build()
                if (sl.lineCount <= BIO_LINES) return
                val head = text.substring(0, sl.getLineEnd(BIO_LINES - 1))
                // A sentence ends at . ! ? (maybe then a closing quote or bracket) and a space; not after a lone initial ("J. ").
                val end = SENTENCE_END.findAll("$head ").filter { m -> !INITIAL.matches(head.substring(maxOf(0, m.range.first - 2), m.range.first)) }
                    .lastOrNull()?.range?.last
                val cut = if (end != null && end >= 40) head.substring(0, end + 1)
                else head.trimEnd().substringBeforeLast(' ').trimEnd(',', ';', ':', ' ', '-', '—') + "…"
                tv.post { if (tv.text.toString() == text) tv.text = cut } // not inside a layout pass
            }
        })
    }

    private fun kicker(t: String) = kit.kicker(t).apply {
        setTextColor(Ui.alpha(fg, 0.62f))
        ellipsize = null
        setPadding(0, 0, 12.dp, 0)
    }

    /**
     * The X-Ray's sideways row: it never jumps to a focused child by itself (Android's instant scroll); [reveal]
     * glides just enough to keep the focused one inside the page margins. Clips at its own edge (the screen's).
     */
    private class XRow(ctx: Context) : HorizontalScrollView(ctx) {
        init {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            clipToPadding = false
            clipChildren = true
        }

        override fun hasOverlappingRendering() = false
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

        fun reveal(item: View, margin: Int) {
            val content = getChildAt(0) ?: return
            val max = maxOf(0, content.width + paddingLeft + paddingRight - width)
            val left = paddingLeft + item.left - scrollX
            val right = paddingLeft + item.right - scrollX
            val target = when {
                left < margin -> item.left + paddingLeft - margin
                right > width - margin -> item.right + paddingLeft + margin - width
                else -> return
            }.coerceIn(0, max)
            smoothScrollTo(target, 0)
        }
    }

    /** A headshot cropped to its circle from a little above the middle (faces sit in the upper part of a portrait). */
    private class FaceView(ctx: Context) : ImageView(ctx) {
        init { scaleType = ScaleType.MATRIX }

        override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); fit() }
        override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean = super.setFrame(l, t, r, b).also { fit() }

        private fun fit() {
            val d = drawable ?: return
            val vw = width.toFloat()
            val vh = height.toFloat()
            val dw = d.intrinsicWidth.toFloat()
            val dh = d.intrinsicHeight.toFloat()
            if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
            val s = maxOf(vw / dw, vh / dh)
            imageMatrix = Matrix().apply {
                setScale(s, s)
                postTranslate((vw - dw * s) / 2f, (vh - dh * s) * 0.28f)
            }
        }
    }

    companion object {
        private const val TAG = "FireHome"
        /** Headshot and its column (dp, about: columns are fitted to the page), gap between columns; the person card's photo; poster cards. */
        private const val HEAD = 108
        private const val ITEM_W = 136
        private const val GAP = 10
        private const val BIG = 132
        private const val POSTER_W = 100
        private const val BIO_LINES = 3
        private val SENTENCE_END = Regex("[.!?][\"”’')\\]]?(?=\\s)")
        /** " J" (or "J" at the start): the period after it ends an initial, not a sentence. */
        private val INITIAL = Regex("\\s?[A-Z]")
        private const val POSTER_R = 10
        private const val POSTER_GAP = 18
        private const val FOCUS_SCALE = 1.06f
        /** Room above and below the rows for the focus lift and the glow (dp). */
        private const val LIFT_V = 20
        /** Headshots loaded at open, and ahead of focus afterwards. */
        private const val LOAD_FIRST = 8
        private const val LOAD_AHEAD = 6
    }
}
