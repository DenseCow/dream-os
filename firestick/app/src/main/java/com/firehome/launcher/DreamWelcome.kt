package com.firehome.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

/**
 * Setup's Welcome page: what Dream OS is, and its features as two quiet rows of named glyphs, each
 * drawn in code ([WelcomeGlyph], the SetupArt line-art look: a pearl monoline icon on a
 * dark-glass disc in a thin rainbow ring).
 */
internal object WelcomeFeatures {
    /** (glyph, name, caption). Each one is a real Dream OS feature (see the files named). */
    // The TV's Welcome shows the names only; the captions say what each one is (and back the names up).
    val ITEMS = listOf(
        Triple(WelcomeGlyph.ENGINE, "Dream Core Engine", "Searches every scraper you've added at once, then picks the best stream that's ready on your account."), // NativeResolver, Scrapers
        Triple(WelcomeGlyph.CORE, "Instant Play", "Powered by Dream Core Engine: what you'll watch next is found ahead of time, so it starts in about a second."), // PreResolve, NewEpisodeBanner
        Triple(WelcomeGlyph.PLAYER, "Dream Player", "Fast starts, Skip Intro, Up Next and X-Ray cast info."), // PlayerOverlay, SkipMarkers, PostPlay, PlayerXRay
        Triple(WelcomeGlyph.ALL, "Everything in One Place", "Movies, shows and your apps on one home screen."),
        Triple(WelcomeGlyph.BELL, "Never Miss an Episode", "Alerts with exact air times and what's ready to watch."), // NewEpisodes, Availability
        Triple(WelcomeGlyph.SEASON, "Seasonal Collections", "Curated collections for every season and holiday."), // SeasonHub
        Triple(WelcomeGlyph.SLIDERS, "Sources & Quality", "Your choice of quality, file size and language."), // DreamFilters, PlaybackPrefs
        Triple(WelcomeGlyph.APPLE, "Works With Apple", "AirPods connect instantly; your iPhone is a remote."), // AirPodsLink, PhoneRemote
        Triple(WelcomeGlyph.PROFILES, "Profiles for Everyone", "Kids profiles with age limits, and Trakt sync."), // Profiles, KidsProfiles, Trakt
        Triple(WelcomeGlyph.VOICE, "Voice Search", "Hold the microphone button and say a title."), // DreamVoice
    )

    /**
     * The features as two quiet rows of five: each glyph over its name, centered, no caption (ten
     * captions on a 1080p screen were the Welcome page's clutter, and they were cut at two lines).
     */
    fun strip(ctx: Context, k: PremiumKit): LinearLayout {
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for ((r, five) in ITEMS.chunked(5).withIndex()) {
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            for (item in five) {
                val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                cell.addView(WelcomeGlyph(ctx, item.first), LinearLayout.LayoutParams(dp(40), dp(40)))
                cell.addView(k.label(item.second, 13.5f, Ui.alpha(SetupBrand.TEXT, 0.86f), k.medium, lines = 0).apply {
                    gravity = Gravity.CENTER
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                line.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
            }
            grid.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (r > 0) topMargin = dp(16) })
        }
        return grid
    }
}

/**
 * One feature's glyph in the Dream OS line-art look ([GlyphPen], same as [SetupArt]): a monoline pearl
 * icon on the dark-glass disc with its thin rainbow ring. Lines are a little heavier than the setup
 * art's so they stay clear at ~42 dp. Static: drawn once, nothing per frame.
 */
