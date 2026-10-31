package com.dreamos.desktop.ui.kit

/**
 * The Browse by Genre glyphs as plain vector data (no Android types, so the same shapes can be
 * previewed off-device). [GenreArt] draws them.
 *
 * One visual language for the whole set, Apple TV / Disney+ category style: a 100 × 100 box with
 * every glyph filling about the same circle (r ≈ 34 around the centre), soft top-to-bottom
 * gradients, fully rounded joins and caps, one line weight ([LINE]) and one fine weight ([FINE]).
 * Colour is a 3-tone pastel from the card's own tint (light, mid, and a deep tone for small details
 * such as eyes) plus a pearl white for highlights; no outlines. Each filled silhouette casts a faint
 * soft shadow straight down.
 */
object GenreGlyphs {
    const val LINE = 5f
    const val FINE = 3.6f

    /** Receives path commands (Android's Path and Java2D's Path2D both implement this via adapters). */
    interface Sink {
        fun moveTo(x: Float, y: Float)
        fun lineTo(x: Float, y: Float)
        fun quadTo(x1: Float, y1: Float, x: Float, y: Float)
        fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
        fun close()
        fun circle(cx: Float, cy: Float, r: Float)
        fun oval(l: Float, t: Float, r: Float, b: Float)
        fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float)
        /** Degrees, clockwise on screen from 3 o'clock (Android's convention). [connect]: line to its start. */
        fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, connect: Boolean)
    }

    class GPath(val rotate: Float = 0f, val px: Float = 0f, val py: Float = 0f) {
        val ops = ArrayList<(Sink) -> Unit>()
        fun moveTo(x: Float, y: Float) = apply { ops += { it.moveTo(x, y) } }
        fun lineTo(x: Float, y: Float) = apply { ops += { it.lineTo(x, y) } }
        fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = apply { ops += { it.quadTo(x1, y1, x, y) } }
        fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = apply { ops += { it.cubicTo(x1, y1, x2, y2, x, y) } }
        fun close() = apply { ops += { it.close() } }
        fun circle(cx: Float, cy: Float, r: Float) = apply { ops += { it.circle(cx, cy, r) } }
        fun oval(l: Float, t: Float, r: Float, b: Float) = apply { ops += { it.oval(l, t, r, b) } }
        fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) = apply { ops += { it.roundRect(l, t, r, b, rad) } }
        fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float, connect: Boolean = false) =
            apply { ops += { it.arc(l, t, r, b, start, sweep, connect) } }
        fun replay(s: Sink) = ops.forEach { it(s) }
    }

    /**
     * One shape. [top]/[bottom]: its fill as a vertical gradient over the glyph box (equal = flat;
     * 0 = no fill). [line] > 0 strokes it in [lineColor] with round caps and joins; a stroke in the
     * fill's own colours ([lineColor] == FILL) just softens the silhouette's corners.
     */
    class Part(
        val path: GPath,
        val top: Int,
        val bottom: Int,
        val line: Float = 0f,
        val lineColor: Int = 0,
        val shadow: Boolean = false,
    )

    const val FILL = 1 // sentinel lineColor: stroke with the fill's gradient

    /** The tones for a card tinted [tint]. */
    class Tones(tint: Int) {
        val pearl = mix(0xFFFFFFFF.toInt(), tint, 0.08f)
        val light = mix(tint, 0xFFFFFFFF.toInt(), 0.66f)
        val mid = mix(tint, 0xFFFFFFFF.toInt(), 0.40f)
        val deep = mix(tint, 0xFF26262A.toInt(), 0.50f) // toward neutral dark gray, never a tint (STANDARDS §2)
        val coral = mix(0xFFFF7A86.toInt(), tint, 0.15f)
    }

    fun build(genre: String, tint: Int): List<Part> {
        val t = Tones(tint)
        val out = ArrayList<Part>()
        // Helpers in the shared language.
        fun body(p: GPath, top: Int = t.light, bottom: Int = t.mid) { out += Part(p, top, bottom, 2.4f, FILL, shadow = true) }
        fun flat(p: GPath, c: Int) { out += Part(p, c, c, 2.4f, FILL) }
        fun dot(p: GPath, c: Int) { out += Part(p, c, c) }
        fun stroke(p: GPath, c: Int, w: Float = LINE) { out += Part(p, 0, 0, w, c) }
        fun gleam(l: Float, tp: Float, r: Float, b: Float) { out += Part(GPath().oval(l, tp, r, b), alpha(0xFFFFFFFF.toInt(), 0.55f), alpha(0xFFFFFFFF.toInt(), 0.55f)) }
        fun heart(cx: Float, cy: Float, w: Float, rot: Float = 0f): GPath {
            val h = w * 0.9f
            return GPath(rot, cx, cy).moveTo(cx, cy + h * 0.5f)
                .cubicTo(cx - w * 0.72f, cy + h * 0.02f, cx - w * 0.52f, cy - h * 0.6f, cx, cy - h * 0.2f)
                .cubicTo(cx + w * 0.52f, cy - h * 0.6f, cx + w * 0.72f, cy + h * 0.02f, cx, cy + h * 0.5f).close()
        }
        fun star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int, rot: Float = -90f): GPath {
            val p = GPath()
            for (i in 0 until points * 2) {
                val r = if (i % 2 == 0) outer else inner
                val a = Math.toRadians((rot + i * 180f / points).toDouble())
                val x = cx + r * Math.cos(a).toFloat()
                val y = cy + r * Math.sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p.close()
        }
        fun blossom(cx: Float, cy: Float, r: Float) {
            val petals = GPath()
            for (i in 0 until 5) {
                val a = Math.toRadians((-90 + i * 72).toDouble())
                petals.circle(cx + r * 0.52f * Math.cos(a).toFloat(), cy + r * 0.52f * Math.sin(a).toFloat(), r * 0.46f)
            }
            body(petals, t.pearl, t.light)
            dot(GPath().circle(cx, cy, r * 0.2f), t.mid)
        }

        when (genre) {
            "Action" -> {
                // A "boom" burst with a smoke puff trailing off it.
                body(GPath().circle(24f, 73f, 11f).circle(37f, 80f, 8.5f).circle(16f, 62f, 7f), t.pearl, t.light)
                body(star(55f, 45f, 34f, 23f, 9, -72f))
                flat(star(55f, 45f, 16f, 12f, 8, -64f), t.pearl)
            }
            "Comedy" -> {
                body(GPath().circle(50f, 50f, 31f))
                stroke(GPath().arc(33f, 37f, 45f, 49f, 200f, 140f), t.deep, FINE)
                stroke(GPath().arc(55f, 37f, 67f, 49f, 200f, 140f), t.deep, FINE)
                flat(GPath().moveTo(34f, 56f).quadTo(50f, 59f, 66f, 56f).quadTo(62f, 78f, 50f, 78f).quadTo(38f, 78f, 34f, 56f).close(), t.deep)
                dot(GPath().oval(43f, 68f, 57f, 76f), t.coral)
                gleam(33f, 27f, 44f, 33f)
            }
            "Drama" -> {
                // A tragedy mask with one tear.
                body(GPath().moveTo(22f, 26f).quadTo(50f, 15f, 78f, 26f).quadTo(81f, 68f, 50f, 84f).quadTo(19f, 68f, 22f, 26f).close())
                stroke(GPath().arc(31f, 42f, 45f, 54f, 20f, 140f), t.deep, FINE)
                stroke(GPath().arc(55f, 42f, 69f, 54f, 20f, 140f), t.deep, FINE)
                stroke(GPath().arc(39f, 64f, 61f, 80f, 205f, 130f), t.deep, FINE)
                flat(GPath().moveTo(37f, 57f).quadTo(32f, 65f, 37f, 68f).quadTo(42f, 65f, 37f, 57f).close(), t.pearl)
                gleam(30f, 24f, 42f, 29f)
            }
            "Sci-Fi" -> {
                // A ringed planet (the ring passes behind and in front) and a small rocket.
                stroke(GPath(-20f, 44f, 58f).arc(7f, 47f, 81f, 69f, 180f, 180f), t.pearl, FINE)
                body(GPath().circle(44f, 58f, 23f))
                stroke(GPath(-20f, 44f, 58f).arc(7f, 47f, 81f, 69f, 0f, 180f), t.pearl, FINE)
                gleam(31f, 42f, 42f, 48f)
                flat(GPath(40f, 79f, 22f).moveTo(74f, 35f).quadTo(79f, 48f, 84f, 35f).close(), t.mid)
                body(GPath(40f, 79f, 22f).moveTo(79f, 5f).quadTo(91f, 15f, 86f, 35f).lineTo(72f, 35f).quadTo(67f, 15f, 79f, 5f).close(), t.pearl, t.light)
                dot(GPath(40f, 79f, 22f).circle(79f, 19f, 3.6f), t.mid)
            }
            "Horror" -> {
                // A friendly little ghost.
                body(GPath().moveTo(22f, 80f).lineTo(22f, 46f).cubicTo(22f, 11f, 78f, 11f, 78f, 46f).lineTo(78f, 80f)
                    .quadTo(73.3f, 86f, 68.7f, 81f).quadTo(64f, 76f, 59.3f, 81f).quadTo(54.7f, 86f, 50f, 81f)
                    .quadTo(45.3f, 76f, 40.7f, 81f).quadTo(36f, 86f, 31.3f, 81f).quadTo(26.7f, 76f, 22f, 80f).close(), t.pearl, t.light)
                dot(GPath().oval(36.5f, 39f, 44.5f, 50f).oval(55.5f, 39f, 63.5f, 50f), t.deep)
                dot(GPath().oval(46f, 55f, 54f, 63f), t.deep)
                gleam(31f, 24f, 43f, 30f)
            }
            "Thriller" -> {
                // A stopwatch with a heartbeat across its face.
                flat(GPath().roundRect(44f, 12f, 56f, 24f, 3f), t.mid)
                flat(GPath().roundRect(38f, 9f, 62f, 15f, 3f), t.light)
                body(GPath().circle(50f, 56f, 29f))
                flat(GPath().circle(50f, 56f, 22f), t.pearl)
                stroke(GPath().moveTo(31f, 58f).lineTo(39f, 58f).lineTo(44f, 48f).lineTo(50f, 68f).lineTo(55f, 52f).lineTo(59f, 58f).lineTo(69f, 58f), t.deep, FINE)
            }
            "Romance" -> {
                // Hearts keep a warm rose whatever the card's hue (a beige heart doesn't read as love).
                body(heart(43f, 56f, 46f, -8f), mix(t.light, t.coral, 0.55f), mix(t.mid, t.coral, 0.7f))
                gleam(27f, 42f, 37f, 48f)
                body(heart(74f, 29f, 25f, 14f), t.pearl, mix(t.light, t.coral, 0.35f))
            }
            "Animation" -> {
                // A pencil drawing a star.
                body(star(37f, 39f, 27f, 12.5f, 5), t.pearl, t.light)
                dot(GPath().circle(56f, 57f, 2f).circle(61.5f, 62.5f, 2f), t.pearl)
                flat(GPath(-45f, 72f, 72f).roundRect(90f, 66f, 99f, 78f, 3f), t.light)
                body(GPath(-45f, 72f, 72f).roundRect(62f, 66f, 93f, 78f, 1.5f))
                flat(GPath(-45f, 72f, 72f).moveTo(62f, 66f).lineTo(51f, 72f).lineTo(62f, 78f).close(), t.pearl)
                dot(GPath(-45f, 72f, 72f).moveTo(55.5f, 69.6f).lineTo(51f, 72f).lineTo(55.5f, 74.4f).close(), t.deep)
            }
            "Anime" -> {
                // A cherry blossom branch.
                stroke(GPath().moveTo(14f, 84f).cubicTo(36f, 70f, 54f, 52f, 86f, 20f), t.deep, LINE)
                stroke(GPath().moveTo(47f, 57f).quadTo(53f, 69f, 64f, 74f), t.deep, FINE)
                blossom(66f, 31f, 19f)
                blossom(33f, 56f, 15f)
                blossom(67f, 74f, 11f)
            }
            "Documentary" -> {
                // A video camera on a tripod.
                stroke(GPath().moveTo(44f, 62f).lineTo(30f, 88f).moveTo(44f, 62f).lineTo(44f, 90f).moveTo(44f, 62f).lineTo(58f, 88f), t.deep, FINE)
                body(GPath().circle(32f, 25f, 9f).circle(55f, 25f, 9f), t.pearl, t.light)
                body(GPath().roundRect(18f, 34f, 70f, 62f, 7f))
                flat(GPath().moveTo(70f, 42f).lineTo(85f, 35f).lineTo(85f, 61f).lineTo(70f, 54f).close(), t.mid)
                dot(GPath().circle(28f, 43f, 3f), t.coral)
            }
            "Family" -> {
                // A little house with a heart in the window.
                flat(GPath().roundRect(63f, 22f, 71f, 38f, 2f), t.mid)
                body(GPath().roundRect(27f, 47f, 73f, 84f, 4f), t.pearl, t.light)
                body(GPath().moveTo(20f, 50f).lineTo(50f, 23f).lineTo(80f, 50f).close(), t.light, t.mid)
                flat(GPath().roundRect(56f, 65f, 66f, 84f, 3f), t.mid)
                dot(heart(41f, 65f, 14f), t.coral)
            }
            "Crime" -> {
                // A fingerprint: loops with gaps, like a real print.
                val r = listOf(5f, 10.5f, 16f, 21.5f, 27f)
                val start = listOf(190f, 175f, 160f, 185f, 168f)
                val sweep = listOf(300f, 290f, 250f, 280f, 235f)
                for (i in r.indices) stroke(GPath().arc(50f - r[i] * 0.8f, 52f - r[i], 50f + r[i] * 0.8f, 52f + r[i], start[i], sweep[i]),
                    if (i % 2 == 0) t.light else t.mid, FINE)
                stroke(GPath().arc(24f, 26f, 76f, 96f, 205f, 130f), t.pearl, FINE)
            }
            "Mystery" -> {
                // A magnifying glass with a question mark in the lens.
                stroke(GPath().moveTo(64f, 64f).lineTo(82f, 82f), t.mid, 9f)
                dot(GPath().circle(44f, 44f, 25f), alpha(t.pearl, 0.30f))
                stroke(GPath().circle(44f, 44f, 25f), t.light, LINE + 1f)
                stroke(GPath().arc(37f, 30f, 51f, 44f, 180f, 230f).lineTo(44f, 47f).lineTo(44f, 50f), t.pearl, FINE + 0.4f)
                dot(GPath().circle(44f, 57f, 2.8f), t.pearl)
            }
            "Reality" -> {
                // A vintage TV with antennas and a REC dot.
                stroke(GPath().moveTo(50f, 32f).lineTo(37f, 15f).moveTo(50f, 32f).lineTo(63f, 15f), t.deep, FINE)
                dot(GPath().circle(37f, 14f, 3.4f).circle(63f, 14f, 3.4f), t.light)
                flat(GPath().roundRect(28f, 80f, 36f, 87f, 2f).roundRect(64f, 80f, 72f, 87f, 2f), t.mid)
                body(GPath().roundRect(17f, 32f, 83f, 82f, 11f))
                flat(GPath().roundRect(25f, 40f, 63f, 74f, 7f), t.deep)
                dot(GPath().circle(31.5f, 46.5f, 3.2f), t.coral)
                dot(GPath().circle(73f, 49f, 4f).circle(73f, 62f, 4f), t.pearl)
            }
            "Kids" -> {
                // A teddy bear.
                body(GPath().circle(29f, 31f, 11f).circle(71f, 31f, 11f), t.mid, t.mid)
                dot(GPath().circle(29f, 31f, 5.5f).circle(71f, 31f, 5.5f), t.light)
                body(GPath().circle(50f, 54f, 29f))
                flat(GPath().oval(38f, 57f, 62f, 76f), t.pearl)
                dot(GPath().oval(45f, 59f, 55f, 65.5f), t.deep)
                dot(GPath().circle(39.5f, 47f, 3.3f).circle(60.5f, 47f, 3.3f), t.deep)
                gleam(35f, 31f, 46f, 36f)
            }
            "Adventure" -> {
                // A compass, its needle pointing north-east.
                flat(GPath().roundRect(44.5f, 15f, 55.5f, 22f, 3f), t.mid)
                body(GPath().circle(50f, 53f, 31f))
                flat(GPath().circle(50f, 53f, 24f), t.pearl)
                dot(GPath(35f, 50f, 53f).moveTo(50f, 33f).lineTo(56f, 53f).lineTo(44f, 53f).close(), t.coral)
                dot(GPath(35f, 50f, 53f).moveTo(50f, 73f).lineTo(56f, 53f).lineTo(44f, 53f).close(), t.deep)
                dot(GPath().circle(50f, 53f, 3f), t.pearl)
                gleam(31f, 33f, 42f, 38f)
            }
            "Fantasy" -> {
                // A fairy-tale castle with a sparkle over it.
                body(GPath().roundRect(19f, 40f, 33f, 85f, 2f).roundRect(67f, 40f, 81f, 85f, 2f), t.light, t.mid)
                body(GPath().roundRect(29f, 56f, 71f, 85f, 2f), t.pearl, t.light)
                body(GPath().roundRect(42f, 31f, 58f, 85f, 2f), t.light, t.mid)
                flat(GPath().moveTo(16f, 41f).lineTo(26f, 19f).lineTo(36f, 41f).close().moveTo(64f, 41f).lineTo(74f, 19f).lineTo(84f, 41f).close(), t.mid)
                flat(GPath().moveTo(39f, 32f).lineTo(50f, 9f).lineTo(61f, 32f).close(), t.mid)
                flat(GPath().moveTo(44f, 85f).lineTo(44f, 74f).quadTo(50f, 66f, 56f, 74f).lineTo(56f, 85f).close(), t.deep)
                dot(GPath().roundRect(24f, 50f, 28f, 57f, 2f).roundRect(72f, 50f, 76f, 57f, 2f).roundRect(48f, 41f, 52f, 48f, 2f), t.deep)
                flat(star(84f, 15f, 9f, 2.8f, 4), t.pearl)
            }
            "History" -> {
                // A classical temple: pediment, columns, steps.
                body(GPath().moveTo(17f, 34f).lineTo(50f, 15f).lineTo(83f, 34f).close(), t.light, t.mid)
                flat(GPath().roundRect(17f, 34f, 83f, 40f, 2f), t.pearl)
                body(GPath().roundRect(23f, 42f, 31f, 76f, 2f).roundRect(37.7f, 42f, 45.7f, 76f, 2f)
                    .roundRect(54.3f, 42f, 62.3f, 76f, 2f).roundRect(69f, 42f, 77f, 76f, 2f), t.pearl, t.light)
                flat(GPath().roundRect(17f, 77f, 83f, 83f, 2f), t.mid)
                flat(GPath().roundRect(12f, 84f, 88f, 89f, 2f), t.light)
                dot(GPath().circle(50f, 28f, 3f), t.deep)
            }
            "Music" -> {
                // Two beamed eighth notes.
                body(GPath().moveTo(36f, 22f).lineTo(79f, 14f).lineTo(79f, 24f).lineTo(36f, 32f).close(), t.light, t.mid)
                stroke(GPath().moveTo(38f, 28f).lineTo(38f, 70f).moveTo(77f, 20f).lineTo(77f, 62f), t.light, LINE)
                body(GPath(-20f, 28f, 72f).oval(16f, 63f, 40f, 81f))
                body(GPath(-20f, 67f, 64f).oval(55f, 55f, 79f, 73f))
                gleam(20f, 66f, 28f, 70f)
            }
            "War" -> {
                // A medal with a star, on its ribbon.
                flat(GPath().moveTo(29f, 11f).lineTo(45f, 11f).lineTo(58f, 46f).lineTo(46f, 50f).close(), t.mid)
                flat(GPath().moveTo(71f, 11f).lineTo(55f, 11f).lineTo(42f, 46f).lineTo(54f, 50f).close(), t.coral)
                body(GPath().circle(50f, 64f, 23f))
                flat(star(50f, 65.5f, 15f, 6.5f, 5), t.pearl)
                gleam(36f, 48f, 46f, 53f)
            }
            "Western" -> {
                // A cowboy hat: the dented crown, its band, the curled brim in front.
                body(GPath().moveTo(30f, 63f).lineTo(33f, 33f).quadTo(35f, 24f, 43f, 27f).quadTo(50f, 32f, 57f, 27f)
                    .quadTo(65f, 24f, 67f, 33f).lineTo(70f, 63f).close(), t.pearl, t.light)
                flat(GPath().moveTo(31.3f, 50f).lineTo(68.7f, 50f).lineTo(69.5f, 58f).lineTo(30.5f, 58f).close(), t.deep)
                dot(GPath().circle(50f, 54f, 2.6f), t.coral)
                body(GPath().moveTo(9f, 57f).quadTo(13f, 72f, 30f, 72f).lineTo(70f, 72f).quadTo(87f, 72f, 91f, 57f)
                    .quadTo(81f, 63f, 70f, 62f).lineTo(30f, 62f).quadTo(19f, 63f, 9f, 57f).close(), t.light, t.mid)
                gleam(37f, 33f, 44f, 41f)
            }
            "Halloween" -> {
                // A jack-o'-lantern: three lobes, a stem, a friendly carved face. Pumpkins stay warm whatever the hue.
                val warm = mix(t.coral, 0xFFFFB25C.toInt(), 0.55f)
                flat(GPath().roundRect(46f, 14f, 54f, 30f, 3f), t.deep)
                stroke(GPath().moveTo(54f, 22f).quadTo(62f, 15f, 69f, 20f), t.mid, FINE)
                body(GPath().oval(14f, 28f, 58f, 84f).oval(42f, 28f, 86f, 84f), mix(t.light, warm, 0.6f), mix(t.mid, warm, 0.75f))
                body(GPath().oval(30f, 26f, 70f, 86f), mix(t.pearl, warm, 0.45f), mix(t.light, warm, 0.7f))
                dot(GPath().moveTo(33f, 52f).lineTo(41f, 42f).lineTo(46f, 53f).close().moveTo(54f, 53f).lineTo(59f, 42f).lineTo(67f, 52f).close(), t.deep)
                dot(GPath().moveTo(31f, 61f).quadTo(50f, 82f, 69f, 61f).lineTo(62f, 64f).lineTo(58f, 60f).lineTo(54f, 66f).lineTo(50f, 61f)
                    .lineTo(46f, 66f).lineTo(42f, 60f).lineTo(38f, 64f).close(), t.deep)
                gleam(36f, 32f, 46f, 37f)
            }
            "Holiday" -> {
                // A wrapped gift with a bow on top.
                body(GPath().roundRect(20f, 44f, 80f, 86f, 5f))
                flat(GPath().roundRect(16f, 34f, 84f, 47f, 4f), t.pearl)
                flat(GPath().roundRect(45f, 34f, 55f, 86f, 2f), t.coral)
                body(GPath().moveTo(50f, 34f).cubicTo(38f, 14f, 22f, 22f, 33f, 33f).close()
                    .moveTo(50f, 34f).cubicTo(62f, 14f, 78f, 22f, 67f, 33f).close(), mix(t.light, t.coral, 0.5f), mix(t.mid, t.coral, 0.7f))
                dot(GPath().circle(50f, 33f, 4.5f), t.coral)
                flat(star(84f, 16f, 8f, 2.6f, 4), t.pearl)
            }
            "Adult Animation" -> {
                // A cartoon speech bubble full of grawlix: a star, a bolt, a swirl.
                body(GPath().moveTo(26f, 18f).lineTo(74f, 18f).quadTo(88f, 18f, 88f, 32f).lineTo(88f, 56f).quadTo(88f, 70f, 74f, 70f)
                    .lineTo(44f, 70f).lineTo(26f, 86f).lineTo(30f, 70f).lineTo(26f, 70f).quadTo(12f, 70f, 12f, 56f).lineTo(12f, 32f)
                    .quadTo(12f, 18f, 26f, 18f).close(), t.pearl, t.light)
                dot(star(31f, 44f, 10f, 4.2f, 5), t.coral)
                dot(GPath().moveTo(53f, 30f).lineTo(45f, 46f).lineTo(51f, 46f).lineTo(47f, 59f).lineTo(57f, 41f).lineTo(51f, 41f).close(), t.deep)
                stroke(GPath().arc(63f, 36f, 79f, 52f, 180f, 270f).lineTo(71f, 44f), t.mid, FINE)
                dot(GPath().circle(73f, 59f, 2.6f), t.deep)
            }
            "K-Drama" -> {
                // A clapperboard with a heart on its slate.
                flat(GPath(-12f, 18f, 34f).roundRect(18f, 24f, 82f, 36f, 3f), t.light)
                dot(GPath(-12f, 18f, 34f).moveTo(28f, 24f).lineTo(36f, 24f).lineTo(31f, 36f).lineTo(23f, 36f).close()
                    .moveTo(44f, 24f).lineTo(52f, 24f).lineTo(47f, 36f).lineTo(39f, 36f).close()
                    .moveTo(60f, 24f).lineTo(68f, 24f).lineTo(63f, 36f).lineTo(55f, 36f).close(), t.deep)
                body(GPath().roundRect(18f, 40f, 82f, 84f, 6f))
                dot(heart(50f, 63f, 26f), t.coral)
                gleam(24f, 44f, 34f, 48f)
            }
            "True Crime" -> {
                // A detective's shield badge with a star.
                body(GPath().moveTo(50f, 14f).quadTo(64f, 22f, 80f, 22f).quadTo(82f, 62f, 50f, 86f).quadTo(18f, 62f, 20f, 22f).quadTo(36f, 22f, 50f, 14f).close())
                flat(GPath().moveTo(50f, 22f).quadTo(61f, 28f, 73f, 28f).quadTo(74f, 59f, 50f, 78f).quadTo(26f, 59f, 27f, 28f).quadTo(39f, 28f, 50f, 22f).close(), t.pearl)
                dot(star(50f, 50f, 15f, 6.5f, 5), t.deep)
                gleam(28f, 26f, 38f, 31f)
            }
            "Stand-Up Comedy" -> {
                // A stage microphone on its stand.
                stroke(GPath().moveTo(50f, 58f).lineTo(50f, 84f).moveTo(36f, 86f).lineTo(64f, 86f), t.deep, LINE)
                stroke(GPath().arc(30f, 30f, 70f, 64f, 20f, 140f), t.mid, FINE)
                body(GPath().roundRect(38f, 14f, 62f, 58f, 12f))
                dot(GPath().roundRect(38f, 34f, 62f, 39f, 1f), t.deep)
                gleam(42f, 18f, 50f, 24f)
                flat(star(80f, 20f, 9f, 2.8f, 4), t.pearl)
            }
            "Sitcoms" -> {
                // The living-room couch every sitcom has.
                body(GPath().roundRect(22f, 30f, 78f, 60f, 9f), t.light, t.mid)
                body(GPath().roundRect(12f, 46f, 30f, 78f, 7f).roundRect(70f, 46f, 88f, 78f, 7f), t.mid, t.mid)
                body(GPath().roundRect(26f, 56f, 74f, 76f, 5f), t.pearl, t.light)
                stroke(GPath().moveTo(50f, 58f).lineTo(50f, 74f), t.mid, FINE)
                flat(GPath().roundRect(18f, 78f, 24f, 86f, 2f).roundRect(76f, 78f, 82f, 86f, 2f), t.deep)
                gleam(30f, 34f, 42f, 39f)
            }
            "Superheroes" -> {
                // An emblem: a shield with a lightning bolt across it.
                body(GPath().moveTo(18f, 30f).lineTo(50f, 14f).lineTo(82f, 30f).quadTo(80f, 66f, 50f, 86f).quadTo(20f, 66f, 18f, 30f).close())
                dot(GPath().moveTo(56f, 22f).lineTo(36f, 54f).lineTo(49f, 54f).lineTo(42f, 78f).lineTo(66f, 44f).lineTo(53f, 44f).close(), t.pearl)
                gleam(27f, 30f, 37f, 35f)
            }
            "Based on a True Story" -> {
                // An open book with a ribbon marker.
                body(GPath().moveTo(50f, 30f).quadTo(32f, 20f, 12f, 26f).lineTo(12f, 76f).quadTo(32f, 70f, 50f, 80f).close()
                    .moveTo(50f, 30f).quadTo(68f, 20f, 88f, 26f).lineTo(88f, 76f).quadTo(68f, 70f, 50f, 80f).close(), t.pearl, t.light)
                stroke(GPath().moveTo(20f, 40f).quadTo(32f, 36f, 42f, 41f).moveTo(20f, 50f).quadTo(32f, 46f, 42f, 51f)
                    .moveTo(58f, 41f).quadTo(68f, 36f, 80f, 40f).moveTo(58f, 51f).quadTo(68f, 46f, 80f, 50f), t.mid, FINE)
                flat(GPath().moveTo(66f, 24f).lineTo(74f, 23f).lineTo(74f, 44f).lineTo(70f, 40f).lineTo(66f, 44f).close(), t.coral)
                stroke(GPath().moveTo(50f, 30f).lineTo(50f, 80f), t.mid, FINE)
            }
            "Feel-Good" -> {
                // A smiling sun.
                val rays = GPath()
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    rays.moveTo(50f + 29f * Math.cos(a).toFloat(), 50f + 29f * Math.sin(a).toFloat())
                        .lineTo(50f + 38f * Math.cos(a).toFloat(), 50f + 38f * Math.sin(a).toFloat())
                }
                stroke(rays, t.light, LINE)
                body(GPath().circle(50f, 50f, 22f), t.pearl, t.light)
                dot(GPath().circle(42.5f, 46f, 2.8f).circle(57.5f, 46f, 2.8f), t.deep)
                stroke(GPath().arc(39f, 44f, 61f, 62f, 25f, 130f), t.deep, FINE)
                dot(GPath().circle(37f, 55f, 3f).circle(63f, 55f, 3f), alpha(t.coral, 0.7f))
            }
            "Mind-Bending" -> {
                // A hypnotic spiral.
                body(GPath().circle(50f, 50f, 34f), t.light, t.mid)
                // Half turns, each 3 wider than the last, alternating about two centers 3 apart: one unbroken line.
                val spiral = GPath()
                var r = 4f
                for (i in 0 until 8) {
                    val cx = if (i % 2 == 0) 50f else 47f
                    spiral.arc(cx - r, 50f - r, cx + r, 50f + r, if (i % 2 == 0) 180f else 0f, 180f, connect = i > 0)
                    r += 3f
                }
                stroke(spiral, t.pearl, FINE)
                dot(GPath().circle(50f, 50f, 3f), t.deep)
            }
            "Sports" -> {
                // A basketball, its seams.
                body(GPath().circle(50f, 50f, 33f), mix(t.light, t.coral, 0.35f), mix(t.mid, t.coral, 0.5f))
                stroke(GPath().moveTo(17f, 50f).lineTo(83f, 50f).moveTo(50f, 17f).lineTo(50f, 83f), t.deep, FINE)
                stroke(GPath().arc(-2f, 22f, 36f, 78f, -60f, 120f).arc(64f, 22f, 102f, 78f, 120f, 120f), t.deep, FINE)
                gleam(30f, 26f, 41f, 32f)
            }
            "Teen" -> {
                // Headphones.
                stroke(GPath().arc(20f, 18f, 80f, 78f, 180f, 180f), t.mid, LINE + 1f)
                body(GPath().roundRect(14f, 46f, 34f, 80f, 9f).roundRect(66f, 46f, 86f, 80f, 9f))
                flat(GPath().roundRect(20f, 52f, 28f, 74f, 4f).roundRect(72f, 52f, 80f, 74f, 4f), t.pearl)
                dot(heart(50f, 64f, 14f), t.coral)
            }
            "Musicals" -> {
                // A top hat and cane under a spotlight's sparkle.
                body(GPath().roundRect(32f, 22f, 68f, 66f, 4f), t.light, t.mid)
                flat(GPath().roundRect(32f, 54f, 68f, 62f, 1f), t.coral)
                body(GPath().roundRect(18f, 64f, 82f, 74f, 5f), t.mid, t.mid)
                stroke(GPath().moveTo(72f, 86f).lineTo(84f, 36f).quadTo(86f, 28f, 79f, 28f), t.pearl, FINE)
                flat(star(22f, 24f, 10f, 3f, 4), t.pearl)
                gleam(36f, 26f, 44f, 34f)
            }
            "LGBTQ+" -> {
                // A rainbow over a little cloud.
                stroke(GPath().arc(16f, 28f, 84f, 96f, 180f, 180f), t.coral, LINE)
                stroke(GPath().arc(23f, 35f, 77f, 89f, 180f, 180f), t.light, LINE)
                stroke(GPath().arc(30f, 42f, 70f, 82f, 180f, 180f), t.mid, LINE)
                stroke(GPath().arc(37f, 49f, 63f, 75f, 180f, 180f), t.pearl, LINE)
                body(GPath().circle(26f, 68f, 9f).circle(36f, 62f, 11f).circle(44f, 70f, 8f).roundRect(18f, 66f, 50f, 78f, 6f), t.pearl, t.light)
            }
            "’70s Classics" -> {
                // A disco ball on its chain.
                stroke(GPath().moveTo(50f, 8f).lineTo(50f, 20f), t.deep, FINE)
                body(GPath().circle(50f, 52f, 31f), t.light, t.mid)
                stroke(GPath().moveTo(19f, 52f).lineTo(81f, 52f).moveTo(23f, 37f).lineTo(77f, 37f).moveTo(23f, 67f).lineTo(77f, 67f)
                    .moveTo(50f, 21f).lineTo(50f, 83f), t.pearl, 2.2f)
                stroke(GPath().arc(34f, 21f, 66f, 83f, 90f, 180f).arc(34f, 21f, 66f, 83f, 270f, 180f), t.pearl, 2.2f)
                gleam(32f, 30f, 43f, 36f)
                flat(star(84f, 22f, 9f, 2.8f, 4), t.pearl)
            }
            "’80s Classics" -> {
                // A cassette tape.
                body(GPath().roundRect(12f, 26f, 88f, 76f, 7f))
                flat(GPath().roundRect(20f, 33f, 80f, 58f, 4f), t.pearl)
                dot(GPath().roundRect(30f, 40f, 70f, 52f, 6f), t.deep)
                dot(GPath().circle(38f, 46f, 4f).circle(62f, 46f, 4f), t.pearl)
                flat(GPath().moveTo(28f, 76f).lineTo(33f, 64f).lineTo(67f, 64f).lineTo(72f, 76f).close(), t.mid)
                dot(GPath().roundRect(20f, 35f, 80f, 38f, 1f), t.coral)
            }
            "’90s Classics" -> {
                // A game controller.
                body(GPath().moveTo(30f, 34f).lineTo(70f, 34f).quadTo(88f, 34f, 90f, 56f).quadTo(92f, 76f, 78f, 74f).quadTo(70f, 72f, 64f, 62f)
                    .lineTo(36f, 62f).quadTo(30f, 72f, 22f, 74f).quadTo(8f, 76f, 10f, 56f).quadTo(12f, 34f, 30f, 34f).close())
                dot(GPath().roundRect(22f, 45f, 38f, 50f, 2f).roundRect(27.5f, 39.5f, 32.5f, 55.5f, 2f), t.deep)
                dot(GPath().circle(68f, 43f, 3.6f), t.coral)
                dot(GPath().circle(76f, 50f, 3.6f), t.pearl)
                gleam(24f, 37f, 34f, 41f)
            }
            "2000s Classics" -> {
                // A shiny disc.
                body(GPath().circle(50f, 50f, 33f), t.pearl, t.light)
                stroke(GPath().arc(25f, 25f, 75f, 75f, 200f, 60f).arc(31f, 31f, 69f, 69f, 20f, 60f), alpha(t.mid, 0.8f), FINE)
                flat(GPath().circle(50f, 50f, 11f), t.mid)
                dot(GPath().circle(50f, 50f, 4.5f), t.deep)
                gleam(30f, 28f, 41f, 34f)
            }
            else -> {
                // Any genre without its own drawing: a soft sparkle.
                body(star(50f, 52f, 32f, 9f, 4), t.pearl, t.light)
                flat(star(77f, 25f, 11f, 3.5f, 4), t.pearl)
            }
        }
        return out
    }

    fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt().coerceIn(0, 255) shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((((c ushr 24) and 0xFF) * a).toInt().coerceIn(0, 255) shl 24)
}
