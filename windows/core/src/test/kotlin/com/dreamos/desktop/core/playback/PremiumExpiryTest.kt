package com.dreamos.desktop.core.playback

import com.dreamos.desktop.core.model.NoticeAction
import com.dreamos.desktop.core.model.NoticeSeverity
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PremiumExpiryTest {
    /** Records notices the way core's center keeps them: a key posts once, ever. */
    class Sink : NoticeSink {
        data class Posted(val key: String, val title: String, val body: String, val severity: NoticeSeverity, val banner: Boolean, val store: Boolean)
        val live = LinkedHashMap<String, Posted>()
        val seen = HashSet<String>()
        val banners = ArrayList<Posted>()
        override fun post(key: String, type: String, title: String, body: String, severity: NoticeSeverity, action: NoticeAction?,
                          banner: Boolean, replace: Boolean, store: Boolean): Boolean {
            if (!replace && key in seen) return false
            seen += key
            val p = Posted(key, title, body, severity, banner, store)
            if (store) live[key] = p
            if (banner) banners += p
            return true
        }
        override fun keys(prefix: String) = live.keys.filter { it.startsWith(prefix) }
        override fun dismiss(key: String) { live.remove(key) }
        override fun dismissPrefix(prefix: String) { live.keys.removeAll { it.startsWith(prefix) } }
    }

    private val utc = ZoneId.of("UTC")
    private val day = 86_400_000L

    private fun setup(http: FakeHttp, clock: FakeClock, vararg linked: Pair<String, String>): Triple<PremiumExpiry, Sink, MemoryStore> {
        val secrets = MemorySecrets().apply { linked.forEach { put(it.first, it.second) } }
        val store = MemoryStore()
        val kit = fakeKit(http, clock, secrets, store)
        val sink = Sink()
        val e = PremiumExpiry(Accounts(kit, store), kit.accounts, store, clock, kit.scope, sink, zone = { utc })
        return Triple(e, sink, store)
    }

    private fun adUser(untilMs: Long, subscribed: Boolean = false) =
        """{"status":"success","data":{"user":{"username":"joe","isPremium":true,"isSubscribed":$subscribed,"premiumUntil":${untilMs / 1000}}}}"""

    @Test fun warnsAtSevenThreeAndOneDaysThenExpired() = runBlocking<Unit> {
        val clock = FakeClock()
        val now = clock.wallMs()
        var until = now + 6 * day
        val http = FakeHttp().on("v4/user") { HttpReply(200, adUser(until)) }
        val (e, sink, _) = setup(http, clock, "alldebridtoken" to "KEY")
        e.check()
        val first = sink.live.values.single()
        assertTrue(first.key.startsWith("expiry:ad:7d:"))
        assertEquals("AllDebrid Premium Ends in 6 Days", first.title)
        assertTrue(first.body.startsWith("Renew by "))
        assertFalse(first.body.contains("—"))
        // Asked again the same day: nothing new.
        e.check()
        assertEquals(1, sink.seen.count { it.startsWith("expiry:") })
        // Three days left: the 3-day warning replaces the 7-day one.
        clock.advance(3 * day)
        e.check()
        assertEquals(listOf("AllDebrid Premium Ends in 3 Days"), sink.live.values.filter { it.key.startsWith("expiry:") }.map { it.title })
        clock.advance(3 * day + 60_000)
        e.check()
        val expired = sink.live.values.single { it.key.startsWith("expiry:") }
        assertEquals("AllDebrid Premium Expired", expired.title)
        assertEquals(NoticeSeverity.CRITICAL, expired.severity)
        assertEquals("Renew or Link Another Service to Keep Streaming", expired.body)
        // Renewed for a year: the warnings go.
        until = clock.wallMs() + 365 * day
        e.check()
        assertTrue(sink.live.keys.none { it.startsWith("expiry:") })
    }

    @Test fun subscriptionsGetNoCountdownAndTheSummaryShowsOnce() = runBlocking<Unit> {
        val clock = FakeClock()
        val now = clock.wallMs()
        val http = FakeHttp()
            .json("v4/user", adUser(now + 3 * day, subscribed = true))
            .json("account/info", """{"status":"success","customer_id":7,"premium_until":${(now + 96 * day) / 1000}}""")
        val (e, sink, _) = setup(http, clock, "alldebridtoken" to "KEY", "premiumizetoken" to "PM")
        assertEquals(2, e.check("start"))
        assertTrue(sink.live.keys.none { it.startsWith("expiry:") })
        val summary = sink.banners.single { it.key.startsWith("summary:") }
        assertEquals("AllDebrid: Renews Automatically\nPremiumize: 96 Days", summary.body)
        assertFalse(summary.store)
        e.check()
        assertEquals(1, sink.banners.count { it.key.startsWith("summary:") })
    }

    @Test fun expiredSaysWhichServiceStillStreams() = runBlocking<Unit> {
        val clock = FakeClock()
        val now = clock.wallMs()
        val http = FakeHttp()
            .json("v4/user", """{"status":"success","data":{"user":{"username":"joe","isPremium":false,"premiumUntil":0}}}""")
            .json("account/info", """{"status":"success","customer_id":7,"premium_until":${(now + 96 * day) / 1000}}""")
        val (e, sink, _) = setup(http, clock, "alldebridtoken" to "KEY", "premiumizetoken" to "PM")
        e.check()
        assertEquals("Streaming Through Premiumize Instead", sink.live.values.single { it.key.startsWith("expiry:ad:") }.body)
    }

    @Test fun roundsComeWhenDueAndSoonerWhenOffline() = runBlocking<Unit> {
        val clock = FakeClock()
        var online = false
        val http = FakeHttp().on("v4/user") { if (online) HttpReply(200, adUser(clock.wallMs() + 200 * day)) else HttpReply(0, "") }
        val (e, _, _) = setup(http, clock, "alldebridtoken" to "KEY")
        e.maybeCheck() // the first in this process always runs
        assertEquals(1, http.count("v4/user"))
        clock.advance(10 * 60_000)
        e.maybeCheck()
        assertEquals(1, http.count("v4/user"))
        clock.advance(11 * 60_000) // nothing answered: tried again after 20 min
        online = true
        e.maybeCheck()
        assertEquals(2, http.count("v4/user"))
        clock.advance(60 * 60_000)
        e.maybeCheck()
        assertEquals(2, http.count("v4/user")) // answered: the next round in 6 hours
    }

    @Test fun rules() {
        val clock = FakeClock()
        val (e, _, _) = setup(FakeHttp(), clock)
        val now = clock.wallMs()
        fun h(until: Long, premium: Boolean? = true, sub: Boolean = false) =
            AccountHealth(DebridServiceId.TORBOX, AccountHealthState.OK, now, premium, until, null, sub)
        assertNull(e.level(now, h(now + 30 * day)))
        assertEquals(PremiumExpiry.Level.D1, e.level(now, h(now + day)))
        assertNull(e.level(now, h(now + day, sub = true)))
        assertEquals(PremiumExpiry.Level.EXPIRED, e.level(now, h(now - 1, sub = true)))
        assertNull(e.level(now, h(0, premium = null))) // nothing known: no notice
        assertEquals("1 Year", e.timeLeft(now, h(now + 400 * day)))
        assertEquals("https://real-debrid.com/premium", PremiumExpiry.renewUrl("renew:rd"))
    }
}
