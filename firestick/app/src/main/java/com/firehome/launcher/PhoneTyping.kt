package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * "Type on Your Phone" for a text field (a repository address, a key, a profile name): a QR code that
 * opens the phone remote's typing box. Scanning it pairs the phone just for this, and what's sent lands
 * in the field on screen (MainActivity's onText → [SetupText.phoneText] / the wizard's phoneText).
 *
 * The phone remote is off by default. While a typing QR is on screen the remote runs; once the last one
 * leaves, it goes back to how it was: still on if the person already used it, otherwise off (the pairing
 * code stops working at once, and a phone paired only for this can't send anything until the remote is
 * turned on again).
 */
internal object PhoneTyping {
    private const val TAG = "FireHome"
    private var users = 0
    private var wasOn = false

    /**
     * Starts (or joins) a typing session; the pairing address for the QR, or null without Wi-Fi.
     * [go]: which part of the phone page opens ("type", or "pm" for Connect Premiumize's own page).
     * Every begin needs its [end], even when this returns null.
     */
    fun begin(go: String = "type"): String? {
        val r = PhoneRemote.current ?: return null
        if (users++ == 0) {
            wasOn = r.enabled() && r.pairedPhones().isNotEmpty()
            Log.w(TAG, "Setup: phone typing on (remote was ${if (wasOn) "on" else "off"})")
        }
        return r.pairingUrl(go)
    }

    /** The pairing address again (a fresh code once a phone used the last one); only during a session. */
    fun url(go: String = "type"): String? = if (users > 0) PhoneRemote.current?.pairingUrl(go) else null

    /** Ends one session; the last one puts the remote back how it was. */
    fun end() {
        if (users == 0) return
        if (--users > 0) return
        val r = PhoneRemote.current ?: return
        if (wasOn) r.closePairing()
        else {
            // Pairing turns the remote on for good; this was only for typing, so it goes back off.
            if (r.enabled()) r.setEnabled(false)
            r.endPairing()
        }
        Log.w(TAG, "Setup: phone typing off")
    }

    /**
     * A small card: the QR code with "Type on Your Phone" under it. It starts its session when it's
     * shown and ends it when it leaves the screen, so callers only add it. Hidden without Wi-Fi.
     */
    fun view(ctx: Context, k: PremiumKit, sizeDp: Int = 104): View {
        val ui = SetupUi(ctx, k)
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val box = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            addView(box, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
            addView(k.label("Type on Your Phone", 12.5f, Ui.alpha(Color.WHITE, 0.72f), k.medium, lines = 0).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        var active = false
        col.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (active) return
                active = true
                val url = begin()
                box.removeAllViews()
                val qr = url?.let { ui.qr(it, sizeDp) }
                if (qr == null) { col.visibility = View.GONE; return }
                box.addView(qr, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
                col.visibility = View.VISIBLE
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (!active) return
                active = false
                end()
            }
        })
        return col
    }
}
