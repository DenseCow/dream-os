package com.dreamos.desktop.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A debrid service Dream Core can ask (fire-home DebridService + the iPhone's resolveMany / discard). Only cached
 * torrents count (nothing is left downloading); anything added to the viewer's account and not used is removed again.
 */
interface DebridService {
    val id: DebridServiceId
    val key get() = id.key

    /**
     * Up to [limit] cached sources of [sources] (best first) unlocked to direct links, best first; empty when none.
     * Gives up at [deadline] (monotonic ms). The first is played, the rest are kept as alternates.
     */
    suspend fun resolveMany(sources: List<Source>, deadline: Long, limit: Int): List<Stream>

    suspend fun resolve(sources: List<Source>, deadline: Long): Stream? = resolveMany(sources, deadline, 1).firstOrNull()

    /** Alternates with these hashes aren't needed any more: what was added to the account for them goes. Returns at once. */
    fun discard(hashes: List<String>) {}
}

/**
 * The debrid accounts the viewer linked, under the same setting ids as the other apps ("realdebridtoken",
 * "alldebridtoken", ...). Tokens live in the [SecretStore]; on / off switches and the ask order in the [KeyValueStore].
 */
class DebridAccounts(private val secrets: SecretStore, private val store: KeyValueStore) {
    companion object {
        private val TOKEN = mapOf(
            DebridServiceId.ALLDEBRID to "alldebridtoken", DebridServiceId.PREMIUMIZE to "premiumizetoken",
            DebridServiceId.TORBOX to "torboxtoken", DebridServiceId.REAL_DEBRID to "realdebridtoken",
        )
        private val ENABLE = mapOf(
            DebridServiceId.ALLDEBRID to "alldebrid.enable", DebridServiceId.PREMIUMIZE to "premiumize.enable",
            DebridServiceId.TORBOX to "torbox.enable", DebridServiceId.REAL_DEBRID to "realdebrid.enable",
        )
        /** Every setting a service's sign-in may save (removed on unlink). */
        private val ALL_KEYS = mapOf(
            DebridServiceId.ALLDEBRID to listOf("alldebridtoken", "alldebridusername"),
            DebridServiceId.PREMIUMIZE to listOf("premiumizetoken", "premiumizeusername"),
            DebridServiceId.TORBOX to listOf("torboxtoken", "torbox.username"),
            DebridServiceId.REAL_DEBRID to listOf("realdebridtoken", "realdebridrefresh", "realdebrid.clientid", "realdebridsecret", "realdebridusername"),
        )
        /** Every key a sign-in keeps in the [SecretStore] (tokens, Real-Debrid's device credentials, user names). */
        val SECRET_KEYS: Set<String> get() = ALL_KEYS.values.flatten().toSet()
        private const val ORDER_KEY = "debrid.order"
        private val LIST = ListSerializer(String.serializer())
    }

    private val _linked = MutableStateFlow(readLinked())
    val linked: StateFlow<List<DebridServiceId>> get() = _linked

    fun token(s: DebridServiceId): String? = secrets.get(TOKEN.getValue(s))?.takeIf { it.isNotBlank() && store.get(ENABLE.getValue(s)) != "false" }
    fun value(key: String): String = secrets.get(key).orEmpty()
    fun user(s: DebridServiceId): String? = ALL_KEYS.getValue(s).firstOrNull { it.endsWith("username") }?.let { secrets.get(it) }?.ifBlank { null }

    val any get() = DebridServiceId.entries.any { token(it) != null }

    /** The viewer's ask order (Settings); services left out follow in the default order. */
    fun order(): List<DebridServiceId> {
        val saved = try { store.get(ORDER_KEY)?.let { Json.decodeFromString(LIST, it) } } catch (e: Exception) { null }.orEmpty()
            .mapNotNull { DebridServiceId.of(it) }.distinct()
        return saved + DebridServiceId.DEFAULT_ORDER.filter { it !in saved }
    }

    fun setOrder(order: List<DebridServiceId>) {
        store.put(ORDER_KEY, Json.encodeToString(LIST, order.distinct().map { it.key }))
        _linked.value = readLinked()
    }

    /** A sign-in finished: its settings saved, the service switched on. */
    fun save(s: DebridServiceId, settings: Map<String, String>) {
        for ((k, v) in settings) if (k == ENABLE.getValue(s)) store.put(k, v) else secrets.put(k, v)
        store.put(ENABLE.getValue(s), "true")
        _linked.value = readLinked()
    }

    /** Real-Debrid's token was renewed: saved at once (fire-home DreamAccounts.refreshedRealDebrid). */
    fun refreshedRealDebrid(access: String, refresh: String?) {
        secrets.put("realdebridtoken", access)
        if (!refresh.isNullOrBlank()) secrets.put("realdebridrefresh", refresh)
    }

    fun unlink(s: DebridServiceId) {
        ALL_KEYS.getValue(s).forEach { secrets.remove(it) }
        store.remove(ENABLE.getValue(s))
        _linked.value = readLinked()
    }

    private fun readLinked() = order().filter { token(it) != null }
}

/** Everything the services share: transport, ledger, accounts, clock and a scope for background cleanup. */
class DebridKit(
    val http: DebridHttp,
    val ledger: DebridLedger,
    val accounts: DebridAccounts,
    val clock: Clock,
    val scope: CoroutineScope,
) {
    fun now() = clock.monotonicMs()
    /** Fire-and-forget background work (cleanup, janitor); failures are logged, never thrown. */
    fun background(what: String, work: suspend () -> Unit) {
        scope.launch { try { work() } catch (e: Exception) { PlaybackLog.w("$what failed (${e.javaClass.simpleName})") } }
    }

    fun found(url: String, service: DebridServiceId, s: Source, path: String, size: Long) =
        Stream(url, service, DebridFiles.base(path), if (size > 0) size else s.sizeBytes, s.quality, s.hash, releaseName = s.torrentName)

    /** Sleeps [ms] only when [deadline] still leaves room for the request after it; false when it doesn't. */
    suspend fun pause(ms: Long, deadline: Long): Boolean {
        if (deadline - now() < ms + 1_000) return false
        kotlinx.coroutines.delay(ms)
        return true
    }
}
