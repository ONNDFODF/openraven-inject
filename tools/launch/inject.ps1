# Injects the agent into a running game, the way the loader does -- but from
# PowerShell.
#
# The loader is the supported path and this is not a replacement for it. It
# exists because a security product can refuse OpenProcess from a binary it does
# not recognise, and the loader is exactly that: an unknown executable asking
# for write access to a game's memory. powershell.exe is not, so the same five
# calls go through. The loader itself says so when it is refused -- "cannot open
# process <pid> (error 5)" -- and this is what to run then.
#
# The agent is staged next to this repo's dist directory, so the log it writes
# lands there too: dist\openraven-native.log.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\launch\inject.ps1 -TargetPid 1234

param(
    [Parameter(Mandatory = $true)][int]$TargetPid,
    [string]$Agent,
    [string]$Stage
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $Agent) {
    # dist\openraven_agent.dll ships with the release; build\native is what a
    # from-source build leaves behind.
    $Agent = Join-Path $root 'dist\openraven_agent.dll'
    if (-not (Test-Path $Agent)) { $Agent = Join-Path $root 'build\native\openraven_native.dll' }
}
if (-not $Stage) { $Stage = Join-Path $root 'dist' }
if (-not (Test-Path $Agent)) { throw "no agent at $Agent -- run build.cmd native first" }
if (-not (Test-Path $Stage)) { New-Item -ItemType Directory -Force -Path $Stage | Out-Null }

$staged = Join-Path $Stage ("openraven_native_" + $TargetPid + ".dll")
Copy-Item $Agent $staged -Force

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class OpenRavenInject {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr OpenProcess(int access, bool inherit, int pid);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr VirtualAllocEx(IntPtr process, IntPtr address, IntPtr size, int allocation, int protect);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool WriteProcessMemory(IntPtr process, IntPtr address, byte[] bytes, IntPtr size, out IntPtr written);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern IntPtr GetModuleHandleW(string name);
    [DllImport("kernel32.dll", CharSet=CharSet.Ansi, SetLastError=true)] public static extern IntPtr GetProcAddress(IntPtr module, string name);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern IntPtr CreateRemoteThread(IntPtr process, IntPtr attributes, IntPtr stack, IntPtr start, IntPtr parameter, int flags, out IntPtr threadId);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern int WaitForSingleObject(IntPtr handle, int milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GetExitCodeThread(IntPtr handle, out int code);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr handle);
}
"@

# PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION |
# PROCESS_VM_WRITE | PROCESS_VM_READ -- the same set the loader asks for.
$process = [OpenRavenInject]::OpenProcess(0x0002 -bor 0x0400 -bor 0x0008 -bor 0x0020 -bor 0x0010, $false, $TargetPid)
if ($process -eq [IntPtr]::Zero) {
    throw ("cannot open process " + $TargetPid + " (error " + [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() + ")")
}
Write-Output ("opened process " + $TargetPid)

$path = [System.Text.Encoding]::Unicode.GetBytes($staged + [char]0)
$remote = [OpenRavenInject]::VirtualAllocEx($process, [IntPtr]::Zero, [IntPtr]$path.Length, 0x3000, 0x04)
if ($remote -eq [IntPtr]::Zero) { throw ("VirtualAllocEx failed (error " + [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() + ")") }
$written = [IntPtr]::Zero
if (-not [OpenRavenInject]::WriteProcessMemory($process, $remote, $path, [IntPtr]$path.Length, [ref]$written)) {
    throw ("WriteProcessMemory failed (error " + [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() + ")")
}
Write-Output ("staged " + $staged)

$kernel32 = [OpenRavenInject]::GetModuleHandleW("kernel32.dll")
$loadLibrary = [OpenRavenInject]::GetProcAddress($kernel32, "LoadLibraryW")
if ($loadLibrary -eq [IntPtr]::Zero) { throw "kernel32 has no LoadLibraryW" }
$threadId = [IntPtr]::Zero
$thread = [OpenRavenInject]::CreateRemoteThread($process, [IntPtr]::Zero, [IntPtr]::Zero, $loadLibrary, $remote, 0, [ref]$threadId)
if ($thread -eq [IntPtr]::Zero) { throw ("CreateRemoteThread failed (error " + [System.Runtime.InteropServices.Marshal]::GetLastWin32Error() + ")") }
[void][OpenRavenInject]::WaitForSingleObject($thread, 15000)
$exit = 0
[void][OpenRavenInject]::GetExitCodeThread($thread, [ref]$exit)
[void][OpenRavenInject]::CloseHandle($thread)
[void][OpenRavenInject]::CloseHandle($process)
if ($exit -eq 0) {
    throw "LoadLibraryW returned null in the target -- the agent did not load"
}
Write-Output "agent loaded; the client's log is next to the staged dll, or in the game's java.io.tmpdir"