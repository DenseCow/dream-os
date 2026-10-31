package com.dreamos.desktop.core.platform

import com.dreamos.desktop.core.playback.SecretStore
import com.dreamos.desktop.core.store.DreamStore
import com.dreamos.desktop.core.store.Prefs
import com.dreamos.desktop.core.util.Log
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.util.Base64

/**
 * Turns a secret into bytes only this PC's user can read back, and back again. Windows: DPAPI ([DpapiCipher]); a Mac
 * gets the Keychain later. Never logs a value.
 */
interface SecretCipher {
    /** False when the values are stored as they are (no OS protection on this platform yet). */
    val protects: Boolean
    fun seal(plain: ByteArray, context: String): ByteArray
    /** Null when the bytes can't be opened here (another Windows user, another PC, damaged). */
    fun open(sealed: ByteArray, context: String): ByteArray?
}

/**
 * Windows DPAPI (CryptProtectData through JNA), current-user scope: only the signed-in Windows user on this PC can
 * open what it sealed, with no prompt. [context] goes in as extra entropy, so a value copied under another key name
 * doesn't open.
 */
object DpapiCipher : SecretCipher {
    override val protects = true
    private const val FLAGS = WinCrypt.CRYPTPROTECT_UI_FORBIDDEN

    private fun entropy(context: String) = "DreamOS|$context".toByteArray(Charsets.UTF_8)

    override fun seal(plain: ByteArray, context: String): ByteArray =
        Crypt32Util.cryptProtectData(plain, entropy(context), FLAGS, "Dream OS", null)

    override fun open(sealed: ByteArray, context: String): ByteArray? = try {
        Crypt32Util.cryptUnprotectData(sealed, entropy(context), FLAGS, null)
    } catch (e: Throwable) {
        null
    }
}

/** Mac / Linux until they get the Keychain / Secret Service: stored as they are (said in [protects]). */
object PlainCipher : SecretCipher {
    override val protects = false
    override fun seal(plain: ByteArray, context: String) = plain
    override fun open(sealed: ByteArray, context: String) = sealed
}

/**
 * Secrets at rest (debrid tokens, Trakt sign-ins): each value sealed with [cipher] and kept base64 in the settings
 * file [file] (`v1:<base64>`), written behind like every settings file. Implements Dream Core's [SecretStore], so the
 * app hands it to `Playback.create` as is.
 *
 * Worst case: a few dozen values of a few hundred bytes (DPAPI adds ~200 bytes each): under 50 KB.
 */
class SealedSecretStore(private val file: Prefs, private val cipher: SecretCipher = Platform.current.secretCipher) : SecretStore {

    override fun get(key: String): String? {
        val stored = file.getString(key) ?: return null
        val b64 = stored.removePrefix(PREFIX)
        if (b64 == stored) return null
        val bytes = try { Base64.getDecoder().decode(b64) } catch (e: IllegalArgumentException) { return null }
        val plain = cipher.open(bytes, context(key))
        if (plain == null) Log.w("Secrets", "${file.name}: a saved value can't be opened on this PC (sign in again)")
        return plain?.toString(Charsets.UTF_8)
    }

    override fun put(key: String, value: String) {
        file.putString(key, PREFIX + Base64.getEncoder().encodeToString(cipher.seal(value.toByteArray(Charsets.UTF_8), context(key))))
    }

    override fun remove(key: String) = file.remove(key)

    /** Every key with a value (moving or wiping a store). */
    val keys: Set<String> get() = file.keys

    fun clear() = file.clear()

    /** The key name seals with the value: a value moved under another key doesn't open. */
    private fun context(key: String) = key

    /**
     * Moves every string in the plain settings file [legacy] in here once and deletes that file (an older build kept
     * these values unprotected). Values already here win. Returns how many moved.
     */
    fun migrateFrom(store: DreamStore, legacy: String): Int {
        if (!store.exists(legacy)) return 0
        val old = store.file(legacy)
        var moved = 0
        for (k in old.keys) {
            val v = old.getString(k) ?: continue
            if (file.getString(k) == null) { put(k, v); moved++ }
        }
        store.delete(legacy)
        Log.i("Secrets", "moved $moved saved sign-ins from $legacy into ${file.name}" + if (cipher.protects) " (protected)" else "")
        return moved
    }

    companion object {
        private const val PREFIX = "v1:"
    }
}

/** The app's secret stores. */
object Secrets {
    /**
     * Dream Core's tokens (debrid accounts), in `secrets_playback.json`. The shell's earlier stand-in kept them plain in
     * `playback_accounts.json`: moved over and deleted on first use.
     */
    val playback: SealedSecretStore by lazy {
        val store = DreamStore.shared
        SealedSecretStore(store.file("secrets_playback")).also { it.migrateFrom(store, "playback_accounts") }
    }

    /** A profile's own secrets (`secrets[_<id>].json`; removed with the profile). */
    fun profile(profileId: String = DreamStore.currentProfile): SealedSecretStore =
        SealedSecretStore(DreamStore.shared.profileFile("secrets", profileId))
}
