package com.firehome.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-time carry-over of Dream OS's private data across a reinstall (the switch from the debug key
 * to the release signing key forces an uninstall, which wipes it). Developer only: driven over adb
 * through [PingReceiver] (DUMP-protected), see tools/migrate-signing.sh.
 *
 *  - export: shared_prefs/, files/, databases/ and no_backup/ zipped and sealed under the key adb
 *    passes (64 hex digits), written to the app's external files folder as [FILE].
 *  - import: the same file checked and opened with the same key (any change to it fails the check),
 *    unpacked over this install's data, the file deleted, and the process ended so everything reloads.
 *
 * Sealing streams (AES-256-CTR, then HMAC-SHA256 over the header and ciphertext), so a large
 * files/ folder never has to fit in memory: Android's GCM buffers the whole message, which ran the
 * stick out of memory on an ~80 MB export. Both keys are derived from the one adb passes.
 *
 * The key travels only in the adb broadcast, so the file on shared storage reveals nothing.
 */
object DataMigration {
    private const val TAG = "FireHome"
    const val FILE = "dreamos-migrate.bin"
    private val DIRS = listOf("shared_prefs", "files", "databases", "no_backup")
    private const val MAGIC = "DOSM2"
    private const val MAC_LEN = 32

    private class Keys(val enc: SecretKeySpec, val mac: SecretKeySpec)

