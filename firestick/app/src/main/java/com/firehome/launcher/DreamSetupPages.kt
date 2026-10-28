package com.firehome.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dream OS's own setup pages, shared by the first-run setup ([SetupWizard]) and Settings:
 *
 *  - [AccountsPages]: Connect Your Accounts (the debrid services, each with its live status, the
 *    device sign-in with a code and QR, Premiumize's key entry when the build has no sign-in for it),
 *    a TMDB key when the build has none, and Trakt (optional, [TraktPages]).
 *  - [ScraperPages]: Set Up Scrapers (add a repository by its address, its providers with switches,
 *    Update and Remove), on [Scrapers].
 *  - [SetupGate]: what "ready to watch" means (one verified premium account, scrapers ready, the
 *    final check passed), the setup's saved step, and Home's one "Finish Setting Up" prompt.
 *  - [DreamSettingsPage]: Settings › Accounts and Settings › Scrapers as full pages.
 *
 * Every screen is a [SetupScreen] in a [SetupPanel] (built once, updated in place); rows are
 * [BrandRow]s, the Dream focus look in every theme.
 */
internal object SetupBrand {
    val TEXT = 0xFFF5F5F7.toInt()
    val DIM = 0xFFA1A1AA.toInt()
    val GREEN = 0xFF30D158.toInt()
    val RED = 0xFFFF453A.toInt()
    const val TAG = "FireHome"

    /** The debrid services in the order setup lists them, with their badge letters and websites. */
    val SERVICES = listOf("ad", "rd", "pm", "tb")
    fun monogram(s: String) = when (s) { "ad" -> "AD"; "rd" -> "RD"; "pm" -> "PM"; "tb" -> "TB"; else -> s.uppercase() }
    fun site(s: String) = when (s) { "ad" -> "alldebrid.com"; "rd" -> "real-debrid.com"; "pm" -> "premiumize.me"; "tb" -> "torbox.app"; else -> "" }

    /** The brand's page: black falling into the darkest gray, dithered (no bands), made off the main thread. */
    fun backdrop(v: View) {
        v.setBackgroundColor(Color.BLACK)
        val dm = v.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        cached?.let { if (it.width == w && it.height == h) { v.background = BitmapDrawable(v.resources, it); return } }
        Net.run {
            val bmp = try { SmoothGradient.render(SmoothGradient.Spec(Color.BLACK, Color.rgb(6, 6, 8), 0, 0, w, h), w, h, 0, null) } catch (e: Throwable) { null }
                ?: return@run
            cached = bmp
            v.post { if (v.isAttachedToWindow) v.background = BitmapDrawable(v.resources, bmp) }
        }
    }
    @Volatile private var cached: Bitmap? = null
}

/** Phone Remote typing into the setup text field on screen (a repository address, a key). */
internal object SetupText {
    private var field: WeakReference<QueryField>? = null
    fun shown(f: QueryField) { field = WeakReference(f) }
    fun hidden(f: QueryField) { if (field?.get() === f) field = null }
    fun phoneText(text: String): Boolean {
        val f = field?.get() ?: return false
        if (!f.isAttachedToWindow || !f.isShown) return false
        f.setQuery(text.trim())
        return true
    }
}

// =====================================================================================
// Rows and small parts
// =====================================================================================

/**
 * One setup row in the Dream focus look: a badge or status mark, title and caption, a value, an
 * optional switch and chevron. Focused: the pearl box with dark ink inside the rainbow border.
 */
internal class BrandRow(
    ctx: Context, private val k: PremiumKit, icon: View?, chevron: Boolean, switch: Boolean?, private val onClick: (() -> Unit)?,
) : LinearLayout(ctx) {
    /**
     * NONE leaves no gap; OFF is a quiet hollow ring (a feature not on yet, keeping a list aligned);
     * PICK is a neutral check for the chosen item in a list (a choice, not a status).
     */
    enum class Mark { NONE, OK, WARN, BUSY, OFF, PICK }
    private val Int.dp get() = Ui.dp(context, this)
    val title: TextView
    val caption: TextView
    val value: TextView
    private val markLeft: TextView?
    private val markRight: TextView
    val sw: SetupSwitch?
    private var shown = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 62.dp
        setPadding(14.dp, 10.dp, 18.dp, 10.dp)
        val interactive = onClick != null
        isFocusable = interactive
        isClickable = interactive
        val glow = if (interactive) PearlFocus.apply(this, Ui.R_MD) else null
        if (!interactive) background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.035f), Ui.R_MD)
        if (icon != null) {
            addView(icon, LayoutParams(40.dp, 40.dp).apply { marginEnd = 16.dp })
            markLeft = null
        } else {
            markLeft = markView()
            addView(markLeft, LayoutParams(26.dp, 26.dp).apply { marginStart = 4.dp; marginEnd = 14.dp })
        }
        val texts = LinearLayout(ctx).apply { orientation = VERTICAL; isDuplicateParentStateEnabled = true }
        // Names stay on one line and never break mid-word ("AllDe-brid"); captions get two lines at most.
        title = k.label("", 17f, SetupBrand.TEXT, k.medium, lines = 1).apply {
            isDuplicateParentStateEnabled = true
            setTextColor(PearlFocus.states(PearlFocus.INK, SetupBrand.TEXT))
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
        caption = k.label("", 13.5f, SetupBrand.DIM, k.font, lines = 2).apply {
            isDuplicateParentStateEnabled = true
            setPadding(0, 4.dp, 0, 0)
            setLineSpacing(2.dp.toFloat(), 1f)
            ellipsize = android.text.TextUtils.TruncateAt.END
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            visibility = GONE
        }
        texts.addView(title)
        texts.addView(caption)
        addView(texts, LayoutParams(0, WRAP_CONTENT, 1f))
        markRight = markView()
        addView(markRight, LayoutParams(22.dp, 22.dp).apply { marginStart = 14.dp })
        value = k.label("", 14.5f, SetupBrand.DIM, k.medium, lines = 2).apply {
            isDuplicateParentStateEnabled = true
            gravity = Gravity.END
            maxWidth = 210.dp
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            visibility = GONE
        }
        addView(value, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 10.dp })
        sw = switch?.let { SetupSwitch(ctx, it, k).also { s -> addView(s, LayoutParams(46.dp, 28.dp).apply { marginStart = 16.dp }) } }
        if (chevron) addView(k.label("›", 26f, SetupBrand.DIM).apply {
            isDuplicateParentStateEnabled = true
            setTextColor(PearlFocus.states(PearlFocus.INK_DIM, Ui.alpha(Color.WHITE, 0.34f)))
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 14.dp })
        if (interactive) {
            setOnFocusChangeListener { v, has ->
                glow?.let { PearlFocus.focus(it, has) }
                Ui.lift(v, has, Ui.LIFT_ROW)
                sw?.lit = has
            }
            setOnClickListener { try { onClick?.invoke() } catch (e: Exception) { Log.w(SetupBrand.TAG, "Setup: row ${title.text}", e) } }
        }
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun markView() = k.label("", 13f, Color.WHITE, k.bold).apply { gravity = Gravity.CENTER; visibility = GONE }

    /** Only what changed is touched (no relayout, no flash). [tone]: the value's (and caption's) status color. */
    fun set(t: String, cap: String? = null, v: String? = null, tone: Int? = null, mark: Mark = Mark.NONE, capTone: Int? = null) {
        val key = "$t|$cap|$v|$tone|$mark|$capTone"
        if (key == shown) return
        shown = key
        if (title.text.toString() != t) title.text = t
        caption.text = cap ?: ""
        caption.visibility = if (cap.isNullOrEmpty()) GONE else VISIBLE
        caption.setTextColor(PearlFocus.states(PearlFocus.INK_DIM, capTone ?: SetupBrand.DIM))
        value.text = v ?: ""
        value.visibility = if (v.isNullOrEmpty()) GONE else VISIBLE
        value.setTextColor(PearlFocus.states(PearlFocus.INK_DIM, tone ?: Ui.alpha(Color.WHITE, 0.82f)))
        val m = markLeft ?: markRight
        if (markLeft == null) markRight.visibility = if (mark == Mark.NONE) GONE else VISIBLE
        paintMark(m, mark)
    }

    private fun paintMark(m: TextView, mark: Mark) {
        m.text = ""
        when (mark) {
            Mark.NONE -> { m.background = null; if (m === markLeft) m.visibility = GONE }
            else -> { m.visibility = VISIBLE; m.background = StatusGlyph(mark) }
        }
    }
}

/**
 * A status mark drawn as a vector (not a text "✓"): a solid disc in the status color with the shape
 * cut out in near-black, the same look as the availability pills' icons.
 */
