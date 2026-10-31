package com.dreamos.desktop.core.store

import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Settings and small data, replacing Android's SharedPreferences: one JSON object per file under
 * `<data>/settings/<name>.json`, kept in memory and written behind (atomic: a temp file, then a move over the old one).
 * A file that can't be read is copied aside (`<name>.json.bad-<time>`) and starts empty, so one bad write never
 * takes Dream OS down. Per-profile files ([profileFile]) follow fire-home: the first profile's file has no suffix,
 * the others `<name>_<profileId>`.
 *
 * Worst-case size: every file is loaded once and kept (the largest is the watch history, 40 entries, a few tens of KB);
 * the whole store stays well under a few MB.
 */
class DreamStore(val dir: Path) {
    private val files = ConcurrentHashMap<String, Prefs>()
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dream-store").apply { isDaemon = true } }

    init {
        Files.createDirectories(dir)
    }

    /** A file shared by every profile ("tmdb", "profiles", "time"). */
    fun file(name: String): Prefs = files.computeIfAbsent(name) { Prefs(this, it, dir.resolve("$it.json")) }

    /** [name] for [profileId] (the current profile by default): `name` for the first profile, else `name_<id>`. */
    fun profileFile(name: String, profileId: String = currentProfile): Prefs = file(name + suffix(profileId))

    /** A file with this name is open or on disk. */
    fun exists(name: String): Boolean = files.containsKey(name) || Files.exists(dir.resolve("$name.json"))

    /** Deletes a file and forgets it (a removed profile's data). */
    fun delete(name: String) {
        files.remove(name)?.clearForDelete()
        try { Files.deleteIfExists(dir.resolve("$name.json")) } catch (e: Exception) { Log.w("Store", "delete $name: $e") }
    }

