# Launches the 1.8.9 Forge instance that the injector is developed against.
#
# The launcher's own command line is rebuilt from the version json in the
# .minecraft that the jar is installed in, so nothing here is specific to one
# launcher: read the json, resolve every library against libraries/, add the
# version jar, and pass the standard arguments. The last line prints the pid,
# which is what the loader wants.
#
# Usage:  powershell -File tools\launch\launch-1.8.9.ps1 [-Version name] [-MemoryMb n]

param(
    [string]$Version = 'The Cloud Client Experience',
    [string]$Username = 'ForAnyone',
    [int]$MemoryMb = 3072,
    [string]$JavaHome = 'C:\Program Files\Java\jre1.8.0_151'
)

$ErrorActionPreference = 'Stop'
$mc = Join-Path $env:APPDATA '.minecraft'
$versionDir = Join-Path $mc "versions\$Version"
$jsonPath = Join-Path $versionDir "$Version.json"
if (-not (Test-Path $jsonPath)) { throw "no version json at $jsonPath" }

$json = Get-Content $jsonPath -Raw | ConvertFrom-Json
$libraries = Join-Path $mc 'libraries'

$classpath = New-Object System.Collections.Generic.List[string]
$missing = @()
foreach ($library in $json.libraries) {
    if (-not $library.downloads -or -not $library.downloads.artifact.path) { continue }
    $path = Join-Path $libraries $library.downloads.artifact.path.Replace('/', '\')
    if (Test-Path $path) { $classpath.Add($path) } else { $missing += $library.name }
}
$client = Join-Path $versionDir "$Version.jar"
if (-not (Test-Path $client)) { throw "no client jar at $client" }
$classpath.Add($client)
if ($missing.Count -gt 0) { Write-Warning ("libraries not present: " + ($missing -join ', ')) }

# The natives go where the launcher extracted them; either name is fine.
$natives = Join-Path $versionDir 'natives-windows-x86_64'
if (-not (Test-Path $natives)) { $natives = Join-Path $versionDir "$Version-natives" }
if (-not (Test-Path $natives)) { throw "no natives directory under $versionDir" }

$uuid = '9de1b9fa2df14b64bbd069d5920adbb5'
$arguments = @(
    "-Xmx${MemoryMb}m", '-Djava.library.path=' + $natives,
    '-Dminecraft.launcher.brand=openraven', '-Dminecraft.launcher.version=1',
    '-cp', ($classpath -join ';'),
    $json.mainClass,
    '--username', $Username,
    '--version', $Version,
    '--gameDir', $mc,
    '--assetsDir', (Join-Path $mc 'assets'),
    '--assetIndex', $json.assetIndex.id,
    '--uuid', $uuid,
    '--accessToken', '0',
    '--userProperties', '{}',
    '--userType', 'legacy',
    '--tweakClass', 'net.minecraftforge.fml.common.launcher.FMLTweaker'
)

$java = Join-Path $JavaHome 'bin\javaw.exe'
if (-not (Test-Path $java)) { throw "no javaw at $java" }

$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $mc -PassThru
Write-Output ("launched " + $Version + " as pid " + $process.Id)
