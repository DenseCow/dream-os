package com.dreamos.desktop.scrapers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The Python helper process (dreamserve.py) and its JSON-lines channel.
 *
 * Started on first need (never at app start: [ScraperEngine.warm] asks for it in the background) and kept warm.
 * pythonw.exe -I: no console window, no PYTHON* variables, no user site, no script folder on the path; a small
 * environment (no inherited variables beyond what Windows networking needs). Requests are answered out of order
 * by id; scrape events go to [onEvent]. If the process ends, every waiting call fails at once, [onExit] tells the
 * engine (its jobs end), and the next call starts a new one. Four unexpected ends within two minutes pause starts
 * for a minute, so a module that crashes Python can't spin the PC.
 *
 * Locks: [startLock] only around starting / stopping; [writeLock] only around one line's write. Readers ([alive],
 * [generation]) take neither, so nothing waits behind a slow start.
 */
internal class ScraperHost(private val runtime: PythonRuntime, private val paths: ScraperPaths) {
    class HostException(message: String) : Exception(message)

    private class Proc(val process: Process, val stdin: OutputStream, val generation: Int, val hello: CompletableFuture<JsonObject>)

    @Volatile var onEvent: (JsonObject) -> Unit = {}
    @Volatile var onExit: (generation: Int) -> Unit = {}

