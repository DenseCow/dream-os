package com.dreamos.desktop.core.playback

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountsTest {
    @Test fun realDebridDeviceSignInSavesTheViewersOwnCredentials() = runBlocking<Unit> {
        val http = FakeHttp()
            .json("device/code", """{"device_code":"DEV","user_code":"ABCD","verification_url":"https://real-debrid.com/device","interval":5,"expires_in":600}""")
            .json("device/credentials", """{"client_id":"MINE","client_secret":"SECRET"}""")
            .json("oauth/v2/token", """{"access_token":"ACC","refresh_token":"REF"}""")
            .json("rest/1.0/user", """{"username":"joe"}""")
        val secrets = MemorySecrets()
        val kit = fakeKit(http, secrets = secrets)
        val a = Accounts(kit, MemoryStore())
        val code = a.startSignIn(DebridServiceId.REAL_DEBRID)!!
        assertEquals("ABCD", code.code)
        assertTrue(code.qrUrl.contains("ABCD"))
        val done = a.poll(code)
        assertIs<SignInPoll.Done>(done)
        assertEquals("ACC", secrets.get("realdebridtoken"))
        assertEquals("MINE", secrets.get("realdebrid.clientid"))
        assertEquals(listOf(DebridServiceId.REAL_DEBRID), a.linked.value)
        // The published open-source client id asks for the code; the token is the viewer's own client id.
        assertTrue(http.sent.first { it.url.contains("oauth/v2/token") }.body.contains("client_id=MINE"))
    }

    @Test fun allDebridWaitsUntilActivatedThenLinks() = runBlocking<Unit> {
        var activated = false
        val http = FakeHttp()
            .json("pin/get", """{"status":"success","data":{"pin":"1234","check":"CHK","user_url":"https://alldebrid.com/pin/?pin=1234","expires_in":600}}""")
            .on("pin/check") { HttpReply(200, if (activated) """{"status":"success","data":{"activated":true,"apikey":"KEY"}}""" else """{"status":"success","data":{"activated":false}}""") }
            .json("v4/user", """{"status":"success","data":{"user":{"username":"joe"}}}""")
        val kit = fakeKit(http)
        val a = Accounts(kit, MemoryStore())
        val code = a.startSignIn(DebridServiceId.ALLDEBRID)!!
        assertIs<SignInPoll.Waiting>(a.poll(code))
        activated = true
        assertIs<SignInPoll.Done>(a.poll(code))
        assertTrue(http.sent.first().url.contains("agent=DreamOS"))
    }

    @Test fun healthReadsAuthBlockedAndPlans() = runBlocking<Unit> {
        val http = FakeHttp().json("v4/user", """{"status":"error","error":{"code":"AUTH_BLOCKED","message":"x"}}""")
        val secrets = MemorySecrets().apply { put("alldebridtoken", "KEY") }
        val kit = fakeKit(http, secrets = secrets)
        val a = Accounts(kit, MemoryStore())
        assertEquals(AccountHealthState.BLOCKED, a.checkHealth(DebridServiceId.ALLDEBRID)?.health)
        val s = a.summary(DebridServiceId.ALLDEBRID)
        assertEquals("Approve This Network", s.text)
        assertEquals(PillTone.BAD, s.tone)
        assertEquals("Not Linked", a.summary(DebridServiceId.TORBOX).text)
        assertTrue(!a.blockedLine(DebridServiceId.ALLDEBRID).contains("—"))
    }

    @Test fun premiumizeKeyIsCheckedBeforeItIsSaved() = runBlocking<Unit> {
        val http = FakeHttp().on("account/info") { s -> HttpReply(200, if ("apikey=GOODKEY123" in s.url) """{"status":"success","customer_id":7}""" else """{"status":"error"}""") }
        val kit = fakeKit(http)
        val a = Accounts(kit, MemoryStore())
        assertIs<SignInPoll.Failed>(a.linkPremiumizeKey("BADKEY999"))
        assertIs<SignInPoll.Done>(a.linkPremiumizeKey("GOODKEY123"))
        assertEquals("apikey:GOODKEY123", kit.accounts.token(DebridServiceId.PREMIUMIZE))
        assertTrue(!a.deviceSignIn(DebridServiceId.PREMIUMIZE)) // no client id registered in this build
    }
}

class MigratingSecretsTest {
    @Test fun plainTokensMoveToTheEncryptedStoreOnce() {
        val plain = MemoryStore().apply { put("alldebridtoken", "KEY"); put("realdebridrefresh", "R"); put("debrid.order", "[]") }
        val secure = MemorySecrets().apply { put("realdebridrefresh", "NEWER") }
        val s = MigratingSecrets(secure, plain)
        assertEquals("KEY", s.get("alldebridtoken"))
        assertEquals("NEWER", s.get("realdebridrefresh")) // a value already encrypted wins
        assertEquals(setOf("debrid.order"), plain.keys()) // only non-secret settings stay plain
        // A store that refuses to encrypt keeps the plain value for the next start.
        val broken = object : SecretStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = throw IllegalStateException("DPAPI unavailable")
            override fun remove(key: String) {}
        }
        val plain2 = MemoryStore().apply { put("torboxtoken", "T") }
        MigratingSecrets(broken, plain2)
        assertEquals("T", plain2.get("torboxtoken"))
    }
}