internal class StatusGlyph(private val mark: BrandRow.Mark) : android.graphics.drawable.Drawable() {
    private val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(14, 14, 16)
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        if (mark == BrandRow.Mark.OFF) {
            // A quiet hollow ring: not on yet, same footprint as the others so a list stays aligned.
            disc.style = android.graphics.Paint.Style.STROKE
            disc.strokeWidth = s * 0.09f
            disc.color = Ui.alpha(Color.WHITE, 0.30f)
            c.drawCircle(cx, cy, s / 2f - disc.strokeWidth / 2f, disc)
            disc.style = android.graphics.Paint.Style.FILL
            return
        }
        disc.color = when (mark) {
            BrandRow.Mark.OK -> SetupBrand.GREEN; BrandRow.Mark.WARN -> SetupBrand.RED
            BrandRow.Mark.PICK -> Color.rgb(150, 150, 158) // mid gray: reads on a dark row and on the white focused one
            else -> Ui.alpha(Color.WHITE, 0.85f)
        }
        c.drawCircle(cx, cy, s / 2f, disc)
        ink.strokeWidth = s * 0.13f
        val u = s / 100f
        when (mark) {
            BrandRow.Mark.OK, BrandRow.Mark.PICK -> c.drawPath(android.graphics.Path().apply {
                moveTo(cx - 22 * u, cy + 1 * u); lineTo(cx - 7 * u, cy + 15 * u); lineTo(cx + 22 * u, cy - 14 * u)
            }, ink)
            BrandRow.Mark.WARN -> {
                c.drawLine(cx, cy - 22 * u, cx, cy + 4 * u, ink)
                ink.style = android.graphics.Paint.Style.FILL
                c.drawCircle(cx, cy + 20 * u, s * 0.075f, ink)
                ink.style = android.graphics.Paint.Style.STROKE
            }
            BrandRow.Mark.BUSY -> {
                ink.style = android.graphics.Paint.Style.FILL
                for (i in -1..1) c.drawCircle(cx + i * 20 * u, cy, s * 0.07f, ink)
                ink.style = android.graphics.Paint.Style.STROKE
            }
            BrandRow.Mark.NONE, BrandRow.Mark.OFF -> {}
        }
    }

    override fun setAlpha(alpha: Int) { disc.alpha = alpha; ink.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { disc.colorFilter = cf; ink.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * A bare drawn check (no disc), [StatusGlyph]'s shape: on a color swatch, a key, or over a disc that's
 * already there. [focusedColor] (when different) is used while the view it sits on is focused.
 */
internal class CheckInk(private val rest: Int, private val focusedColor: Int = rest, private val weight: Float = 0.12f) :
    android.graphics.drawable.Drawable() {
    private val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = rest
    }
    private var focused = false

    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = s / 100f
        pen.strokeWidth = s * weight
        pen.color = if (focused) focusedColor else rest
        c.drawPath(android.graphics.Path().apply {
            moveTo(cx - 22 * u, cy + 1 * u); lineTo(cx - 7 * u, cy + 15 * u); lineTo(cx + 22 * u, cy - 14 * u)
        }, pen)
    }

    override fun isStateful() = focusedColor != rest
    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
        if (f == focused) return false
        focused = f
        invalidateSelf()
        return true
    }
    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { pen.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Small parts every setup screen uses. */
internal class SetupParts(val ctx: Context, val k: PremiumKit) {
    val Int.dp get() = Ui.dp(ctx, this)

    fun row(icon: View? = null, chevron: Boolean = false, switch: Boolean? = null, onClick: (() -> Unit)? = null) = BrandRow(ctx, k, icon, chevron, switch, onClick)

    /** A section heading with its "Required" / "Optional" tag. */
    fun section(t: String, required: Boolean?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(6.dp, 0, 0, 0)
        addView(k.label(t.uppercase(), 12f, Ui.alpha(Color.WHITE, 0.62f), k.semibold, Ui.TRACK_CAPS))
        if (required != null) addView(tag(if (required) "Required" else "Optional", required), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
    }

    fun tag(t: String, strong: Boolean) = k.label(t, 11.5f, if (strong) Color.WHITE else SetupBrand.DIM, k.semibold, 0.04f).apply {
        setPadding(10.dp, 3.dp, 10.dp, 4.dp)
        background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, if (strong) 0.14f else 0.05f), Ui.R_PILL, 1f, Ui.alpha(Color.WHITE, if (strong) 0.30f else 0.16f))
    }

    fun note(t: String, size: Float = 15f) = k.label(t, size, SetupBrand.DIM, k.font, lines = 0).apply {
        setLineSpacing(4.dp.toFloat(), 1f)
        setPadding(6.dp, 0, 6.dp, 0)
    }

    fun pill(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        k.pill(label, null, primary, 46, dream = true) { onClick() }.apply { setPadding(30.dp, 0, 30.dp, 0) }

    fun pills(vararg p: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, v) in p.withIndex()) addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, 46.dp).apply { if (i > 0) marginStart = 14.dp })
    }

    /** A crisp QR code on a white card ([SetupUi.qr]'s look, larger). */
    fun qr(ui: SetupUi, text: String, sizeDp: Int) = ui.qr(text, sizeDp)

    fun resultMark(ok: Boolean?) = when (ok) { true -> BrandRow.Mark.OK; false -> BrandRow.Mark.WARN; null -> BrandRow.Mark.BUSY }

    /** A big result line: a status disc and a headline (the link and test results). */
    fun result(ok: Boolean?, headline: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // The drawn status disc (never a text check / "!" / "•").
        addView(View(ctx).apply { background = StatusGlyph(resultMark(ok)) }, LinearLayout.LayoutParams(40.dp, 40.dp))
        addView(k.label(headline, 22f, SetupBrand.TEXT, k.semibold, lines = 2), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 16.dp })
    }
}

/** Keyed [BrandRow]s on one [SetupScreen]: made once (in order), updated in place, hidden when not wanted. */
internal class ScreenRows(private val s: SetupScreen) {
    private val rows = HashMap<String, BrandRow>()
    fun row(key: String, top: Int, make: () -> BrandRow): BrandRow {
        rows[key]?.let { s.view(key, top) { it }; return it }
        val r = make()
        rows[key] = r
        s.view(key, top) { r }
        return r
    }
    fun get(key: String) = rows[key]
    fun clear() { rows.clear(); s.clear() }
}

/** A text entry screen: the Dream keyboard, extra keys, a note and an optional QR code. Phone Remote types into it too. */
internal fun setupEntry(
    ctx: Context, ui: SetupUi, parts: SetupParts, title: String, kicker: String?, hint: String, initial: String,
    extras: List<String>, note: String?, qr: String?, done: (String) -> Unit,
): SetupScreen {
    val k = ui.k
    val dp = { v: Int -> Ui.dp(ctx, v) }
    val s = SetupScreen(ui, title, kicker)
    val field = QueryField(ctx).apply {
        maxLen = 300
        textSize = 18f
        typeface = k.font
        setTextColor(SetupBrand.TEXT)
        setHintTextColor(Ui.alpha(Color.WHITE, 0.36f))
        this.hint = hint
        caretColor = Color.WHITE
        setPadding(dp(18), 0, dp(18), 0)
        background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.07f), Ui.R_PILL, 1f, Ui.alpha(Color.WHITE, 0.14f))
        setQuery(initial)
    }
    s.add(field.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)) }, 0)
    val kb = DreamKeyboard(k, field, 420, 32, 6, null).apply {
        glideStroke = true
        glideColors = { CinemaStyle.glowNow.let { intArrayOf(it, it) } }
        CinemaStyle.followGlow(this) { v, _ -> (v as DreamKeyboard).refreshGlide() }
    }
    field.focusRedirect = { kb.focusEntry() }
    fun key(label: String, act: () -> Unit) = ui.pill(label, false, 36, act).apply { textSize = Ui.T_CAPTION; setPadding(dp(16), 0, dp(16), 0) }
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val keys = extras.map { e -> key(e) { field.insert(e) } } + key("Caps") { kb.caps = !kb.caps }
    for ((i, v) in keys.withIndex()) row.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { if (i > 0) marginStart = dp(10) })
    s.add(row, 12)
    s.add(kb.apply { layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) }, 12)
    run {
        // The note, the website's QR (when there is one, captioned) and "Type on Your Phone" (PhoneTyping), side by side.
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(parts.note(note ?: "", 14f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (qr != null) ui.qr(qr, 104)?.let { code ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(code, LinearLayout.LayoutParams(dp(104), dp(104)))
            col.addView(k.label("Open the Website", 12.5f, Ui.alpha(Color.WHITE, 0.72f), k.medium).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            wrap.addView(col, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(18) })
        }
        wrap.addView(PhoneTyping.view(ctx, k, 104), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(18) })
        s.add(wrap, 14)
    }
    val ok = parts.pill("Done", true) { val t = field.text.toString().trim(); if (t.isNotEmpty() && t != "https://") done(t) }
    s.add(parts.pills(ok), 14)
    s.focus = { kb.entryKey() }
    s.onShow = { SetupText.shown(field) }
    s.onHide = { SetupText.hidden(field) }
    return s
}

