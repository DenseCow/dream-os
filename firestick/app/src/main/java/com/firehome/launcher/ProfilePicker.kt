package com.firehome.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen "Who's watching?" chooser. Editing and adding profiles happen in inner modals
 * stacked inside [view]; the host forwards BACK to [handleBack] and closes the picker when it
 * returns false (and [canClose] allows it). Choosing or editing a PIN-locked profile asks for its
 * PIN, except [trustedId] (the profile already unlocked when opened from Settings).
 * Dream Cinema's Netflix / Disney+ look ([cinemaTile]); its cards and buttons borrow only the theme's
 * selection and focus-outline colors for focused controls.
 */
class ProfilePicker(
    private val ctx: Context,
    private val pins: PinPrompt,
    private val trustedId: String?,
    private val onChosen: (Profiles.Profile) -> Unit,
) {
    val view: FrameLayout
    private val main: LinearLayout
    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        // Focused tiles scale up past their bounds; the row's padding leaves room for that.
        clipChildren = false
        clipToPadding = false
    }
    private val modals = ArrayList<View>()
    private val modalBack = HashMap<View, () -> Unit>()
    private val modalReturn = HashMap<View, View>() // view focused when each modal opened
    private var lastFocusId: String? = null
    private var editOnlyDone: (() -> Unit)? = null
    private val select = Theme.get(ctx, Theme.SELECT)
    private val onSelect = Theme.contrastOn(select)
    private val semibold = Ui.semibold(Typeface.DEFAULT)
    /** Cinema's "Edit Profiles" mode: choosing a tile edits it instead (MENU / hold OK still do, as always). */
    private var editing = false
    private var editButton: TextView? = null
    /** Cinema's per-tile pencil badges. Declared before [init] (which builds the row) so it isn't null there. */
    private val pencils = ArrayList<View>()

    init {
        row.setPadding(CinemaStyle.GUTTER.dp, 36.dp, CinemaStyle.GUTTER.dp, 36.dp)
        val title = text("Who's Watching?", 40f, CinemaStyle.text(ctx)).apply {
            gravity = Gravity.CENTER
            typeface = Ui.semibold(Theme.typeface(ctx))
            letterSpacing = -0.01f
        }
        val edit = cinemaButton("Edit Profiles") { setEditing(!editing) }
        editButton = edit
        main = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                clipChildren = false
                clipToPadding = false
                addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 20.dp })
            addView(edit, LinearLayout.LayoutParams(WRAP_CONTENT, 44.dp).apply { topMargin = 8.dp })
        }
        view = FrameLayout(ctx).apply {
            isClickable = true
            clipChildren = false
            // The Cinema ground: the theme's near-black into its purple, corner to corner.
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                CinemaStyle.bgTop(ctx), Ui.mix(CinemaStyle.bgTop(ctx), CinemaStyle.bg(ctx), 0.55f), CinemaStyle.bg(ctx)))
            addView(main, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
        buildRow(null)
        // Entrance: the title settles from above, the avatars pop in one by one (scale + fade), then Edit.
        title.alpha = 0f
        title.translationY = -14.dp.toFloat()
        title.animate().alpha(1f).translationY(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        for (i in 0 until row.childCount) row.getChildAt(i).let { t ->
            t.alpha = 0f
            t.scaleX = 0.86f
            t.scaleY = 0.86f
            t.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90L + i * 55L).setDuration(380).setInterpolator(Ui.SPRING).start()
        }
        Ui.rise(listOf(edit), fromDp = 10, startDelay = 140L + row.childCount * 55L, duration = Ui.OVERLAY_IN)
    }

    /** The current profile's tile: the initial focus to give the host. */
    fun initialFocus(): View = row.findViewWithTag(Profiles.current(ctx).id) ?: row.getChildAt(0)

    /** Focuses the current profile's tile (posted again if the view is not attached yet). */
    private fun focusFirst() {
        if (modals.isNotEmpty()) return
        val t = initialFocus()
        if (!t.requestFocus()) row.post { if (modals.isEmpty()) t.requestFocus() }
    }

    /** Opens just the edit card for the current profile (from the sidebar); [onDone] runs when it closes. */
    fun editCurrent(onDone: () -> Unit) {
        editOnlyDone = onDone
        main.visibility = View.INVISIBLE
        openEdit(Profiles.current(ctx).id)
    }

    /** Closes the top inner modal if one is open. Returns false when the picker itself should close. */
    fun handleBack(): Boolean {
        val top = modals.lastOrNull() ?: return false
        modalBack[top]?.invoke() ?: closeModal()
        return true
    }

    /** False while the current profile is locked and wasn't unlocked yet (boot, or it was just deleted). */
    fun canClose(): Boolean {
        val id = Profiles.current(ctx).id
        return id == trustedId || !Profiles.hasPin(ctx, id)
    }

    // ---- tile row ----

    private fun buildRow(focusId: String?) {
        row.removeAllViews()
        pencils.clear()
        for ((i, p) in Profiles.all(ctx).withIndex()) {
            row.addView(cinemaTile(p.id, p.name, CinemaStyle.glowColor(i), p.emoji, Profiles.hasPin(ctx, p.id)) {
                if (editing) edit(p.id) else unlock(p) { choose(p) }
            }.apply {
                isLongClickable = true
                setOnLongClickListener { edit(p.id); true }
                setOnKeyListener { _, k, e ->
                    if (k == KeyEvent.KEYCODE_MENU) {
                        if (e.action == KeyEvent.ACTION_UP) edit(p.id)
                        true
                    } else false
                }
            })
        }
        row.addView(cinemaTile(ADD, "Add Profile", 0, "+", false) { openAdd() })
        if (modals.isEmpty()) focusId?.let { id -> row.findViewWithTag<View>(id)?.requestFocus() }
    }

    private fun unlock(p: Profiles.Profile, onOk: () -> Unit) = when {
        p.id == trustedId -> onOk()
        Profiles.hasPin(ctx, p.id) -> pins.verify(p, onOk)
        else -> leaveKids(p, onOk)
    }

    /**
     * Leaving a kids profile whose "PIN to leave" is on ([KidsProfiles.exitPin]) for a profile without its own PIN:
     * the main profile's PIN (else the first grown-up profile's that has one). No grown-up PIN at all: not blocked.
     */
    private fun leaveKids(to: Profiles.Profile, onOk: () -> Unit) {
        val cur = Profiles.current(ctx)
        if (to.id == cur.id || !KidsProfiles.isKids(ctx, cur.id) || !KidsProfiles.exitPin(ctx, cur.id)) return onOk()
        val all = Profiles.all(ctx)
        val guard = all.firstOrNull()?.takeIf { it.id != cur.id && Profiles.hasPin(ctx, it.id) }
            ?: all.firstOrNull { it.id != cur.id && !KidsProfiles.isKids(ctx, it.id) && Profiles.hasPin(ctx, it.id) }
            ?: return onOk()
        android.util.Log.i("FireHome", "Kids: leaving ${cur.name} asks for ${guard.name}'s PIN")
        pins.verify(guard, onOk)
    }

    private fun choose(p: Profiles.Profile) {
        Profiles.setCurrent(ctx, p.id)
        onChosen(p)
    }

    private fun edit(id: String) { find(id)?.let { p -> unlock(p) { openEdit(id) } } }

    // ---- Dream Cinema look ----

    /**
     * Cinema avatar: a big rounded square in its own vivid color from the glow palette ([color]; 0 for Add,
     * drawn as an outline), the name under it. Focus lifts the avatar and lights its glow in that same color
     * while the other profiles step back; in Edit Profiles mode a pencil shows over every face.
     */
    private fun cinemaTile(key: String, name: String, color: Int, glyph: String, locked: Boolean, onClick: () -> Unit): LinearLayout {
        val fg = CinemaStyle.text(ctx)
        val hue = if (color != 0) color else CinemaStyle.glowColor(row.childCount)
        val glow = CinemaStyle.glowDrawable(ctx, hue, AV_R, CinemaStyle.GLOW_HALO)
        val face = FrameLayout(ctx).apply {
            background = if (color != 0) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.mix(color, Color.WHITE, 0.12f), Ui.mix(color, Color.BLACK, 0.38f)))
                .apply { cornerRadius = AV_R.dp.toFloat() }
            else Ui.rounded(ctx, Ui.alpha(fg, 0.06f), AV_R, 2f, Ui.alpha(fg, 0.32f))
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            addView(text(glyph, if (color != 0) 60f else 54f, if (color != 0) Color.WHITE else Ui.alpha(fg, 0.8f)).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (color != 0) addView(text("✎", 30f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setBackgroundColor(Color.argb(150, 0, 0, 0))
                alpha = if (editing) 1f else 0f
                pencils += this
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val avatar = FrameLayout(ctx).apply {
            background = glow
            clipChildren = false
            addView(face, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (locked) addView(lockBadge(), FrameLayout.LayoutParams(32.dp, 32.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 8.dp, 8.dp, 0) })
        }
        val label = TextView(ctx).apply {
            text = name
            textSize = 18f
            typeface = Ui.medium(Theme.typeface(ctx))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (AV + 24).dp
            setTextColor(CinemaStyle.textDim(ctx))
        }
        return LinearLayout(ctx).apply {
            tag = key
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isClickable = true
            addView(avatar, LinearLayout.LayoutParams(AV.dp, AV.dp))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 18.dp })
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                if (has) lastFocusId = key
                if (has) glow.show(glow.hue, hue) else glow.hide()
                CinemaStyle.lift(avatar, has, z = false)
                avatar.animate().translationY(if (has) -6.dp.toFloat() else 0f).setDuration(CinemaStyle.FOCUS_MS).setInterpolator(Ui.EASE).start()
                label.setTextColor(if (has) fg else CinemaStyle.textDim(ctx))
                // Spotlight: the focused profile stays bright, the others step back.
                if (has) for (i in 0 until row.childCount) row.getChildAt(i).let { t ->
                    (t as? ViewGroup)?.getChildAt(0)?.animate()?.alpha(if (t === v) 1f else 0.55f)?.setDuration(Ui.FOCUS_IN)?.setInterpolator(Ui.SETTLE)?.start()
                }
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 20.dp; marginEnd = 20.dp }
        }
    }

    /** "Edit Profiles" / "Done": an outlined pill that fills pearl and glows (next rainbow hue) when focused. */
    private fun cinemaButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        val fg = CinemaStyle.text(ctx)
        val ink = CinemaStyle.bgTop(ctx)
        text = label
        textSize = 15f
        typeface = Ui.semibold(Theme.typeface(ctx))
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(26.dp, 0, 26.dp, 0)
        isFocusable = true
        isClickable = true
        val glow = CinemaStyle.glowDrawable(ctx, CinemaStyle.glowNow, 22, CinemaStyle.GLOW_HALO_SMALL)
        background = android.graphics.drawable.LayerDrawable(arrayOf(glow, states(Ui.rounded(ctx, Ui.alpha(fg, 0.94f), 22),
            Ui.rounded(ctx, Color.TRANSPARENT, 22, 1.2f, Ui.alpha(fg, 0.36f)))))
        setTextColor(focusColors(ink, Ui.alpha(fg, 0.86f)))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, has ->
            CinemaStyle.focusGlow(glow, has)
            // Off the avatars: every profile back to full brightness.
            if (has) for (i in 0 until row.childCount) (row.getChildAt(i) as? ViewGroup)?.getChildAt(0)?.animate()?.alpha(1f)?.setDuration(Ui.FOCUS_IN)?.start()
            CinemaStyle.lift(v, has, big = true, z = false)
        }
    }

    private fun setEditing(on: Boolean) {
        editing = on
        editButton?.text = if (on) "Done" else "Edit Profiles"
        for (p in pencils) if (p.isAttachedToWindow) p.animate().alpha(if (on) 1f else 0f).setDuration(Ui.FOCUS_IN).setInterpolator(Ui.EASE).start()
        else p.alpha = if (on) 1f else 0f
    }

    private fun lockBadge() = text("🔒", 14f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CARD_BG)
            setStroke(2.dp, Color.parseColor("#0A0A0C"))
        }
    }

    // ---- storage helpers ----

    private fun find(id: String) = Profiles.all(ctx).firstOrNull { it.id == id }

    private fun update(id: String, change: (Profiles.Profile) -> Unit) {
        val list = Profiles.all(ctx)
        list.firstOrNull { it.id == id }?.let(change) ?: return
        Profiles.save(ctx, list)
    }

    // ---- edit profile ----

    private fun openEdit(id: String) {
        val start = find(id) ?: return
        val c = card("Edit Profile")
        val preview = TextView(ctx).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val name = editText(start.name, "Name")
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun commitName() {
            val n = name.text.toString().trim()
            if (n.isNotEmpty() && n != find(id)?.name) {
                update(id) { it.name = n }
                buildRow(null)
            }
        }
        fun refresh(focusTag: String?) {
            val p = find(id) ?: return
            preview.text = p.emoji
            preview.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.color) }
            body.removeAllViews()
            body.addView(label("Avatar"))
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((i, e) in Profiles.EMOJIS.withIndex()) {
                    addView(emojiChip("e$i", e, e == p.emoji) {
                        update(id) { it.emoji = e }; buildRow(null); refresh("e$i")
                    }, LinearLayout.LayoutParams(44.dp, 44.dp).apply { marginEnd = 6.dp })
                }
            })
            body.addView(label("Color"))
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((i, col) in Profiles.COLORS.withIndex()) {
                    addView(swatchButton("c$i", col, col == p.color) {
                        update(id) { it.color = col }; buildRow(null); refresh("c$i")
                    }, LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = 10.dp })
                }
            })
            body.addView(settingRow("pin", "Lock with PIN", if (Profiles.hasPin(ctx, id)) "On" else "Off") {
                if (Profiles.hasPin(ctx, id)) { Profiles.setPin(ctx, id, null); buildRow(null); refresh("pin") }
                else pins.setNew(id) { saved -> if (saved) { buildRow(null); refresh("pin") } }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 14.dp })
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 16.dp, 0, 0)
                addView(button("Done", primary = true) { commitName(); closeModal() }.apply { tag = "done" })
                if (p.id != Profiles.FIRST) addView(button("Delete", primary = false, danger = true) { commitName(); confirmDelete(id) })
            })
            focusTag?.let { t -> body.post { body.findViewWithTag<View>(t)?.requestFocus() } }
        }
        name.setOnEditorActionListener { _, _, _ ->
            commitName()
            hideKeyboard()
            val sel = find(id)?.emoji?.let { Profiles.EMOJIS.indexOf(it) } ?: 0
            body.findViewWithTag<View>("e${maxOf(sel, 0)}")?.requestFocus()
            true
        }
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(56.dp, 56.dp).apply { marginEnd = 16.dp })
            addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        c.addView(body)
        refresh(null)
        // Start on the selected avatar rather than the text field so the keyboard doesn't pop up.
        val sel = Profiles.EMOJIS.indexOf(start.emoji)
        showModal(c, 700, body.findViewWithTag("e${maxOf(sel, 0)}"), onBack = { commitName(); closeModal() })
    }

    private fun confirmDelete(id: String) {
        val p = find(id) ?: return
        val c = card("Delete ${p.name}?")
        c.addView(text("Their theme, favorites and My List will be removed.", 15f, DIM).apply { setPadding(0, 0, 0, 18.dp) })
        val cancel = button("Cancel", primary = false) { closeModal() }
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Delete", primary = false, danger = true) {
                Profiles.remove(ctx, id)
                closeModal() // confirm
                closeModal() // edit
                lastFocusId = null
                buildRow(Profiles.current(ctx).id)
            })
            addView(cancel)
        })
        showModal(c, 460, cancel)
    }

    // ---- add profile ----

    private fun openAdd() {
        val c = card("Add Profile")
        val name = editText("", "Name")
        var lock = false
        val create = button("Create", primary = true) {
            val p = Profiles.add(ctx, name.text.toString().trim().ifBlank { "Profile" })
            closeModal()
            buildRow(p.id)
            if (lock) pins.setNew(p.id) { saved -> if (saved) buildRow(p.id) }
        }
        name.setOnEditorActionListener { _, _, _ -> hideKeyboard(); create.requestFocus(); true }
        c.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        c.addView(settingRow("pin", "Lock with PIN", "Off") { v ->
            lock = !lock
            v.text = if (lock) "On" else "Off"
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12.dp })
        c.addView(text("A locked profile asks for its PIN when chosen or edited.", 12f, MUTED)
            .apply { setPadding(14.dp, 6.dp, 14.dp, 0) })
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 18.dp, 0, 0)
            addView(create)
            addView(button("Cancel", primary = false) { closeModal() })
        })
        showModal(c, 480, name)
    }

    // ---- modal framework ----

    private fun showModal(content: View, widthDp: Int, focus: View?, onBack: (() -> Unit)? = null) {
        // Tall cards scroll on small screens; ScrollView follows the focused child.
        val inner = SmoothScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            // Soft edges when a tall card scrolls: the card melts into what's behind it (the page's near-black
            // ground under the 170/255 scrim), painted over the card with no offscreen layer.
            val behind = Ui.mix(Ui.mix(CinemaStyle.bgTop(ctx), CinemaStyle.bg(ctx), 0.55f), Color.BLACK, 170 / 255f)
            softEdges(behind, behind, 32.dp)
        }
        val scrim = FrameLayout(ctx).apply {
            isClickable = true
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            addView(inner, FrameLayout.LayoutParams(widthDp.dp, WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = 24.dp; bottomMargin = 24.dp
            })
        }
        view.findFocus()?.let { modalReturn[scrim] = it }
        // Only the top modal can take focus; the tile row and lower modals are locked.
        (modals.lastOrNull() as? ViewGroup ?: main).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.addView(scrim, MATCH_PARENT, MATCH_PARENT)
        modals += scrim
        // Lifted controls touch the card's content edges: only the card's own edge clips (it has padding to spare).
        (content as? ViewGroup)?.let { Ui.unclip(it) }
        if (onBack != null) modalBack[scrim] = onBack
        // Scrim fades in while the card rises into place.
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        Ui.rise(listOf(inner), fromDp = 16, duration = Ui.OVERLAY_IN)
        (focus ?: content).post { (focus ?: firstFocusable(content))?.requestFocus() }
    }

    private fun closeModal() {
        val top = modals.removeLastOrNull() ?: return
        modalBack.remove(top)
        val back = modalReturn.remove(top)
        hideKeyboard()
        view.removeView(top)
        (modals.lastOrNull() as? ViewGroup ?: main).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (modals.isEmpty()) editOnlyDone?.let { done -> editOnlyDone = null; return done() }
        if (modals.isEmpty()) main.post {
            if (modals.isEmpty() && main.findFocus() == null) {
                val t = lastFocusId?.let { row.findViewWithTag<View>(it) }
                if (t == null || !t.requestFocus()) focusFirst()
            }
        } else view.post {
            // Back in a lower modal (e.g. Delete cancelled): return to the button that opened this one,
            // unless that card was rebuilt meanwhile and already moved focus itself.
            if (view.findFocus() == null && back != null && back.isAttachedToWindow) back.requestFocus()
        }
    }

    private fun firstFocusable(v: View): View? {
        val list = ArrayList<View>()
        v.addFocusables(list, View.FOCUS_FORWARD)
        return list.firstOrNull()
    }

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun card(title: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.S6.dp, Ui.S5.dp, Ui.S6.dp, Ui.S5.dp)
        background = Ui.rounded(ctx, CARD_BG, Ui.R_LG, 1f, HAIRLINE)
        addView(text(title, Ui.T_HEADLINE, Color.WHITE).apply {
            typeface = semibold
            letterSpacing = Ui.TRACK_TITLE
            setPadding(0, 0, 0, Ui.S4.dp)
        })
    }

    // ---- small widgets ----

    private fun editText(value: String, hint: String) = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        filters = arrayOf<InputFilter>(InputFilter.LengthFilter(20))
        textSize = 18f
        setTextColor(Color.WHITE)
        setHintTextColor(MUTED)
        this.hint = hint
        setText(value)
        setSelection(value.length)
        setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        background = states(
            // Text stays white on glass; focus shows as a ring in the selection color.
            Ui.rounded(ctx, Color.parseColor("#1FFFFFFF"), Ui.R_SM, 2f, select),
            Ui.rounded(ctx, Color.parseColor("#14FFFFFF"), Ui.R_SM, 1f, HAIRLINE),
        )
    }

    /** A full-width setting row; [onClick] gets the value label so toggles can update it in place. */
    private fun settingRow(key: String, label: String, value: String, onClick: (TextView) -> Unit): LinearLayout {
        val v = text(value, 15f, DIM)
        return LinearLayout(ctx).apply {
            tag = key
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            isFocusable = true
            isClickable = true
            background = rowBackground()
            val l = text(label, 16f, Color.WHITE)
            addView(l, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(v)
            // Focused row fills with the selection color; both labels follow it.
            for (t in listOf(l, v)) t.isDuplicateParentStateEnabled = true
            l.setTextColor(focusColors(onSelect, Color.WHITE))
            v.setTextColor(focusColors(Ui.alpha(onSelect, 0.8f), DIM))
            l.typeface = Ui.medium(Typeface.DEFAULT)
            setOnFocusChangeListener { r, has -> Ui.lift(r, has, Ui.LIFT_ROW) }
            setOnClickListener { onClick(v) }
        }
    }

    private fun label(t: String) = text(t, Ui.T_MICRO, MUTED, bold = true).apply {
        setPadding(0, Ui.S4.dp, 0, Ui.S2.dp)
    }

    private fun emojiChip(key: String, emoji: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        tag = key
        text = emoji
        textSize = 22f
        gravity = Gravity.CENTER
        includeFontPadding = false
        isFocusable = true
        isClickable = true
        background = states(
            Ui.rounded(ctx, select, Ui.R_SM),
            if (selected) Ui.rounded(ctx, Ui.alpha(select, 0.3f), Ui.R_SM, 1.5f, Ui.alpha(select, 0.9f))
            else Ui.rounded(ctx, Color.TRANSPARENT, Ui.R_SM),
        )
        setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ICON) }
        setOnClickListener { onClick() }
    }

    private fun swatchButton(key: String, color: Int, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        tag = key
        // The chosen color: a drawn check over the swatch (never a text "✓").
        foreground = if (selected) CheckInk(Theme.contrastOn(color), weight = 0.09f) else null
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = states(
            LayerDrawable(arrayOf(
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(Ui.dp(ctx, 2), Theme.get(ctx, Theme.ACCENT)) },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) },
            )).apply { val g = 4.dp; setLayerInset(1, g, g, g, g) },
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(1.dp, if (selected) Color.parseColor("#CCFFFFFF") else HAIRLINE) },
        )
        setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ICON) }
        setOnClickListener { onClick() }
    }

    private fun button(label: String, primary: Boolean, danger: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(22.dp, 0, 22.dp, 0)
        isFocusable = true
        isClickable = true
        premiumButton(this, primary, danger)
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, 42.dp).apply { marginEnd = Ui.S3.dp }
        setOnClickListener { onClick() }
    }

    /**
     * The button: solid selection fill (red for danger) with contrast text when focused,
     * the theme's main button box (primary) or quiet glass otherwise; a springy lift and one sheen sweep.
     */
    private fun premiumButton(b: TextView, primary: Boolean, danger: Boolean) {
        val focusFill = if (danger) DANGER else select
        val idleFill = when {
            danger -> Color.parseColor("#2EF87171")
            primary -> Theme.get(ctx, Theme.BTN_PRIMARY)
            else -> Color.parseColor("#1AFFFFFF")
        }
        val idleText = when {
            danger -> Color.parseColor("#FF9A92")
            primary -> Theme.contrastOn(idleFill)
            else -> Color.WHITE
        }
        b.typeface = semibold
        b.includeFontPadding = false
        b.setTextColor(focusColors(Theme.contrastOn(focusFill), idleText))
        b.background = states(Ui.rounded(ctx, focusFill, Ui.R_SM),
            if (primary || danger) Ui.rounded(ctx, idleFill, Ui.R_SM) else Ui.rounded(ctx, idleFill, Ui.R_SM, 1f, HAIRLINE))
        val sheen = SheenDrawable(Ui.dpf(ctx, Ui.R_SM.toFloat()))
        b.foreground = sheen
        b.setOnFocusChangeListener { v, has ->
            Ui.lift(v, has, Ui.LIFT_PILL)
            if (has) sheen.sweep() else sheen.cancel()
        }
    }

    private fun states(focused: Drawable, idle: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(IntArray(0), idle)
    }

    private fun focusColors(focused: Int, idle: Int) =
        ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(focused, idle))

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rowBackground(filled: Boolean = false): Drawable = states(
        Ui.rounded(ctx, select, Ui.R_SM),
        Ui.rounded(ctx, Color.parseColor(if (filled) "#1AFFFFFF" else "#0DFFFFFF"), Ui.R_SM, 1f, HAIRLINE),
    )

    private val Int.dp get() = (this * ctx.resources.displayMetrics.density).toInt()

    companion object {
        /** Cinema avatar size and corner (dp). */
        private const val AV = 136
        private const val AV_R = 30
        private const val ADD = "__add"
        private val CARD_BG = Color.parseColor("#FF161618")
        private val DIM = Color.parseColor("#B4B4BC")
        private val MUTED = Color.parseColor("#80808A")
        /** 1dp edges and the danger fill. */
        private val HAIRLINE = Color.parseColor("#17FFFFFF")
        private val DANGER = Color.parseColor("#E5484D")
    }
}
