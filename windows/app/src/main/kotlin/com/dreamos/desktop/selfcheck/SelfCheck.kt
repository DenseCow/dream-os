package com.dreamos.desktop.selfcheck

import com.dreamos.desktop.scrapers.PythonRuntime
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import java.io.File
import java.net.http.HttpClient
import java.security.KeyPairGenerator
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.system.exitProcess

/**
 * `Dream OS.exe --selfcheck [report file]`: checks that this install has what it needs and exits. Opens no window,
 * makes no sound and touches no network. tools/build-installer.sh runs it on every app image it builds.
 *
 * Checks: libmpv loads from the app's resources (the player's lookup order) and answers its API version; the bundled
 * Python starts and imports ssl, sqlite3 and requests from python-site; the Java runtime has the modules the app
 * uses (TLS with EC keys, HttpClient, the stream cache's HTTP server, XML); Skia's native library loads.
 * Prints one line per check, writes the same lines to the report file when one is given, exits 0 when all pass.
 */
object SelfCheck {
    fun run(args: List<String>): Int {
        val lines = ArrayList<String>()
        var ok = true
        fun check(name: String, block: () -> String) {
            val line = try {
                "PASS $name: ${block()}"
            } catch (t: Throwable) {
                ok = false
                "FAIL $name: ${t.javaClass.simpleName}: ${t.message}"
            }
            lines += line
            println(line)
        }

        check("version") {
            "${System.getProperty("dream.version")} (${System.getProperty("dream.channel") ?: "DEV"}), Java ${System.getProperty("java.runtime.version")} at ${System.getProperty("java.home")}"
        }
        check("resources") {
            System.getProperty("compose.application.resources.dir")
                ?: "not set (started outside the installer and Gradle: the lookups fall back to third_party/)"
        }
        check("libmpv") { probeMpv() }
        check("python") { probePython() }
        check("java modules") {
            SSLContext.getInstance("TLSv1.3").init(null, null, null)
            KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            HttpClient.newHttpClient()
            Class.forName("com.sun.net.httpserver.HttpServer")
            javax.xml.stream.XMLInputFactory.newInstance()
            "TLS 1.3 with EC keys, HttpClient, HttpServer, XML"
        }
        check("skia") {
            org.jetbrains.skia.Surface.makeRasterN32Premul(4, 4).close()
            "native library loaded"
        }

        args.firstOrNull()?.let { File(it).writeText(lines.joinToString("\n", postfix = "\n")) }
        return if (ok) 0 else 1
    }

    /** Same folders, same order as the player's LibMpv.api. */
    private fun probeMpv(): String {
        val dirs = listOfNotNull(
            System.getProperty("dream.mpv.dir"),
            System.getProperty("compose.application.resources.dir"),
            "third_party/mpv", "../third_party/mpv",
        )
        val dir = dirs.map { File(it) }.firstOrNull { File(it, "libmpv-2.dll").isFile }
            ?: error("libmpv-2.dll not in ${dirs.joinToString()}")
        System.setProperty("jna.library.path", dir.absolutePath)
        val lib = NativeLibrary.getInstance("mpv-2")
        val v = lib.getFunction("mpv_client_api_version").invokeLong(emptyArray())
        return "client API ${v shr 16}.${v and 0xffff} from ${File(dir, "libmpv-2.dll").absolutePath}"
    }

    /** pythonw.exe (no console window), isolated, with python-site on the path the way the scraper host adds it. */
    private fun probePython(): String {
        val rt = PythonRuntime.locate() ?: error("no usable Python found")
        val site = rt.siteDir.absolutePath.replace("\\", "\\\\")
        val code = "import sys; sys.path.insert(0, '$site'); import ssl, sqlite3, requests; " +
            "print(sys.version.split()[0], ssl.OPENSSL_VERSION, 'requests', requests.__version__)"
        val p = ProcessBuilder(rt.executable.absolutePath, "-I", "-c", code).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); error("Python didn't finish in 30 s") }
        if (p.exitValue() != 0) error("exit ${p.exitValue()}: $out")
        return "$out (${rt.executable.absolutePath})"
    }
}

/** Run straight from the build: `java -cp ... com.dreamos.desktop.selfcheck.SelfCheckKt [report file]`. */
fun main(args: Array<String>) {
    exitProcess(SelfCheck.run(args.toList()))
}
