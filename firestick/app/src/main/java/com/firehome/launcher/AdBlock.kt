package com.firehome.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Ad blocking through Android 9's Private DNS (DNS-over-TLS). Writes Settings.Global
 * private_dns_mode / private_dns_specifier (needs WRITE_SECURE_SETTINGS, granted by Unlock Features or deploy.sh)
 * and remembers what was set before so Off puts it back exactly.
 */
object AdBlock {
    data class Provider(val id: String, val name: String, val host: String, val blurb: String)

    val PROVIDERS = listOf(
        Provider("adguard", "AdGuard", "dns.adguard-dns.com", "Ads and trackers"),
        Provider("adguard_family", "AdGuard Family", "family.adguard-dns.com", "Ads, trackers, adult sites + safe search"),
        Provider("mullvad", "Mullvad", "adblock.dns.mullvad.net", "Ads and trackers, no logs"),
    )

    data class Status(val mode: String?, val host: String?, val provider: Provider?) {
        val on get() = mode == "hostname" && !host.isNullOrBlank()
        val label get() = when {
            !on -> "Off"
            provider != null -> provider.name
            else -> host!!
        }
    }

    /** Outcome of turning it on and checking it. [kind]: ok, warn, error. */
    data class Result(val kind: String, val message: String)

    private const val TAG = "AdBlock"
    private const val MODE = "private_dns_mode"
    private const val SPEC = "private_dns_specifier"
    private const val PREFS = "adblock"
    private const val UNSET = "\u0000unset" // the key had no value before we touched it
    private val main = Handler(Looper.getMainLooper())

    /** A switch or check is running (AdBlockPanel's busy flag, shared by every card that opens meanwhile). */
    @Volatile var running = false

    /** Real ad hosts that resolve on a normal network and are on every ad list. */
    private val AD_HOSTS = listOf(
        "pagead2.googlesyndication.com", "ad.doubleclick.net", "googleads.g.doubleclick.net",
        "securepubads.g.doubleclick.net", "adservice.google.com",
    )

    /** Wildcard domains: a random label always resolves, and is never cached. */
    private val WILDCARD_DOMAINS = listOf("s3.amazonaws.com", "github.io", "1.1.1.1.sslip.io")

    fun supported() = Build.VERSION.SDK_INT >= 28

    fun status(ctx: Context): Status {
        val cr = ctx.contentResolver
        val mode = try { Settings.Global.getString(cr, MODE) } catch (e: Exception) { null }
        val host = try { Settings.Global.getString(cr, SPEC) } catch (e: Exception) { null }
        return Status(mode, host, PROVIDERS.firstOrNull { it.host.equals(host, ignoreCase = true) })
    }

    /** Switches system DNS to [host] (strict mode). Returns false when the write isn't allowed. */
    fun enable(ctx: Context, host: String): Boolean {
        val cr = ctx.contentResolver
        val p = prefs(ctx)
        return try {
            // Keep the original values across provider switches, so Off always goes back to them.
            if (!p.getBoolean("saved", false)) {
                p.edit()
                    .putString("prevMode", Settings.Global.getString(cr, MODE) ?: UNSET)
                    .putString("prevSpec", Settings.Global.getString(cr, SPEC) ?: UNSET)
                    .putBoolean("saved", true)
                    .apply()
            }
            Settings.Global.putString(cr, SPEC, host)
            Settings.Global.putString(cr, MODE, "hostname")
            Log.w(TAG, "Private DNS -> $host")
            true
        } catch (e: Exception) {
            Log.w(TAG, "enable failed", e)
            false
        }
    }

