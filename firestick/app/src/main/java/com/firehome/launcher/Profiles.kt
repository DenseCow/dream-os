package com.firehome.launcher

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Viewer profiles. Each profile gets its own theme / favorites / My List by suffixing the
 * preference files and keys ([suffix]); the first profile uses the original ones so nothing is lost.
 * Any profile can optionally be locked with a 4-digit PIN (stored as a salted hash).
 */
object Profiles {
    class Profile(val id: String, var name: String, var emoji: String, var color: Int)

    val EMOJIS = listOf("😀", "😎", "🦄", "🐱", "🐶", "🦊", "👾", "🌟", "🚀", "🎮", "🌸", "🐼")
    val COLORS = listOf("#7C5CFF", "#38BDF8", "#F472B6", "#4ADE80", "#FBBF24", "#F87171", "#A78BFA", "#2DD4BF").map { Color.parseColor(it) }

    const val FIRST = "p1"
    @Volatile private var cachedSuffix: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<Profile> {
        val raw = prefs(ctx).getString("list", null) ?: return mutableListOf(Profile(FIRST, "Me", EMOJIS[0], COLORS[0]))
        val a = JSONArray(raw)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Profile(o.getString("id"), o.getString("name"), o.getString("emoji"), o.getInt("color"))
        }.toMutableList()
    }

    fun save(ctx: Context, list: List<Profile>) {
        val a = JSONArray()
        for (p in list) a.put(JSONObject().put("id", p.id).put("name", p.name).put("emoji", p.emoji).put("color", p.color))
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    fun current(ctx: Context): Profile {
        val id = prefs(ctx).getString("current", FIRST)
        val list = all(ctx)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun setCurrent(ctx: Context, id: String) {
        prefs(ctx).edit().putString("current", id).apply()
        cachedSuffix = null
    }

    fun add(ctx: Context, name: String): Profile {
        val list = all(ctx)
        val n = list.size
        val p = Profile("p" + System.currentTimeMillis().toString(36), name, EMOJIS[n % EMOJIS.size], COLORS[n % COLORS.size])
        list += p
        save(ctx, list)
        return p
    }

    fun remove(ctx: Context, id: String) {
        if (id == FIRST) return
        save(ctx, all(ctx).filter { it.id != id })
        if (current(ctx).id == id) setCurrent(ctx, FIRST)
        prefs(ctx).edit().remove("pin_$id").apply()
        ctx.getSharedPreferences("tmdb", Context.MODE_PRIVATE).edit().remove("mylist_$id").apply()
        for (f in listOf("theme", "home")) ctx.deleteSharedPreferences(f + "_" + id)
        // Its Trakt sign-in goes too (it was kept forever and could never be signed out of again).
        Trakt.forgetProfile(ctx, id)
    }

    /** Appended to per-profile preference names ("" for the first profile). Cached: read on hot paths. */
    fun suffix(ctx: Context): String = cachedSuffix ?: (current(ctx).id.let { if (it == FIRST) "" else "_$it" }).also { cachedSuffix = it }

    // ---- first-run setup ----

    /** False until the setup wizard has run once; until then the first profile has no real name yet. */
    fun isSetUp(ctx: Context) = prefs(ctx).getBoolean("setup_done", false)

    fun markSetUp(ctx: Context) = prefs(ctx).edit().putBoolean("setup_done", true).apply()

    // ---- optional per-profile PIN ----

    fun hasPin(ctx: Context, id: String) = prefs(ctx).contains("pin_$id")

    /** Saves [pin] for profile [id] as "salt:sha256(salt+pin)", or removes the lock when null. */
    fun setPin(ctx: Context, id: String, pin: String?) {
        val e = prefs(ctx).edit()
        if (pin == null) e.remove("pin_$id")
        else {
            val salt = hex(ByteArray(8).also { SecureRandom().nextBytes(it) })
            e.putString("pin_$id", salt + ":" + hash(salt, pin))
        }
        e.apply()
    }

    /**
     * True when [pin] unlocks profile [id] (or it has no PIN). After [FREE_TRIES] wrong PINs in a row
     * every further try waits a growing while ([pinWaitSeconds]); a try during the wait always fails.
     * A 4-digit PIN keeps profiles apart; it isn't a parental lock.
     */
    fun checkPin(ctx: Context, id: String, pin: String): Boolean {
        val stored = prefs(ctx).getString("pin_$id", null) ?: return true
        if (pinWaitSeconds(ctx, id) > 0) return false
        val salt = stored.substringBefore(':')
        val ok = java.security.MessageDigest.isEqual(hash(salt, pin).toByteArray(), stored.substringAfter(':').toByteArray())
        val p = prefs(ctx)
        if (ok) p.edit().remove("pinFails_$id").remove("pinWait_$id").apply()
        else {
            val fails = p.getInt("pinFails_$id", 0) + 1
            val wait = if (fails < FREE_TRIES) 0L else minOf(30_000L shl minOf(fails - FREE_TRIES, 5), 15 * 60_000L)
            p.edit().putInt("pinFails_$id", fails).putLong("pinWait_$id", if (wait > 0) System.currentTimeMillis() + wait else 0L).apply()
        }
        return ok
    }

    /** Seconds before profile [id]'s PIN can be tried again (0 = now). */
    fun pinWaitSeconds(ctx: Context, id: String): Int {
        val until = prefs(ctx).getLong("pinWait_$id", 0L)
        val left = until - System.currentTimeMillis()
        // Never longer than the longest wait, even if the clock was moved.
        return if (left <= 0) 0 else ((minOf(left, 15 * 60_000L) + 999) / 1000).toInt()
    }

    private const val FREE_TRIES = 5

    private fun hash(salt: String, pin: String) = hex(MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
