package com.dreamos.desktop.core.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** One log for Dream OS (tag "DreamOS" plus the area), on stderr; the app can add a file sink ([sink]). */
object Log {
    @Volatile var sink: ((String) -> Unit)? = null
    @Volatile var debug = System.getProperty("dream.debug") == "true"
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun d(area: String, msg: String) { if (debug) write("D", area, msg) }
    fun i(area: String, msg: String) = write("I", area, msg)
    fun w(area: String, msg: String) = write("W", area, msg)

    /** Keys and tokens never reach a log line, whatever an exception message carries (a URL with the viewer's key). */
    private val SECRET = Regex("(?i)(api_key|apikey|access_token|refresh_token|client_secret|client_id|token)=[^&\\s\"']+")

    fun redact(s: String): String = SECRET.replace(s) { it.groupValues[1] + "=***" }

    private fun write(level: String, area: String, msg: String) {
        val line = "${LocalTime.now().format(clock)} $level DreamOS/$area: ${redact(msg)}"
        System.err.println(line)
        sink?.let { try { it(line) } catch (e: Exception) {} }
    }
}
