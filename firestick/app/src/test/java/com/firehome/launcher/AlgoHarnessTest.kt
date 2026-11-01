package com.firehome.launcher

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.Random

/**
 * The algorithm harness: the ranking, the taste profile and the daily rotation run on this PC against a synthetic
 * catalogue and synthetic viewers, with no device and no network. It compares the algorithm as it was before this
 * pass ([Legacy], a faithful copy) with the code in [Taste] / [HomePicks] on the same inputs, prints the numbers and
 * holds the invariants STANDARDS section 5 relies on.
 *
 *   gradle :app:testReleaseUnitTest --tests "*AlgoHarness*"      report: app/build/reports/algo-harness.txt
 *
 * Every title here is made up (synthetic names, synthetic numbers): the catalogue sets vote counts, dates and art to
 * exactly the cases under test. The shared-rules extraction (docs/shared-rules.md) must leave the "Fixed row" block of
 * the report byte for byte the same. One test per section, run in name order so the report reads the same every time;
 * every weight set is spelled out in full, never left to AlgoRules' defaults, so a label can't drift from what ran.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AlgoHarnessTest {
    companion object {
        private val out = StringBuilder()

        @JvmStatic @AfterClass fun writeReport() {
            File("build/reports").mkdirs()
            File("build/reports/algo-harness.txt").writeText("Dream OS algorithm harness, synthetic catalogue\n\n$out")
        }
    }

    private fun line(s: String = "") { out.append(s).append('\n'); println(s) }

    /** A weight set spelled out in full (the art penalty always on); nothing here reads AlgoRules' defaults by accident. */
    private fun w(order: Double, taste: Double, quality: Double = 0.20, fresh: Double = 0.05, rotation: Double = 0.0) =
        Taste.Weights(order = order, taste = taste, quality = quality, fresh = fresh, art = AlgoRules.ART_PENALTY, rotation = rotation)
    /** AlgoRules as checked in, and its label. */
    private val current = w(AlgoRules.W_ORDER, AlgoRules.W_TASTE, AlgoRules.W_QUALITY, AlgoRules.W_FRESH, AlgoRules.DAY_ROTATION)
    private val currentNoRotation = w(AlgoRules.W_ORDER, AlgoRules.W_TASTE, AlgoRules.W_QUALITY, AlgoRules.W_FRESH, 0.0)
    private val currentLabel = "${AlgoRules.W_ORDER}/${AlgoRules.W_TASTE}/${AlgoRules.W_QUALITY}/${AlgoRules.W_FRESH}"

    private val today = "2026-10-31"
    private val day = 2026 * 400 + 304
    private val now = 1_793_404_800_000L

    // ---- the synthetic catalogue ----

    private val genres = listOf(28, 80, 53, 35, 18, 878, 27, 10749, 16, 10751, 99, 9648)

    /**
     * [n] titles in TMDB popularity order, [sagas] of them in franchises of [sagaSize]; about 15% without a backdrop.
     * Vote counts fall with the popularity place (with noise), as TMDB's do, so a deeper page reads lower in
     * [Taste.quality], never equal to the first; ratings are independent of place.
     */
    private fun catalogue(n: Int, seed: Long, sagas: Int = 0, sagaSize: Int = 1, noArt: Double = 0.15): List<Title> {
        val r = Random(seed)
        val out = ArrayList<Title>()
        var saga = 0
        var chapter = 0
        for (i in 0 until n) {
            val tv = r.nextDouble() < 0.4
            val gs = (1..1 + r.nextInt(3)).map { genres[r.nextInt(genres.size)] }.distinct()
            val year = 2026 - (r.nextDouble().let { it * it } * 36).toInt()
            val votes = Math.exp(3 + 7.5 * (0.6 * (1 - i.toDouble() / n) + 0.4 * r.nextDouble())).toInt()
            val rating = (4.5 + r.nextDouble() * 4.3).let { Math.round(it * 10) / 10.0 }
            val lang = if (r.nextDouble() < 0.85) "en" else listOf("ko", "ja", "es", "fr")[r.nextInt(4)]
            val name = if (saga < sagas) {
                chapter++
                val s = "Saga ${'A' + saga}" + (if (chapter == 1) "" else ": Chapter $chapter")
                if (chapter >= sagaSize) { saga++; chapter = 0 }
                s
            } else "Title ${1000 + i}"
            out += Title(1000 + i, if (tv) "tv" else "movie", name, "", "/p$i", if (r.nextDouble() < noArt) null else "/b$i",
                year.toString(), rating, "$year-${(1 + r.nextInt(12)).toString().padStart(2, '0')}-${(1 + r.nextInt(28)).toString().padStart(2, '0')}",
                votes, gs, lang)
        }
        return out
    }

    // ---- synthetic viewers ----

    /** A viewer's hidden taste (genre -> how much they really like it) and what the app would have recorded of them. */
    class Viewer(val name: String, val truth: Map<Int, Double>, val finished: Int, val saved: Int = 0, val opened: Int = 0, val tvShare: Double = 0.4)

    private val viewers = listOf(
        Viewer("cold start", emptyMap(), 0),
        Viewer("one finished title", mapOf(80 to 1.0, 53 to 0.8), 1),
        Viewer("crime and thrillers, 6 finished", mapOf(80 to 1.0, 53 to 0.8, 18 to 0.4), 6, saved = 2),
        Viewer("comedy, 12 finished", mapOf(35 to 1.0, 10749 to 0.5), 12, saved = 4, opened = 10),
        Viewer("shows only, 8 finished", mapOf(18 to 1.0, 9648 to 0.7), 8, tvShare = 1.0),
        Viewer("browser only, 40 pages opened", mapOf(27 to 1.0), 0, opened = 40),
    )

    private fun truth(v: Viewer, t: Title) = if (t.genreIds.isEmpty() || v.truth.isEmpty()) 0.0 else t.genreIds.sumOf { v.truth[it] ?: 0.05 } / t.genreIds.size

    /** The viewer's history, My List and opened pages, drawn from [pool] by their hidden taste, as the app records them. */
    class Record(val history: List<WatchHistory.Entry>, val myList: List<Title>, val opened: List<Taste.Opened>, val genresOf: Map<String, Title>)

    private fun record(v: Viewer, pool: List<Title>, seed: Long): Record {
        val r = Random(seed)
        val byType = { tv: Boolean -> pool.filter { (it.type == "tv") == tv && truth(v, it) > 0.3 } }
        fun draw(tv: Boolean): Title? = byType(tv).takeIf { it.isNotEmpty() }?.let { it[r.nextInt(it.size)] }
        val used = HashSet<String>()
        fun pick(): Title? { repeat(40) { draw(r.nextDouble() < v.tvShare)?.let { if (used.add(it.key)) return it } }; return null }
        val history = (0 until v.finished).mapNotNull { k -> pick()?.let { t -> entry(t, 1.0f, now - k * 5L * 86_400_000L) } }
        val myList = (0 until v.saved).mapNotNull { pick() }
        val opened = (0 until v.opened).mapNotNull { k -> pick()?.let { Taste.Opened(it.key, it.genreIds, it.lang, it.year, now - k * 86_400_000L) } }
        return Record(history, myList, opened, pool.associateBy { it.key })
    }

    private fun entry(t: Title, progress: Float, at: Long, upNext: Boolean = false, hidden: Boolean = progress >= 0.92f, positionMs: Long = 5_400_000L) =
        WatchHistory.Entry(if (t.type == "tv") "episode" else "movie", t.id, t.name, null, 1, 1, t.year.toIntOrNull(), null, progress, positionMs,
            6_000_000L, at, "DREAM", upNext, hidden, t.posterPath, t.backdropPath, null, "", t.rating, null)

    /** The profile as the app builds it now (details filled from the catalogue instead of TMDB); [scale] only to compare confidence curves. */
    private fun profile(rec: Record, scale: Double = AlgoRules.STRENGTH_SCALE): TasteProfile {
        val seeds = Taste.seeds(rec.history, rec.myList, rec.opened, now)
        for (s in seeds) if (s.genres.isEmpty()) rec.genresOf["${s.type}:${s.id}"]?.let { s.genres = it.genreIds; s.lang = it.lang; s.year = it.year }
        return Taste.profileOf(seeds, "", now, scale)
    }

    /** Lift and tau over [runs] rows for [v], with its profile built at [scale] and ranked with [wt]: shared by t2 and t2b so their rows compare. */
    private fun warm(v: Viewer, wt: Taste.Weights, scale: Double, runs: Int): DoubleArray {
        val a = DoubleArray(5)
        for (s in 1..runs) {
            val pool = catalogue(60, 100L + s)
            val rec = record(v, catalogue(400, 500L + s), 900L + s)
            val p = profile(rec, scale)
            val ranked = Taste.rank(p, pool, artFirst = true, today = today, day = day, w = wt)
            a[0] += meanTruth(v, ranked) - meanTruth(v, pool); a[1] += meanQuality(ranked); a[2] += obscure(ranked).toDouble(); a[3] += kendall(pool, ranked.take(20)); a[4] += p.strength
        }
        for (i in a.indices) a[i] = a[i] / runs.toDouble()
        return a
    }

    // ---- the algorithm before this pass, copied as it was ----

    object Legacy {
        fun strength(total: Double) = minOf(1.0, total / 6)

        /** build()'s weights: finished 1.0, Up Next 0.9, 10 minutes in 0.6, a short play 0.25; My List 0.5; opened 0.15. */
        fun profile(history: List<WatchHistory.Entry>, myList: List<Title>, opened: List<Taste.Opened>, genresOf: Map<String, Title>, now: Long): TasteProfile {
            class S(val type: String, val genres: List<Int>, val lang: String, val year: String, val w: Double)
            val seeds = ArrayList<S>()
            val seen = HashSet<String>()
            for (e in history.sortedByDescending { it.updatedAt }) {
                if (!seen.add(e.key)) continue
                if (e.hidden && e.progress < 0.92f && !e.upNext) continue
                val w = when {
                    e.progress >= 0.92f || (e.hidden && e.progress >= 0.5f) -> 1.0
                    e.upNext -> 0.9
                    e.positionMs >= 600_000L -> 0.6
                    else -> 0.25
                }
                val t = genresOf[e.key]
                seeds += S(if (e.ofShow) "tv" else "movie", t?.genreIds.orEmpty(), t?.lang.orEmpty(), t?.year.orEmpty(), w * Math.pow(0.5, ((now - e.updatedAt) / 86_400_000.0) / 45))
            }
            for (t in myList) if (seen.add(t.key)) seeds += S(t.type, t.genreIds, t.lang, t.year, 0.5)
            for (o in opened) if (seen.add(o.key)) seeds += S(o.key.substringBefore(':'), o.genres, o.lang, o.year, 0.15 * Math.pow(0.5, ((now - o.at) / 86_400_000.0) / 14))
            val genre = HashMap<Int, Double>(); val lang = HashMap<String, Double>(); val era = HashMap<Int, Double>()
            var total = 0.0; var shows = 0.0
            for (s in seeds) {
                total += s.w; if (s.type == "tv") shows += s.w
                for (g in s.genres) genre[g] = (genre[g] ?: 0.0) + s.w
                if (s.lang.isNotEmpty()) lang[s.lang] = (lang[s.lang] ?: 0.0) + s.w
                s.year.toIntOrNull()?.let { era[it / 10 * 10] = (era[it / 10 * 10] ?: 0.0) + s.w }
            }
            fun <K> norm(m: Map<K, Double>): Map<K, Double> { val mx = m.values.maxOrNull() ?: 0.0; return if (mx > 0) m.mapValues { it.value / mx } else emptyMap() }
            return TasteProfile(norm(genre), norm(lang), norm(era), if (total > 0) shows / total else 0.5, strength(total), "", now)
        }

        /** tidy's last line: every title with a backdrop ahead of every one without, before the rank saw the list. */
        fun artFirst(list: List<Title>) = list.filter { it.backdropPath != null } + list.filter { it.backdropPath == null }

        fun rank(p: TasteProfile, list: List<Title>, today: String): List<Title> {
            if (list.size <= 2) return list
            val n = maxOf(1, list.size - 1).toDouble()
            val y = today.take(4).toInt()
            fun fresh(t: Title) = if (t.date.length != 10) 0.0 else if (t.date >= "${y - 1}${today.substring(4)}") 1.0 else if (t.date >= "${y - 3}${today.substring(4)}") 0.6 else if (t.date >= "${y - 10}${today.substring(4)}") 0.3 else 0.0
            val scored = list.mapIndexed { i, t -> Triple(t, 0.50 * (1 - i / n) + 0.25 * p.strength * p.affinity(t) + 0.20 * Taste.quality(t) + 0.05 * fresh(t), i) }
                .sortedWith(compareByDescending<Triple<Title, Double, Int>> { it.second }.thenBy { it.third }).map { it.first }
            return spread(scored)
        }

        fun spread(list: List<Title>): List<Title> {
            val seen = HashSet<String>(); val kept = ArrayList<Title>(); val later = ArrayList<Title>()
            for (t in list) if (seen.add(Taste.stem(t.name))) kept += t else later += t
            return kept + later
        }

        /** The hero's score before: no lean to the profile's own type, no rotation. */
        fun hero(i: Int, t: Title, m: HomePicks.Moment, taste: TasteProfile) =
            (1.0 - i / 40.0) + (if (t.genreIds.any { it in m.genres }) 1.0 else 0.0) + (if (t.type == "movie") 0.6 * m.movies else 0.0) +
                0.8 * taste.strength * taste.affinity(t) + 0.4 * Taste.quality(t)

        fun signature(history: List<WatchHistory.Entry>, myList: List<Title>, opened: List<Taste.Opened>) =
            "${history.size}|${(history.maxOfOrNull { it.updatedAt } ?: 0L) / 1000}|${myList.size}|${(opened.firstOrNull()?.at ?: 0L) / 1000}"
    }

    // ---- metrics ----

    private fun kendall(order: List<Title>, ranked: List<Title>): Double {
        val pos = order.withIndex().associate { it.value.key to it.index }
        val r = ranked.map { pos[it.key]!! }
        var c = 0; var d = 0
        for (i in r.indices) for (j in i + 1 until r.size) if (r[i] < r[j]) c++ else d++
        return if (c + d == 0) 1.0 else (c - d).toDouble() / (c + d)
    }

    private fun f(x: Double) = String.format(java.util.Locale.US, "%.3f", x)
    private fun f2(x: Double) = String.format(java.util.Locale.US, "%.2f", x)
    private fun first6(l: List<Title>) = l.take(6)
    private fun meanTruth(v: Viewer, l: List<Title>) = first6(l).map { truth(v, it) }.average()
    private fun meanQuality(l: List<Title>) = first6(l).map { Taste.quality(it) }.average()
    private fun obscure(l: List<Title>) = first6(l).count { it.votes < 300 }
    private fun noArt(l: List<Title>) = first6(l).count { it.backdropPath == null }

    // ---- the sections, in report order ----

    /** Finding 2: art-first ran before the rank, so half the score measured whether a title had a backdrop. */
    @Test
    fun t1_artPartition() {
        line("== Art partition before the rank (cold profile, 60-title rows, 15% without a backdrop; rotation 0 throughout) ==")
        line("Columns: Kendall tau of the first 20 against TMDB's order; art-less titles in the first six over 20 rows; TMDB's own top-six art-less titles: mean landing place and how many past place 12")
        val variants = listOf(
            "before: partition, then 50/25/20/5" to null,
            "after: 50/25/20/5 with the art penalty" to w(0.50, 0.25),
            "after: $currentLabel with the art penalty" to currentNoRotation,
        )
        val runs = 20
        for ((name, wt) in variants) {
            var tau = 0.0; var art = 0; var top6 = 0; var past = 0; var land = 0.0
            for (s in 1..runs) {
                val pool = catalogue(60, s.toLong())
                val ranked = if (wt == null) Legacy.rank(TasteProfile.COLD, Legacy.artFirst(pool), today) else Taste.rank(TasteProfile.COLD, pool, artFirst = true, today = today, day = day, w = wt)
                tau += kendall(pool, ranked.take(20)); art += noArt(ranked)
                for (t in pool.take(6).filter { it.backdropPath == null }) { top6++; land += ranked.indexOf(t) + 1; if (ranked.indexOf(t) >= 12) past++ }
            }
            line("   ${name.padEnd(44)} tau ${f(tau / runs)}  art-less in first six $art  top-six art-less ($top6): land ${f2(land / top6)}, past 12: $past")
        }
        line()
    }

    /** Weight sets, each spelled out: the old 50/25/20/5 (old strength), the same under the new strength, 45/30, the review's 30/30/20/10, 40/35. */
    @Test
    fun t2_tasteLift() {
        line("== Taste lift by weight set (first six cards; truth = the viewer's hidden taste, 0..1; every set has the art penalty unless marked legacy) ==")
        line("Columns: truth lift over TMDB order, mean steadied quality, obscure titles (<300 votes), tau vs TMDB order")
        line("Shape: one 60-title popularity list per run, one fixed day, 48 runs per viewer with a fresh synthetic history each run. Lift here is not")
        line("comparable with the day-over-day blocks below (different pools, 80-title two-sort rows, one history, seven days); compare rows within a block only.")
        val sets = listOf(
            "legacy 50/25/20/5, old strength" to null,
            "50/25/20/5, rotation 0" to w(0.50, 0.25),
            "50/25/20/5, rotation 0.06" to w(0.50, 0.25, rotation = 0.06),
            "45/30/20/5, rotation 0" to w(0.45, 0.30),
            "45/30/20/5, rotation 0.06" to w(0.45, 0.30, rotation = 0.06),
            "review 30/30/20/10, rotation 0" to w(0.30, 0.30, 0.20, 0.10),
            "40/35/20/5, rotation 0" to w(0.40, 0.35),
            "current AlgoRules ($currentLabel, rotation ${AlgoRules.DAY_ROTATION})" to current,
        )
        // Every metric of every set over every viewer: two sets with different weights must never read the same (the
        // guard for a label that drifts from what ran).
        val fingerprint = sets.associate { it.first to StringBuilder() }
        for (v in viewers) {
            var base = 0.0; var sOld = 0.0; var sNew = 0.0
            val acc = sets.associate { it.first to DoubleArray(4) }
            val runs = 48
            for (s in 1..runs) {
                val pool = catalogue(60, 100L + s)
                val rec = record(v, catalogue(400, 500L + s), 900L + s)
                val pNew = profile(rec)
                val pOld = Legacy.profile(rec.history, rec.myList, rec.opened, rec.genresOf, now)
                sOld += pOld.strength; sNew += pNew.strength
                base += meanTruth(v, pool)
                for ((name, w) in sets) {
                    val ranked = if (w == null) Legacy.rank(pOld, Legacy.artFirst(pool), today) else Taste.rank(pNew, pool, artFirst = true, today = today, day = day, w = w)
                    val a = acc.getValue(name)
                    a[0] += meanTruth(v, ranked); a[1] += meanQuality(ranked); a[2] += obscure(ranked).toDouble(); a[3] += kendall(pool, ranked.take(20))
                }
            }
            line("-- ${v.name}: strength before ${f2(sOld / runs)}, after ${f2(sNew / runs)}; TMDB order alone: truth ${f(base / runs)}")
            for ((name, _) in sets) {
                val a = acc.getValue(name)
                line("   ${name.padEnd(48)} lift ${f(a[0] / runs - base / runs)}  quality ${f(a[1] / runs)}  obscure ${f2(a[2] / runs)}  tau ${f(a[3] / runs)}")
                fingerprint.getValue(name).append(a.joinToString(",")).append(';')
            }
        }
        fun same(a: Taste.Weights?, b: Taste.Weights?) = a != null && b != null && a.order == b.order && a.taste == b.taste && a.quality == b.quality &&
            a.fresh == b.fresh && a.art == b.art && a.rotation == b.rotation && a.gap == b.gap
        for (i in sets.indices) for (j in i + 1 until sets.size) if (!same(sets[i].second, sets[j].second))
            assertTrue("'${sets[i].first}' and '${sets[j].first}' differ in weights but read the same", fingerprint.getValue(sets[i].first).toString() != fingerprint.getValue(sets[j].first).toString())
        line("Guard: every pair of weight sets that differ produced different metrics.")
        line()
    }

    /** The confidence curve's scale: the checked-in 4.0 against 3.0, same rows and histories as t2 (48 runs), 45/30 at rotation 0 and 0.06. */
    @Test
    fun t2b_strengthScale() {
        line("== Confidence scale: STRENGTH_SCALE ${AlgoRules.STRENGTH_SCALE} (checked in) against 3.0, the t2 rows (48 runs per viewer), 45/30/20/5 ==")
        line("weight   scale 4.0   scale 3.0   (legacy min(1, w/6))")
        for (wt in listOf(1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0)) line("  ${f2(wt).padStart(5)}     ${f2(Taste.strength(wt, 4.0))}        ${f2(Taste.strength(wt, 3.0))}        ${f2(Legacy.strength(wt))}")
        line("Columns: strength, lift, quality, obscure, tau; legacy line = 50/25 with the old strength on the same rows")
        for (v in viewers.drop(1)) {
            val legacy = DoubleArray(4)
            for (s in 1..48) {
                val pool = catalogue(60, 100L + s); val rec = record(v, catalogue(400, 500L + s), 900L + s)
                val ranked = Legacy.rank(Legacy.profile(rec.history, rec.myList, rec.opened, rec.genresOf, now), Legacy.artFirst(pool), today)
                legacy[0] += meanTruth(v, ranked) - meanTruth(v, pool); legacy[1] += meanQuality(ranked); legacy[2] += obscure(ranked).toDouble(); legacy[3] += kendall(pool, ranked.take(20))
            }
            line("-- ${v.name}")
            line("   ${"legacy".padEnd(26)} strength (own)  lift ${f(legacy[0] / 48)}  quality ${f(legacy[1] / 48)}  obscure ${f2(legacy[2] / 48)}  tau ${f(legacy[3] / 48)}")
            for (scale in listOf(4.0, 3.0)) for (rot in listOf(0.0, AlgoRules.DAY_ROTATION)) {
                val a = warm(v, w(0.45, 0.30, rotation = rot), scale, 48)
                line("   ${"scale $scale, rotation $rot".padEnd(26)} strength ${f2(a[4])}  lift ${f(a[0])}  quality ${f(a[1])}  obscure ${f2(a[2])}  tau ${f(a[3])}")
            }
        }
        line()
    }

    /** Finding 9: the linear clamp against the curve, and what browsing alone used to buy. */
    @Test
    fun t3_strengthCurve() {
        line("== Confidence (strength) by accumulated signal weight ==")
        line("weight  legacy min(1, w/6)  new 1-exp(-w/${AlgoRules.STRENGTH_SCALE})")
        for (w in listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 25.0)) line("  ${f2(w).padStart(5)}      ${f2(Legacy.strength(w))}               ${f2(Taste.strength(w))}")
        val browser = viewers.first { it.name.startsWith("browser") }
        val rec = record(browser, catalogue(400, 7), 8)
        val old = Legacy.profile(rec.history, rec.myList, rec.opened, rec.genresOf, now)
        val new = profile(rec)
        line("A viewer who opened 40 pages and finished nothing: strength before ${f2(old.strength)}, after ${f2(new.strength)} (opened pages count toward strength only up to ${AlgoRules.OPENED_STRENGTH_CAP})")
        val one = record(viewers[1], catalogue(400, 9), 10)
        line("One finished title: before ${f2(Legacy.profile(one.history, one.myList, one.opened, one.genresOf, now).strength)}, after ${f2(profile(one).strength)}; for-you rows need ${AlgoRules.FOR_YOU_MIN_STRENGTH}")
        assertEquals(0.0, Taste.strength(0.0), 0.0)
        assertEquals(0.0, profile(record(viewers[0], catalogue(400, 11), 12)).strength, 0.0)
        // The written rule: a title only started seeds nothing, in any amount of watching.
        val pool = catalogue(50, 13)
        val started = listOf(entry(pool[0], 0.5f, now, hidden = false, positionMs = 3_600_000L), entry(pool[1], 0.1f, now, hidden = false, positionMs = 60_000L),
            entry(pool[2], 0.7f, now, hidden = true, positionMs = 3_600_000L))
        assertTrue("started-but-unfinished titles seed nothing", Taste.seeds(started, emptyList(), emptyList(), now).isEmpty())
        assertEquals(1, Taste.seeds(listOf(entry(pool[3], 0.95f, now)), emptyList(), emptyList(), now).size)
        assertEquals(1, Taste.seeds(listOf(entry(pool[4], 0.0f, now, upNext = true, hidden = false)), emptyList(), emptyList(), now).size)
        // A show cleared from Continue Watching: nothing while it could still be a sample, kept once the viewer has
        // finished enough episodes of it that clearing means "done with it for now".
        val cleared = listOf(entry(pool.first { it.type == "tv" }, 0.7f, now, hidden = true))
        assertTrue("a show cleared after a sample seeds nothing", Taste.seeds(cleared, emptyList(), emptyList(), now) { 1 }.isEmpty())
        assertEquals(1, Taste.seeds(cleared, emptyList(), emptyList(), now) { AlgoRules.KEPT_AFTER_EPISODES }.size)
        line("Rule check: 3 started-but-unfinished entries (50% in, 10% in, removed at 70%) seed 0; a finished movie seeds 1; an Up Next seeds 1.")
        line("Rule check: a show cleared from Continue Watching seeds 0 after 1 finished episode, 1 after ${AlgoRules.KEPT_AFTER_EPISODES}.")
        line()
    }

    /** Finding 6: sequels adjacent at the tail. */
    @Test
    fun t4_spread() {
        line("== Franchise spread (a 24-title row holding eight entries of one saga and four of another) ==")
        val pool = catalogue(24, 21, sagas = 2, sagaSize = 8).let { it.take(12) + it.drop(12).shuffled(Random(3)) }
        val before = Legacy.spread(pool)
        val after = Taste.spread(pool)
        fun adjacent(l: List<Title>) = l.zipWithNext().count { (a, b) -> Taste.stem(a.name) == Taste.stem(b.name) }
        fun minGap(l: List<Title>): Int { var m = Int.MAX_VALUE; val last = HashMap<String, Int>(); l.forEachIndexed { i, t -> last[Taste.stem(t.name)]?.let { m = minOf(m, i - it) }; last[Taste.stem(t.name)] = i }; return m }
        line("Adjacent same-franchise pairs: before ${adjacent(before)}, after ${adjacent(after)}; smallest gap between two of one saga: before ${minGap(before)}, after ${minGap(after)}")
        line("Positions of the eight-entry saga: before ${before.withIndex().filter { it.value.name.startsWith("Saga A") }.map { it.index + 1 }}, after ${after.withIndex().filter { it.value.name.startsWith("Saga A") }.map { it.index + 1 }}")
        assertEquals(pool.map { it.key }.toSet(), after.map { it.key }.toSet())
        assertEquals(pool.size, after.size)
        val plain = catalogue(20, 22)
        assertEquals("no shared stem: order kept", plain, Taste.spread(plain))
        for ((a, b) in listOf("The Saga: Chapter 4" to "The Saga", "The Saga and the Long Subtitle" to "The Saga", "Ocean Thirteen" to "Ocean", "Hero Returns" to "Hero",
            "Saga Part Two" to "Saga", "Two Words and the Subtitle" to "Two Words", "Two Words: Part II" to "Two Words"))
            assertEquals(a, Taste.stem(b), Taste.stem(a))
        assertTrue(Taste.stem("Other Saga") != Taste.stem("The Saga"))
        line("Stem checks: chapter, subtitle, number word, sequel word and 'part two' all fold to the franchise name.")
        line()
    }

    /** The owner's complaint: Home looks the same every day. Twelve variety-style rows over a week, then rows with no daily page. */
    @Test
    fun t5_dayOverDay() {
        line("== Day-over-day change on a simulated Home (12 variety rows: 20 popular + a daily page of 80 favorites, interleaved, ranked, 20 shown) ==")
        line("Columns: first-six cards new today (of 6), whole row new today (of 20), lead card changed, mean quality of the first six, truth lift")
        val v = viewers[2]
        val rec = record(v, catalogue(400, 31), 32)
        val pNew = profile(rec); val pOld = Legacy.profile(rec.history, rec.myList, rec.opened, rec.genresOf, now)
        class Row(val name: String, val popular: List<Title>, val favorites: List<Title>)
        val rows = (1..12).map { i -> val c = catalogue(100, 40L + i); Row("Row $i", c.take(20), c.drop(20)) }
        fun pool(r: Row, d: Int): List<Title> { val pg = RowMix.dailyPage(r.name, 4, d); return RowMix.interleave(listOf(r.popular, r.favorites.subList((pg - 1) * 20, pg * 20))) }
        line("Every ranked variant below uses $currentLabel with the art penalty; only the rotation weight changes.")
        fun rot(r: Double) = w(AlgoRules.W_ORDER, AlgoRules.W_TASTE, AlgoRules.W_QUALITY, AlgoRules.W_FRESH, r)
        val variants = listOf(
            "before (no rotation, art partition)" to null,
            "rotation 0.00" to rot(0.0),
            "rotation 0.02" to rot(0.02),
            "rotation 0.04" to rot(0.04),
            "rotation 0.06" to rot(0.06),
            "rotation 0.08" to rot(0.08),
            "rotation 0.12" to rot(0.12),
            "current AlgoRules (rotation ${AlgoRules.DAY_ROTATION})" to current,
        )
        for ((name, w) in variants) {
            var new6 = 0.0; var new20 = 0.0; var lead = 0.0; var q = 0.0; var lift = 0.0; var n = 0
            for (d in day until day + 7) for (r in rows) {
                fun rank(dd: Int) = (if (w == null) Legacy.rank(pOld, Legacy.artFirst(pool(r, dd)), today) else Taste.rank(pNew, pool(r, dd), artFirst = true, today = today, day = dd, w = w)).take(20)
                val a = rank(d); val b = rank(d + 1)
                new6 += 6 - first6(b).count { t -> first6(a).any { it.key == t.key } }
                new20 += 20 - b.count { t -> a.any { it.key == t.key } }
                if (a[0].key != b[0].key) lead += 1
                q += meanQuality(b); lift += meanTruth(v, b) - meanTruth(v, pool(r, d + 1)); n++
                if (w != null) assertEquals("same day, same row", rank(d), rank(d))
            }
            line("   ${name.padEnd(36)} first six ${f2(new6 / n)}  row ${f2(new20 / n)}  lead ${f2(lead / n)}  quality ${f(q / n)}  lift ${f(lift / n)}")
        }
        line()
        line("== Day-over-day change on rows with no daily page (a service row: 20 shows + 20 movies interleaved, ranked, 16 shown) ==")
        val fixed = (1..12).map { i -> val c = catalogue(40, 60L + i); RowMix.interleave(listOf(c.filter { it.type == "tv" }, c.filter { it.type == "movie" })) }
        for ((name, w) in variants) {
            var new6 = 0.0; var new16 = 0.0; var lead = 0.0; var q = 0.0; var lift = 0.0; var n = 0
            for (d in day until day + 7) for (pool in fixed) {
                fun rank(dd: Int) = (if (w == null) Legacy.rank(pOld, Legacy.artFirst(pool), today) else Taste.rank(pNew, pool, artFirst = true, today = today, day = dd, w = w)).take(16)
                val a = rank(d); val b = rank(d + 1)
                new6 += 6 - first6(b).count { t -> first6(a).any { it.key == t.key } }
                new16 += 16 - b.count { t -> a.any { it.key == t.key } }
                if (a[0].key != b[0].key) lead += 1
                q += meanQuality(b); lift += meanTruth(v, b) - meanTruth(v, pool); n++
            }
            line("   ${name.padEnd(36)} first six ${f2(new6 / n)}  row ${f2(new16 / n)}  lead ${f2(lead / n)}  quality ${f(q / n)}  lift ${f(lift / n)}")
        }
        line()
        line("== The same service rows with the day's deeper page behind the first (RowMix.rotatingPage: page 1 then one of pages 2-4 per type, types interleaved, 16 shown) ==")
        line("'interleaved' is the rejected shape (pages of one sort alternating): a deeper title's order score then reads as page 1, so the rank lies about TMDB's order.")
        val deep = (1..12).map { i -> catalogue(300, 60L + i) }
        /** The pool as the row builds it, and each title's real page. */
        fun deepPool(c: List<Title>, d: Int, interleave: Boolean): Pair<List<Title>, Map<String, Int>> {
            val pg = RowMix.rotatingPage("Row ${c[0].date}", d)
            fun page(tv: Boolean, p: Int) = c.filter { (it.type == "tv") == tv }.subList((p - 1) * 20, p * 20)
            val pages = HashMap<String, Int>()
            for (tv in listOf(true, false)) { page(tv, 1).forEach { pages[it.key] = 1 }; page(tv, pg).forEach { pages[it.key] = pg } }
            val pool = if (interleave) RowMix.interleave(listOf(page(true, 1), page(false, 1), page(true, pg), page(false, pg)))
            else RowMix.interleave(listOf(page(true, 1) + page(true, pg), page(false, 1) + page(false, pg)))
            return pool to pages
        }
        /** Rank honesty: within one sort (one type), a title from a later page never sits ahead of one from an earlier page. */
        fun honest(pool: List<Title>, pages: Map<String, Int>): Boolean = listOf("tv", "movie").all { type ->
            pool.filter { it.type == type }.map { pages.getValue(it.key) }.zipWithNext().all { (a, b) -> a <= b }
        }
        for (inter in listOf(false, true)) for ((name, w) in listOf("rotation 0.00" to currentNoRotation, "rotation ${AlgoRules.DAY_ROTATION}" to current)) {
            var new6 = 0.0; var new16 = 0.0; var lead = 0.0; var q = 0.0; var lift = 0.0; var n = 0; var deeperInSix = 0.0
            for (d in day until day + 7) for (c in deep) {
                val (poolA, pagesA) = deepPool(c, d, inter); val (poolB, pagesB) = deepPool(c, d + 1, inter)
                if (inter) assertFalse("the guard must catch the interleaved shape", honest(poolA, pagesA)) else assertTrue("rank honesty: page order kept within a sort", honest(poolA, pagesA))
                fun rank(p: List<Title>, dd: Int) = Taste.rank(pNew, p, artFirst = true, today = today, day = dd, w = w).take(16)
                val a = rank(poolA, d); val b = rank(poolB, d + 1)
                new6 += 6 - first6(b).count { t -> first6(a).any { it.key == t.key } }
                new16 += 16 - b.count { t -> a.any { it.key == t.key } }
                if (a[0].key != b[0].key) lead += 1
                deeperInSix += first6(b).count { pagesB.getValue(it.key) > 1 }
                q += meanQuality(b); lift += meanTruth(v, b) - meanTruth(v, poolB); n++
            }
            line("   ${(if (inter) "interleaved (rejected), " else "appended, ").plus(name).padEnd(40)} first six ${f2(new6 / n)}  row ${f2(new16 / n)}  lead ${f2(lead / n)}  quality ${f(q / n)}  lift ${f(lift / n)}  deeper-page titles in the first six ${f2(deeperInSix / n)}")
        }
        line("Rank honesty held for every appended pool; the guard rejected every interleaved pool.")
        line()
    }

    /** Finding 5: a one-in-one-out My List swap left the signature unchanged. */
    @Test
    fun t6_signature() {
        line("== Taste signature ==")
        val pool = catalogue(30, 51)
        val history = listOf(entry(pool[0], 1.0f, now))
        val a = listOf(pool[1], pool[2]); val b = listOf(pool[1], pool[3])
        val same = Legacy.signature(history, a, emptyList()) == Legacy.signature(history, b, emptyList())
        val differs = Taste.signature(history, a, emptyList()) != Taste.signature(history, b, emptyList())
        line("One saved title swapped for another: before ${if (same) "no rebuild (same signature)" else "rebuild"}, after ${if (differs) "rebuild (signature changed)" else "no rebuild"}")
        assertTrue(same); assertTrue(differs)
        assertEquals(Taste.signature(history, a, emptyList()), Taste.signature(history, a, emptyList()))
        line()
    }

    /** Finding 4: TasteProfile.shows read by the hero. */
    @Test
    fun t7_hero() {
        line("== Hero (evening slot, 40 trending titles) for a shows-only viewer ==")
        val v = viewers.first { it.name.startsWith("shows only") }
        val rec = record(v, catalogue(400, 61), 62)
        val pNew = profile(rec); val pOld = Legacy.profile(rec.history, rec.myList, rec.opened, rec.genresOf, now)
        val m = HomePicks.Moment("Tonight", setOf(28, 18, 53, 80, 10759, 9648), 1.0)
        var showsBefore = 0; var showsAfter = 0; var leadChanged = 0
        for (s in 1..20) {
            val pool = catalogue(40, 70L + s, noArt = 0.0)
            val before = pool.withIndex().sortedByDescending { (i, t) -> Legacy.hero(i, t, m, pOld) }.map { it.value }.take(6)
            val after = pool.withIndex().sortedByDescending { (i, t) -> HomePicks.heroScore(i, t, m, pNew, day) }.map { it.value }.take(6)
            showsBefore += before.count { it.type == "tv" }; showsAfter += after.count { it.type == "tv" }
            val tomorrow = pool.withIndex().sortedByDescending { (i, t) -> HomePicks.heroScore(i, t, m, pNew, day + 1) }.map { it.value }
            if (tomorrow[0].key != after[0].key) leadChanged++
        }
        line("Shows among the six hero titles (20 runs, 120 slots): before $showsBefore, after $showsAfter (profile shows share ${f2(pNew.shows)}, strength ${f2(pNew.strength)})")
        line("Hero lead title different tomorrow: $leadChanged of 20 runs")
        // The clock against a known taste: a drama viewer in the morning slot (comedy, family, animation, adventure, kids).
        val morning = HomePicks.Moment("This Morning", setOf(35, 10751, 16, 12, 10762), 0.0)
        var dramaBefore = 0; var dramaAfter = 0; var clockBefore = 0; var clockAfter = 0
        for (s in 1..20) {
            val pool = catalogue(40, 90L + s, noArt = 0.0)
            val before = pool.withIndex().sortedByDescending { (i, t) -> Legacy.hero(i, t, morning, pOld) }.map { it.value }.take(6)
            val after = HomePicks.heroPick(pool, emptySet(), morning, pNew, day, 6)
            dramaBefore += before.count { 18 in it.genreIds }; dramaAfter += after.count { 18 in it.genreIds }
            clockBefore += before.count { it.genreIds.any { g -> g in morning.genres } }; clockAfter += after.count { it.genreIds.any { g -> g in morning.genres } }
        }
        line("Drama viewer in the morning slot, 120 hero slots: drama titles before $dramaBefore, after $dramaAfter; morning-genre titles before $clockBefore, after $clockAfter")
        // Nothing the viewer has watched: the chart's top title, once in their history, never leads the hero.
        val chart = catalogue(40, 95, noArt = 0.0)
        val open = HomePicks.heroPick(chart, emptySet(), m, TasteProfile.COLD, day, 6)
        val skipped = HomePicks.heroPick(chart, setOf(open[0].key), m, TasteProfile.COLD, day, 6)
        assertTrue(open[0].key !in skipped.map { it.key })
        assertEquals(6, skipped.size)
        line("Watched titles: the cold hero's lead ${open[0].key} is skipped once in the watch history (the six are filled from the rest of the chart).")
        line()
    }

    /** Finding 1: a fetch error is not the end of a row. */
    @Test
    fun t8_paging() {
        line("== Paging ==")
        val boom = RowPaging.next("Row", 2, emptySet()) { throw java.io.IOException("TMDB blip") }
        val empty = RowPaging.next("Row", 2, emptySet()) { emptyList() }
        assertTrue(boom.failed); assertTrue(boom.titles.isEmpty())
        assertFalse(empty.failed); assertTrue(empty.titles.isEmpty())
        line("A throwing loader: failed=true, titles 0 (the row stays pageable). An empty page: failed=false (the row is done).")
        line()
    }

    /** A fixed ranked row, for the shared-rules extraction: this block must not change when the values move out. */
    @Test
    fun t9_fixedRow() {
        line("== Fixed row (crime viewer, catalogue seed 101, day $day, today $today, AlgoRules as checked in) ==")
        val v = viewers[2]
        val rec = record(v, catalogue(400, 501), 901)
        val p = profile(rec)
        val ranked = Taste.rank(p, catalogue(60, 101), artFirst = true, today = today, day = day)
        line("strength ${f(p.strength)} shows ${f(p.shows)} genres ${p.genre.entries.sortedBy { it.key }.joinToString { "${it.key}=${f2(it.value)}" }}")
        line(ranked.take(20).joinToString(" ") { it.key })
        assertEquals(60, ranked.size)
        assertEquals(60, ranked.distinctBy { it.key }.size)
        // Two titles rank in a well-defined order (finding 8).
        val two = catalogue(2, 102)
        assertEquals(2, Taste.rank(TasteProfile.COLD, two, today = today, day = day).size)
        line()
    }
}
