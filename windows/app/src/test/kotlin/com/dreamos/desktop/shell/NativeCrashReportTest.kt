package com.dreamos.desktop.shell

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A native crash's hs_err report left in `<install folder>\logs` by the run before moves next to dream.log at the next
 * start, and dream.log says what crashed; another program's report in the same place is left alone. All in a temp
 * folder: AppLog's own folder comes from -Ddream.log.dir.
 */
class NativeCrashReportTest {
    @Test fun movesDreamOsReportsAndSummarizesThem() {
        val tmp = Files.createTempDirectory("dream-hs-err").toFile()
        val logs = File(tmp, "logs").apply { mkdirs() }
        val install = File(tmp, "install").apply { mkdirs() }
        val crashDir = File(install, "logs").apply { mkdirs() }
        System.setProperty("dream.log.dir", logs.path)
        System.setProperty("jpackage.app-path", File(install, "Dream OS.exe").path)
        // The other two places it looks (the working folder, %TEMP%) point into the temp folder too: real reports of
        // other runs on this PC stay untouched.
        val keep = listOf("user.dir", "java.io.tmpdir").associateWith { System.getProperty(it) }
        keep.keys.forEach { System.setProperty(it, install.path) }
        File(crashDir, "hs_err_1234.log").writeText("""
            #
            # A fatal error has been detected by the Java Runtime Environment:
            #
            #  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffa3168aeb1, pid=1234, tid=31932
            #
            # Problematic frame:
            # C  [libmpv-2.dll+0x5aeb1]
            #
            Command Line: -Ddream.version=4.3.5 com.dreamos.desktop.MainKt
            Current thread (0x000001c2e0d528d0):  JavaThread "dream-player-close" daemon [_thread_in_native, id=31932]
        """.trimIndent())
        File(crashDir, "hs_err_999.log").writeText("# Problematic frame:\n# C  [other.dll]\nCommand Line: org.other.Main\n")

        AppLog::class.java.getDeclaredMethod("open").apply { isAccessible = true }.invoke(AppLog)
        AppLog::class.java.getDeclaredMethod("nativeCrashReports").apply { isAccessible = true }.invoke(AppLog)

        assertTrue(File(logs, "hs_err_1234.log").isFile, "Dream OS's report moved next to dream.log")
        assertFalse(File(crashDir, "hs_err_1234.log").exists(), "and not left behind")
        assertTrue(File(crashDir, "hs_err_999.log").isFile, "another program's report stays where it was")
        val log = File(logs, "dream.log").readText()
        assertTrue(log.contains("native crash (EXCEPTION_ACCESS_VIOLATION"), log)
        assertTrue(log.contains("C  [libmpv-2.dll+0x5aeb1]"), log)
        assertTrue(log.contains("JavaThread \"dream-player-close\""), log)
        keep.forEach { (k, v) -> System.setProperty(k, v) }
        tmp.deleteRecursively()
    }
}
