package com.dreamos.player.engine

import java.io.File
import java.io.PrintWriter
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Dream Player's log ("DreamPlayer" tag). Lines go to stderr and to `player.log` in [dir] (rolled at 4 MB). Stream
 * URLs are cut to their host before they're written.
 */
object PlayerLog {
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val MAX_BYTES = 4L * 1024 * 1024

    /**
     * The logs folder: `-Ddream.log.dir`, else `DREAM_LOG_DIR`, else `%APPDATA%\Dream OS\logs`. Dev runners and tests
     * set one of the first two, so they never write into the viewer's own player.log. Read at the first line, so a run
     * that sets the property early in main (the app's offscreen checks) is followed.
     */
    val dir: File by lazy {
        System.getProperty("dream.log.dir")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
        System.getenv("DREAM_LOG_DIR")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
        val base = System.getenv("APPDATA")?.let { File(it, "Dream OS") } ?: File(System.getProperty("user.home"), ".dream-os")
        File(base, "logs")
    }
    private val file: File? by lazy { runCatching { dir.apply { mkdirs() }.let { File(it, "player.log") } }.getOrNull() }
    private var out: PrintWriter? = null

    var verbose: Boolean = System.getProperty("dream.player.verbose") == "true"

    fun d(msg: String) { if (verbose) write("D", msg) }
    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)

    /** An error with its type, message and where it came from (the top frames: enough to find it, short for the log). */
    fun e(msg: String, t: Throwable? = null) = write("E", msg + (t?.let { ": ${describe(it)}" } ?: ""))

    /** [t] as "Type message at a.b(F.kt:1) < c.d(G.kt:2) ..." with its causes, on one line. */
    fun describe(t: Throwable, frames: Int = 6): String = generateSequence(t) { c -> c.cause?.takeIf { it !== c } }.take(3)
        .joinToString(" caused by ") { c ->
            "${c.javaClass.name} ${c.message.orEmpty()}".trim() +
                c.stackTrace.take(frames).joinToString("") { f -> " < ${f.className.substringAfterLast('.')}.${f.methodName}(${f.fileName}:${f.lineNumber})" }
        }

    /** Every thread's name, state and top [frames] frames, for a log line about something stuck. */
    fun threadDump(frames: Int = 16): String = Thread.getAllStackTraces().entries.sortedBy { it.key.name }.joinToString("\n") { (t, st) ->
        "  \"${t.name}\" ${t.state}" + st.take(frames).joinToString("") { "\n      at $it" }
    }

    /** Cuts a URL to scheme + host so tokens and file names never reach a log. */
    fun host(url: String): String = runCatching {
        val u = java.net.URI(url.substringBefore('|'))
        if (u.host == null) url.take(24) else "${u.scheme}://${u.host}/..."
    }.getOrDefault("(link)")

    @Synchronized
    private fun write(level: String, msg: String) {
        val line = "${LocalTime.now().format(stamp)} $level DreamPlayer: $msg"
        System.err.println(line)
        val f = file ?: return
        try {
            if (out == null || f.length() > MAX_BYTES) {
                out?.close()
                if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "player.1.log"))
                out = PrintWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(f, true), Charsets.UTF_8), true)
            }
            out?.println(line)
        } catch (_: Exception) { }
    }
}
