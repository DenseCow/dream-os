package com.firehome.launcher
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Net.run stand-in: a background pool like the app's, with a counter so the tests can wait for cleanup jobs. */
object Net {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "net-misc").apply { isDaemon = true } }
    val pending = AtomicInteger()
    fun run(work: () -> Unit) {
        pending.incrementAndGet()
        pool.execute { try { work() } catch (e: Exception) { android.util.Log.w("Net", "background job failed: $e") } finally { pending.decrementAndGet() } }
    }
}

/** DreamAccounts stand-in: only what the debrid code calls. */
internal object DreamAccounts {
    val refreshed = CopyOnWriteArrayList<Pair<String, String?>>()
    fun values(ctx: Context): Map<String, String>? = null
    fun refreshedRealDebrid(access: String, refresh: String?) { refreshed.add(access to refresh) }
}
