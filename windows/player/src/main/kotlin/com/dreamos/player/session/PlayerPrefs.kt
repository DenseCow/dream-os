package com.dreamos.player.session

import com.dreamos.desktop.core.content.ContentPrefs
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.store.Prefs
import com.dreamos.player.engine.PlayerLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The viewer's player choices (fire-home PlayerPrefs), per profile in core's settings store:
 *
 * - `player[_<id>]`, the file core's ContentPrefs already reads: `audioLang` (ISO 639-1, as core keeps it), `subLang`
 *   ("off" or a code), `boost`, `volume`.
 * - `pages[_<id>]`, what Settings › Playback shows, so a change in either place shows in both: `subs.size`,
 *   `subs.style`, `subs.position` (the rows' words) and `play.skipIntros`.
 *
 * Reads come from the store's memory; writes go behind. Round 1's single `%APPDATA%\Dream OS\player.json` becomes the
 * first profile's choices, once.
 */
data class PlayerPrefs(
    /** 0..3 = S / M / L / XL. */
    val subSize: Int = 1,
    /** 0 Classic, 1 Boxed, 2 Yellow. */
    val subStyle: Int = 0,
    /** 0 Low, 1 Standard, 2 Raised. */
    val subPos: Int = 1,
    /** null: no choice yet; ISO 639-1 ("en"), or "pob" for Brazilian Portuguese. */
    val audioLang: String? = null,
    /** null: no choice; [OFF]; or a language code like [audioLang]. */
    val subLang: String? = null,
    /** 0..3 = Off / +25% / +50% / +100%. */
    val boost: Int = 0,
    val volume: Int = 100,
    /** Skip Intros Automatically (Settings › Playback and the player's Playback sheet). */
    val autoSkipIntros: Boolean = false,
) {
    val subScale get() = SIZE_SCALE[subSize.coerceIn(0, 3)]
    val subBottom get() = POS_BOTTOM[subPos.coerceIn(0, 2)]
    val boostDb get() = BOOST_DB[boost.coerceIn(0, 3)]

    companion object {
        const val OFF = "off"
        val SIZE_SCALE = doubleArrayOf(0.78, 1.0, 1.25, 1.5)
        val SIZE_LABELS = listOf("S", "M", "L", "XL")
        val STYLE_LABELS = listOf("Classic", "Boxed", "Yellow")
        val POS_LABELS = listOf("Low", "Standard", "Raised")
        val POS_BOTTOM = doubleArrayOf(0.045, 0.08, 0.16)
        val BOOST_LABELS = listOf("Off", "+25%", "+50%", "+100%")
        val BOOST_DB = intArrayOf(0, 3, 6, 10)

        /** Settings' words for the same choices (SettingsPage's cycle rows). */
        private val SIZE_WORDS = listOf("Small", "Medium", "Large", "Extra Large")

        fun load(): PlayerPrefs {
            migrate()
            val pl = PlayerSettings.player()
            return PlayerPrefs(
                subSize = SIZE_WORDS.indexOf(PlayerSettings.text("subs.size")).takeIf { it >= 0 } ?: 1,
                subStyle = STYLE_LABELS.indexOf(PlayerSettings.text("subs.style")).takeIf { it >= 0 } ?: 0,
                subPos = POS_LABELS.indexOf(PlayerSettings.text("subs.position")).takeIf { it >= 0 } ?: 1,
                audioLang = pl.getString("audioLang")?.let { ContentPrefs.normLang(it) },
                subLang = pl.getString("subLang")?.let { if (it == OFF) OFF else ContentPrefs.normLang(it) },
                boost = pl.getInt("boost", 0).coerceIn(0, 3),
                volume = pl.getInt("volume", 100).coerceIn(0, 100),
                autoSkipIntros = PlayerSettings.flag("play.skipIntros", false),
            )
        }

        /** Saves what changed since [before] (the volume wheel saves often; the pages' file is touched only when needed). */
        fun save(p: PlayerPrefs, before: PlayerPrefs? = null) {
            runCatching {
                PlayerSettings.player().edit {
                    putString("audioLang", p.audioLang)
                    putString("subLang", p.subLang)
                    putInt("boost", p.boost)
                    putInt("volume", p.volume)
                }
                if (before == null || before.subSize != p.subSize || before.subStyle != p.subStyle || before.subPos != p.subPos ||
                    before.autoSkipIntros != p.autoSkipIntros
                ) PlayerSettings.pages().edit {
                    putString("subs.size", SIZE_WORDS[p.subSize.coerceIn(0, 3)])
                    putString("subs.style", STYLE_LABELS[p.subStyle.coerceIn(0, 2)])
                    putString("subs.position", POS_LABELS[p.subPos.coerceIn(0, 2)])
                    putBoolean("play.skipIntros", p.autoSkipIntros)
                }
            }.onFailure { PlayerLog.w("Prefs: not saved (${it.message})") }
        }

        @Volatile private var migrated = false

        private fun migrate() {
            if (migrated) return
            migrated = true
            val old = System.getenv("APPDATA")?.let { File(File(it, "Dream OS"), "player.json") }?.takeIf { it.isFile } ?: return
            runCatching {
                val o = Json.parseToJsonElement(old.readText()).jsonObject
                fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                fun int(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
                // The first profile's files have no suffix.
                val pl = DreamStore.shared.file("player")
                val pg = DreamStore.shared.file("pages")
                pl.edit {
                    if (!pl.contains("audioLang")) str("audioLang")?.let { putString("audioLang", ContentPrefs.normLang(it)) }
                    if (!pl.contains("subLang")) str("subLang")?.let { putString("subLang", if (it == OFF) OFF else ContentPrefs.normLang(it)) }
                    if (!pl.contains("boost")) int("boost")?.let { putInt("boost", it) }
                    if (!pl.contains("volume")) int("volume")?.let { putInt("volume", it) }
                }
                pg.edit {
                    if (!pg.contains("subs.size")) int("subSize")?.let { putString("subs.size", SIZE_WORDS[it.coerceIn(0, 3)]) }
                    if (!pg.contains("subs.style")) int("subStyle")?.let { putString("subs.style", STYLE_LABELS[it.coerceIn(0, 2)]) }
                    if (!pg.contains("subs.position")) int("subPos")?.let { putString("subs.position", POS_LABELS[it.coerceIn(0, 2)]) }
                    if (!pg.contains("play.skipIntros")) (o["autoSkipIntros"] as? JsonPrimitive)?.booleanOrNull?.let { putBoolean("play.skipIntros", it) }
                }
                old.renameTo(File(old.parentFile, "player.json.moved"))
                PlayerLog.i("Prefs: player.json moved into the first profile's settings")
            }.onFailure { PlayerLog.w("Prefs: player.json not moved (${it.message})") }
        }
    }
}

/**
 * The per-profile settings files Dream Player reads, for the profile in use now (core's DreamStore switches them when
 * the viewer changes profile). The pages write toggles as booleans; a "true" / "false" string reads the same.
 */
object PlayerSettings {
    fun player(): Prefs = DreamStore.shared.profileFile("player")
    fun pages(): Prefs = DreamStore.shared.profileFile("pages")

    fun flag(key: String, default: Boolean): Boolean = runCatching {
        val v = pages().getJson(key) as? JsonPrimitive ?: return default
        v.booleanOrNull ?: v.contentOrNull?.toBooleanStrictOrNull() ?: default
    }.getOrDefault(default)

    fun text(key: String): String? = runCatching { (pages().getJson(key) as? JsonPrimitive)?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Settings › Playback › Autoplay Next Episode (on by default). */
    val autoplayNext: Boolean get() = flag("play.autoplay", true)
    /** Settings › Playback › Are You Still Watching? (on by default). */
    val stillWatching: Boolean get() = flag("play.stillWatching", true)
}