// =====================================================================================
// The gate: what "ready to watch" means
// =====================================================================================

internal object SetupGate {
    private const val PREFS = "dream_setup"
    private const val DAY = 86_400_000L
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- the setup's place (resume) ----

    fun savedStep(ctx: Context): String? = prefs(ctx).getString("step", null)
    fun saveStep(ctx: Context, step: String) = prefs(ctx).edit().putString("step", step).apply()
    fun checkPassed(ctx: Context) = prefs(ctx).getBoolean("check_ok", false)
    fun setCheckPassed(ctx: Context, ok: Boolean) = prefs(ctx).edit().putBoolean("check_ok", ok).apply()
    fun zoneConfirmed(ctx: Context) = prefs(ctx).getBoolean("zone_ok", false)
    fun setZoneConfirmed(ctx: Context) = prefs(ctx).edit().putBoolean("zone_ok", true).apply()
    fun reset(ctx: Context) = prefs(ctx).edit().remove("step").remove("check_ok").remove("zone_ok").apply()

    // ---- accounts ----

    /** Verified live (the last account check, at most [AccountHealth.STALE_MS] old) with an active premium plan. */
    fun verified(ctx: Context, s: String, fresh: Boolean = false): Boolean {
        if (!DreamAccounts.linked(ctx, s)) return false
        val i = AccountHealth.last(ctx, s) ?: return false
        if (fresh && System.currentTimeMillis() - i.at > AccountHealth.STALE_MS) return false
        if (i.health != AccountHealth.Health.OK && i.health != AccountHealth.Health.LIMIT) return false
        if (i.premium == false) return false
        return !(i.until in 1 until System.currentTimeMillis())
    }

    fun anyVerified(ctx: Context, fresh: Boolean = false) = DreamAccounts.linkedInOrder(ctx).any { verified(ctx, it, fresh) }

    private val DATE = SimpleDateFormat("MMM d, yyyy", Locale.US)

    /** A service's live status: (words, color, mark). [checking]: a check is running for it now. */
    fun status(ctx: Context, s: String, checking: Boolean): Triple<String, Int?, BrandRow.Mark> {
        if (!DreamAccounts.linked(ctx, s)) return Triple("Not Connected", null, BrandRow.Mark.NONE)
        if (checking) return Triple("Checking…", null, BrandRow.Mark.BUSY)
        val i = AccountHealth.last(ctx, s) ?: return Triple("Connected · Not Checked Yet", null, BrandRow.Mark.NONE)
        val now = System.currentTimeMillis()
        return when {
            i.health == AccountHealth.Health.INVALID -> Triple("Sign In Again", SetupBrand.RED, BrandRow.Mark.WARN)
            i.premium == false || i.until in 1 until now -> Triple("Expired", SetupBrand.RED, BrandRow.Mark.WARN)
            i.health == AccountHealth.Health.OFFLINE -> Triple("Couldn't Check Right Now", null, BrandRow.Mark.NONE)
            i.until > 0 -> Triple("Connected · Premium Until ${DATE.format(Date(i.until))}", SetupBrand.GREEN, BrandRow.Mark.OK)
            else -> Triple("Connected · Premium", SetupBrand.GREEN, BrandRow.Mark.OK)
        }
    }

    /** What still keeps the Accounts step from being done, in plain words; null when it's done. */
    fun accountsBlocker(ctx: Context, checking: Boolean): String? {
        val linked = DreamAccounts.linkedInOrder(ctx)
        if (!Tmdb.hasKey(ctx) && linked.any { verified(ctx, it) }) return "Add your TMDB key to continue."
        if (linked.any { verified(ctx, it) }) return if (Tmdb.hasKey(ctx)) null else "Add your TMDB key to continue."
        if (linked.isEmpty()) return "Connect at least one debrid service to continue."
        if (checking) return "Checking your account…"
        val s = linked.first()
        val n = AccountHealth.name(s)
        val i = AccountHealth.last(ctx, s)
        return when {
            i == null -> "Checking your account…"
            i.health == AccountHealth.Health.INVALID -> "Sign in to $n again, or connect another service, to continue."
            i.premium == false || i.until in 1 until System.currentTimeMillis() -> "Your $n plan has expired. Renew it, or connect another service, to continue."
            else -> "Couldn't reach $n. Check your internet connection, then choose $n to check again."
        }
    }

    fun scrapersReady(ctx: Context) = try { Scrapers.status(ctx).ready } catch (e: Exception) { false }

    // ---- Home's prompt (people who finished setup before a requirement existed) ----

    /** What an existing setup lacks: (headline, line, Settings page), or null. No network. */
    fun missing(ctx: Context): Triple<String, String, String>? = when {
        DreamAccounts.linkedInOrder(ctx).isEmpty() ->
            Triple("Connect a Debrid Service", "Dream OS plays everything through your own debrid account. Connect one to keep watching.", DreamSettingsPage.ACCOUNTS)
        !scrapersReady(ctx) ->
            Triple("Add a Scraper Repository", "Scrapers find the streams for what you pick. Add the repository you use to keep watching.", DreamSettingsPage.SCRAPERS)
        else -> null
    }

    @Volatile private var promptedThisRun = false
    private var promptView: View? = null

    /**
     * Once per run (and at most twice a day), over Home: one card saying what's missing, with
     * "Set Up Now" (that Settings page) and "Later". Nothing when setup isn't finished or nothing's missing.
     */
    fun maybePrompt(a: Activity) {
        if (promptedThisRun || promptView != null || !Profiles.isSetUp(a) || DreamSettingsPage.isOpen()) return
        val miss = missing(a) ?: return
        val p = prefs(a)
        if (System.currentTimeMillis() - p.getLong("prompt_at", 0) < DAY / 2) return
        promptedThisRun = true
        p.edit().putLong("prompt_at", System.currentTimeMillis()).apply()
        Log.w(SetupBrand.TAG, "Setup: Home prompt, missing ${miss.third}")
        showPrompt(a, miss.first, miss.second, miss.third)
    }

    fun showPrompt(a: Activity, headline: String, line: String, page: String) {
        if (promptView != null) return
        val k = PremiumKit(a)
        val parts = SetupParts(a, k)
        val dp = { v: Int -> Ui.dp(a, v) }
        val back = a.currentFocus
        lateinit var root: OverlayRoot
        fun close(then: (() -> Unit)?) {
            val r = root
            promptView = null
            r.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT).withEndAction { (r.parent as? ViewGroup)?.removeView(r) }.start()
            if (then == null && back != null && back.isAttachedToWindow) back.requestFocus()
            then?.invoke()
        }
        root = OverlayRoot(a) { close(null) }
        root.isClickable = true
        root.setBackgroundColor(Color.argb(170, 0, 0, 0))
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(36), dp(32), dp(40), dp(32))
            background = Ui.rounded(a, Color.rgb(18, 18, 21), Ui.R_XL, 1f, Ui.alpha(Color.WHITE, 0.10f))
        }
        card.addView(SetupArt(a, if (page == DreamSettingsPage.SCRAPERS) SetupArt.Kind.SCRAPERS else SetupArt.Kind.ACCOUNTS), LinearLayout.LayoutParams(dp(130), dp(130)))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(k.label("FINISH SETTING UP", 12f, Ui.alpha(Color.WHITE, 0.62f), k.semibold, Ui.TRACK_CAPS))
        col.addView(k.label(headline, 26f, SetupBrand.TEXT, k.semibold, Ui.TRACK_TITLE, lines = 2), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        col.addView(parts.note(line, 16f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        val go = parts.pill("Set Up Now", true) { close { DreamSettingsPage.open(a, page) } }
        val later = parts.pill("Later", false) { close(null) }
        col.addView(parts.pills(go, later), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(24) })
        card.addView(col, LinearLayout.LayoutParams(dp(420), WRAP_CONTENT).apply { marginStart = dp(32) })
        root.addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        Ui.unclip(root)
        (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
        promptView = root
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        card.translationY = dp(14).toFloat()
        card.animate().translationY(0f).setDuration(Ui.SHEET_IN).setInterpolator(Ui.EASE).start()
        go.post { go.requestFocus() }
        // Met meanwhile (a repository added from the phone, an account linked elsewhere): the card leaves by itself.
        val watch = object : Runnable {
            override fun run() {
                if (promptView !== root) return
                if (missing(a) == null) { Log.w(SetupBrand.TAG, "Setup: Home prompt closed, nothing missing now"); close(null) }
                else root.postDelayed(this, 2000)
            }
        }
        root.postDelayed(watch, 2000)
    }

    fun isPromptOpen() = promptView != null
}

/** A full-screen layer over the app: keeps focus inside, Back runs [onBack]. */
internal class OverlayRoot(ctx: Context, private val onBack: () -> Unit) : FrameLayout(ctx) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun focusSearch(focused: View?, direction: Int): View? =
        FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused

    /** Full-screen layers under this one that [coverBelow] hid (to show again in [uncover]). */
    private val hidden = ArrayList<View>()
    private val coverCheck = Runnable { cover() }

    /**
     * An opaque page (its own full-screen backdrop): once it has faded fully in, the full-screen layers under
     * it (the Settings page, Home) stop drawing, since every frame of this page used to draw them too. Call
     * [uncover] as the page starts to close, before its fade-out shows them again.
     */
    fun coverBelow() { postDelayed(coverCheck, Ui.OVERLAY_IN + 60) }

    private fun cover() {
        val decor = parent as? ViewGroup ?: return
        if (alpha < 1f || !isAttachedToWindow) { postDelayed(coverCheck, 100); return }
        val me = decor.indexOfChild(this)
        for (i in 0 until me) {
            val v = decor.getChildAt(i)
            if (v.visibility != View.VISIBLE || v.width < decor.width || v.height < decor.height) continue
            v.visibility = View.INVISIBLE
            hidden += v
        }
    }

    fun uncover() {
        removeCallbacks(coverCheck)
        for (v in hidden) if (v.visibility == View.INVISIBLE) v.visibility = View.VISIBLE
        hidden.clear()
    }

    override fun onDetachedFromWindow() {
        uncover()
        super.onDetachedFromWindow()
    }
}