    /** Puts back whatever DNS mode was set before [enable]. */
    fun disable(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val p = prefs(ctx)
        return try {
            if (p.getBoolean("saved", false)) {
                val mode = p.getString("prevMode", UNSET)
                val spec = p.getString("prevSpec", UNSET)
                Settings.Global.putString(cr, SPEC, spec.takeUnless { it == UNSET })
                Settings.Global.putString(cr, MODE, mode.takeUnless { it == UNSET })
            } else {
                // Turned on outside Dream OS: fall back to Android's default.
                Settings.Global.putString(cr, MODE, "opportunistic")
            }
            p.edit().remove("prevMode").remove("prevSpec").remove("saved").apply()
            Log.w(TAG, "Private DNS restored: ${Settings.Global.getString(cr, MODE)}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "disable failed", e)
            false
        }
    }

    fun customHost(ctx: Context): String = prefs(ctx).getString("custom", "") ?: ""
    fun setCustomHost(ctx: Context, host: String) = prefs(ctx).edit().putString("custom", host).apply()

    /** The last check's result, shown when the panel reopens. Cleared on any change. */
    fun lastResult(ctx: Context): Result? {
        val p = prefs(ctx)
        val kind = p.getString("lastKind", null) ?: return null
        return Result(kind, p.getString("lastMsg", "") ?: "")
    }

    private fun saveResult(ctx: Context, r: Result?) {
        val e = prefs(ctx).edit()
        if (r == null) e.remove("lastKind").remove("lastMsg") else e.putString("lastKind", r.kind).putString("lastMsg", r.message)
        e.apply()
    }

    fun clearResult(ctx: Context) = saveResult(ctx, null)

    fun normalizeHost(s: String) = s.trim().lowercase()
        .removePrefix("tls://").removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')

    fun isValidHost(s: String) =
        s.length in 4..253 && '.' in s && s.split('.').all { it.matches(Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) }

    /**
     * Pre-flight check, then switch, then prove it: normal names still resolve (else revert) and
     * ad hosts come back blocked. Runs on a background thread; callbacks arrive on the main thread.
     */
    fun enableAndVerify(ctx: Context, host: String, progress: (String) -> Unit, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        clearResult(app)
        fun step(s: String) { main.post { progress(s) } }
        fun finish(r: Result) {
            saveResult(app, r)
            main.post { done(r) }
        }
        Net.run {
            step("Testing a secure connection to $host…")
            val dotError = checkDot(host)
            if (dotError != null) {
                finish(Result("error", "$dotError Nothing was changed."))
                return@run
            }
            // Which ad hosts resolve now, so a failure later means "blocked" and not "never existed".
            val baseline = AD_HOSTS.filter { resolve(it)?.none { a -> isNullAddress(a) } == true }
            // A failed switch from one provider to another goes back to the one that was on, not all the way Off.
            val before = status(app)
            step("Switching system DNS…")
            if (!enable(app, host)) {
                finish(Result("error", "Dream OS isn't allowed to change system DNS. Turn it on in Settings › Unlock Features."))
                return@run
            }
            finish(verify(app, host, baseline, ::step) { if (before.on) restore(app, before) else disable(app) })
        }
    }

    /** Re-runs the checks for the provider that's already on. */
    fun recheck(ctx: Context, progress: (String) -> Unit, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        val host = status(app).host ?: return
        Net.run {
            val r = verify(app, host, AD_HOSTS, step = { s -> main.post { progress(s) } })
            saveResult(app, r)
            main.post { done(r) }
        }
    }

    /** Puts back a provider that was on before a switch ([enable] keeps the original values for Off). */
    private fun restore(ctx: Context, s: Status) {
        try {
            Settings.Global.putString(ctx.contentResolver, SPEC, s.host)
            Settings.Global.putString(ctx.contentResolver, MODE, s.mode)
            Log.w(TAG, "Private DNS back to the previous provider")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
            disable(ctx)
        }
    }

    private fun verify(ctx: Context, host: String, adHosts: List<String>, step: (String) -> Unit,
                       revert: () -> Unit = { disable(ctx) }): Result {
        step("Checking that websites still load…")
        // The system needs a moment to pick up the change and validate the TLS server.
        val start = SystemClock.elapsedRealtime()
        var accepted = false
        var dnsOk = false
        while (SystemClock.elapsedRealtime() - start < 14_000) {
            sleep(1500)
            accepted = systemDnsHost(ctx).equals(host, ignoreCase = true)
            dnsOk = normalResolves()
            if (dnsOk && accepted) break
            if (dnsOk && SystemClock.elapsedRealtime() - start > 6_000) break
        }
        if (!dnsOk) {
            sleep(2000)
            dnsOk = normalResolves()
        }
        Log.w(TAG, "verify $host accepted=$accepted dnsOk=$dnsOk")
        if (!dnsOk) {
            revert()
            return Result("error", "Websites stopped loading with $host (your network may block secure DNS on port 853). Switched back to your previous DNS.")
        }
        step("Checking that ads are blocked…")
        repeat(4) { attempt ->
            if (attempt > 0) sleep(2500)
            if (adsBlocked(adHosts)) return Result("ok", "Ads are blocked.")
        }
        return Result("warn",
            if (accepted) "Private DNS is on, but ad domains still resolve. Cached answers can take a few minutes to clear. Check again shortly."
            else "Ad domains still resolve. Fire OS may be ignoring Private DNS on this device.")
    }

    /** Private DNS server Android actually applied to the active network (strict mode), or null. */
    private fun systemDnsHost(ctx: Context): String? = try {
        if (!supported()) null else {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName
        }
    } catch (e: Exception) {
        null
    }

    /** Null when [host] answers DNS-over-TLS on port 853, else a short reason. */
    private fun checkDot(host: String): String? {
        var socket: Socket? = null
        return try {
            socket = Socket().apply { connect(InetSocketAddress(host, 853), 5000); soTimeout = 5000 }
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, 853, true) as SSLSocket
            socket = ssl
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) "$host's certificate doesn't match." else null
        } catch (e: java.net.UnknownHostException) {
            "Couldn't find $host."
        } catch (e: javax.net.ssl.SSLException) {
            "$host doesn't support secure DNS."
        } catch (e: Exception) {
            Log.w(TAG, "DoT check failed for $host", e)
            "Couldn't reach $host on port 853 (your network may block secure DNS)."
        } finally {
            try { socket?.close() } catch (e: Exception) {}
        }
    }

    /** A never-before-seen name under a wildcard domain resolves, so DNS really works (no cache). */
    private fun normalResolves() = WILDCARD_DOMAINS.any { resolve("dreamos${Random.nextInt(0x10000000, 0x7fffffff).toString(16)}.$it") != null }

    /** True when any ad host comes back as a null address or doesn't exist. */
    private fun adsBlocked(adHosts: List<String>): Boolean {
        // A random subdomain isn't cached anywhere; blockers answer it with 0.0.0.0 (it normally doesn't exist).
        val fresh = resolve("dreamos${Random.nextInt(0x10000000, 0x7fffffff).toString(16)}.doubleclick.net")
        if (fresh != null && fresh.all { isNullAddress(it) }) return true
        return adHosts.any { h ->
            val a = resolve(h)
            a == null || a.all { isNullAddress(it) }
        }
    }

    private fun isNullAddress(a: InetAddress) = a.isAnyLocalAddress || a.isLoopbackAddress ||
        (a is Inet4Address && a.address.all { it == 0.toByte() })

    private fun resolve(name: String): Array<InetAddress>? = try {
        InetAddress.getAllByName(name).takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {}

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
