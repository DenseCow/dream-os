package debridtest

import android.util.Log
import com.firehome.launcher.Candidate
import com.firehome.launcher.Net

/** A tiny test runner: named scenarios, soft checks, a summary per service. */
object T {
    class Result(val svc: String, val name: String, val failures: List<String>, val ms: Long)
    val results = ArrayList<Result>()
    private var fails = ArrayList<String>()
    private var secrets: List<String> = emptyList()

    fun check(ok: Boolean, what: String) { if (!ok) fails.add(what) }
    fun eq(actual: Any?, expected: Any?, what: String) = check(actual == expected, "$what: expected <$expected>, got <$actual>")

    /** [secret]s (tokens, stream URLs) must never appear in a log line. */
    fun scenario(svc: String, name: String, route: (Req) -> Resp, vararg secret: String, body: () -> Unit) {
        Mock.reset(route); Log.lines.clear(); fails = ArrayList(); secrets = secret.toList()
        val t0 = now()
        try { body() } catch (t: Throwable) { fails.add("threw ${t.javaClass.simpleName}: ${t.message}") }
        settle()
        if (Mock.strays.isNotEmpty()) fails.add("requests to unexpected hosts: ${Mock.strays}")
        for (l in Log.lines) for (s in secrets + "https://") if (s.isNotEmpty() && l.contains(s)) fails.add("log leaks '${s.take(12)}…': $l")
        val r = Result(svc, name, fails, now() - t0)
        results.add(r)
        println("${if (r.failures.isEmpty()) "PASS" else "FAIL"}  [$svc] $name  (${r.ms} ms, ${Mock.log.size} requests)")
        for (f in r.failures) println("        - $f")
        if (r.failures.isNotEmpty() || System.getenv("DEBRID_TEST_VERBOSE") == "1") for (q in Mock.log) println("        > $q")
    }

    /** Waits until background cleanup (Net.run, probe threads) has finished talking to the mock. */
    fun settle(maxMs: Long = 10_000) {
        val end = now() + maxMs
        while (now() < end) {
            if (Net.pending.get() == 0 && Mock.inflight.get() == 0 && now() - Mock.lastAt > 500) return
            Thread.sleep(50)
        }
        fails.add("background work still running after $maxMs ms")
    }

    fun now() = System.nanoTime() / 1_000_000

    fun <R> timed(block: () -> R): Pair<R, Long> { val t = now(); val r = block(); return r to now() - t }

    fun summary(): Boolean {
        println()
        println("=== Summary (offline contract tests against a local mock; NOT a live test) ===")
        for ((svc, rs) in results.groupBy { it.svc }) {
            val bad = rs.count { it.failures.isNotEmpty() }
            println("  $svc: ${rs.size - bad}/${rs.size} scenarios pass${if (bad > 0) "  <-- $bad FAILING" else ""}")
        }
        val bad = results.count { it.failures.isNotEmpty() }
        println("  total: ${results.size - bad}/${results.size}")
        return bad == 0
    }
}

/** A season pack holding S02E01-E04, a sample of the wanted episode and an .nfo; the wanted file is E03 (not the largest). */
object Pack {
    const val DIR = "The.Show.S02.1080p.WEB-DL.x264-GRP"
    val files = listOf(
        "$DIR/The.Show.S02E01.1080p.WEB-DL.x264-GRP.mkv" to 2_100_000_000L,
        "$DIR/The.Show.S02E02.1080p.WEB-DL.x264-GRP.mkv" to 2_200_000_000L,
        "$DIR/The.Show.S02E03.1080p.WEB-DL.x264-GRP.mkv" to 2_000_000_000L,
        "$DIR/The.Show.S02E04.1080p.WEB-DL.x264-GRP.mkv" to 2_300_000_000L,
        "$DIR/Sample/the.show.s02e03.sample.mkv" to 52_000_000L,
        "$DIR/The.Show.S02.1080p.WEB-DL.x264-GRP.nfo" to 1_234L,
    )
    const val WANT = 2
    val wantName = files[WANT].first.substringAfterLast('/')
    /** A pack of the same season that is missing E03. */
    val withoutE03 = files.filterIndexed { i, _ -> i != WANT && i != 4 }

    fun hash(c: Char) = c.toString().repeat(40)
    fun cand(hash: String, name: String = DIR) = Candidate(hash, name, null, null, 0, "1080p", 50, 2, 3)
    fun btih(magnet: String) = Regex("btih:([0-9a-fA-F]{40})").find(magnet)?.groupValues?.get(1)?.lowercase()
}
