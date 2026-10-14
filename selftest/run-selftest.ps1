# Compiles the injection core plus the transformer fixtures and runs them.
#
# The fixtures need no game, so it does not go through Gradle or Loom at all --
# which is what makes it fast enough to run after every change to the
# transformer. It does need ASM, and the list of jars is asm-classpath.txt.
$ErrorActionPreference = 'Stop'

$ws  = Split-Path -Parent $PSScriptRoot
$src = Join-Path $ws 'OpenRaven-Inject\src\main\java\xyz\daikirai\inject'
$har = Join-Path $ws 'selftest\src'
$tmp = Join-Path $ws 'build-selftest'
$out = Join-Path $tmp 'classes'
$cpFile = Join-Path $PSScriptRoot 'asm-classpath.txt'

$jdk  = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Java\jdk-17.0.2\bin' }
$javac = Join-Path $jdk 'javac.exe'
$java  = Join-Path $jdk 'java.exe'
if (-not (Test-Path $javac)) { throw "javac not found at $javac; set JAVA_HOME" }

if (-not (Test-Path $cpFile)) {
    throw "missing $cpFile -- copy it from the OpenRaven-Inject Gradle cache, or point it at your own ASM jars"
}
# Windows PowerShell's Get-Content keeps the UTF-8 BOM, which would become part
# of the first classpath entry.
$cp = ((Get-Content $cpFile -Raw) -replace "^\uFEFF", '').Trim()
foreach ($jar in ($cp -split ';')) {
    if (-not (Test-Path $jar)) { throw "ASM jar listed in asm-classpath.txt is missing: $jar" }
}

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

# Bootstrap and NativeBridge are left out on purpose: they name Bootstrap,
# which names Forge, and nothing in the transformer's logic needs either.
$sources = @(
    (Join-Path $src 'Log.java'),
    (Join-Path $src 'MappingBridge.java'),
    (Join-Path $src 'HookRegistry.java'),
    (Join-Path $src 'HookTransformer.java'),
    (Join-Path $har 'selftest\TransformerFixtures.java'),
    (Join-Path $har 'selftest\RunFixtures.java'),
    (Join-Path $har 'selftest\FixtureCompiler.java'),
    (Join-Path $har 'selftest\fixtures\Recorder.java'),
    (Join-Path $har 'selftest\fixtures\Target.java')
)

& $javac -nowarn -encoding UTF-8 --release 8 -cp $cp -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

& $java '-Dfile.encoding=UTF-8' -cp "$out;$cp" selftest.RunFixtures
exit $LASTEXITCODE
