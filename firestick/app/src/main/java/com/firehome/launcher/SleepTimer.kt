package com.firehome.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sleep timer owned by the accessibility service, so its "Still watching?" card can appear over
 * any app. When time runs out it goes Home (pausing playback) and puts the stick to sleep.
 */
class SleepTimer(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private var overlay: View? = null
    private var countdown: TextView? = null
    private var secondsLeft = 0
    var endsAt = 0L
        private set
    private var chosenMinutes = 0

    /** Like Netflix: "Keep watching" re-arms for the full chosen time, at least 90 minutes. */
    private fun keepWatching() = arm(maxOf(chosenMinutes, 90))

    // postDelayed runs on uptime, which stops while the stick sleeps: a timer that ran out during a sleep is over,
    // not a card that appears (and turns the stick off) after it wakes.
    private val prompt = Runnable { if (SystemClock.elapsedRealtime() > endsAt + 60_000L) cancel() else showPrompt() }

    private val tick = object : Runnable {
        override fun run() {
            secondsLeft--
            countdown?.text = "Turning off in $secondsLeft s"
            if (secondsLeft <= 0) sleepNow() else handler.postDelayed(this, 1000)
        }
    }

    fun set(minutes: Int) {
        chosenMinutes = minutes
        arm(minutes)
    }

    private fun arm(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(prompt, minutes * 60_000L)
    }

    fun cancel() {
        endsAt = 0L
        handler.removeCallbacks(prompt)
        handler.removeCallbacks(tick)
        hidePrompt()
    }

    fun minutesLeft(): Int = if (endsAt == 0L) 0 else ((endsAt - SystemClock.elapsedRealtime()) / 60_000L + 1).toInt().coerceAtLeast(0)

    private fun showPrompt() {
        if (overlay != null) return
        val dp = { v: Int -> (v * density).toInt() }
        countdown = TextView(service).apply {
            textSize = Ui.T_BODY
            setTextColor(Color.argb(170, 255, 255, 255))
            setPadding(0, dp(Ui.S2), 0, dp(Ui.S5))
        }
        val keep = button("Keep Watching", primary = true) { keepWatching() }
        val off = button("Turn Off Now", primary = false) { sleepNow() }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(44), dp(32), dp(44), dp(30))
            background = Ui.rounded(service, Ui.surface(service, 0.97f), Ui.R_XL, 1f, Color.argb(20, 255, 255, 255))
            addView(TextView(service).apply {
                text = "Still Watching?"
                textSize = 28f
                typeface = Ui.semibold(Typeface.DEFAULT)
                letterSpacing = Ui.TRACK_TITLE
                setTextColor(Color.WHITE)
            })
            addView(countdown)
            addView(LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(keep)
                addView(off)
            })
        }
        // A lifted button reaches past its row: only the card's own edge clips (it has padding to spare).
        Ui.unclip(card)
        val root = object : FrameLayout(service) {
            // Back counts as "keep watching" so the card is easy to dismiss.
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    keepWatching()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            sleepNow() // couldn't show the card: fall back to just turning off
            return
        }
        overlay = root
        // Scrim fades in while the card rises into place.
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        Ui.rise(listOf(card), fromDp = 16, duration = Ui.OVERLAY_IN)
        keep.post { keep.requestFocus() }
        secondsLeft = 60
        countdown?.text = "Turning off in $secondsLeft s"
        handler.postDelayed(tick, 1000)
    }

    private fun hidePrompt() {
        handler.removeCallbacks(tick)
        overlay?.let { v ->
            if (v.isAttachedToWindow) {
                // Quick fade out; stop taking keys right away so the remote goes back to the app.
                try {
                    wm.updateViewLayout(v, (v.layoutParams as WindowManager.LayoutParams).apply {
                        flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    })
                } catch (e: Exception) {}
                v.animate().alpha(0f).setDuration(Ui.OVERLAY_OUT).setInterpolator(Ui.EXIT)
                    .withEndAction { try { wm.removeView(v) } catch (e: Exception) {} }.start()
            } else try { wm.removeView(v) } catch (e: Exception) {}
        }
        overlay = null
        countdown = null
    }

    private fun sleepNow() {
        cancel()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) // pauses whatever is playing
        handler.postDelayed({ service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }, 1500)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(service).apply {
        text = label
        gravity = Gravity.CENTER
        setPadding((26 * density).toInt(), 0, (26 * density).toInt(), 0)
        isFocusable = true
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * density).toInt()).apply {
            marginStart = (6 * density).toInt(); marginEnd = (6 * density).toInt()
        }
        setOnClickListener { onClick() }
        // Focused: the theme's selection color with contrast text, a springy lift and one sheen sweep.
        val sel = Theme.get(service, Theme.SELECT)
        typeface = Ui.semibold(Typeface.DEFAULT)
        textSize = 16f
        setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)),
            intArrayOf(PearlFocus.INK, Color.WHITE),
        ))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(service, sel, Ui.R_MD))
            addState(IntArray(0), Ui.rounded(service, Color.argb(if (primary) 38 else 22, 255, 255, 255), Ui.R_MD, 1f, Color.argb(20, 255, 255, 255)))
        }
        val sheen = SheenDrawable(Ui.dpf(service, Ui.R_MD.toFloat()))
        foreground = sheen
        setOnFocusChangeListener { v, has ->
            Ui.lift(v, has, Ui.LIFT_PILL)
            if (has) sheen.sweep() else sheen.cancel()
        }
        cinemaRowFocus(this, Ui.R_MD, Color.argb(if (primary) 38 else 22, 255, 255, 255), Color.argb(20, 255, 255, 255)) // Dream Cinema: rainbow border
    }
}
