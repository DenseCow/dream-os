package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's "Now Playing stuck until the app restarts": a lookup that hangs (or is cancelled) must never hold a
 * Play past its budget, and a Play or Try Again after it must start a fresh lookup instead of joining the stuck one.
 */
class StuckLookupTest {
    /** No IMDb id: the hook is asked, and that is where these lookups hang. */
    private val movie = PlayTarget(MediaKind.MOVIE, 603, "The Matrix", 1999)

    private fun hash(c: Char) = c.toString().repeat(40)

    /** The IMDb hook hangs on its first call (ignoring cancellation, like a blocking network call); later calls answer. */
    private class HangingHooks(val cancellable: Boolean = false) : PlaybackHooks {
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        override suspend fun imdb(target: PlayTarget): String? {
            if (calls.incrementAndGet() == 1) {
                if (cancellable) release.await() else withContext(NonCancellable) { release.await() }
            }
            return "tt0133093"
        }
        override fun audioLang() = "en"
        override fun viewerSourceSettings() = emptyMap<String, String>()
    }

    private val engine get() = FakeEngine { _, _ -> listOf(ScrapedSource(hash('a'), "The.Matrix.1999.1080p.BluRay.x264-GRP", "1080p", 2L shl 30, 50, "m:torrentio")) }

    private fun premiumize() = FakeHttp()
        .on("cache/check") { s ->
            val hs = Regex("items%5B%5D=([a-z0-9]+)").findAll(s.body).map { it.groupValues[1] }.toList()
            HttpReply(200, """{"status":"success","response":[${hs.joinToString(",") { "true" }}]}""")
        }
        .on("transfer/directdl") { s ->
            val h = Regex("btih%3A([a-z0-9]+)").find(s.body)!!.groupValues[1]
            HttpReply(200, """{"status":"success","content":[{"path":"Movie/$h.mkv","size":2000,"link":"https://pm.example/$h.mkv"}]}""")
        }

    private fun rig(hooks: PlaybackHooks, clock: Clock = Clock.SYSTEM, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)): Playback =
        Playback.create(engine, hooks, MemoryStore(), MemorySecrets().apply { put("premiumizetoken", "tok") }, object : ScreenFacts {},
            object : ReleaseInfo {
                override fun today(): LocalDate = LocalDate.of(2026, 10, 30)
                override fun dateWords(date: String) = date
                override fun airWords(at: Long) = "$at"
                override fun cardDate(at: Long) = "Nov 1" to "Nov 1"
            }, http = premiumize(), clock = clock, scope = scope)

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    @Test fun aPlayJoiningAHungLookAheadEndsWithinItsBudget() = runBlocking<Unit> {
        val hooks = HangingHooks()
        val p = rig(hooks)
        val ahead = async(Dispatchers.IO) { p.core.find(movie, 30_000, DebridPurpose.PAGE) }
        delay(150)
        val (found, ms) = timed { p.core.find(movie, 1_500, DebridPurpose.PLAY) }
        assertNull(found)
        assertTrue(ms < 2_500, "the Play waited $ms ms on a 1.5 s budget")
        hooks.release.complete(Unit)
        ahead.cancel()
    }

    @Test fun aCancelledLookAheadNeverHoldsAPlay() = runBlocking<Unit> {
        // Cancelled while hung: the Play that joined it hears at once and looks again (here the scope is gone, so: nothing).
        val hooks = HangingHooks(cancellable = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val p = rig(hooks, scope = scope)
        val ahead = async(Dispatchers.IO) { p.core.find(movie, 30_000, DebridPurpose.PAGE) }
        delay(150)
        val play = async(Dispatchers.IO) { timed { p.core.find(movie, 6_000, DebridPurpose.PLAY) } }
        delay(150)
        scope.cancel()
        val (found, ms) = play.await()
        assertNull(found)
        assertTrue(ms < 2_000, "the Play waited $ms ms on a cancelled lookup")
        assertNull(ahead.await())
        // A lookup cancelled before it even ran (its scope gone) releases its caller at once, not after the budget.
        val (late, lateMs) = timed { p.core.find(movie, 4_000, DebridPurpose.PLAY) }
        assertNull(late)
        assertTrue(lateMs < 1_500, "a lookup that never ran held its caller $lateMs ms")
    }

    @Test fun tryAgainAfterAHungLookupStartsAFreshOne() = runBlocking<Unit> {
        val hooks = HangingHooks()
        val p = rig(hooks)
        val ahead = async(Dispatchers.IO) { p.core.find(movie, 30_000, DebridPurpose.PAGE) }
        delay(150)
        // The first Play joins the hung look-ahead and runs out of budget.
        assertNull(p.core.find(movie, 1_000, DebridPurpose.PLAY))
        // Try Again: the stuck lookup is dropped, the fresh one finds the stream.
        p.abandon(movie)
        val (found, ms) = timed { p.core.find(movie, 5_000, DebridPurpose.PLAY) }
        assertNotNull(found, "Try Again joined the stuck lookup")
        assertEquals(hash('a'), found.hash)
        assertEquals(2, hooks.calls.get())
        assertTrue(ms < 5_000)
        // The look-ahead's own caller was let go when its lookup was dropped.
        assertNull(ahead.await())
        hooks.release.complete(Unit)
    }

    @Test fun aLookupPastItsDeadlineIsNeverJoined() = runBlocking<Unit> {
        val hooks = HangingHooks()
        val clock = FakeClock()
        val p = rig(hooks, clock)
        // A look-ahead whose budget is over while its lookup hangs: its caller goes, the entry stays behind.
        assertNull(p.core.find(movie, 300, DebridPurpose.PAGE))
        clock.advance(300 + DreamCore.STALE_MS + 1)
        // The next Play drops it and starts a fresh lookup instead of waiting on the stuck one.
        val found = p.core.find(movie, 5_000, DebridPurpose.PLAY)
        assertNotNull(found, "the Play joined a lookup past its deadline")
        assertEquals(2, hooks.calls.get())
        hooks.release.complete(Unit)
    }
}