// =====================================================================================
// Connect Your Accounts
// =====================================================================================

/**
 * The Accounts screens for a [SetupPanel]. [onChange] runs (main thread) whenever something that
 * decides the gate may have changed: a link, an unlink, a check's answer, a key saved.
 */
internal class AccountsPages(
    private val ctx: Context,
    private val ui: SetupUi,
    private val panel: SetupPanel,
    private val inSetup: Boolean,
    private val onChange: () -> Unit,
) {
    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    private val Int.dp get() = Ui.dp(ctx, this)
    private fun app() = ctx.applicationContext
    private val checking = HashSet<String>()
    private var checkedOnce = false

    fun isChecking() = checking.isNotEmpty()

    /** Checks [s] with its service (one read-only account call), then repaints whatever shows. */
    fun check(s: String, then: (() -> Unit)? = null) {
        if (s in checking || !DreamAccounts.linked(app(), s)) return
        checking += s
        onChange()
        Net.run {
            val i = try { AccountHealth.check(app(), s) } catch (e: Exception) { null }
            Log.w(SetupBrand.TAG, "Setup: step Accounts, ${AccountHealth.name(s)} " +
                (if (SetupGate.verified(app(), s)) "verified" else "not ready (${i?.health ?: "no answer"}${if (i?.premium == false) ", no premium" else ""})"))
            ui.main.post {
                checking -= s
                onChange()
                panel.top?.let { if (it.alive) it.onShow() }
                then?.invoke()
            }
        }
    }

    // ---- the overview ----

    fun overview(): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val rows = ScreenRows(s)
        val keys = KeyPages(ctx, ui, panel, onChange)
        // A beginner's setup (the check hasn't passed yet) leads with the help; Settings leads with the accounts.
        val helpFirst = inSetup && !SetupGate.checkPassed(app())
        fun help(top: Int) = SetupGuide.helpRows(rows, "debrid", parts, panel, ui, "Sign Up, Then Connect It to Dream OS",
            "Expired Codes, Plans and Keys", { SetupGuide.debridSteps() }, SetupGuide.DEBRID_TROUBLE, top = top)
        fun paint() {
            s.view("k:debrid", 0) { parts.section("Debrid Services", true) }
            s.view("n:pick", 8) { parts.note(SetupGuide.PICK_LINE, 14f) }
            if (helpFirst) help(12)
            for ((i, key) in SetupBrand.SERVICES.withIndex()) {
                val r = rows.row("d:$key", if (i == 0) (if (helpFirst) 18 else 12) else 8) {
                    parts.row(SetupArt.monogram(ctx, SetupBrand.monogram(key)), chevron = true) {
                        if (DreamAccounts.linked(app(), key)) panel.push(service(key)) else link(key)?.let { panel.push(it) }
                    }
                }
                val (words, tone, mark) = SetupGate.status(app(), key, key in checking)
                val linked = DreamAccounts.linked(app(), key)
                val user = DreamAccounts.user(app(), key)
                // Short value on the right ("Connected"); who's signed in and the plan go under the name.
                val plan = words.substringAfter(" · ", "").takeIf { linked && it.isNotEmpty() }
                val cap = if (linked) listOfNotNull(user?.let { "Signed In as $it" }, plan).joinToString("  ·  ").ifEmpty { SetupBrand.site(key) }
                    else SetupBrand.site(key)
                r.set(AccountHealth.name(key), cap, if (linked) words.substringBefore(" · ") else "Connect", tone, if (linked) mark else BrandRow.Mark.NONE)
            }
            if (!helpFirst) help(8)
            rows.row("info", 8) { parts.row(null, chevron = true) { panel.push(debridInfo()) } }
                .set("What's a Debrid Service?", "What It Does, and Where to Get One")
            rows.row("best", 8) { parts.row(null, chevron = true) { panel.push(debridBest()) } }
                .set("Which Service Works Best?", "Premiumize Gives the Fastest Play")
            // Setup has its own Connect Movie & Show Info step; Settings' Accounts shows the key while there is none.
            if (!inSetup && (!Tmdb.hasKey(app()) || s.has("k:tmdb"))) {
                s.view("k:tmdb", 24) { parts.section("Movie & Show Info", true) }
                keys.addRows(s, rows, KeyPages.Kind.TMDB, 10)
            }
            s.view("k:trakt", 24) { parts.section("Trakt", false) }
            s.view("n:trakt", 8) { parts.note("Keeps your watch history and watchlist in sync across your devices.", 14f) }
            val signedIn = Trakt.signedIn(app())
            rows.row("trakt", 10) { parts.row(SetupArt.monogram(ctx, "T"), chevron = true) { panel.push(TraktPages(ctx, ui, panel, fromAccounts = true).overview()) } }
                .set("Trakt", if (signedIn) Trakt.lastSyncedText(app()) else "trakt.tv", TraktPage.status(app()),
                    if (signedIn) SetupBrand.GREEN else null, if (signedIn) BrandRow.Mark.OK else BrandRow.Mark.NONE)
            rows.row("trakt:how", 8) { parts.row(null, chevron = true) {
                panel.push(SetupGuide.guide(ui, parts, panel, "Show Me How to Connect Trakt", SetupGuide.traktSteps()))
            } }.set("Show Me How to Connect Trakt", "Optional and Free. Add It Now or Later in Settings")
        }
        s.onShow = {
            paint()
            // Setup checks every linked service once when it opens (the gate needs a live answer); later, only old answers.
            val first = !checkedOnce
            checkedOnce = true
            for (key in DreamAccounts.linkedInOrder(app())) if ((inSetup && first) || AccountHealth.stale(app(), key)) check(key)
            onChange()
        }
        s.onResume = { paint(); onChange() }
        return s
    }

    // ---- one service ----

    fun service(key: String): SetupScreen {
        val name = AccountHealth.name(key)
        val s = SetupScreen(ui, name, null)
        val rows = ScreenRows(s)
        s.onShow = paint@{
            if (!DreamAccounts.linked(app(), key)) { panel.pop(); return@paint }
            val (words, tone, mark) = SetupGate.status(app(), key, key in checking)
            val user = DreamAccounts.user(app(), key) ?: AccountHealth.last(app(), key)?.user
            rows.row("acct", 0) { parts.row(SetupArt.monogram(ctx, SetupBrand.monogram(key))) }.set(user ?: name, SetupBrand.site(key))
            rows.row("status", 8) { parts.row() }.set("Status", null, words, tone, mark)
            val bad = tone == SetupBrand.RED
            if (bad) s.view("fix", 10) { parts.note("") }.let { (it as TextView).text = SetupGate.accountsBlocker(app(), false)?.takeIf { _ -> true }
                ?.let { _ -> if (words == "Expired") "Renew your plan on $name's website, then choose Check Again." else "Link $name again to sign back in." } ?: "" }
            else s.hide("fix")
            s.view("k:act", 22) { parts.section("Actions", null) }
            rows.row("check", 10) { parts.row(null) { check(key) } }.set("Check Again", "Asks $name for Your Account's Status", if (key in checking) "Checking…" else null)
            val order = DreamAccounts.linkedInOrder(app())
            if (order.size > 1 && order.first() != key) rows.row("primary", 8) { parts.row(null) { DreamAccounts.makePrimary(app(), key); s.onShow() } }
                .set("Use First", "Dream OS Asks $name Before Your Other Services")
            else s.hide("primary")
            rows.row("again", 8) { parts.row(null, chevron = true) { link(key)?.let { panel.push(it) } } }.set("Link Again", "Sign In Again, or With Another Account")
            rows.row("unlink", 8) { parts.row(null, chevron = true) { panel.push(confirmUnlink(key)) } }.set("Disconnect", "Dream OS Stops Using This Account")
        }
        s.focus = { rows.get("check") }
        return s
    }

    private fun confirmUnlink(key: String): SetupScreen {
        val name = AccountHealth.name(key)
        val s = SetupScreen(ui, "Disconnect $name?", null)
        s.add(parts.note("Dream OS stops using $name on this TV. You can connect it again anytime."), 0)
        val cancel = parts.pill("Cancel", false) { panel.pop() }
        val go = parts.pill("Disconnect", false) {
            DreamAccounts.unlink(app(), key)
            try { DebridExpiry.accountsChanged(app()) } catch (e: Exception) {}
            AccountHealth.forget(app(), key)
            Log.w(SetupBrand.TAG, "Setup: step Accounts, $name disconnected")
            onChange()
            panel.pop(); panel.pop()
        }
        s.add(parts.pills(cancel, go), 22)
        s.focus = { cancel }
        return s
    }

    // ---- linking ----

    /** The sign-in for [key]: a short code and a QR code (or, for Premiumize without a sign-in in this build, its key). */
    fun link(key: String): SetupScreen? {
        if (key !in AccountHealth.NAMES) return null
        // Premiumize without its own client id in this build: one QR code to a small Dream OS page on the phone
        // (typing the key on the TV only without Wi-Fi). Never another app's client id.
        if (!DebridLink.deviceSignIn(key)) return if (PhoneRemote.current != null && PhoneRemote.localIp() != null) phoneLink(key) else apiKey(key)
        val name = AccountHealth.name(key)
        val s = SetupScreen(ui, "Connect $name", null)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val step = k.label("Getting a code…", 15f, SetupBrand.DIM, k.font, lines = 2)
        val site = k.label("", 20f, SetupBrand.TEXT, k.semibold, lines = 2)
        val code = k.label("", 46f, SetupBrand.TEXT, k.semibold).apply { letterSpacing = 0.14f; visibility = View.GONE }
        val status = k.label("", 14f, SetupBrand.DIM, k.font, lines = 2).apply { visibility = View.GONE }
        col.addView(step)
        col.addView(site, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 6.dp })
        col.addView(code, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        col.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 14.dp })
        val qrBox = FrameLayout(ctx)
        wrap.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        wrap.addView(qrBox, LinearLayout.LayoutParams(160.dp, 160.dp).apply { marginStart = 24.dp })
        s.add(wrap, 0)
        // "What to Try", filled when the sign-in fails (under the message, above the buttons).
        val helpBox = FrameLayout(ctx).apply { visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }
        s.add(helpBox, 16)
        val button = parts.pill("Cancel", false) { panel.pop() }
        s.add(parts.pills(button), 22)
        s.focus = { button }
        val tag = "setup-link:$key"
        var finished = false
        var started = false
        fun stop() { finished = true; ui.main.removeCallbacksAndMessages(tag) }
        s.onBack = { stop(); false }
        s.onHide = { stop() }
        fun fail(message: String) {
            stop()
            qrBox.removeAllViews()
            step.text = message
            site.text = ""
            code.visibility = View.GONE
            status.visibility = View.GONE
            helpBox.removeAllViews()
            helpBox.addView(SetupGuide.whatToTry(ctx, k, SetupGuide.linkHelp(message, name, key = false)))
            helpBox.visibility = View.VISIBLE
            button.text = "Try Again"
            button.setOnClickListener { panel.replace(link(key) ?: return@setOnClickListener) }
        }
        fun poll(p: DebridLink.Pending) {
            if (finished || !s.alive) return
            s.bg({ DebridLink.poll(p) }) { r ->
                if (finished) return@bg
                when (r) {
                    is DebridLink.Poll.Waiting -> ui.main.postAtTime({ poll(p) }, tag, SystemClock.uptimeMillis() + p.intervalS * 1000L)
                    is DebridLink.Poll.Failed -> fail(r.message)
                    is DebridLink.Poll.Done -> {
                        stop()
                        status.text = "Approved. Saving…"
                        Log.w(SetupBrand.TAG, "Setup: step Accounts, $name approved")
                        s.bg({ DreamAccounts.link(app(), key, r.settings); AccountHealth.forget(app(), key) }) { panel.replace(verify(key)) }
                    }
                }
            }
        }
        fun begin(retries: Int) {
            s.bg({ DebridLink.start(key) ?: Unit }) { p ->
                if (finished) return@bg
                if (p !is DebridLink.Pending) {
                    if (retries > 0) ui.main.postAtTime({ if (s.alive && !finished) begin(retries - 1) }, tag, SystemClock.uptimeMillis() + 3000)
                    else fail("$name didn't answer. Check your internet connection and try again.")
                    return@bg
                }
                step.text = "On your phone, scan the code, or go to"
                site.text = p.url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                code.text = p.code
                code.visibility = View.VISIBLE
                status.text = "Then enter this code. Waiting for you to approve…"
                status.visibility = View.VISIBLE
                ui.qr(p.qr, 160)?.let { v -> v.alpha = 0f; qrBox.addView(v); v.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start() }
                ui.main.postAtTime({ poll(p) }, tag, SystemClock.uptimeMillis() + p.intervalS * 1000L)
            }
        }
        s.onShow = { if (!started) { started = true; begin(1) } }
        return s
    }

    /**
     * Premiumize without a device sign-in in this build, in one scan: the QR code pairs the phone (one-time
     * code, home network only) and opens a small Dream OS page there with Open Your Premiumize Account and
     * Paste Your API Key + Link. The key comes straight to this screen ([PhoneRemote.onPremiumizeKey]), is
     * checked with Premiumize and linked; it's never logged. The phone remote's server runs only while this
     * screen does (unless the remote was already on: [PhoneTyping]).
     */
    private fun phoneLink(key: String): SetupScreen {
        val name = AccountHealth.name(key)
        val s = SetupScreen(ui, "Connect $name", null)
        val rows = ScreenRows(s)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(ui.steps(listOf(
            "Scan the code with your phone's camera.",
            "Tap Open Your $name Account, then copy your API key.",
            "Paste it on your phone and tap Link.",
        )), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val qrBox = FrameLayout(ctx)
        wrap.addView(qrBox, LinearLayout.LayoutParams(160.dp, 160.dp).apply { marginStart = 24.dp })
        s.add(wrap, 0)
        val status = rows.row("status", 18) { parts.row() }
        fun waiting() = status.set("Waiting for Your Phone", "Your key goes straight to this TV, on your home network only.", mark = BrandRow.Mark.BUSY)
        waiting()
        // Only when the phone page can't open (no Wi-Fi on the TV): the key typed on the TV instead.
        val typeIt = parts.pill("Type It on the TV", false) { panel.replace(apiKey(key)) }.apply { visibility = View.GONE }
        val cancel = parts.pill("Cancel", false) { panel.pop() }
        s.view("buttons", 22) { parts.pills(cancel, typeIt) }
        s.focus = { cancel }
        val tag = "setup-pm-phone"
        var session = false
        var shownUrl: String? = null
        var checking = false
        fun showQr(url: String?) {
            if (url == null || url == shownUrl) return
            shownUrl = url
            qrBox.removeAllViews()
            ui.qr(url, 160)?.let { v -> v.alpha = 0f; qrBox.addView(v); v.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start() }
        }
        // A phone used the one-time code: the QR code gets a fresh one (a second scan still works).
        fun refresh() {
            if (!s.alive || !session) return
            showQr(PhoneTyping.url("pm"))
            ui.main.postAtTime({ refresh() }, tag, SystemClock.uptimeMillis() + 3000)
        }
        fun end() {
            ui.main.removeCallbacksAndMessages(tag)
            PhoneRemote.onPremiumizeKey = null
            if (session) { session = false; PhoneTyping.end() }
        }
        s.onShow = {
            if (!session) {
                session = PhoneRemote.current != null
                showQr(PhoneTyping.begin("pm"))
                if (shownUrl == null) {
                    status.set("Connect the TV to Wi-Fi", "Then try again, or type the key on the TV.", mark = BrandRow.Mark.WARN)
                    typeIt.visibility = View.VISIBLE
                }
                ui.main.postAtTime({ refresh() }, tag, SystemClock.uptimeMillis() + 3000)
            }
            PhoneRemote.onPremiumizeKey = hook@{ typed, reply ->
                if (!s.alive) { reply(false, "Choose $name on your TV first."); return@hook }
                if (checking) { reply(false, "Still checking your last key. One moment."); return@hook }
                checking = true
                status.set("Checking Your Key", "One Moment…", mark = BrandRow.Mark.BUSY)
                Log.w(SetupBrand.TAG, "Setup: step Accounts, $name key received from the phone")
                Net.run {
                    val r = try { DebridLink.premiumizeKey(typed) } catch (e: Exception) { DebridLink.Poll.Failed("$name didn't answer. Try again.") }
                    if (r is DebridLink.Poll.Done) { DreamAccounts.link(app(), key, r.settings); AccountHealth.forget(app(), key) }
                    ui.main.post {
                        checking = false
                        when (r) {
                            is DebridLink.Poll.Done -> {
                                reply(true, "Linked. Your TV shows the green check.")
                                onChange()
                                if (s.alive) panel.replace(verify(key))
                            }
                            is DebridLink.Poll.Failed -> {
                                reply(false, r.message)
                                if (s.alive) {
                                    status.set("That Key Didn't Work", r.message, mark = BrandRow.Mark.WARN)
                                    val box = s.view("help", 14) { FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) } } as FrameLayout
                                    box.removeAllViews()
                                    box.addView(SetupGuide.whatToTry(ctx, k, SetupGuide.linkHelp(r.message, name, key = true)))
                                }
                            }
                            else -> { reply(false, "Something went wrong. Try again."); if (s.alive) waiting() }
                        }
                    }
                }
            }
        }
        s.onHide = { end() }
        return s
    }

    /** Premiumize without a device sign-in in this build: its API key, typed or sent from the phone. */
    private fun apiKey(key: String): SetupScreen {
        val name = AccountHealth.name(key)
        return setupEntry(ctx, ui, parts, "$name Key", null, "Your $name Key", "", emptyList(),
            "Scan the code to open your $name account page, then copy the key shown as API Key. Phone Remote can type it here.",
            "https://www.premiumize.me/account") { typed ->
            val s = SetupScreen(ui, "Connect $name", null)
            val rows = ScreenRows(s)
            rows.row("check", 0) { parts.row() }.set("Checking Your Key", "One Moment…", mark = BrandRow.Mark.BUSY)
            panel.replace(s)
            s.bg({ DebridLink.premiumizeKey(typed) }) { r ->
                when (r) {
                    is DebridLink.Poll.Done -> s.bg({ DreamAccounts.link(app(), key, r.settings); AccountHealth.forget(app(), key) }) { panel.replace(verify(key)) }
                    is DebridLink.Poll.Failed -> {
                        rows.get("check")?.set("That Key Didn't Work", r.message, mark = BrandRow.Mark.WARN)
                        s.view("help", 14) { SetupGuide.whatToTry(ctx, k, SetupGuide.linkHelp(r.message, name, key = true)) }
                        val again = parts.pill("Try Again", true) { panel.replace(apiKey(key)) }
                        s.view("again", 18) { parts.pills(again) }
                        again.requestFocus()
                    }
                    else -> {}
                }
            }
        }
    }

    /** Right after a sign-in: checks the account with its service and says plainly whether it's ready. */
    private fun verify(key: String): SetupScreen {
        val name = AccountHealth.name(key)
        val s = SetupScreen(ui, "Connect $name", null)
        val head = s.view("head", 0) { parts.result(null, "Checking Your Account…") } as LinearLayout
        val line = s.view("line", 12) { parts.note("") } as TextView
        val done = parts.pill("Done", true) { panel.pop() }
        var recheck: () -> Unit = {}
        val again = parts.pill("Check Again", false) { recheck() }
        s.view("buttons", 24) { parts.pills(done, again) }
        again.visibility = View.GONE
        s.focus = { done }
        fun show() {
            val ok = SetupGate.verified(app(), key)
            val (words, _, _) = SetupGate.status(app(), key, key in checking)
            head.getChildAt(0).background = StatusGlyph(parts.resultMark(if (key in checking) null else ok))
            (head.getChildAt(1) as TextView).text = when {
                key in checking -> "Checking Your Account…"
                ok -> "$name Is Connected"
                else -> "$name Isn't Ready Yet"
            }
            val user = DreamAccounts.user(app(), key)
            line.text = when {
                key in checking -> "One moment while Dream OS asks $name about your plan."
                ok -> listOfNotNull(user?.let { "Signed in as $it." }, words.removePrefix("Connected · ").let { "$it." }).joinToString(" ")
                words == "Expired" -> "This account doesn't have an active premium plan. Renew it on $name's website, then choose Check Again."
                else -> "Dream OS couldn't confirm your plan. Check your internet connection, then choose Check Again."
            }
            again.visibility = if (key in checking || ok) View.GONE else View.VISIBLE
        }
        recheck = { check(key) { if (s.alive) show() }; show() }
        var started = false
        s.onShow = { if (!started) { started = true; recheck() } else show() }
        return s
    }

    private fun debridInfo(): SetupScreen {
        val s = SetupScreen(ui, "What's a Debrid Service?", null)
        s.add(parts.note("A debrid service is a paid account that keeps a huge library of movies and shows on fast servers. " +
            "When you pick something, Dream OS finds it and plays it from your account in full quality, with no waiting."), 0)
        s.add(parts.note("You need at least one. If you don't have one yet, sign up on your phone with any of these, then come back and connect it."), 12)
        for ((i, key) in SetupBrand.SERVICES.withIndex()) {
            val r = parts.row(SetupArt.monogram(ctx, SetupBrand.monogram(key)), chevron = true) { panel.push(siteQr(key)) }
            r.set(AccountHealth.name(key), SetupBrand.site(key), "Sign Up")
            s.add(r, if (i == 0) 18 else 8)
        }
        return s
    }

    /**
     * Which Service Works Best? (owner, 2026-10-28; the iPhone's page): Premiumize and TorBox can be asked whether a
     * title is ready without adding anything to the account, so Instant Play and the pills check ahead; AllDebrid
     * and Real-Debrid are asked only on Play (magnet safety).
     */
    private fun debridBest(): SetupScreen {
        val s = SetupScreen(ui, "Which Service Works Best?", null)
        s.add(parts.note("Premiumize is the best match for Dream OS. Dream OS can ask Premiumize whether a title is ready without adding anything to your account, " +
            "so your stream is ready the moment you open a title, and Available Now is checked before you reach it. Play starts almost instantly."), 0)
        s.add(parts.note("TorBox works the same way, so it's a great choice too."), 12)
        s.add(parts.note("AllDebrid and Real-Debrid answer that question only once a title is added to your account. To keep your account tidy, " +
            "Dream OS asks them when you choose Play, so Play takes a second or two longer. Everything else works the same."), 12)
        s.add(parts.note("You can connect more than one. Dream OS asks them in the order you choose."), 12)
        for ((i, key) in listOf("pm", "tb").withIndex()) {
            val r = parts.row(SetupArt.monogram(ctx, SetupBrand.monogram(key)), chevron = true) { panel.push(siteQr(key)) }
            r.set(AccountHealth.name(key), SetupBrand.site(key), "Sign Up")
            s.add(r, if (i == 0) 18 else 8)
        }
        return s
    }

    private fun siteQr(key: String): SetupScreen {
        val name = AccountHealth.name(key)
        val s = SetupScreen(ui, "Sign Up for $name", null)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(parts.note("Scan the code with your phone's camera, or go to ${SetupBrand.site(key)}. Once your plan is active, come back and connect it."),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        ui.qr("https://" + (if (key == "pm") "www." else "") + SetupBrand.site(key), 160)?.let { wrap.addView(it, LinearLayout.LayoutParams(160.dp, 160.dp).apply { marginStart = 24.dp }) }
        s.add(wrap, 0)
        val back = parts.pill("Done", true) { panel.pop() }
        s.add(parts.pills(back), 22)
        s.focus = { back }
        return s
    }

}

