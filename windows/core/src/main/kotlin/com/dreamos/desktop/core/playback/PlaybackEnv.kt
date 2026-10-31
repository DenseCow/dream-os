package com.dreamos.desktop.core.playback

import java.util.concurrent.ConcurrentHashMap

/*
 * The seams Dream Core needs from the rest of the app, kept small so tests run on fakes and win-content's settings
 * store / Platform can be plugged in after the merge (docs/port/requests.md).
 */

/** Time. [monotonicMs] for durations and TTLs that must not jump; [wallMs] for anything stored. */
interface Clock {
    fun monotonicMs(): Long
    fun wallMs(): Long

    companion object {
        val SYSTEM: Clock = object : Clock {
            override fun monotonicMs() = System.nanoTime() / 1_000_000
            override fun wallMs() = System.currentTimeMillis()
        }
    }
}

/** A string key-value store that survives restarts (win-content's settings store, JSON under %APPDATA%\Dream OS). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** In memory: tests, and until the real store is wired. */
class MemoryStore : KeyValueStore {
    private val m = ConcurrentHashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
    fun keys(): Set<String> = m.keys
}

/**
 * Secrets (debrid tokens): the app wires an encrypted store (Windows DPAPI; Keychain on a Mac). The values never reach
 * the log.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemorySecrets : SecretStore {
    private val m = ConcurrentHashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}

/** The log. Tokens and full stream URLs are never written to it. */
object PlaybackLog {
    @Volatile var sink: (level: Char, msg: String) -> Unit = { l, m -> if (l != 'D') System.err.println("DreamCore $l $m") }
    fun i(msg: String) = sink('I', msg)
    fun w(msg: String) = sink('W', msg)
    fun d(msg: String) = sink('D', msg)
}

/**
 * What the PC's screen and GPU can show (fire-home DeviceVideo, iPhone HDRPreference). [maxQuality]: the best grade
 * worth picking (a 1080p screen still takes 4K when the viewer allows it: the PC scales down fine, so the default is
 * 4K); [hdr]: the window's screen shows HDR now (Windows "Use HDR" on), so Prefer HDR may go above the cap.
 */
interface ScreenFacts {
    val maxQuality: Quality get() = Quality.UHD_4K
    val hdr: Boolean get() = false
}

/** One daemon thread for small delayed writes (debounced saves); never blocks the caller. */
object Background {
    private val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dream-core-saves").apply { isDaemon = true } }
    fun after(ms: Long, work: () -> Unit) {
        exec.schedule({ try { work() } catch (e: Exception) { PlaybackLog.w("Background save failed (${e.javaClass.simpleName})") } }, ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
}

/**
 * Debrid tokens moving from a plain settings file to an encrypted [secure] store, once: each of [keys] found in
 * [plain] is written to [secure] (unless it already holds one), read back, and only then removed from [plain]. A key
 * that can't be encrypted stays where it was and is tried again at the next start. Afterwards everything reads and
 * writes [secure] only. Values never reach the log, only how many moved.
 */
class MigratingSecrets(private val secure: SecretStore, plain: KeyValueStore,
                       keys: Collection<String> = DebridAccounts.SECRET_KEYS) : SecretStore {
    init {
        var moved = 0
        var kept = 0
        for (k in keys) {
            val v = try { plain.get(k) } catch (e: Exception) { null }
            if (v.isNullOrEmpty()) continue
            try {
                if (secure.get(k).isNullOrEmpty()) secure.put(k, v)
                if (!secure.get(k).isNullOrEmpty()) { plain.remove(k); moved++ } else kept++
            } catch (e: Exception) { kept++ }
        }
        if (moved > 0 || kept > 0) PlaybackLog.w("Accounts: $moved saved sign-in values encrypted" + (if (kept > 0) ", $kept left for the next start" else ""))
    }

    override fun get(key: String) = secure.get(key)
    override fun put(key: String, value: String) = secure.put(key, value)
    override fun remove(key: String) = secure.remove(key)
}
