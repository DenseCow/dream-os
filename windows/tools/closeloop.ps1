# Dream Player's close loop with real windows (player/src/main/kotlin/com/dreamos/player/dev/CloseLoop.kt), run where
# nobody sees it: on a Win32 desktop of its own that is never switched to, with no sound, a throwaway APPDATA and
# LOCALAPPDATA, and its logs (and any JVM crash report) in player\build\closeloop\run-<time>\logs.
#
#   powershell -ExecutionPolicy Bypass -File tools\closeloop.ps1 [-Rounds 50] [-Only FROM_MINI,RIGHT_AFTER_MINI] [-Url <link>]
#
# Exit code 0: every round passed. The summary and each failure come from the run's player.log.
param(
    [int]$Rounds = 50,
    [string]$Only = "",
    [string]$Url = "",
    [string]$Java = "",
    [int]$TimeoutMin = 60
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Java) {
    $Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { (Get-Command java.exe).Source }
}

& (Join-Path $root "gradlew.bat") -q -p $root :player:closeloopArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$run = Join-Path $root ("player\build\closeloop\run-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
foreach ($d in "appdata", "localappdata", "logs") { New-Item -ItemType Directory -Force (Join-Path $run $d) | Out-Null }
$logs = (Join-Path $run "logs").Replace("\", "/")
$javaArgs = Get-Content (Join-Path $root "player\build\closeloop\args.txt")
$extra = @("`"-Ddream.log.dir=$logs`"", "`"-XX:ErrorFile=$logs/hs_err_%p.log`"", "-Ddream.rounds=$Rounds")
if ($Only) { $extra += "-Ddream.only=$Only" }
if ($Url) { $extra += "`"-Ddream.url=$Url`"" }
# The main class stays last.
$all = @($javaArgs[0..($javaArgs.Count - 2)]) + $extra + @($javaArgs[-1])
$argFile = Join-Path $run "args.txt"
Set-Content -Path $argFile -Value $all -Encoding ascii

Add-Type @"
using System; using System.Runtime.InteropServices;
public static class HiddenDesktop {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  public struct StartupInfo { public int cb; public string reserved; public string desktop; public string title; public int x, y, w, h, cx, cy, fill, flags; public short show, reserved2; public IntPtr reserved3, stdIn, stdOut, stdErr; }
  [StructLayout(LayoutKind.Sequential)] public struct ProcessInfo { public IntPtr process, thread; public int pid, tid; }
  [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  public static extern IntPtr CreateDesktop(string name, IntPtr device, IntPtr mode, int flags, uint access, IntPtr sa);
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
  public static extern bool CreateProcess(IntPtr app, string cmd, IntPtr pa, IntPtr ta, bool inherit, int flags, IntPtr env, string dir, ref StartupInfo si, out ProcessInfo pi);
  [DllImport("kernel32.dll")] public static extern uint WaitForSingleObject(IntPtr h, uint ms);
  [DllImport("kernel32.dll")] public static extern bool GetExitCodeProcess(IntPtr h, out uint code);
  [DllImport("kernel32.dll")] public static extern bool TerminateProcess(IntPtr h, uint code);
}
"@
$desk = "DreamCloseLoop"
# GENERIC_ALL; the desktop is never switched to, so nothing on it reaches the screen.
if ([HiddenDesktop]::CreateDesktop($desk, [IntPtr]::Zero, [IntPtr]::Zero, 0, 0x10000000, [IntPtr]::Zero) -eq [IntPtr]::Zero) { throw "CreateDesktop failed" }
$env:APPDATA = Join-Path $run "appdata"
$env:LOCALAPPDATA = Join-Path $run "localappdata"
$si = New-Object HiddenDesktop+StartupInfo
$si.cb = [Runtime.InteropServices.Marshal]::SizeOf($si)
$si.desktop = "winsta0\$desk"
$pi = New-Object HiddenDesktop+ProcessInfo
# CREATE_NO_WINDOW: no console window either.
$cmd = "`"$Java`" `"@$argFile`""
if (-not [HiddenDesktop]::CreateProcess([IntPtr]::Zero, $cmd, [IntPtr]::Zero, [IntPtr]::Zero, $false, 0x08000000, [IntPtr]::Zero, (Join-Path $root "player"), [ref]$si, [ref]$pi)) {
    throw "CreateProcess failed ($([Runtime.InteropServices.Marshal]::GetLastWin32Error()))"
}
Write-Output "Close loop: $Rounds rounds, pid $($pi.pid), logs in $run\logs"
if ([HiddenDesktop]::WaitForSingleObject($pi.process, [uint32]($TimeoutMin * 60000)) -ne 0) {
    [HiddenDesktop]::TerminateProcess($pi.process, 9) | Out-Null
    Write-Output "Close loop: still running after $TimeoutMin minutes, stopped"
    exit 4
}
$code = 0
[HiddenDesktop]::GetExitCodeProcess($pi.process, [ref]$code) | Out-Null
$log = Join-Path $run "logs\player.log"
if (Test-Path $log) { Select-String -Path $log -Pattern "CloseLoop: .*(FAILED|rounds passed)" | ForEach-Object { $_.Line } }
Get-ChildItem (Join-Path $run "logs") -Filter "hs_err*" | ForEach-Object { Write-Output "Close loop: the JVM crashed, report $($_.FullName)" }
exit $code