// =====================================================================================
// Set Up Scrapers
// =====================================================================================

internal class ScraperPages(
    private val ctx: Context,
    private val ui: SetupUi,
    private val panel: SetupPanel,
    private val onChange: () -> Unit,
) {
    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    private val Int.dp get() = Ui.dp(ctx, this)
    private fun app() = ctx.applicationContext
    private var lastUrl = ""
    /** A repository was just added: back on the overview, focus lands on Use Recommended Settings. */
    private var focusRecommended = false

    fun overview(): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val rows = ScreenRows(s)
        var shape = ""
        lateinit var paint: () -> Unit
        paint = {
            val st = try { Scrapers.status(app()) } catch (e: Exception) { Scrapers.Status(emptyList(), emptyList(), false) }
            val now = st.repos.joinToString { it.id } + "|" + st.providers.joinToString { it.id }
            if (now != shape) {
                // What's listed changed (a repository came or went): rebuilt in order, focus kept on the same item where it still exists.
                val focusedKey = (s.body.findFocus()?.let { f -> generateSequence(f) { it.parent as? View }.firstOrNull { it.parent === s.body } })?.let { v ->
                    listOf("add", "rec", "all").firstOrNull { rows.get(it) === v } ?: st.providers.firstOrNull { rows.get("p:${it.id}") === v }?.let { "p:${it.id}" }
                }
                rows.clear()
                shape = now
                if (focusedKey != null) ui.main.post { if (s.alive) (rows.get(focusedKey) ?: s.focus())?.requestFocus() }
            }
            if (st.repos.isEmpty()) {
                s.view("k:repo", 0) { parts.section("Your Repository", true) }
                s.view("none", 10) { parts.note("No repository yet. Add the one you use by typing its address. Repositories are shared by the people who make them, usually on a web page or on GitHub.", 15f) }
                s.view("examples", 12) { SetupGuide.addressExamples(ctx, k) }
                rows.row("add", 14) { parts.row(null, chevron = true) { panel.push(addRepo()) } }
                    .set("Add Scraper Repository", "Type Its Address, Like Adding a Repository on Other Media Apps")
                helpRows(rows)
            } else {
                s.view("k:repo", 0) { parts.section(if (st.repos.size == 1) "Your Repository" else "Your Repositories", true) }
                for ((i, r) in st.repos.withIndex()) {
                    rows.row("r:${r.id}", if (i == 0) 10 else 8) { parts.row(SetupArt.monogram(ctx, r.name.take(1).uppercase()), chevron = true) { panel.push(repo(r)) } }
                        .set(r.name, listOfNotNull(r.version.takeIf { it.isNotBlank() }?.let { "Version $it" }, host(r.url)).joinToString(" · "),
                            "Installed", SetupBrand.GREEN, BrandRow.Mark.OK)
                }
                rows.row("add", 8) { parts.row(null, chevron = true) { panel.push(addRepo()) } }.set("Add Another Repository")
                helpRows(rows)
                val on = st.providers.count { it.enabled }
                (s.view("k:prov", 24) { parts.section("", null) } as LinearLayout).let { (it.getChildAt(0) as TextView).text = "PROVIDERS · $on OF ${st.providers.size} ON" }
                if (st.providers.isEmpty()) s.view("noprov", 10) { parts.note("This repository didn't add any providers yet. Try Update, or add another repository.", 14f) }
                else {
                    // Use Recommended Settings: the fastest, most reliable set in one press (Scrapers.recommended).
                    val rec = try { Scrapers.isRecommended(app()) } catch (e: Exception) { false }
                    rows.row("rec", 10) { parts.row(null) {
                        if (Scrapers.isRecommended(app())) return@row
                        rows.get("rec")?.set("Recommended Settings On", "The fastest, most reliable providers are on.", mark = BrandRow.Mark.OK)
                        Net.run { Scrapers.applyRecommended(app()); ui.main.post { if (s.alive) paint(); onChange() } }
                    } }.let { r ->
                        if (rec) r.set("Recommended Settings On", "The fastest, most reliable providers are on.", mark = BrandRow.Mark.OK)
                        else r.set("Use Recommended Settings", "Turns on the fastest, most reliable providers so Instant Play can start most titles in about a second.",
                            mark = BrandRow.Mark.OFF)
                    }
                    val allOn = on == st.providers.size
                    rows.row("all", 8) { parts.row(null) {
                        val target = Scrapers.status(app()).providers.let { ps -> ps.count { it.enabled } != ps.size }
                        Net.run { for (p in Scrapers.status(app()).providers) Scrapers.setEnabled(app(), p.id, target); ui.main.post { if (s.alive) paint(); onChange() } }
                    } }.set(if (allOn) "Turn All Off" else "Turn All On")
                    for (p in st.providers) {
                        val r = rows.row("p:${p.id}", 6) {
                            lateinit var row: BrandRow
                            row = parts.row(null, switch = p.enabled) {
                                val want = !(Scrapers.status(app()).providers.firstOrNull { it.id == p.id }?.enabled ?: false)
                                row.sw?.set(want)
                                Net.run { Scrapers.setEnabled(app(), p.id, want); ui.main.post { if (s.alive) paint(); onChange() } }
                            }
                            row
                        }
                        r.set(p.name, Scrapers.caption(p.id) ?: kindWords(p.kind))
                        r.sw?.set(p.enabled)
                    }
                }
            }
        }
        val listener: () -> Unit = { ui.main.post { if (s.alive) { paint(); onChange() } } }
        s.onShow = { Scrapers.addListener(listener); paint(); onChange() }
        s.onHide = { Scrapers.removeListener(listener); focusRecommended = false }
        s.onResume = { paint() }
        s.focus = { (if (focusRecommended) rows.get("rec") else null) ?: rows.get("add") }
        return s
    }

    /** Show Me How and If Something Goes Wrong (SetupGuide), right under the add row. */
    private fun helpRows(rows: ScreenRows) = SetupGuide.helpRows(rows, "repo", parts, panel, ui, "Add It, Then Choose Your Providers",
        "Addresses That Won't Install", { SetupGuide.SCRAPER_STEPS }, SetupGuide.SCRAPER_TROUBLE)

    private fun kindWords(kind: String) = when (kind.lowercase()) { "torrent" -> "Torrent Sources"; "hoster" -> "File Hosts"; else -> kind.replaceFirstChar { it.uppercase() } }

    private fun host(url: String) = try { java.net.URI(url.trim()).host?.removePrefix("www.") } catch (e: Exception) { null } ?: url.take(48)

    fun addRepo(): SetupScreen = setupEntry(ctx, ui, parts, "Add Scraper Repository", null, "Example: https://yourname.github.io/repo", lastUrl,
        listOf("https://", ".github.io", "/", ".", ".com"),
        "Type the address exactly as the repository's website shows it. You can also type it on your phone with Phone Remote.", null) { url ->
        lastUrl = url
        panel.replace(adding(url))
    }

    /** Adding a repository: calm progress in its own words, then the result. */
    private fun adding(url: String): SetupScreen {
        val s = SetupScreen(ui, "Adding Your Repository", null)
        val rows = ScreenRows(s)
        val row = rows.row("p", 0) { parts.row() }
        row.set("Starting…", host(url), mark = BrandRow.Mark.BUSY)
        val bar = ui.progress()
        s.view("bar", 16) { bar.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 6.dp) } }
        var steps = 0
        Log.w(SetupBrand.TAG, "Setup: step Scrapers, adding a repository (${host(url)})")
        Net.run {
            val r = try {
                Scrapers.addRepository(app(), url) { msg -> ui.main.post { if (s.alive) { steps++; row.set(msg, host(url), mark = BrandRow.Mark.BUSY); bar.set(1f - 0.8f / (steps + 1)) } } }
            } catch (e: Exception) { Scrapers.AddResult.Failed("That didn't work. Check the address and try again.") }
            ui.main.post {
                onChange()
                if (!s.alive) return@post
                when (r) {
                    is Scrapers.AddResult.Ok -> {
                        Log.w(SetupBrand.TAG, "Setup: step Scrapers, added ${r.repo.name} ${r.repo.version}")
                        bar.set(1f)
                        val n = Scrapers.status(app()).providers.size
                        focusRecommended = n > 0 // back on the overview, Use Recommended Settings is focused first
                        row.set("Added ${r.repo.name}", if (n > 0) "$n Providers Ready" else "Installed", mark = BrandRow.Mark.OK)
                        val done = parts.pill("Done", true) { panel.pop() }
                        s.view("done", 22) { parts.pills(done) }
                        done.requestFocus()
                    }
                    is Scrapers.AddResult.Failed -> {
                        Log.w(SetupBrand.TAG, "Setup: step Scrapers, add failed: ${r.message}")
                        row.set("Couldn't Add It", r.message, mark = BrandRow.Mark.WARN, capTone = null)
                        s.hide("bar")
                        s.view("help", 16) { SetupGuide.whatToTry(ctx, k, SetupGuide.repoHelp(r.message)) }
                        val again = parts.pill("Try Again", true) { panel.replace(addRepo()) }
                        val cancel = parts.pill("Cancel", false) { panel.pop() }
                        s.view("again", 22) { parts.pills(again, cancel) }
                        again.requestFocus()
                    }
                }
            }
        }
        return s
    }

    /** Installed scraper modules as (name, version), read from their addon.xml in Dream OS's private scraper folder. */
    private fun installedModules(): List<Pair<String, String>> = try {
        (java.io.File(app().filesDir, "scrapers/home/addons").listFiles() ?: emptyArray()).mapNotNull { d ->
            val x = java.io.File(d, "addon.xml").takeIf { it.isFile }?.readText()?.take(4000) ?: return@mapNotNull null
            val tag = Regex("<addon\\b[^>]*>").find(x)?.value ?: return@mapNotNull null
            fun attr(n: String) = Regex("\\b$n=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            val name = attr("name")?.replace(Regex("\\[/?[A-Z]+[^\\]]*\\]"), "")?.trim()?.ifEmpty { null } ?: d.name
            val ver = attr("version") ?: return@mapNotNull null
            if (!d.name.contains("scraper", ignoreCase = true)) null else name to ver
        }.sortedBy { it.first }
    } catch (e: Exception) { emptyList() }

    private fun repo(r: Scrapers.Repo): SetupScreen {
        val s = SetupScreen(ui, r.name, null)
        val rows = ScreenRows(s)
        var updating = false
        // The scraper module's own version (e.g. CocoScrapers 1.0.45), not the repository wrapper's (1.0.1).
        val modules = installedModules()
        if (modules.isEmpty()) rows.row("v", 0) { parts.row() }.set("Version", null, r.version.ifBlank { "Unknown" })
        else for ((i, m) in modules.withIndex()) rows.row("m$i", if (i == 0) 0 else 8) { parts.row() }.set(m.first, "Installed", "Version ${m.second}")
        rows.row("a", 8) { parts.row() }.set("Address", r.url)
        s.view("k", 22) { parts.section("Actions", null) }
        lateinit var upd: BrandRow
        upd = rows.row("update", 10) { parts.row(null) {
            if (updating) return@row
            updating = true
            upd.set("Checking for Updates…", null, mark = BrandRow.Mark.BUSY)
            Net.run {
                val res = try { Scrapers.updateAll(app()) { m -> ui.main.post { if (s.alive) upd.set(m, null, mark = BrandRow.Mark.BUSY) } } }
                    catch (e: Exception) { Scrapers.AddResult.Failed("Couldn't check right now. Try again later.") }
                ui.main.post {
                    updating = false
                    onChange()
                    if (!s.alive) return@post
                    when (res) {
                        is Scrapers.AddResult.Ok -> upd.set("Up to Date", "Version ${res.repo.version}", mark = BrandRow.Mark.OK)
                        is Scrapers.AddResult.Failed -> upd.set("Update", res.message, mark = BrandRow.Mark.WARN)
                    }
                }
            }
        } }
        upd.set("Update", "Gets the Newest Version of Its Providers")
        rows.row("remove", 8) { parts.row(null, chevron = true) { panel.push(confirmRemove(r)) } }.set("Remove Repository", "Its Providers Are Removed Too")
        s.focus = { rows.get("update") }
        return s
    }

    private fun confirmRemove(r: Scrapers.Repo): SetupScreen {
        val s = SetupScreen(ui, "Remove ${r.name}?", null)
        s.add(parts.note("Dream OS removes this repository and its providers. Without a repository, Dream OS can't find streams until you add one again."), 0)
        val cancel = parts.pill("Cancel", false) { panel.pop() }
        val go = parts.pill("Remove", false) {
            Net.run {
                try { Scrapers.removeRepository(app(), r.id) } catch (e: Exception) { Log.w(SetupBrand.TAG, "Setup: remove failed: $e") }
                Log.w(SetupBrand.TAG, "Setup: step Scrapers, removed ${r.name}")
                ui.main.post { onChange(); panel.pop(); panel.pop() }
            }
        }
        s.add(parts.pills(cancel, go), 22)
        s.focus = { cancel }
        return s
    }
}

