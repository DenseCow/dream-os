package com.dreamos.desktop.startup

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Launch timings, measured from the moment Windows started the process (the launcher exe), so the JVM's own start is
 * included. Marks cost a list add; nothing is written unless asked: `DREAM_TRACE=<file>` (a real window) or the
 * headless probe (`DREAM_PROBE=<file>`, [StartupProbe]) writes them as "name  ms" lines.
 */
object StartupTrace {
    /** Process start in epoch ms (the launcher exe's start, before the JVM loads). */
    val processStart: Long = ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(System.currentTimeMillis())
    private val marks = CopyOnWriteArrayList<Pair<String, Long>>()
    private val once = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Milliseconds since the process started. */
    fun now(): Long = System.currentTimeMillis() - processStart

    /** Records [name] at this moment (the first time only for a name). */
    fun mark(name: String) { if (once.add(name)) marks += name to now() }

    /** Times [block] as "<name>" (its end) and "<name> took" (its length). */
    inline fun <T> span(name: String, block: () -> T): T {
        val t = System.nanoTime()
        try { return block() } finally {
            took(name, (System.nanoTime() - t) / 1_000_000)
            mark(name)
        }
    }

    private val lengths = CopyOnWriteArrayList<Pair<String, Long>>()
    fun took(name: String, ms: Long) { lengths += name to ms }

    fun lines(): List<String> =
        listOf("vm: " + System.getProperty("java.vm.info").orEmpty(), "") + marks.sortedBy { it.second }.map { (n, t) -> "%-34s %6d ms".format(n, t) } +
            listOf("", "-- lengths --") + lengths.map { (n, t) -> "%-34s %6d ms".format(n, t) }

    /** One line in the log (dream.log) when the intro ends: the launch's phases, and whether the archive was used. */
    fun logSummary() {
        val at = marks.toMap()
        fun t(name: String) = at[name]?.let { "$it" } ?: "-"
        com.dreamos.desktop.core.util.Log.i("Startup", "main ${t("main")} ms, services ${t("services")}, first frame ${t("frame: first")}, " +
            "Home ready ${t("home: ready")}, intro gone ${t("intro: gone")} (from the process start; startup archive " +
            (if (!StartArchive.inUse) "none" else if (StartArchive.full) "full" else "Java's own classes") + ")")
    }

    /** The real window's trace (`DREAM_TRACE=<file>`), written once the first screen is in. */
    fun writeIfAsked() {
        val f = System.getenv("DREAM_TRACE")?.takeIf { it.isNotBlank() } ?: return
        runCatching { File(f).writeText(lines().joinToString("\n") + "\n") }
    }
}
