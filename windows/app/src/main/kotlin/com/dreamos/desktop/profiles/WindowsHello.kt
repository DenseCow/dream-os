package com.dreamos.desktop.profiles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Windows Hello for profile PINs (the PC's Face ID, iPhone pages.md): asks Windows' own consent prompt (face,
 * fingerprint or Windows Hello PIN) through WinRT's UserConsentVerifier, run by the PowerShell that ships with
 * Windows. Not on Windows, or Hello not set up: [available] is false and the PIN pad is the only way.
 */
object WindowsHello {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    @Volatile private var cached: Boolean? = null

    private const val LOAD = "Add-Type -AssemblyName System.Runtime.WindowsRuntime;" +
        "\$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? { \$_.Name -eq 'AsTask' -and \$_.GetParameters().Count -eq 1 -and \$_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0];" +
        "function Await(\$op, [Type]\$t) { \$task = \$asTask.MakeGenericMethod(\$t).Invoke(\$null, @(\$op)); \$task.Wait(-1) | Out-Null; \$task.Result };" +
        "[Windows.Security.Credentials.UI.UserConsentVerifier,Windows.Security.Credentials.UI,ContentType=WindowsRuntime] | Out-Null;"

    /** Whether Windows Hello is set up (checked once, about a second, off the UI thread on first use). */
    fun available(): Boolean {
        if (!isWindows) return false
        cached?.let { return it }
        val ok = run("Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::CheckAvailabilityAsync()) ([Windows.Security.Credentials.UI.UserConsentVerifierAvailability])", 15) == "Available"
        cached = ok
        return ok
    }

    /** Shows Windows Hello with [reason] ("Confirm it's you to open this profile."); true when confirmed. */
    suspend fun verify(reason: String): Boolean = withContext(Dispatchers.IO) {
        if (!isWindows) return@withContext false
        val r = reason.replace("'", "''")
        run("Await ([Windows.Security.Credentials.UI.UserConsentVerifier]::RequestVerificationAsync('$r')) ([Windows.Security.Credentials.UI.UserConsentVerificationResult])", 120) == "Verified"
    }

    private fun run(expr: String, timeoutSec: Long): String? = runCatching {
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", LOAD + expr)
            .redirectErrorStream(true).start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        p.inputStream.bufferedReader().readText().trim().lines().lastOrNull()?.trim()
    }.getOrNull()
}
