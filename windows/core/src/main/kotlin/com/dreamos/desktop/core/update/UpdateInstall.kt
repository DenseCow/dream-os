package com.dreamos.desktop.core.update

import com.dreamos.desktop.core.model.DreamError
import com.dreamos.desktop.core.model.UpdateInfo
import com.dreamos.desktop.core.net.Net
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.util.Log
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Installing an update on Windows: download the release's `DreamOS-Windows-Setup.exe` into `<cache>\update`, check it
 * against the release's SHA-256 (no checksum, no install: a file we can't verify never runs), then start it silently
 * ([SETUP_ARGS]: a progress window, no questions) as its own process; the app quits right after ([launch] returns
 * true) so the setup can replace it, and the setup opens Dream OS again when it's done. Only the viewer's Install Now
 * runs this.
 */
object UpdateInstall {
    private const val READ_MS = 120_000L
    /** Inno Setup: progress only, no message boxes, no restart, close a Dream OS still holding its files. */
    val SETUP_ARGS = listOf("/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS")

    /** Where the installer is saved. */
    fun folder(): Path = Files.createDirectories(Platform.current.cacheDir.resolve("update"))

    /**
     * Downloads [info]'s installer and verifies it; [progress] gets 0..1 as it arrives (when the size is known).
     * Throws [DreamError] with a plain sentence when it can't. Blocking: IO threads only.
     */
    fun download(info: UpdateInfo, progress: (Float) -> Unit = {}): Path {
        val url = info.downloadUrl
        val want = info.sha256?.lowercase()
        if (!url.startsWith("https://")) throw DreamError("This update isn't available for Windows yet.")
        if (want == null) throw DreamError("This update can't be checked, so it wasn't installed. Try again later.")
        val dir = folder()
        val file = dir.resolve(UpdateCheck.ASSET)
        // Already downloaded and intact: no second download.
        if (Files.exists(file) && sha256(file) == want) return file
        val part = dir.resolve(UpdateCheck.ASSET + ".part")
        try {
            val req = HttpRequest.newBuilder(Net.uri(url)).timeout(Duration.ofMillis(READ_MS)).header("User-Agent", Net.USER_AGENT).GET().build()
            val resp = Net.client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299) throw DreamError("Couldn't download the update. Try again in a moment.")
            val total = resp.headers().firstValueAsLong("Content-Length").orElse(info.sizeBytes)
            val md = MessageDigest.getInstance("SHA-256")
            resp.body().use { input ->
                Files.newOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        got += n
                        if (total > 0) progress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (hex(md.digest()) != want) {
                Files.deleteIfExists(part)
                Log.w("Update", "downloaded installer doesn't match the published SHA-256; deleted")
                throw DreamError("The update didn't download correctly. Try again.")
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING)
            progress(1f)
            return file
        } catch (e: DreamError) {
            throw e
        } catch (e: Exception) {
            try { Files.deleteIfExists(part) } catch (x: Exception) {}
            throw DreamError("Couldn't download the update. Check your connection and try again.", cause = e)
        }
    }

    /**
     * Starts the setup [file] silently ([SETUP_ARGS]) through `cmd /c start`, so it lives on after Dream OS quits.
     * True when it started: the app should quit now. False off Windows or when it couldn't start.
     */
    fun launch(file: Path): Boolean {
        if (Platform.current.osName != "Windows" || !Files.exists(file)) return false
        return try {
            ProcessBuilder(listOf("cmd", "/c", "start", "\"\"", file.toAbsolutePath().toString()) + SETUP_ARGS)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            Log.i("Update", "installer started; Dream OS closes")
            true
        } catch (e: Exception) {
            Log.w("Update", "installer didn't start: ${e.javaClass.simpleName}")
            false
        }
    }

    fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return hex(md.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
