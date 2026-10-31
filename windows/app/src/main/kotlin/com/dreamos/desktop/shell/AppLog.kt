package com.dreamos.desktop.shell

import com.dreamos.desktop.core.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's log file: everything Dream OS writes to stdout and stderr (core's Log, Dream Player's PlayerLog, Dream
 * Core, the scrapers, uncaught exceptions with their stack traces) also goes to `%APPDATA%\Dream OS\logs\dream.log`, so
 * a bug the viewer saw can be read afterwards. Rolled at [MAX_BYTES] into dream.1.log and dream.2.log: at most 15 MB on
 * disk. Each line gets the date and time, and keys and tokens are masked (core's [Log.redact]) before it is written.
 */
object AppLog {
    const val MAX_BYTES = 5L * 1024 * 1024
    private const val KEEP = 2

    /** `-Ddream.log.dir`, else `DREAM_LOG_DIR` (test and dev runs: never the viewer's logs), else the folder above. */
    val dir: File by lazy {
        System.getProperty("dream.log.dir")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
        System.getenv("DREAM_LOG_DIR")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
        val base = System.getenv("APPDATA")?.let { File(it, "Dream OS") } ?: File(System.getProperty("user.home"), ".dream-os")
        File(base, "logs")
    }
    val file: File get() = File(dir, "dream.log")

    private val lock = Any()
    private var out: FileOutputStream? = null
    private var size = 0L
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private var installed = false

    /** Once, first thing in main: stdout and stderr keep going to the console and now also to the file. */
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            dir.mkdirs()
            open()
            System.setOut(PrintStream(Tee(System.out), true, Charsets.UTF_8))
            System.setErr(PrintStream(Tee(System.err), true, Charsets.UTF_8))
            val v = System.getProperty("dream.version") ?: "dev"
            line("----- Dream OS $v (${System.getProperty("dream.channel") ?: "DEV"}) started, Java ${System.getProperty("java.version")}, " +
                "${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        }
        runCatching { nativeCrashReports() }
    }

    /**
     * A crash inside native code (libmpv, Skia, a driver) skips every Java handler: the JVM writes an hs_err report
     * and the process ends. The packaged app asks for it in `<install folder>\logs` (`-XX:ErrorFile` in
     * app/build.gradle.kts: the launcher can't expand %APPDATA%); when that folder isn't writable the JVM falls back to
     * the working folder, then %TEMP%. At the next start, any report of Dream OS's from those places moves here, next
     * to dream.log, and the log says what crashed.
     */
    private fun nativeCrashReports() {
        val app = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }
        app?.let { File(it, "logs").mkdirs() }
        val places = listOfNotNull(app?.let { File(it, "logs") }, File(System.getProperty("user.dir")), File(System.getProperty("java.io.tmpdir")))
            .map { it.absoluteFile }.distinct().filter { it != dir.absoluteFile }
        for (place in places) {
            val reports = place.listFiles { f -> f.isFile && f.name.startsWith("hs_err") && f.name.endsWith(".log") } ?: continue
            for (f in reports) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (!text.contains("com.dreamos") && !text.contains("Dream OS")) continue
                val to = File(dir, f.name)
                val moved = if (f.renameTo(to)) to else runCatching { f.copyTo(to, true); f.delete(); to }.getOrDefault(f)
                line("E DreamOS/Crash: the run before ended in a native crash (${summary(text)}); the JVM's report: ${moved.path}")
            }
        }
    }

    /** The hs_err report's own short answer: the error, the frame it happened in and the thread. */
    private fun summary(report: String): String {
        val lines = report.lines()
        val error = lines.firstOrNull { it.startsWith("#  EXCEPTION_") || it.startsWith("#  Internal Error") || it.startsWith("# There is insufficient memory") }
        val frame = lines.indexOfFirst { it.startsWith("# Problematic frame:") }.takeIf { it >= 0 }?.let { lines.getOrNull(it + 1) }
        val thread = lines.firstOrNull { it.startsWith("Current thread") }
        return listOfNotNull(error, frame, thread).joinToString(" | ") { it.removePrefix("#").trim() }.ifEmpty { "no summary in the report" }
    }

    /** Writes [t]'s whole stack trace under [what] (uncaught exceptions). */
    fun error(what: String, t: Throwable) {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        System.err.println("E DreamOS/Crash: $what\n$sw")
    }

    /**
     * One line straight to the file, for when [error] itself failed (out of memory, a stack overflow): no stack
     * trace, no console, as little memory as a line takes.
     */
    fun lastWords(text: String) = line("E DreamOS/Crash: $text")

    private fun open() {
        val f = file
        size = if (f.isFile) f.length() else 0L
        out = FileOutputStream(f, true)
    }

    private fun roll() {
        out?.close()
        for (i in KEEP downTo 1) {
            val from = if (i == 1) file else File(dir, "dream.${i - 1}.log")
            val to = File(dir, "dream.$i.log")
            if (from.isFile) { to.delete(); from.renameTo(to) }
        }
        open()
    }

    private fun line(text: String) = synchronized(lock) {
        val o = out ?: return
        try {
            val bytes = "${LocalDateTime.now().format(stamp)} ${Log.redact(text)}\r\n".toByteArray(Charsets.UTF_8)
            if (size + bytes.size > MAX_BYTES) roll()
            out?.write(bytes)
            out?.flush()
            size += bytes.size
        } catch (e: Exception) {
            // A full disk or a locked file never stops the app; the console still has the line.
            runCatching { o.close() }
            out = null
        }
    }

    /** Passes bytes on to the console unchanged and hands each whole line to the file. */
    private class Tee(private val console: OutputStream) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) {
            console.write(b)
            synchronized(buf) {
                if (b == '\n'.code) flushLine() else buf.write(b)
            }
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            console.write(b, off, len)
            synchronized(buf) {
                for (i in off until off + len) {
                    val c = b[i].toInt()
                    if (c == '\n'.code) flushLine() else buf.write(c)
                }
            }
        }
        override fun flush() = console.flush()
        private fun flushLine() {
            val s = buf.toString(Charsets.UTF_8).trimEnd('\r')
            buf.reset()
            if (s.isNotEmpty()) line(s)
        }
    }
}
