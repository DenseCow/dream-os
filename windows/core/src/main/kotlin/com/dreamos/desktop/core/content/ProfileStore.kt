package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.KidsLevel
import com.dreamos.desktop.core.model.PinResult
import com.dreamos.desktop.core.model.Profile
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Viewer profiles (fire-home Profiles.kt + KidsProfiles.kt). Each profile gets its own My List, history, taste and
 * settings by suffixing the settings files ([DreamStore.profileFile]); the first profile uses the plain names. Any
 * profile can be locked with a 4-digit PIN, stored only as a salted SHA-256 hash ("salt:hash"). After 5 wrong PINs in a
 * row every further try waits a growing while (30 s doubling, 15 min at most). A PIN keeps profiles apart; it isn't a
 * parental lock (that's the Kids rating limit).
 *
 * File `profiles`: `list` (id, name, emoji, color), `current`, `setup_done`, `pin_<id>`, `pinFails_<id>`,
 * `pinWait_<id>`, `kids_<id>`, `maxlevel_<id>`, `kidsexitpin_<id>` (keys as on the Fire Stick).
 */
object ProfileStore {
    private const val FREE_TRIES = 5
    private const val MAX_WAIT_MS = 15 * 60_000L

    @Serializable
    private data class Stored(val id: String, val name: String, val emoji: String = "😀", val color: Int = 0)

    private val ser = ListSerializer(Stored.serializer())
    private val lock = Any()
    private fun prefs() = DreamStore.shared.file("profiles")

    private val _all = MutableStateFlow<List<Profile>>(emptyList())
    private val _current = MutableStateFlow(Profile(Profile.FIRST, "Me"))
    val all: StateFlow<List<Profile>> get() = _all
    val current: StateFlow<Profile> get() = _current

    /** Told after the current profile changed (caches per profile reload). */
    @Volatile var onSwitched: ((Profile) -> Unit)? = null

    /** Loads the list and points the store at the current profile. Call once at launch (and in tests after `DreamStore.use`). */
    fun init() = synchronized(lock) {
        refresh()
        DreamStore.currentProfile = _current.value.id
    }

    private fun stored(): List<Stored> =
        prefs().get("list", ser)?.takeIf { it.isNotEmpty() } ?: listOf(Stored(Profile.FIRST, "Me", Profile.EMOJIS[0], 0))

    private fun view(s: Stored): Profile {
        val p = prefs()
        val kids = ContentPrefs.isKids(s.id, s.name)
        return Profile(
            id = s.id, name = s.name, emoji = s.emoji, color = s.color, isKids = kids,
            kidsLevel = ContentPrefs.kidsLevel(s.id), exitPin = p.getBoolean("kidsexitpin_${s.id}"),
            hasPin = p.contains("pin_${s.id}"),
        )
    }

    private fun refresh() {
        val list = stored().map { view(it) }
        _all.value = list
        val id = prefs().getString("current") ?: Profile.FIRST
        _current.value = list.firstOrNull { it.id == id } ?: list.first()
    }

    fun find(id: String): Profile? = _all.value.firstOrNull { it.id == id }

    // ---- setup ----

    fun isSetUp() = prefs().getBoolean("setup_done")
    fun markSetUp() = prefs().putBoolean("setup_done", true)

    // ---- editing ----

    fun add(name: String): Profile = synchronized(lock) {
        val list = stored()
        val n = list.size
        // Two adds in the same millisecond must not share an id.
        var t = System.currentTimeMillis()
        while (list.any { it.id == "p" + t.toString(36) }) t++
        val s = Stored("p" + t.toString(36), name.trim().ifEmpty { "Profile ${n + 1}" },
            Profile.EMOJIS[n % Profile.EMOJIS.size], n % Profile.COLORS.size)
        prefs().put("list", ser, list + s)
        refresh()
        view(s)
    }

    /** Saves name, emoji, color and the Kids settings. [pin]: null keeps it, "" removes it, digits set it. */
    fun save(profile: Profile, pin: String? = null) = synchronized(lock) {
        val list = stored().toMutableList()
        val i = list.indexOfFirst { it.id == profile.id }
        val s = Stored(profile.id, profile.name.trim().ifEmpty { list.getOrNull(i)?.name ?: "Me" }, profile.emoji, profile.color)
        if (i >= 0) list[i] = s else list += s
        val wasKids = find(profile.id)?.isKids ?: false
        val everyone = KidsLevel.ALL.size - 1
        // Turning Kids on starts at the Kids level (not Everyone); turning it off lifts the limit.
        val level = when {
            profile.isKids && !wasKids && profile.kidsLevel >= everyone -> KidsLevel.DEFAULT_KIDS
            !profile.isKids && wasKids -> everyone
            else -> profile.kidsLevel.coerceIn(0, everyone)
        }
        prefs().edit {
            putJson("list", com.dreamos.desktop.core.store.Prefs.JSON.encodeToJsonElement(ser, list))
            putBoolean("kids_${profile.id}", profile.isKids)
            putInt("maxlevel_${profile.id}", level)
            putBoolean("kidsexitpin_${profile.id}", profile.isKids && profile.exitPin)
        }
        if (pin != null) setPin(profile.id, pin.ifEmpty { null })
        refresh()
        Tmdb.clearLists() // the Kids limit may have changed
        ContentPrefs.bump()
    }

    /** Removes [id] and its data. The first profile stays. */
    fun delete(id: String) = synchronized(lock) {
        if (id == Profile.FIRST) return
        prefs().edit {
            putJson("list", com.dreamos.desktop.core.store.Prefs.JSON.encodeToJsonElement(ser, stored().filter { it.id != id }))
            for (k in listOf("pin_", "pinFails_", "pinWait_", "kids_", "maxlevel_", "kidsexitpin_")) remove(k + id)
        }
        DreamStore.shared.deleteProfile(id)
        if (_current.value.id == id) setCurrent(Profile.FIRST) else refresh()
    }

    fun setCurrent(id: String) {
        synchronized(lock) {
            prefs().putString("current", id)
            refresh()
            DreamStore.currentProfile = _current.value.id
        }
        Watched.profileChanged()
        MyListStore.reload()
        Tmdb.clearLists()
        Log.i("Profiles", "now ${_current.value.name}")
        try { onSwitched?.invoke(_current.value) } catch (e: Exception) {}
    }

    // ---- PINs ----

    fun hasPin(id: String) = prefs().contains("pin_$id")

    /** Saves [pin] for [id] as "salt:sha256(salt+pin)", or removes the lock when null. Only 4 digits are taken. */
    fun setPin(id: String, pin: String?) {
        if (pin == null) { prefs().remove("pin_$id"); return }
        require(pin.length == 4 && pin.all { it.isDigit() }) { "A PIN is 4 digits" }
        val salt = hex(ByteArray(8).also { SecureRandom().nextBytes(it) })
        prefs().edit { putString("pin_$id", salt + ":" + hash(salt, pin)); remove("pinFails_$id"); remove("pinWait_$id") }
    }

    /** Checks [pin] for [id] (no PIN: always Ok). After [FREE_TRIES] wrong ones every try waits a growing while. */
    fun checkPin(id: String, pin: String, now: Long = System.currentTimeMillis()): PinResult {
        val p = prefs()
        val stored = p.getString("pin_$id") ?: return PinResult.Ok
        val wait = pinWaitSeconds(id, now)
        if (wait > 0) return PinResult.Wait(wait)
        val salt = stored.substringBefore(':')
        val ok = MessageDigest.isEqual(hash(salt, pin).toByteArray(), stored.substringAfter(':').toByteArray())
        if (ok) { p.edit { remove("pinFails_$id"); remove("pinWait_$id") }; return PinResult.Ok }
        val fails = p.getInt("pinFails_$id") + 1
        val w = if (fails < FREE_TRIES) 0L else minOf(30_000L shl minOf(fails - FREE_TRIES, 5), MAX_WAIT_MS)
        p.edit { putInt("pinFails_$id", fails); putLong("pinWait_$id", if (w > 0) now + w else 0L) }
        return if (w > 0) PinResult.Wait(((w + 999) / 1000).toInt()) else PinResult.Wrong
    }

    /** Seconds before [id]'s PIN can be tried again (0 = now). Never longer than the longest wait, even if the clock moved. */
    fun pinWaitSeconds(id: String, now: Long = System.currentTimeMillis()): Int {
        val left = prefs().getLong("pinWait_$id") - now
        return if (left <= 0) 0 else ((minOf(left, MAX_WAIT_MS) + 999) / 1000).toInt()
    }

    /**
     * The profile whose PIN switching to [to] needs, or null: [to]'s own PIN; else, leaving a Kids profile whose
     * "Ask for a PIN to Leave" is on, the main profile's PIN (else the first grown-up's that has one). No grown-up PIN
     * at all: not blocked.
     */
    fun pinNeeded(to: String): String? {
        if (hasPin(to)) return to
        val cur = _current.value
        if (to == cur.id || !cur.isKids || !cur.exitPin) return null
        val all = _all.value
        return (all.firstOrNull()?.takeIf { it.id != cur.id && hasPin(it.id) }
            ?: all.firstOrNull { it.id != cur.id && !it.isKids && hasPin(it.id) })?.id
    }

    /** Switches to [id] once [pin] passes [pinNeeded]'s check. */
    fun select(id: String, pin: String?): PinResult {
        if (find(id) == null) return PinResult.Wrong
        val guard = pinNeeded(id)
        if (guard != null) {
            val r = checkPin(guard, pin.orEmpty())
            if (r != PinResult.Ok) return r
        }
        setCurrent(id)
        return PinResult.Ok
    }

    /** The profile turned on "Use Windows Hello" (the pages' `hello.<id>` in the shared `pages` file; only with a PIN). */
    fun helloOn(id: String): Boolean = hasPin(id) && DreamStore.shared.file("pages").getBoolean("hello.$id")

    /**
     * Switches to [id] after Windows Hello verified the person at the PC: the pass stands in for [id]'s own PIN, only
     * when that profile turned Windows Hello on ([helloOn]). It never stands in for a grown-up's PIN to leave a Kids
     * profile (a child may know the PC's Windows PIN): then [PinResult.Wrong] comes back without switching and the page
     * asks for the PIN [pinNeeded] names. Call it only with a real Hello pass.
     */
    fun selectTrusted(id: String): PinResult {
        if (find(id) == null) return PinResult.Wrong
        val guard = pinNeeded(id)
        if (guard != null && (guard != id || !helloOn(id))) return PinResult.Wrong
        setCurrent(id)
        return PinResult.Ok
    }

    private fun hash(salt: String, pin: String) = hex(MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