    @Volatile private var proc: Proc? = null
    /** Bumped for every new process: the engine re-runs its init / module sync when it changes. */
    @Volatile var generation = 0; private set
    @Volatile var startMs = 0L; private set
    private val startLock = Any()
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val ids = AtomicLong()
    private val exits = ArrayDeque<Long>()
    @Volatile private var pausedUntil = 0L
    @Volatile private var stopping = false

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "scrapers-host-stop"))
    }

    fun alive(): Boolean = proc?.process?.isAlive == true

    fun pid(): Long? = proc?.process?.takeIf { it.isAlive }?.pid()

    /** Blocking: the process is up and said hello. Throws [HostException] when it can't start. */
    fun ensureStarted(): Int {
        proc?.let { if (it.process.isAlive) return it.generation }
        synchronized(startLock) {
            proc?.let { if (it.process.isAlive) return it.generation }
            if (System.currentTimeMillis() < pausedUntil) throw HostException("paused after repeated crashes")
            stopping = false
            proc?.process?.destroyForcibly()
            val t0 = System.nanoTime()
            val hostDir = PythonRuntime.extractHost(paths.ensure().hostRoot)
            val cmd = listOf(
                runtime.executable.absolutePath, "-I", "-X", "utf8", "-X", "pycache_prefix=${paths.pycache.absolutePath}",
                File(hostDir, "dreamserve.py").absolutePath, "--site", runtime.siteDir.absolutePath,
            )
            val pb = ProcessBuilder(cmd).directory(hostDir)
            val env = pb.environment()
            val keep = env.filterKeys { it.uppercase() in KEEP_ENV }
            env.clear()
            env.putAll(keep)
            env["PATH"] = listOfNotNull(runtime.pythonDir.absolutePath, keep.entries.firstOrNull { it.key.equals("SystemRoot", true) }?.value?.let { "$it\\System32" })
                .joinToString(File.pathSeparator)
            env["TEMP"] = paths.temp.absolutePath
            env["TMP"] = paths.temp.absolutePath
            val process = try { pb.start() } catch (e: IOException) { throw HostException("couldn't start python (${e.message})") }
            val gen = ++generation
            val p = Proc(process, process.outputStream, gen, CompletableFuture())
            proc = p
            Thread({ readLoop(p) }, "scrapers-host-out-$gen").apply { isDaemon = true }.start()
            Thread({ drainErrors(p) }, "scrapers-host-err-$gen").apply { isDaemon = true }.start()
            val hello = try {
                p.hello.get(20, TimeUnit.SECONDS)
            } catch (e: Exception) {
                process.destroyForcibly()
                throw HostException("python didn't start (${(e.cause ?: e).javaClass.simpleName})")
            }
            startMs = (System.nanoTime() - t0) / 1_000_000
            ScraperLog.w("python ${hello["py"]?.jsonPrimitive?.contentOrNull} up in $startMs ms (pid ${process.pid()}, host ${hostDir.name})")
            return gen
        }
    }

    /** Blocking request / answer. Throws [HostException] on an error answer, a timeout, or the process ending. */
    fun call(op: String, fields: Map<String, JsonElement> = emptyMap(), timeoutMs: Long = 30_000): JsonElement {
        val p = proc?.takeIf { it.process.isAlive } ?: run { ensureStarted(); proc } ?: throw HostException("not running")
        val id = ids.incrementAndGet()
        val f = CompletableFuture<JsonElement>()
        pending[id] = f
        try {
            write(p, op, id, fields)
            return f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw HostException("$op timed out after $timeoutMs ms")
        } catch (e: ExecutionException) {
            throw (e.cause as? HostException) ?: HostException("$op failed (${e.cause?.message})")
        } finally {
            pending.remove(id)
        }
    }

    /** Fire and forget (cancel): only if the process is up; never starts one. */
    fun send(op: String, fields: Map<String, JsonElement> = emptyMap()) {
        val p = proc?.takeIf { it.process.isAlive } ?: return
        try { write(p, op, ids.incrementAndGet(), fields) } catch (_: HostException) {}
    }

    private fun write(p: Proc, op: String, id: Long, fields: Map<String, JsonElement>) {
        val line = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("op", JsonPrimitive(op))
            for ((k, v) in fields) put(k, v)
        }.toString() + "\n"
        try {
            synchronized(writeLock) {
                p.stdin.write(line.toByteArray(Charsets.UTF_8))
                p.stdin.flush()
            }
        } catch (e: IOException) {
            p.process.destroyForcibly()
            throw HostException("python stopped")
        }
    }

    /** Ends the process (app exit, tests). The next call starts a new one. */
    fun stop() {
        stopping = true
        val p = proc ?: return
        try { synchronized(writeLock) { p.stdin.close() } } catch (_: IOException) {}
        if (!p.process.waitFor(800, TimeUnit.MILLISECONDS)) p.process.destroyForcibly()
    }

    private fun readLoop(p: Proc) {
        val reader = BufferedReader(InputStreamReader(p.process.inputStream, Charsets.UTF_8), 64 * 1024)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = try { JSON.parseToJsonElement(line) as? JsonObject } catch (e: Exception) { null }
                if (o == null) { ScraperLog.w("unreadable line from python skipped"); continue }
                val re = o["re"]?.jsonPrimitive?.longOrNull
                if (re != null) {
                    val f = pending[re] ?: continue
                    val err = o["err"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                    if (err != null) f.completeExceptionally(HostException(err)) else f.complete(o["ok"] ?: JsonNull)
                    continue
                }
                when (o["ev"]?.jsonPrimitive?.contentOrNull) {
                    "hello" -> p.hello.complete(o)
                    "log" -> {
                        val lvl = o["lvl"]?.jsonPrimitive?.intOrNull ?: 2
                        val msg = o["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (lvl >= 2) ScraperLog.w(msg.take(500)) else ScraperLog.d(msg.take(500))
                    }
                    else -> try { onEvent(o) } catch (e: Exception) { ScraperLog.w("event handling failed (${e.javaClass.simpleName})") }
                }
            }
        } catch (_: IOException) {
        } finally {
            p.process.destroyForcibly()
            exited(p)
        }
    }

    private fun exited(p: Proc) {
        p.hello.completeExceptionally(HostException("python stopped"))
        if (proc === p) {
            for (f in pending.values) f.completeExceptionally(HostException("python stopped"))
        }
        if (!stopping) {
            val now = System.currentTimeMillis()
            synchronized(exits) {
                exits.addLast(now)
                while (exits.isNotEmpty() && now - exits.first() > 120_000) exits.removeFirst()
                if (exits.size >= 4) {
                    pausedUntil = now + 60_000
                    exits.clear()
                    ScraperLog.w("python stopped 4 times in 2 minutes; waiting a minute before starting it again")
                }
            }
            val code = try { p.process.waitFor(500, TimeUnit.MILLISECONDS); p.process.exitValue() } catch (_: Exception) { -1 }
            ScraperLog.w("python stopped (exit $code)")
        }
        try { onExit(p.generation) } catch (_: Exception) {}
    }

    /** stderr: a module's print() and Python's own errors, into the log (bounded: 60 lines a minute). */
    private fun drainErrors(p: Proc) {
        var windowStart = System.currentTimeMillis()
        var lines = 0
        try {
            BufferedReader(InputStreamReader(p.process.errorStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val now = System.currentTimeMillis()
                    if (now - windowStart > 60_000) { windowStart = now; lines = 0 }
                    if (++lines <= 60) ScraperLog.d("py: ${line.take(300)}")
                }
            }
        } catch (_: IOException) {}
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        /** What Windows networking and Python need from the environment; nothing else is passed on. */
        private val KEEP_ENV = setOf("SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "COMPUTERNAME", "USERPROFILE", "APPDATA",
            "LOCALAPPDATA", "PROGRAMDATA", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS")
    }
}
