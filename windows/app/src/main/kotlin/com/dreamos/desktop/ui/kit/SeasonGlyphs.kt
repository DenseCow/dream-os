package com.dreamos.desktop.ui.kit

import com.dreamos.desktop.ui.kit.GenreGlyphs.FILL
import com.dreamos.desktop.ui.kit.GenreGlyphs.FINE
import com.dreamos.desktop.ui.kit.GenreGlyphs.GPath
import com.dreamos.desktop.ui.kit.GenreGlyphs.LINE
import com.dreamos.desktop.ui.kit.GenreGlyphs.Part

/**
 * The seasonal hubs' glyphs ("Season:Snowflake", "Season:Christmas"...), in [GenreGlyphs]' visual language: a
 * 100 × 100 box, every drawing filling about the same circle, soft top-to-bottom gradients from the tint's
 * tones, rounded joins, the same two line weights and the faint shadow under filled silhouettes. Plain data
 * like [GenreGlyphs] (no Android types); [GenreArt] draws them. Halloween and Pride reuse the genre set's
 * jack-o'-lantern and rainbow.
 */
object SeasonGlyphs {
    /** [name]'s parts for a card tinted [tint], or null when it isn't a season glyph. */
    fun build(name: String, tint: Int): List<Part>? {
        if (!name.startsWith("Season:")) return null
        val t = GenreGlyphs.Tones(tint)
        val out = ArrayList<Part>()
        fun body(p: GPath, top: Int = t.light, bottom: Int = t.mid) { out += Part(p, top, bottom, 2.4f, FILL, shadow = true) }
        fun flat(p: GPath, c: Int) { out += Part(p, c, c, 2.4f, FILL) }
        fun dot(p: GPath, c: Int) { out += Part(p, c, c) }
        fun stroke(p: GPath, c: Int, w: Float = LINE) { out += Part(p, 0, 0, w, c) }
        fun gleam(l: Float, tp: Float, r: Float, b: Float) {
            val c = GenreGlyphs.alpha(0xFFFFFFFF.toInt(), 0.55f)
            out += Part(GPath().oval(l, tp, r, b), c, c)
        }
        fun cos(deg: Double) = Math.cos(Math.toRadians(deg)).toFloat()
        fun sin(deg: Double) = Math.sin(Math.toRadians(deg)).toFloat()
        /** A heart whose point is at ([px], [py]), [w] wide, turned [rot] degrees about its point. */
        fun heartAt(px: Float, py: Float, w: Float, rot: Float = 0f): GPath {
            val h = w * 0.9f
            val cy = py - h * 0.5f
            return GPath(rot, px, py).moveTo(px, py)
                .cubicTo(px - w * 0.72f, cy + h * 0.02f, px - w * 0.52f, cy - h * 0.6f, px, cy - h * 0.2f)
                .cubicTo(px + w * 0.52f, cy - h * 0.6f, px + w * 0.72f, cy + h * 0.02f, px, py).close()
        }
        fun star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int, rot: Float = -90f): GPath {
            val p = GPath()
            for (i in 0 until points * 2) {
                val r = if (i % 2 == 0) outer else inner
                val a = (rot + i * 180f / points).toDouble()
                val x = cx + r * cos(a)
                val y = cy + r * sin(a)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p.close()
        }
        fun sparkle(cx: Float, cy: Float, r: Float) = flat(star(cx, cy, r, r * 0.31f, 4), t.pearl)
        val rose = GenreGlyphs.mix(t.light, t.coral, 0.55f)
        val roseDeep = GenreGlyphs.mix(t.mid, t.coral, 0.7f)

        when (name) {
            "Season:Snowflake" -> {
                // Six arms, each with two pairs of little branches, around a soft hexagon.
                val arms = GPath()
                val twigs = GPath()
                for (i in 0 until 6) {
                    val a = -90.0 + i * 60
                    arms.moveTo(50f, 50f).lineTo(50f + 34f * cos(a), 50f + 34f * sin(a))
                    for ((at, len) in listOf(17f to 9f, 26f to 6.5f)) {
                        val bx = 50f + at * cos(a)
                        val by = 50f + at * sin(a)
                        for (side in listOf(-45.0, 45.0)) twigs.moveTo(bx, by).lineTo(bx + len * cos(a + side), by + len * sin(a + side))
                    }
                }
                stroke(arms, t.light, LINE)
                stroke(twigs, t.pearl, FINE)
                val hex = GPath()
                for (i in 0 until 6) {
                    val a = -90.0 + i * 60
                    if (i == 0) hex.moveTo(50f + 9f * cos(a), 50f + 9f * sin(a)) else hex.lineTo(50f + 9f * cos(a), 50f + 9f * sin(a))
                }
                body(hex.close(), t.pearl, t.light)
                dot(GPath().circle(50f, 50f, 3f), t.mid)
            }
            "Season:Blossom" -> {
                // A tulip on its stem, one leaf curling up beside it.
                stroke(GPath().moveTo(50f, 58f).quadTo(51f, 74f, 49f, 90f), t.deep, LINE)
                body(GPath().moveTo(49f, 86f).quadTo(28f, 82f, 24f, 60f).quadTo(42f, 64f, 49f, 80f).close(), t.mid, t.mid)
                body(GPath().moveTo(30f, 18f).lineTo(40f, 28f).lineTo(50f, 12f).lineTo(60f, 28f).lineTo(70f, 18f)
                    .quadTo(75f, 52f, 50f, 60f).quadTo(25f, 52f, 30f, 18f).close(), mix(t.pearl, rose, 0.35f), mix(t.light, roseDeep, 0.6f))
                stroke(GPath().moveTo(40f, 30f).quadTo(42f, 46f, 50f, 56f).moveTo(60f, 30f).quadTo(58f, 46f, 50f, 56f), alpha(t.pearl, 0.7f), FINE - 1f)
                gleam(35f, 26f, 42f, 36f)
            }
            "Season:Sun" -> {
                // The summer sun over two rolling waves.
                val rays = GPath()
                for (i in 0 until 10) {
                    val a = -180.0 + i * 20
                    if (a > 0) continue
                    rays.moveTo(50f + 27f * cos(a), 52f + 27f * sin(a)).lineTo(50f + 37f * cos(a), 52f + 37f * sin(a))
                }
                stroke(rays, t.light, LINE)
                body(GPath().arc(30f, 32f, 70f, 72f, 180f, 180f).close(), t.pearl, t.light)
                gleam(36f, 38f, 46f, 44f)
                stroke(GPath().moveTo(14f, 62f).quadTo(23f, 56f, 32f, 62f).quadTo(41f, 68f, 50f, 62f).quadTo(59f, 56f, 68f, 62f).quadTo(77f, 68f, 86f, 62f), t.mid, LINE)
                stroke(GPath().moveTo(22f, 75f).quadTo(31f, 69f, 40f, 75f).quadTo(49f, 81f, 58f, 75f).quadTo(67f, 69f, 78f, 75f), t.light, LINE)
            }
            "Season:Leaf" -> {
                // A maple leaf on its stem, its veins in pearl.
                stroke(GPath().moveTo(50f, 66f).quadTo(51f, 80f, 46f, 90f), t.deep, FINE)
                body(GPath().moveTo(50f, 70f).lineTo(31f, 74f).lineTo(34f, 64f).lineTo(15f, 52f).lineTo(22f, 47f).lineTo(17f, 34f)
                    .lineTo(30f, 37f).lineTo(32f, 29f).lineTo(41f, 36f).lineTo(38f, 19f).lineTo(44f, 21f).lineTo(50f, 9f)
                    .lineTo(56f, 21f).lineTo(62f, 19f).lineTo(59f, 36f).lineTo(68f, 29f).lineTo(70f, 37f).lineTo(83f, 34f)
                    .lineTo(78f, 47f).lineTo(85f, 52f).lineTo(66f, 64f).lineTo(69f, 74f).close(),
                    mix(t.light, t.coral, 0.25f), mix(t.mid, t.coral, 0.45f))
                stroke(GPath().moveTo(50f, 68f).lineTo(50f, 22f).moveTo(50f, 58f).lineTo(25f, 42f).moveTo(50f, 58f).lineTo(75f, 42f)
                    .moveTo(50f, 64f).lineTo(31f, 66f).moveTo(50f, 64f).lineTo(69f, 66f), alpha(t.pearl, 0.75f), 2.2f)
            }
            "Season:NewYear" -> {
                // Two champagne flutes meeting in a toast, a sparkle where they touch.
                for ((rot, px) in listOf(-14f to 38f, 14f to 62f)) {
                    val x = px
                    flat(GPath(rot, x, 60f).roundRect(x - 1.6f, 52f, x + 1.6f, 78f, 1.6f), t.mid)
                    flat(GPath(rot, x, 60f).roundRect(x - 9f, 76f, x + 9f, 81f, 2.5f), t.mid)
                    body(GPath(rot, x, 60f).moveTo(x - 10f, 16f).lineTo(x + 10f, 16f).quadTo(x + 10.5f, 46f, x, 54f).quadTo(x - 10.5f, 46f, x - 10f, 16f).close(),
                        t.pearl, t.light)
                    flat(GPath(rot, x, 60f).moveTo(x - 9.4f, 28f).lineTo(x + 9.4f, 28f).quadTo(x + 9f, 46f, x, 51f).quadTo(x - 9f, 46f, x - 9.4f, 28f).close(), t.mid)
                    dot(GPath(rot, x, 60f).circle(x - 2f, 38f, 1.6f).circle(x + 3f, 44f, 1.3f).circle(x, 33f, 1.2f), t.pearl)
                }
                sparkle(50f, 10f, 9f)
                dot(GPath().circle(36f, 8f, 2.2f).circle(65f, 7f, 1.8f), t.pearl)
            }
            "Season:Valentine" -> {
                // A heart with Cupid's arrow through it.
                stroke(GPath().moveTo(14f, 82f).lineTo(48f, 52f), t.pearl, FINE)
                body(heartAt(50f, 82f, 60f), rose, roseDeep)
                gleam(30f, 40f, 40f, 47f)
                stroke(GPath().moveTo(56f, 46f).lineTo(78f, 26f), t.pearl, FINE)
                flat(GPath().moveTo(86f, 18f).lineTo(72f, 22f).lineTo(82f, 32f).close(), t.pearl)
                stroke(GPath().moveTo(14f, 82f).lineTo(10f, 74f).moveTo(14f, 82f).lineTo(22f, 86f).moveTo(19f, 78f).lineTo(15f, 70f).moveTo(19f, 78f).lineTo(27f, 82f), t.light, FINE - 0.6f)
            }
            "Season:Stories" -> {
                // An open book, a star rising from its pages: stories that shaped history.
                body(GPath().moveTo(50f, 50f).quadTo(32f, 41f, 12f, 47f).lineTo(12f, 84f).quadTo(32f, 78f, 50f, 88f).close()
                    .moveTo(50f, 50f).quadTo(68f, 41f, 88f, 47f).lineTo(88f, 84f).quadTo(68f, 78f, 50f, 88f).close(), t.pearl, t.light)
                stroke(GPath().moveTo(20f, 60f).quadTo(32f, 56f, 42f, 61f).moveTo(20f, 70f).quadTo(32f, 66f, 42f, 71f)
                    .moveTo(58f, 61f).quadTo(68f, 56f, 80f, 60f).moveTo(58f, 71f).quadTo(68f, 66f, 80f, 70f), t.mid, FINE)
                stroke(GPath().moveTo(50f, 50f).lineTo(50f, 88f), t.mid, FINE)
                body(star(50f, 25f, 15f, 6.5f, 5), t.pearl, mix(t.light, t.coral, 0.25f))
                dot(GPath().circle(30f, 32f, 2.2f).circle(70f, 30f, 2.6f).circle(38f, 16f, 1.6f), t.light)
            }
            "Season:Award" -> {
                // A trophy cup with a star on it, its handles, stem and base.
                stroke(GPath().moveTo(29f, 24f).quadTo(12f, 24f, 15f, 38f).quadTo(18f, 50f, 33f, 52f)
                    .moveTo(71f, 24f).quadTo(88f, 24f, 85f, 38f).quadTo(82f, 50f, 67f, 52f), t.mid, LINE)
                flat(GPath().roundRect(45f, 62f, 55f, 76f, 2f), t.mid)
                body(GPath().roundRect(30f, 76f, 70f, 88f, 4f), t.light, t.mid)
                flat(GPath().roundRect(36f, 79f, 64f, 83f, 2f), alpha(t.pearl, 0.6f))
                body(GPath().moveTo(26f, 14f).lineTo(74f, 14f).lineTo(72f, 36f).quadTo(68f, 62f, 50f, 64f).quadTo(32f, 62f, 28f, 36f).close(), t.pearl, t.light)
                flat(star(50f, 36f, 11f, 4.6f, 5), t.coral)
                gleam(32f, 18f, 39f, 32f)
            }
            "Season:Women" -> {
                // The Venus symbol, a small sparkle beside it.
                stroke(GPath().circle(48f, 38f, 21f), t.light, LINE + 1.5f)
                stroke(GPath().moveTo(48f, 60f).lineTo(48f, 90f).moveTo(36f, 77f).lineTo(60f, 77f), t.light, LINE + 1.5f)
                stroke(GPath().arc(33f, 23f, 63f, 53f, 200f, 50f), t.pearl, FINE - 0.6f)
                sparkle(80f, 20f, 10f)
                dot(GPath().circle(80f, 40f, 2.2f), t.pearl)
            }
            "Season:Shamrock" -> {
                // Three heart-shaped leaves and a curling stem.
                stroke(GPath().moveTo(50f, 54f).quadTo(54f, 74f, 66f, 88f), t.deep, LINE)
                for (rot in listOf(0f, 120f, 240f)) body(heartAt(50f, 52f, 34f, rot), t.light, t.mid)
                dot(GPath().circle(50f, 52f, 3.2f), t.mid)
                gleam(43f, 27f, 49f, 31f)
            }
            "Season:Easter" -> {
                // A painted egg: a zigzag band and a band of dots.
                body(GPath().moveTo(50f, 12f).cubicTo(70f, 12f, 78f, 50f, 76f, 64f).cubicTo(74f, 80f, 63f, 88f, 50f, 88f)
                    .cubicTo(37f, 88f, 26f, 80f, 24f, 64f).cubicTo(22f, 50f, 30f, 12f, 50f, 12f).close(), t.pearl, t.light)
                stroke(GPath().moveTo(26f, 52f).lineTo(33f, 45f).lineTo(41f, 53f).lineTo(50f, 45f).lineTo(59f, 53f).lineTo(67f, 45f).lineTo(74f, 52f), t.mid, FINE)
                dot(GPath().circle(32f, 66f, 3f).circle(43f, 68f, 3f).circle(57f, 68f, 3f).circle(68f, 66f, 3f), t.coral)
                stroke(GPath().moveTo(30f, 76f).quadTo(50f, 82f, 70f, 76f), t.mid, FINE - 0.6f)
                gleam(36f, 22f, 46f, 32f)
            }
            "Season:Mother" -> {
                // A small bouquet of flowers tied with a bow.
                val stems = GPath().moveTo(50f, 80f).lineTo(30f, 42f).moveTo(50f, 80f).lineTo(50f, 28f).moveTo(50f, 80f).lineTo(70f, 42f)
                stroke(stems, t.deep, FINE)
                fun flower(cx: Float, cy: Float, r: Float) {
                    val petals = GPath()
                    for (i in 0 until 5) {
                        val a = -90.0 + i * 72
                        petals.circle(cx + r * 0.55f * cos(a), cy + r * 0.55f * sin(a), r * 0.48f)
                    }
                    body(petals, t.pearl, rose)
                    dot(GPath().circle(cx, cy, r * 0.24f), mix(t.mid, t.coral, 0.4f))
                }
                flower(28f, 40f, 14f)
                flower(72f, 40f, 14f)
                flower(50f, 22f, 15f)
                flat(GPath().moveTo(50f, 68f).cubicTo(40f, 58f, 34f, 70f, 46f, 72f).close().moveTo(50f, 68f).cubicTo(60f, 58f, 66f, 70f, 54f, 72f).close(), t.coral)
                flat(GPath().moveTo(48f, 70f).lineTo(42f, 86f).lineTo(47f, 84f).close().moveTo(52f, 70f).lineTo(58f, 86f).lineTo(53f, 84f).close(), t.coral)
                dot(GPath().circle(50f, 69f, 3f), mix(t.coral, 0xFFFFFFFF.toInt(), 0.2f))
            }
            "Season:Father" -> {
                // A necktie: its knot, the blade, a few diagonal stripes.
                flat(GPath().moveTo(30f, 10f).lineTo(50f, 18f).lineTo(70f, 10f).lineTo(66f, 20f).lineTo(50f, 24f).lineTo(34f, 20f).close(), t.pearl)
                body(GPath().moveTo(42f, 20f).lineTo(58f, 20f).lineTo(55f, 32f).lineTo(45f, 32f).close(), t.light, t.mid)
                body(GPath().moveTo(45f, 32f).lineTo(55f, 32f).lineTo(64f, 76f).lineTo(50f, 90f).lineTo(36f, 76f).close(), t.light, t.mid)
                stroke(GPath().moveTo(44f, 46f).lineTo(57f, 40f).moveTo(42f, 58f).lineTo(60f, 50f).moveTo(40f, 70f).lineTo(62f, 60f), alpha(t.pearl, 0.8f), FINE - 0.4f)
                gleam(46f, 36f, 50f, 46f)
            }
            "Season:Fireworks" -> {
                // Two bursts in the night sky, a trail rising to the big one.
                stroke(GPath().moveTo(44f, 92f).quadTo(40f, 72f, 42f, 56f), alpha(t.light, 0.7f), FINE - 0.6f)
                val big = GPath()
                val tips = GPath()
                for (i in 0 until 12) {
                    val a = i * 30.0
                    big.moveTo(42f + 9f * cos(a), 40f + 9f * sin(a)).lineTo(42f + 26f * cos(a), 40f + 26f * sin(a))
                    tips.circle(42f + 31f * cos(a), 40f + 31f * sin(a), 2.4f)
                }
                stroke(big, t.light, LINE - 0.6f)
                dot(tips, t.pearl)
                dot(GPath().circle(42f, 40f, 4f), t.pearl)
                val small = GPath()
                for (i in 0 until 8) {
                    val a = 22.5 + i * 45.0
                    small.moveTo(76f + 5f * cos(a), 72f + 5f * sin(a)).lineTo(76f + 14f * cos(a), 72f + 14f * sin(a))
                }
                stroke(small, t.coral, FINE)
                dot(GPath().circle(76f, 72f, 2.6f), t.pearl)
                sparkle(82f, 18f, 8f)
            }
            "Season:School" -> {
                // An apple on a stack of books.
                flat(GPath().roundRect(16f, 76f, 84f, 88f, 3f), t.mid)
                flat(GPath().roundRect(22f, 64f, 80f, 76f, 3f), t.light)
                stroke(GPath().moveTo(28f, 82f).lineTo(78f, 82f), alpha(t.pearl, 0.7f), 2f)
                stroke(GPath().moveTo(28f, 70f).lineTo(74f, 70f), alpha(t.pearl, 0.7f), 2f)
                body(GPath().moveTo(50f, 30f).cubicTo(60f, 22f, 76f, 26f, 74f, 44f).cubicTo(72f, 58f, 60f, 64f, 50f, 60f)
                    .cubicTo(40f, 64f, 28f, 58f, 26f, 44f).cubicTo(24f, 26f, 40f, 22f, 50f, 30f).close(), rose, roseDeep)
                stroke(GPath().moveTo(50f, 30f).quadTo(49f, 22f, 53f, 16f), t.deep, FINE)
                flat(GPath().moveTo(53f, 22f).quadTo(60f, 12f, 70f, 16f).quadTo(62f, 26f, 53f, 22f).close(), t.mid)
                gleam(32f, 34f, 40f, 42f)
            }
            "Season:Voices" -> {
                // Two speech bubbles in conversation: many voices, many stories.
                body(GPath().moveTo(22f, 14f).lineTo(60f, 14f).quadTo(72f, 14f, 72f, 26f).lineTo(72f, 42f).quadTo(72f, 54f, 60f, 54f)
                    .lineTo(34f, 54f).lineTo(22f, 66f).lineTo(25f, 54f).quadTo(10f, 54f, 10f, 42f).lineTo(10f, 26f).quadTo(10f, 14f, 22f, 14f).close(), t.light, t.mid)
                body(GPath().moveTo(44f, 44f).lineTo(78f, 44f).quadTo(90f, 44f, 90f, 56f).lineTo(90f, 68f).quadTo(90f, 80f, 78f, 80f)
                    .lineTo(76f, 80f).lineTo(80f, 92f).lineTo(64f, 80f).lineTo(44f, 80f).quadTo(32f, 80f, 32f, 68f).lineTo(32f, 56f)
                    .quadTo(32f, 44f, 44f, 44f).close(), t.pearl, t.light)
                dot(GPath().circle(49f, 62f, 3.4f).circle(61f, 62f, 3.4f).circle(73f, 62f, 3.4f), t.mid)
                dot(heartAt(41f, 38f, 14f), t.coral)
                gleam(16f, 18f, 26f, 23f)
            }
            "Season:Turkey" -> {
                // A friendly turkey: its fanned tail, round body, head and wattle.
                val fan = listOf(-150f, -125f, -100f, -80f, -55f, -30f)
                for ((i, a) in fan.withIndex()) {
                    val x = 50f + 24f * cos(a.toDouble())
                    val y = 58f + 24f * sin(a.toDouble())
                    val c = if (i % 2 == 0) t.mid else mix(t.light, t.coral, 0.35f)
                    body(GPath(a + 90f, x, y).oval(x - 8f, y - 17f, x + 8f, y + 17f), c, c)
                }
                stroke(GPath().moveTo(44f, 78f).lineTo(42f, 90f).moveTo(56f, 78f).lineTo(58f, 90f), t.deep, FINE)
                body(GPath().circle(50f, 64f, 19f), t.light, t.mid)
                body(GPath().circle(50f, 44f, 10f), t.pearl, t.light)
                dot(GPath().circle(46.5f, 42f, 1.8f).circle(53.5f, 42f, 1.8f), t.deep)
                flat(GPath().moveTo(47f, 46f).lineTo(53f, 46f).lineTo(50f, 51f).close(), mix(t.pearl, 0xFFFFB25C.toInt(), 0.5f))
                dot(GPath().moveTo(53f, 48f).quadTo(57f, 54f, 53f, 57f).quadTo(50f, 53f, 53f, 48f).close(), t.coral)
                gleam(42f, 58f, 50f, 63f)
            }
            "Season:Christmas" -> {
                // A Christmas tree: three tiers, a trunk, ornaments and a star on top.
                flat(GPath().roundRect(45f, 78f, 55f, 90f, 2f), t.deep)
                body(GPath().moveTo(50f, 44f).lineTo(80f, 80f).lineTo(20f, 80f).close(), t.light, t.mid)
                body(GPath().moveTo(50f, 30f).lineTo(73f, 60f).lineTo(27f, 60f).close(), t.light, t.mid)
                body(GPath().moveTo(50f, 18f).lineTo(66f, 41f).lineTo(34f, 41f).close(), t.pearl, t.light)
                stroke(GPath().moveTo(33f, 70f).quadTo(50f, 76f, 69f, 66f).moveTo(36f, 52f).quadTo(50f, 57f, 63f, 48f), alpha(t.pearl, 0.8f), 2.2f)
                dot(GPath().circle(40f, 66f, 3f).circle(60f, 74f, 3f).circle(55f, 52f, 2.6f), t.coral)
                dot(GPath().circle(64f, 64f, 2.6f).circle(44f, 50f, 2.4f).circle(50f, 34f, 2.2f), t.pearl)
                body(star(50f, 13f, 9f, 3.8f, 5), t.pearl, mix(t.pearl, 0xFFFFD21F.toInt(), 0.35f))
            }
            else -> return null
        }
        return out
    }

    private fun mix(a: Int, b: Int, f: Float) = GenreGlyphs.mix(a, b, f)
    private fun alpha(c: Int, a: Float) = GenreGlyphs.alpha(c, a)
}