internal class WelcomeGlyph(ctx: Context, private val kind: Int) : View(ctx) {
    private val g = GlyphPen(weight = 4.2f, ring = 2.4f)

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 100f
        if (s <= 0f) return
        val save = c.save()
        c.translate(width / 2f - 50f * s, height / 2f - 50f * s)
        c.scale(s, s)
        g.backdrop(c)
        when (kind) {
            PLAYER -> {
                g.pen(c, GlyphPen.roundRect(30f, 32f, 70f, 58f, 5f))
                g.pen(c, GlyphPen.play(50f, 45f, 11f))
                g.pen(c, Path().apply { moveTo(31f, 67f); lineTo(69f, 67f) }, alpha = 90)
                g.pen(c, Path().apply { moveTo(31f, 67f); lineTo(49f, 67f) })
            }
            CORE -> g.pen(c, Path().apply {
                moveTo(55f, 25f); lineTo(36f, 53f); lineTo(50f, 53f); lineTo(45f, 75f); lineTo(64f, 46f); lineTo(50f, 46f); close()
            })
            ALL -> for (i in 0 until 4) {
                val x = 31.5f + (i % 2) * 21f
                val y = 31.5f + (i / 2) * 21f
                g.pen(c, GlyphPen.roundRect(x, y, x + 16f, y + 16f, 4f), alpha = if (i == 0) 255 else 150)
            }
            BELL -> {
                g.pen(c, Path().apply {
                    moveTo(50f, 29f)
                    cubicTo(60f, 29f, 64f, 37f, 64f, 46f); lineTo(64f, 55f); lineTo(69f, 62f); lineTo(31f, 62f); lineTo(36f, 55f); lineTo(36f, 46f)
                    cubicTo(36f, 37f, 40f, 29f, 50f, 29f); close()
                })
                g.pen(c, Path().apply { moveTo(45f, 69f); quadTo(50f, 73f, 55f, 69f) })
            }
            SEASON -> {
                val flake = Path().apply {
                    moveTo(50f, 28f); lineTo(50f, 72f)
                    moveTo(44f, 28f); lineTo(50f, 34f); lineTo(56f, 28f)
                    moveTo(44f, 72f); lineTo(50f, 66f); lineTo(56f, 72f)
                }
                for (i in 0 until 3) { c.save(); c.rotate(i * 60f, 50f, 50f); g.pen(c, flake); c.restore() }
            }
            APPLE -> {
                g.pen(c, Path().apply { addArc(RectF(29f, 30f, 71f, 72f), 180f, 180f) })
                g.pen(c, GlyphPen.roundRect(27f, 51f, 37f, 69f, 5f))
                g.pen(c, GlyphPen.roundRect(63f, 51f, 73f, 69f, 5f))
            }
            PROFILES -> {
                g.pen(c, GlyphPen.person(59f, 34f, 0.72f), alpha = 130)
                val front = GlyphPen.person(44f, 38f, 0.9f)
                g.knock(c, front, 2.4f)
                g.pen(c, front)
            }
            ENGINE -> {
                // A hexagonal core with three spokes out to the sources it searches.
                val hex = Path().apply {
                    for (i in 0 until 6) {
                        val a = Math.toRadians(60.0 * i - 90.0)
                        val x = 50f + 13f * Math.cos(a).toFloat(); val y = 50f + 13f * Math.sin(a).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                g.pen(c, hex)
                for (deg in listOf(-150.0, -30.0, 90.0)) {
                    val a = Math.toRadians(deg)
                    val cx = Math.cos(a).toFloat(); val cy = Math.sin(a).toFloat()
                    g.pen(c, Path().apply { moveTo(50f + 16f * cx, 50f + 16f * cy); lineTo(50f + 23f * cx, 50f + 23f * cy) }, alpha = 170)
                    g.pen(c, Path().apply { addCircle(50f + 27f * cx, 50f + 27f * cy, 4f, Path.Direction.CW) })
                }
                g.pen(c, Path().apply { addCircle(50f, 50f, 3.2f, Path.Direction.CW) })
            }
            SLIDERS -> for ((i, y) in listOf(37f, 50f, 63f).withIndex()) {
                g.pen(c, Path().apply { moveTo(30f, y); lineTo(70f, y) }, alpha = 120)
                val x = listOf(58f, 40f, 62f)[i]
                g.pen(c, Path().apply { addCircle(x, y, 4.5f, Path.Direction.CW) })
            }
            VOICE -> {
                g.pen(c, GlyphPen.roundRect(43f, 26f, 57f, 55f, 7f))
                g.pen(c, Path().apply {
                    addArc(RectF(34f, 36f, 66f, 64f), 0f, 180f)
                    moveTo(50f, 64f); lineTo(50f, 72f); moveTo(43f, 72f); lineTo(57f, 72f)
                })
            }
        }
        c.restoreToCount(save)
    }

    companion object {
        const val PLAYER = 0
        const val CORE = 1
        const val ALL = 2
        const val BELL = 3
        const val SEASON = 4
        const val APPLE = 5
        const val PROFILES = 6
        const val VOICE = 7
        const val ENGINE = 8
        const val SLIDERS = 9
    }
}
