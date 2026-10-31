package com.dreamos.desktop.startup

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * The startup archive (`app\dream.jsa`, named by the launcher: `-XX:SharedArchiveFile=$APPDIR/dream.jsa`): Java's
 * class-data sharing, so a launch maps classes ready-made instead of reading and checking each one.
 *
 * - **Shipped:** the Java runtime's own classes (made by the build, works in any folder).
 * - **Written here:** the full archive with the app's classes too (about 9,000, from the class list the build ships,
 *   `app\dream.classlist`), which brings the first frame from about 2.2 s to about 0.8 s on this PC (headless probe).
 *   Java 17 ties it to the folder it was made in, so it's made on the viewer's PC: by the setup
 *   (`"Dream OS.exe" --prepare`, 15 to 20 s) and, if that didn't happen, in the background once Home is up (swapped in
 *   when Dream OS closes, since the running app holds the file).
 *
 * Java 17 can't use the app's classes from an archive when the folder's path has a space or other characters a file
 * URL escapes (its class sources are compared as escaped URLs): there the shipped archive stays.
 * An archive that doesn't match the installed files is ignored by Java on its own: never a failed launch, only a
 * slower one.
 */
object StartArchive {
    private const val LIST = "dream.classlist"
    private const val ARCHIVE = "dream.jsa"
    /** The version whose full archive `dream.jsa` is (absent: the shipped one). */
    private const val FULL_MARK = "dream.jsa.app"
    /** Set for the JVM that writes the archive: that JVM stops while starting, so it must never reach `main`. */
    private const val CHILD = "DREAM_CDS_DUMP"

    /** "Dream OS.exe" of a packaged launch (jpackage's launcher sets it); null under Gradle. */
    private val launcher: File? get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    /** The jars' folder ($APPDIR) when it carries the class list. */
    private val appDir: File? get() = launcher?.parentFile?.resolve("app")?.takeIf { it.resolve(LIST).isFile }

    private val version: String get() = System.getProperty("dream.version").orEmpty()

    /** This launch mapped an archive ("sharing" in the VM's description). */
    val inUse: Boolean get() = System.getProperty("java.vm.info").orEmpty().contains("sharing")

    /** The full archive for this version is in place. */
    val full: Boolean get() = appDir?.let { d -> runCatching { File(d, FULL_MARK).readText().trim() == version }.getOrDefault(false) } == true

    /** Characters a file URL keeps as they are: Java 17 finds the app's archived classes only in such a folder. */
    private fun plainPath(dir: File) = dir.absolutePath.all { it.isLetterOrDigit() && it.code < 128 || it in "\\/:._-~" }

    /** `main`'s first word: an exit code for `--prepare` and for a writer JVM that got as far as `main`, else null. */
    fun command(args: Array<String>): Int? = when {
        System.getenv(CHILD) != null -> 3
        args.firstOrNull() == "--prepare" -> if (!needed(say = true) || write(background = false)) 0 else 1
        else -> null
    }

    private fun needed(say: Boolean = false): Boolean {
        val dir = appDir ?: return false
        if (!plainPath(dir)) {
            if (say) println("Dream OS: startup archive: the app's classes can't be archived in $dir (Java 17 and a space in the path)")
            return false
        }
        return !full
    }

    /** After Home is up: writes the full archive in the background when this version doesn't have it yet. */
    fun refreshLater() {
        if (!needed()) return
        Thread({
            Thread.sleep(20_000)
            write(background = true)
        }, "dream-start-archive").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    /**
     * Runs the launcher once more with `-Xshare:dump`: it reads the class list, writes the archive and exits without
     * opening anything. Paths are relative to the app folder. A version that failed once isn't tried again in the
     * background until the next update.
     */
    @Synchronized private fun write(background: Boolean): Boolean {
        val exe = launcher ?: return false
        val dir = appDir ?: return false
        val failed = File(dir, "$ARCHIVE.failed")
        if (background && runCatching { failed.readText() }.getOrNull() == version) return false
        val tmp = File(dir, "$ARCHIVE.new")
        val ok = runCatching {
            tmp.delete()
            val pb = ProcessBuilder(exe.path).directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.environment()["_JAVA_OPTIONS"] = "-Xshare:dump -XX:SharedClassListFile=$LIST -XX:SharedArchiveFile=${tmp.name}"
            pb.environment()[CHILD] = "1"
            val p = pb.start()
            if (background) belowNormal(p.pid())
            if (!p.waitFor(3, TimeUnit.MINUTES)) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); error("timed out") }
            check(p.exitValue() == 0 && tmp.length() > 20_000_000) { "exit ${p.exitValue()}" }
            // Java writes the archive read-only; writable, so the next one and the setup can replace it.
            tmp.setWritable(true)
            swap(dir)
            failed.delete()
        }.onFailure {
            tmp.delete()
            runCatching { failed.writeText(version) }
            System.err.println("Dream OS: startup archive not written (${it.message})")
        }.isSuccess
        StartupTrace.mark(if (ok) "archive: written" else "archive: failed")
        return ok
    }

    /**
     * Puts `dream.jsa.new` in place. This running app holds the archive it started with, so in the background the move
     * waits for Dream OS to close: a hidden `cmd` moves it two seconds after.
     */
    private fun swap(dir: File) {
        val target = File(dir, ARCHIVE)
        val moved = runCatching {
            if (target.isFile) target.setWritable(true)
            Files.move(File(dir, "$ARCHIVE.new").toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (moved) { File(dir, FULL_MARK).writeText(version); return }
        val v = version.filter { it.isLetterOrDigit() || it == '.' }
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching {
                ProcessBuilder("cmd", "/c", "ping -n 3 127.0.0.1 >nul & move /y $ARCHIVE.new $ARCHIVE >nul && (echo $v)>$FULL_MARK")
                    .directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }
        })
    }

    /** The writer JVM at below-normal priority, so the viewer's PC never feels it. */
    private fun belowNormal(pid: Long) = runCatching {
        val k = com.sun.jna.Native.load("kernel32", Kernel32::class.java)
        val h = k.OpenProcess(0x0200 /* PROCESS_SET_INFORMATION */, false, pid.toInt()) ?: return@runCatching
        k.SetPriorityClass(h, 0x4000 /* BELOW_NORMAL_PRIORITY_CLASS */)
        k.CloseHandle(h)
    }

    @Suppress("FunctionName")
    private interface Kernel32 : com.sun.jna.Library {
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): com.sun.jna.Pointer?
        fun SetPriorityClass(process: com.sun.jna.Pointer, priorityClass: Int): Boolean
        fun CloseHandle(handle: com.sun.jna.Pointer): Boolean
    }
}