// =====================================================================================
// Settings › Accounts and Settings › Scrapers
// =====================================================================================

object DreamSettingsPage {
    const val ACCOUNTS = "accounts"
    const val SCRAPERS = "scrapers"
    private var openPage: View? = null

    fun isOpen() = openPage != null

    /** The Settings row values: short status words. */
    fun accountsValue(ctx: Context): String {
        val linked = DreamAccounts.linkedInOrder(ctx)
        return when {
            linked.isEmpty() -> "Not Connected"
            linked.size == 1 -> AccountHealth.name(linked.first())
            else -> "${linked.size} Connected"
        }
    }

    fun scrapersValue(ctx: Context): String {
        val st = try { Scrapers.status(ctx) } catch (e: Exception) { return "Not Set Up" }
        return when {
            st.repos.isEmpty() -> "Not Set Up"
            else -> "${st.providers.count { it.enabled }} Providers On"
        }
    }

    /** Opens [kind] ([ACCOUNTS] or [SCRAPERS]) over the current screen; [onClose] runs once it's gone. */
    fun open(a: Activity, kind: String, onClose: (() -> Unit)? = null) {
        if (openPage != null) return
        val k = PremiumKit(a)
        val ui = SetupUi(a, k)
        val panel = SetupPanel(ui)
        val parts = SetupParts(a, k)
        val back = a.currentFocus
        val dp = { v: Int -> Ui.dp(a, v) }
        lateinit var root: OverlayRoot
        val status = parts.note("", 15f).apply { setPadding(0, 0, 0, 0) }
        lateinit var accounts: AccountsPages
        fun paintStatus() {
            val (t, c) = if (kind == ACCOUNTS) {
                val b = SetupGate.accountsBlocker(a, accounts.isChecking())
                (b ?: "Ready to watch.") to (if (b == null) SetupBrand.GREEN else SetupBrand.DIM)
            } else {
                if (SetupGate.scrapersReady(a)) "Ready to watch." to SetupBrand.GREEN
                else "Add a repository and turn on at least one provider." to SetupBrand.DIM
            }
            status.text = t
            status.setTextColor(c)
            // Ready: the drawn green check beside the line (never a text "✓").
            val glyph = if (c == SetupBrand.GREEN) StatusGlyph(BrandRow.Mark.OK).apply { setBounds(0, 0, dp(18), dp(18)) } else null
            status.setCompoundDrawablesRelative(glyph, null, null, null)
            status.compoundDrawablePadding = dp(10)
        }
        accounts = AccountsPages(a, ui, panel, inSetup = false) { paintStatus() }
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
        left.addView(SetupArt(a, if (kind == ACCOUNTS) SetupArt.Kind.ACCOUNTS else SetupArt.Kind.SCRAPERS), LinearLayout.LayoutParams(dp(132), dp(132)))
        left.addView(k.label("SETTINGS", 12f, Ui.alpha(Color.WHITE, 0.62f), k.semibold, Ui.TRACK_CAPS), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        left.addView(k.label(if (kind == ACCOUNTS) "Accounts" else "Scrapers", 32f, SetupBrand.TEXT, k.semibold, Ui.TRACK_TITLE, lines = 2),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        left.addView(parts.note(if (kind == ACCOUNTS)
            "Your debrid services find and stream your movies and shows in high quality. Connect, check or disconnect them here. Trakt is optional."
            else "Scrapers search the web for streams of what you pick. Dream OS doesn't include any: add the repository you use, and choose its providers.", 16f)
            .apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        left.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        row.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.36f))
        row.addView(panel, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.64f).apply { marginStart = dp(56) })
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP))
        Ui.unclip(row)
        (a.window.decorView as FrameLayout).addView(root, MATCH_PARENT, MATCH_PARENT)
        openPage = root
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        Log.w(SetupBrand.TAG, "Setup: Settings › ${if (kind == ACCOUNTS) "Accounts" else "Scrapers"} opened")
        panel.push(if (kind == ACCOUNTS) accounts.overview() else ScraperPages(a, ui, panel) { paintStatus() }.overview(), animate = false)
        paintStatus()
    }
}

/** Unused drawable helper kept local (transparent rest face for picture-like controls). */
internal fun clearFace(): Drawable = ColorDrawable(Color.TRANSPARENT)