    /** Every profile-scoped file of [profileId] (removing a profile). */
    fun deleteProfile(profileId: String) {
        if (profileId == FIRST_PROFILE) return
        val sfx = "_$profileId"
        val names = (files.keys + (Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
            .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") })).filter { it.endsWith(sfx) }.toSet()
        names.forEach { delete(it) }
    }

    /** Writes everything pending now (app exit). */
    fun flush() {
        files.values.forEach { it.writeNow() }
    }

    internal fun schedule(p: Prefs) {
        try { writer.schedule({ p.writeNow() }, WRITE_DELAY_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) { p.writeNow() }
    }

    companion object {
        const val FIRST_PROFILE = "p1"
        private const val WRITE_DELAY_MS = 150L

        /** The profile whose files [profileFile] opens. Set by Profiles on launch and on every switch. */
        @Volatile var currentProfile: String = FIRST_PROFILE

        fun suffix(profileId: String) = if (profileId == FIRST_PROFILE) "" else "_$profileId"

        @Volatile private var _shared: DreamStore? = null

        /** The app's store, under the platform's data folder. Tests set their own ([use]). */
        val shared: DreamStore
            get() = _shared ?: synchronized(this) {
                _shared ?: DreamStore(Platform.current.dataDir.resolve("settings")).also {
                    _shared = it
                    Runtime.getRuntime().addShutdownHook(Thread { it.flush() })
                }
            }

        fun use(store: DreamStore) { _shared = store }
    }
}

/** One settings file. Reads are from memory; every change is written behind. Thread-safe. */
class Prefs internal constructor(private val store: DreamStore, val name: String, private val path: Path) {
    private val lock = Any()
    private var map: MutableMap<String, JsonElement> = load()
    private var dirty = false
    private var deleted = false
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** The key of every change (an edit emits each key it touched). */
    val changes: SharedFlow<String> get() = _changes

    private fun load(): MutableMap<String, JsonElement> {
        if (!Files.exists(path)) return LinkedHashMap()
        return try {
            val text = Files.readString(path)
            if (text.isBlank()) LinkedHashMap() else LinkedHashMap(Json.parseToJsonElement(text).jsonObject)
        } catch (e: Exception) {
            // Unreadable: keep it aside for a look later, start empty.
            val aside = path.resolveSibling(path.fileName.toString() + ".bad-" + System.currentTimeMillis())
            try { Files.copy(path, aside, StandardCopyOption.REPLACE_EXISTING) } catch (x: Exception) {}
            Log.w("Store", "$name: unreadable, copied aside to ${aside.fileName}: $e")
            LinkedHashMap()
        }
    }

    fun contains(key: String) = synchronized(lock) { key in map }
    val keys: Set<String> get() = synchronized(lock) { map.keys.toSet() }

    fun getJson(key: String): JsonElement? = synchronized(lock) { map[key] }

    fun getString(key: String, def: String? = null): String? =
        (getJson(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: def

    fun string(key: String, def: String = ""): String = getString(key) ?: def
    fun getInt(key: String, def: Int = 0): Int = (getJson(key) as? JsonPrimitive)?.intOrNull ?: def
    fun getLong(key: String, def: Long = 0): Long = (getJson(key) as? JsonPrimitive)?.longOrNull ?: def
    fun getDouble(key: String, def: Double = 0.0): Double = (getJson(key) as? JsonPrimitive)?.doubleOrNull ?: def
    fun getBoolean(key: String, def: Boolean = false): Boolean = (getJson(key) as? JsonPrimitive)?.booleanOrNull ?: def
    fun getStringList(key: String): List<String> =
        (getJson(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    /** A stored object decoded with [ser]; null when absent or unreadable (an old shape). */
    fun <T> get(key: String, ser: kotlinx.serialization.KSerializer<T>): T? = getJson(key)?.let {
        try { JSON.decodeFromJsonElement(ser, it) } catch (e: Exception) { null }
    }

    fun <T> put(key: String, ser: kotlinx.serialization.KSerializer<T>, value: T) =
        edit { putJson(key, JSON.encodeToJsonElement(ser, value)) }

    fun putString(key: String, v: String?) = edit { putString(key, v) }
    fun putInt(key: String, v: Int) = edit { putInt(key, v) }
    fun putLong(key: String, v: Long) = edit { putLong(key, v) }
    fun putBoolean(key: String, v: Boolean) = edit { putBoolean(key, v) }
    fun putDouble(key: String, v: Double) = edit { putDouble(key, v) }
    fun putStringList(key: String, v: List<String>) = edit { putStringList(key, v) }
    fun remove(key: String) = edit { remove(key) }

    /** Several changes, one write. */
    fun edit(block: Editor.() -> Unit) {
        val e = Editor()
        e.block()
        if (e.ops.isEmpty()) return
        val touched = ArrayList<String>()
        synchronized(lock) {
            if (e.clear) { touched += map.keys; map.clear() }
            for ((k, v) in e.ops) {
                if (k.isEmpty()) continue
                val old = map[k]
                if (v == null) { if (map.remove(k) != null) touched += k } else if (old != v) { map[k] = v; touched += k }
            }
            if (touched.isEmpty()) return
            dirty = true
            deleted = false
        }
        store.schedule(this)
        touched.distinct().forEach { _changes.tryEmit(it) }
    }

    fun clear() = edit { clear() }

    internal fun clearForDelete() = synchronized(lock) { map.clear(); dirty = false; deleted = true }

    internal fun writeNow() {
        val snapshot: String
        synchronized(lock) {
            if (!dirty || deleted) return
            dirty = false
            snapshot = JSON_PRETTY.encodeToString(JsonObject.serializer(), JsonObject(LinkedHashMap(map)))
        }
        try {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, snapshot)
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Log.w("Store", "$name: write failed: $e")
            synchronized(lock) { dirty = true }
        }
    }

    class Editor internal constructor() {
        internal val ops = LinkedHashMap<String, JsonElement?>()
        internal var clear = false
        fun putString(key: String, v: String?) { ops[key] = v?.let { JsonPrimitive(it) } }
        fun putInt(key: String, v: Int) { ops[key] = JsonPrimitive(v) }
        fun putLong(key: String, v: Long) { ops[key] = JsonPrimitive(v) }
        fun putBoolean(key: String, v: Boolean) { ops[key] = JsonPrimitive(v) }
        fun putDouble(key: String, v: Double) { ops[key] = JsonPrimitive(v) }
        fun putStringList(key: String, v: List<String>) { ops[key] = JsonArray(v.map { JsonPrimitive(it) }) }
        fun putJson(key: String, v: JsonElement?) { ops[key] = v }
        fun remove(key: String) { ops[key] = null }
        fun clear() { clear = true; ops[""] = null }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
        private val JSON_PRETTY = Json { prettyPrint = false }
    }
}