    private fun keys(hex: String?): Keys? {
        val h = hex?.trim()?.lowercase() ?: return null
        if (!h.matches(Regex("[0-9a-f]{64}"))) return null
        val master = ByteArray(32) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun derive(label: String) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(master, "HmacSHA256")); doFinal(label.toByteArray())
        }
        return Keys(SecretKeySpec(derive("dreamos-migrate enc"), "AES"), SecretKeySpec(derive("dreamos-migrate mac"), "HmacSHA256"))
    }

    private fun file(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FILE)

    /** Feeds every byte written through to [mac] as well. */
    private class MacOut(out: OutputStream, private val mac: Mac) : FilterOutputStream(out) {
        override fun write(b: Int) { mac.update(b.toByte()); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { mac.update(b, off, len); out.write(b, off, len) }
    }

    /** Stops [limit] bytes in, so the trailing tag isn't read as ciphertext. */
    private class Limited(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    /** Writes the sealed export (to [out]; Move to a New Stick uses its own file). True when it was written. */
    fun export(ctx: Context, keyHex: String?, out: File = file(ctx)): Boolean {
        val k = keys(keyHex) ?: run { Log.w(TAG, "Migrate: export needs --es key <64 hex digits>"); return false }
        val tmp = File(out.path + ".tmp")
        return try {
            out.parentFile?.mkdirs()
            val data = ctx.applicationContext.dataDir
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val mac = Mac.getInstance("HmacSHA256").apply { init(k.mac) }
            var count = 0
            val sizes = linkedMapOf<String, Long>()
            val self = setOf(tmp.canonicalPath, out.canonicalPath)
            tmp.outputStream().buffered().use { raw ->
                val header = MAGIC.toByteArray() + iv
                raw.write(header); mac.update(header)
                val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k.enc, IvParameterSpec(iv)) }
                // CipherOutputStream.close() would close raw before the tag is written, so it's flushed by hand.
                val sealed = CipherOutputStream(MacOut(NoClose(raw), mac), cipher)
                ZipOutputStream(sealed).use { z ->
                    for (d in DIRS) {
                        val root = File(data, d)
                        if (!root.isDirectory) continue
                        // Never the export itself (with no external storage it is written inside files/).
                        root.walkTopDown().filter { it.isFile && it.canonicalPath !in self }.forEach { f ->
                            z.putNextEntry(ZipEntry(f.relativeTo(data).invariantSeparatorsPath))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                            count++
                            sizes[d] = (sizes[d] ?: 0L) + f.length()
                        }
                    }
                }
                raw.write(mac.doFinal())
            }
            if (!tmp.renameTo(out)) { out.delete(); if (!tmp.renameTo(out)) throw IllegalStateException("couldn't move ${tmp.name} into place") }
            Log.w(TAG, "Migrate: exported $count files (${out.length()} bytes; ${sizes.entries.joinToString { "${it.key} ${it.value / 1024} KB" }}) to ${out.path}")
            true
        } catch (e: Throwable) {
            tmp.delete()
            Log.w(TAG, "Migrate: export failed: $e")
            false
        }
    }

    /** Keeps [ZipOutputStream.close] from closing the file before the tag is appended. */
    private class NoClose(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        override fun close() { out.flush() }
    }

    /**
     * After a restore from another Fire TV, setup carries on from the start (everything pre-filled) instead of
     * jumping to Home: a marker outside the restored folders, read once by [consumeContinueSetup] on the next start.
     */
    private fun continueMarker(ctx: Context) = File(ctx.applicationContext.noBackupFilesDir, "continue_setup")

    /** True once after a restore that should continue setup; also clears "setup finished". Call early in startup. */
    fun consumeContinueSetup(ctx: Context): Boolean {
        val f = continueMarker(ctx)
        if (!f.exists()) return false
        f.delete()
        ctx.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE).edit().putBoolean("setup_done", false).commit()
        SetupGate.clearStep(ctx) // the old stick's place in its setup: this one starts at the beginning
        Log.w(TAG, "Migrate: restored from another Fire TV, setup continues")
        return true
    }

    /** Restores a sealed export over this install's data, then ends the process. False when nothing was restored. */
    fun import(ctx: Context, keyHex: String?, src: File = file(ctx), continueSetup: Boolean = false): Boolean {
        val k = keys(keyHex) ?: run { Log.w(TAG, "Migrate: import needs --es key <64 hex digits>"); return false }
        if (!src.isFile) { Log.w(TAG, "Migrate: no ${src.path}"); return false }
        val m = MAGIC.toByteArray()
        val headerLen = m.size + 16
        val bodyLen = src.length() - headerLen - MAC_LEN
        val iv: ByteArray
        // Check the whole file before unpacking anything.
        try {
            if (bodyLen < 0) throw IllegalStateException("not a migration file")
            val mac = Mac.getInstance("HmacSHA256").apply { init(k.mac) }
            val header = ByteArray(headerLen)
            val tag = ByteArray(MAC_LEN)
            src.inputStream().buffered().use { input ->
                if (input.readFully(header) != headerLen || !header.copyOfRange(0, m.size).contentEquals(m)) throw IllegalStateException("not a migration file")
                mac.update(header)
                val body = Limited(input, bodyLen)
                val buf = ByteArray(64 * 1024)
                while (true) { val n = body.read(buf); if (n < 0) break; mac.update(buf, 0, n) }
                if (input.readFully(tag) != MAC_LEN) throw IllegalStateException("file cut short")
            }
            if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw SecurityException("check failed")
            iv = header.copyOfRange(m.size, headerLen)
        } catch (e: Throwable) {
            Log.w(TAG, "Migrate: import refused (wrong key or damaged file): $e")
            return false
        }
        val data = ctx.applicationContext.dataDir
        val base = data.canonicalPath + File.separator
        var count = 0
        try {
            src.inputStream().buffered().use { input ->
                if (input.readFully(ByteArray(headerLen)) != headerLen) throw IllegalStateException("file cut short")
                val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, k.enc, IvParameterSpec(iv)) }
                ZipInputStream(CipherInputStream(Limited(input, bodyLen), cipher)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val top = e.name.substringBefore('/')
                        val out = File(data, e.name)
                        if (top !in DIRS || !out.canonicalPath.startsWith(base)) { Log.w(TAG, "Migrate: skipped ${e.name}"); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { z.copyTo(it) }
                        count++
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Migrate: import failed after $count files: $e")
            return false
        }
        src.delete()
        if (continueSetup) try { continueMarker(ctx).apply { parentFile?.mkdirs() }.writeText("1") } catch (e: Exception) {}
        Log.w(TAG, "Migrate: imported $count files; restarting so they load")
        // In-memory preference caches would otherwise write the fresh install's values back over them.
        android.os.Process.killProcess(android.os.Process.myPid())
        return true
    }

    private fun InputStream.readFully(b: ByteArray): Int {
        var n = 0
        while (n < b.size) { val r = read(b, n, b.size - n); if (r < 0) break; n += r }
        return n
    }
}
