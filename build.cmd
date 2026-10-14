@echo off
rem ---------------------------------------------------------------------------
rem Builds the injector: client jar, embedded agent DLL, and the loader exe.
rem
rem Runs with no network access. Everything it needs -- the MCP-named Minecraft
rem and Forge jars, LWJGL, ASM -- is already in the Gradle cache from a previous
rem Loom build, so this bypasses Gradle and Loom entirely.
rem
rem Usage:  build.cmd                        both stages, srg client
rem         build.cmd client [srg|mcp|notch]  only the jar, for one naming scheme
rem         build.cmd native                 only the loader (needs the jars already built)
rem
rem The naming is which client the jar is for: srg is a Forge production client
rem (and the one everything else defaults to), mcp is Lunar -- MCP class and
rem member names, nothing to rewrite -- and notch is Badlion, whose game classes
rem are the vanilla obfuscated ones. Each variant is a jar of its own and the
rem loader picks between them at injection time.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "OUT=%ROOT%build"
set "CLIENT=%ROOT%OpenRaven-Inject"
set "NATIVES=%ROOT%openraven-natives"
set "STAGE=%OUT%\classes"
set "DIST=%ROOT%dist"

set "JAVA_HOME=C:\Program Files\Java\jdk-17.0.2"
set "JAVAC=%JAVA_HOME%\bin\javac.exe"
set "JAR=%JAVA_HOME%\bin\jar.exe"
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

set "STAGE_ONLY=%~1"
if "%STAGE_ONLY%"=="" set "STAGE_ONLY=all"
set "NAMING=%~2"
if "%NAMING%"=="" set "NAMING=srg"
if /i not "%NAMING%"=="srg" if /i not "%NAMING%"=="mcp" if /i not "%NAMING%"=="notch" (
    echo ERROR: unknown naming "%NAMING%": expected srg, mcp or notch.
    exit /b 1
)
set "CLIENT_JAR=OpenRaven-Inject.jar"
if /i "%NAMING%"=="mcp" set "CLIENT_JAR=OpenRaven-Inject-mcp.jar"
if /i "%NAMING%"=="notch" set "CLIENT_JAR=OpenRaven-Inject-notch.jar"
echo === stage 1: client jar (%NAMING%) ===

if not exist "%JAVAC%" (
    echo ERROR: javac not found at "%JAVAC%". Set JAVA_HOME in this script.
    exit /b 1
)

if /i not "%STAGE_ONLY%"=="native" goto :client
goto :native

rem ============================================================== the client ==
:client
echo.
echo === stage 1: client jar ===

rem Ask PowerShell to locate the cached dependencies, since their paths contain
rem hashes that change between downloads.
for /f "usebackq delims=" %%L in (`powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$c = $env:USERPROFILE + '\.gradle\caches';" ^
  "$l = Join-Path $c 'essential-loom\1.8.9\de.oceanlabs.mcp.mcp_stable.1_8_9.22-1.8.9-forge-1.8.9-11.15.1.2318-1.8.9';" ^
  "$m = Join-Path $c 'modules-2\files-2.1';" ^
  "function one($p, $f) { $x = Get-ChildItem $p -Recurse -Filter $f -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1; if ($x) { $x.FullName } }" ^
  "$jars = @(" ^
  "  (Join-Path $l 'minecraft-mapped.jar')," ^
  "  (Join-Path $l 'forge\forge-mapped.jar')," ^
  "  (one (Join-Path $m 'org.lwjgl.lwjgl') 'lwjgl-2.9.4-nightly-20150209.jar')," ^
  "  (one (Join-Path $m 'org.lwjgl.lwjgl\lwjgl_util') 'lwjgl_util-2.9.4-nightly-20150209.jar')," ^
  "  (one (Join-Path $m 'net.java.jinput') 'jinput-2.0.5.jar')," ^
  "  (one (Join-Path $m 'io.netty') 'netty-all-4.0.23.Final.jar')," ^
  "  (one (Join-Path $m 'com.google.guava') 'guava-17.0.jar')," ^
  "  (one (Join-Path $m 'org.apache.commons') 'commons-lang3-3.3.2.jar')," ^
  "  (one (Join-Path $m 'org.apache.logging.log4j') 'log4j-api-2.0-beta9.jar')," ^
  "  (one (Join-Path $m 'org.apache.logging.log4j') 'log4j-core-2.0-beta9.jar')," ^
  "  (one (Join-Path $m 'com.mojang') 'authlib-1.5.21.jar')," ^
  "  (one (Join-Path $m 'net.minecraft\launchwrapper') 'launchwrapper-1.12.jar')," ^
  "  (one (Join-Path $m 'com.google.code.gson') 'gson-2.2.4.jar')" ^
  ");" ^
  "foreach ($j in $jars) { if ($j -and (Test-Path $j)) { Write-Output $j } else { Write-Output ('MISSING ' + $j) } }"`) do (
    echo(%%L | findstr /b /c:"MISSING" >nul && (
        echo ERROR: a dependency is not in the Gradle cache: %%L
        echo Run the original Loom build once with network access to populate it.
        exit /b 1
    )
    set "CP=!CP!;%%L"
)

rem ASM is shaded into the jar: the game will not supply it.
set "ASM_CP="
for %%A in (asm asm-tree asm-commons asm-util asm-analysis) do (
    for /f "usebackq delims=" %%P in (`powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$x = Get-ChildItem ($env:USERPROFILE + '\.gradle\caches\modules-2\files-2.1\org.ow2.asm\%%A') -Recurse -Filter '%%A-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object Name -Descending | Select-Object -First 1; if ($x) { $x.FullName }"`) do set "ASM_CP=!ASM_CP!;%%P"
)

echo classpath:
echo   !CP!
echo asm:
echo   !ASM_CP!

if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%" 2>nul
mkdir "%DIST%" 2>nul

rem The sources are Java 8 and so is the game, but javac 17 is what is here.
rem --release 8 is what keeps the generated bytecode at 52.
dir /s /b "%CLIENT%\src\main\java\*.java" > "%OUT%\sources.txt"
rem The Forge-shaped shim goes into the two jars for clients that have no Forge
rem of their own -- Lunar and Badlion. A Forge client already has these classes,
rem and shipping a second copy of net.minecraftforge.* would be a way to find
rem out which loader wins.
if /i not "%NAMING%"=="srg" (
    dir /s /b "%CLIENT%\src\shim\java\*.java" >> "%OUT%\sources.txt"
)
for /f %%C in ('type "%OUT%\sources.txt" ^| find /c /v ""') do echo compiling %%C sources

"%JAVAC%" -nowarn -encoding UTF-8 --release 8 -cp "!CP:~1!;!ASM_CP:~1!" -d "%STAGE%" "@%OUT%\sources.txt"
if errorlevel 1 (
    echo ERROR: the client did not compile.
    exit /b 1
)

rem resources, and ASM, go into the jar alongside our classes
xcopy /e /i /q /y "%CLIENT%\src\main\resources\*" "%STAGE%\" >nul
for %%A in ("!ASM_CP:~1!") do echo %%A
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$stage = '%STAGE%'; $asm = '!ASM_CP:~1!'.Split(';') | Where-Object { $_ };" ^
  "Add-Type -AssemblyName System.IO.Compression.FileSystem;" ^
  "foreach ($j in $asm) { $z = [System.IO.Compression.ZipFile]::OpenRead($j); foreach ($e in $z.Entries) {" ^
  "  if ($e.FullName -like 'META-INF/*' -or $e.FullName.EndsWith('/') ) { continue };" ^
  "  $dest = Join-Path $stage $e.FullName;" ^
  "  $dir = Split-Path $dest -Parent; if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null };" ^
  "  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true) }; $z.Dispose() }"
if errorlevel 1 (
    echo ERROR: could not unpack ASM into the jar staging directory.
    exit /b 1
)

rem ASM moves out of org.objectweb.asm here, into xyz/daikirai/shadow.
rem The game's own class path already carries an ASM (Forge ships one), the
rem system loader serves that one for the package, and a mix of two ASM
rem versions is an IllegalArgumentException from inside ClassVisitor.<init>
rem the first time the client tries to rewrite anything. See
rem tools/relocate/Relocate.java for the full story.
if not exist "%OUT%\relocate" mkdir "%OUT%\relocate" 2>nul
"%JAVAC%" -nowarn -encoding UTF-8 -cp "!ASM_CP:~1!" -d "%OUT%\relocate" ^
   "%ROOT%tools\relocate\Relocate.java"
if errorlevel 1 (
    echo ERROR: could not compile the ASM relocation tool.
    exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -cp "%OUT%\relocate;!ASM_CP:~1!" Relocate "%STAGE%"
if errorlevel 1 (
    echo ERROR: could not relocate ASM into xyz/daikirai/shadow.
    exit /b 1
)
rem The client itself moves to the game's naming here, the way InjectMyau's
rem jar arrives already reobfuscated. Everything downstream -- the hook table,
rem the callbacks, the accessors -- is then written in the names the game
rem actually has, and a running game does no rewriting of its own.
rem The table the reobf and the running game read is one file, always at the
rem same name inside the jar: which one it is depends on the naming this jar is
rem built for. An MCP jar needs none of it.
if /i "%NAMING%"=="notch" (
    copy /y "%STAGE%\openraven_mappings_notch.tsv" "%STAGE%\openraven_mappings.tsv" >nul
)
if /i "%NAMING%"=="mcp" (
    del /q "%STAGE%\openraven_mappings.tsv" 2>nul
)
del /q "%STAGE%\openraven_mappings_notch.tsv" 2>nul
if not exist "%OUT%\reobf" mkdir "%OUT%\reobf" 2>nul
"%JAVAC%" -nowarn -encoding UTF-8 -cp "%STAGE%" -d "%OUT%\reobf" ^
   "%ROOT%tools\reobf\Reobf.java"
if errorlevel 1 (
    echo ERROR: could not compile the reobf tool.
    exit /b 1
)
if /i "%NAMING%"=="mcp" (
    echo the jar keeps its MCP names -- nothing to rewrite
) else (
    "%JAVA_HOME%\bin\java.exe" -cp "%OUT%\reobf;%STAGE%;!CP:~1!" Reobf "%STAGE%"
    if errorlevel 1 (
        echo ERROR: could not rewrite the client to the game's naming.
        exit /b 1
    )
)
rem Every reference the reobf produced is checked against the game's own jars
rem here. A rename is only correct if the class it lands on really has that
rem member, and the mapping table cannot see that: it is keyed by the member's
rem declaring class, and its flat fallback answers for any class at all. That
rem is how a field of a Forge event class became a Minecraft field name and the
rem game threw NoSuchFieldError once per frame for as long as it was open. See
rem tools/checkrefs/CheckRefs.java.
rem The mcp build is checked against the MCP Minecraft jar alone: Forge's own
rem classes are not what it will run against -- the shim in the jar is.
set "GAME_JARS="
for /f "usebackq delims=" %%L in (`powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$home_ = $env:USERPROFILE;" ^
  "$loom = Join-Path ($home_ + '\.gradle\caches') 'essential-loom\1.8.9\de.oceanlabs.mcp.mcp_stable.1_8_9.22-1.8.9-forge-1.8.9-11.15.1.2318-1.8.9';" ^
  "$srg = @((Join-Path $loom 'minecraft-srg.jar'), (Join-Path $loom 'forge\forge-srg.jar'));" ^
  "$mcp = @((Join-Path $loom 'minecraft-mapped.jar'));" ^
  "$notch = @((Join-Path $home_ 'AppData\Roaming\.minecraft\versions\1.8.9\1.8.9.jar'));" ^
  "$pick = $srg; if ('%NAMING%' -eq 'mcp') { $pick = $mcp }; if ('%NAMING%' -eq 'notch') { $pick = $notch };" ^
  "foreach ($x in $pick) { if (Test-Path $x) { Write-Output $x } }"`) do set "GAME_JARS=!GAME_JARS!;%%L"
if "!GAME_JARS!"=="" (
    echo ERROR: the jars to check the %NAMING% build against are missing.
    exit /b 1
)
if not exist "%OUT%\checkrefs" mkdir "%OUT%\checkrefs" 2>nul
"%JAVAC%" -nowarn -encoding UTF-8 -cp "!ASM_CP:~1!" -d "%OUT%\checkrefs" "%ROOT%tools\checkrefs\CheckRefs.java"
if errorlevel 1 (
    echo ERROR: could not compile the reference checker.
    exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -cp "%OUT%\checkrefs;!ASM_CP:~1!" CheckRefs "%STAGE%" "!GAME_JARS:~1!"
if errorlevel 1 (
    echo ERROR: the reobfuscated client has references the game cannot resolve.
    exit /b 1
)

rem Tells the runtime which naming this jar is already in: it then knows both
rem that it must not rewrite the classes a second time and which member names
rem the hooks have to match.
> "%STAGE%\openraven-naming.txt" echo %NAMING%
"%JAR%" --create --file "%DIST%\%CLIENT_JAR%" -C "%STAGE%" .
if errorlevel 1 (
    echo ERROR: could not build the jar.
    exit /b 1
)
echo built %DIST%\%CLIENT_JAR%

rem The hook table is a list of strings, so no compiler checks it: a rename it
rem cannot resolve is a module that quietly does nothing, and until this ran the
rem only place that showed up was a live run. tools/bytecode reads the game's own
rem classes out of the jars this naming runs against, puts each of them through
rem the real transformer, and says per hook which method it landed in and what
rem the instruction next to it is. A required hook that cannot be placed fails
rem the build.
rem It resolves names exactly the way the running transformer does, through
rem MappingBridge and the same table, so this is the same answer the game gets --
rem without the game.
if not exist "%OUT%\bytecode" mkdir "%OUT%\bytecode" 2>nul
"%JAVAC%" -nowarn -encoding UTF-8 --release 8 -cp "%DIST%\%CLIENT_JAR%" -d "%OUT%\bytecode" ^
   "%ROOT%tools\bytecode\xyz\daikirai\inject\BytecodeCheck.java"
if errorlevel 1 (
    echo ERROR: could not compile the hook placement checker.
    exit /b 1
)
rem The game jars are the ones checkrefs just used, one block up: the bytes the
rem hooks have to be resolvable against for this naming.
"%JAVA_HOME%\bin\java.exe" -cp "%OUT%\bytecode;%DIST%\%CLIENT_JAR%" ^
   xyz.daikirai.inject.BytecodeCheck "%DIST%\%CLIENT_JAR%" --game "!GAME_JARS:~1!"
if errorlevel 1 (
    echo ERROR: the hook table does not land on this client -- see the lines above.
    exit /b 1
)

if /i "%STAGE_ONLY%"=="client" goto :done

rem ============================================================== the natives ==
:native
echo.
echo === stage 2: loader ===

if not exist "%DIST%\OpenRaven-Inject.jar" (
    echo ERROR: the srg client jar is missing. Run "build.cmd client srg" first.
    exit /b 1
)
if not exist "%DIST%\OpenRaven-Inject-mcp.jar" (
    echo ERROR: the mcp client jar is missing. Run "build.cmd client mcp" first.
    exit /b 1
)
if not exist "%DIST%\OpenRaven-Inject-notch.jar" (
    echo ERROR: the notch client jar is missing. Run "build.cmd client notch" first.
    exit /b 1
)
if not exist "%VCVARS%" (
    echo ERROR: vcvars64.bat not found at "%VCVARS%". Set VCVARS in this script.
    exit /b 1
)

rem JAVA_INCLUDE points at a JDK, not a JRE: jni.h and jvmti.h are only in a JDK.
set "JAVA_INCLUDE=%JAVA_HOME%\include"

call "%VCVARS%" >nul
if errorlevel 1 (
    echo ERROR: vcvars64.bat failed.
    exit /b 1
)

mkdir "%OUT%\native" 2>nul

rem The resource scripts name the payload inline, because rc.exe resolves
rem relative paths against its own working directory.
set "RC_DLL=%OUT%\native\native_resources.rc"
set "RC_EXE=%OUT%\native\loader_resources.rc"
rem Forward slashes in the resource scripts, not backslashes: rc.exe treats a
rem backslash in a quoted string as an escape, so a path containing \n or \t
rem silently becomes a path containing a newline or a tab and the file is then
rem "not found". This cost an afternoon once.
set "DIST_FWD=%DIST:\=/%"
set "NATIVE_FWD=%OUT:\=/%/native"
set "MANIFEST_FWD=%NATIVES:\=/%/loader/app.manifest"
rem All three jars are carried, and the agent picks one by looking at the names
rem the game is running with -- see detectNaming in dllmain.cpp. They are about
rem 2 MB each and a loader that has to be rebuilt because the client was is
rem worse than a loader that is 4 MB bigger.
> "%RC_DLL%" echo #define IDR_EMBEDDED_JAR_SRG 101
>>"%RC_DLL%" echo IDR_EMBEDDED_JAR_SRG RCDATA "%DIST_FWD%/OpenRaven-Inject.jar"
>>"%RC_DLL%" echo #define IDR_EMBEDDED_JAR_MCP 102
>>"%RC_DLL%" echo IDR_EMBEDDED_JAR_MCP RCDATA "%DIST_FWD%/OpenRaven-Inject-mcp.jar"
>>"%RC_DLL%" echo #define IDR_EMBEDDED_JAR_NOTCH 103
>>"%RC_DLL%" echo IDR_EMBEDDED_JAR_NOTCH RCDATA "%DIST_FWD%/OpenRaven-Inject-notch.jar"
> "%RC_EXE%" echo #define IDR_EMBEDDED_AGENT 101
>>"%RC_EXE%" echo IDR_EMBEDDED_AGENT RCDATA "%NATIVE_FWD%/openraven_native.dll"
>>"%RC_EXE%" echo 1 24 "%MANIFEST_FWD%"

echo compiling the agent
cl /nologo /c /W3 /EHsc /O2 /MT /Fo"%OUT%\native\\" /I"%JAVA_INCLUDE%" /I"%JAVA_INCLUDE%\win32" ^
   "%NATIVES%\dll\dllmain.cpp"
if errorlevel 1 (
    echo ERROR: the agent did not compile.
    exit /b 1
)

rc /nologo /fo "%OUT%\native\native_resources.res" "%RC_DLL%"
if errorlevel 1 (
    echo ERROR: the agent resources did not compile.
    exit /b 1
)

echo linking the agent
rem Standard DLL link, exactly what InjectMyau does. No /NODEFAULTLIB and no
rem /ENTRY: the CRT's own startup is what calls DllMain, and replacing it
rem produces a DLL that maps into the target and then never runs.
link /nologo /DLL /OUT:"%OUT%\native\openraven_native.dll" ^
     "%OUT%\native\dllmain.obj" "%OUT%\native\native_resources.res" ^
     kernel32.lib user32.lib
if errorlevel 1 (
    echo ERROR: the agent did not link.
    exit /b 1
)

echo compiling the loader
cl /nologo /c /W3 /EHsc /O2 /MT /Fo"%OUT%\native\\" ^
   "%NATIVES%\loader\main.cpp" "%NATIVES%\loader\inject.cpp" "%NATIVES%\loader\target.cpp"
if errorlevel 1 (
    echo ERROR: the loader did not compile.
    exit /b 1
)

rc /nologo /fo "%OUT%\native\loader_resources.res" "%RC_EXE%"
if errorlevel 1 (
    echo ERROR: the loader resources did not compile.
    exit /b 1
)

echo linking the loader
link /nologo /SUBSYSTEM:CONSOLE /OUT:"%DIST%\openraven_loader.exe" ^
     "%OUT%\native\main.obj" "%OUT%\native\inject.obj" "%OUT%\native\target.obj" ^
     "%OUT%\native\loader_resources.res" psapi.lib advapi32.lib user32.lib shell32.lib
if errorlevel 1 (
    echo ERROR: the loader did not link.
    exit /b 1
)

echo built %DIST%\openraven_loader.exe

rem The agent has to be copied out here as well, and the copy has to be checked.
rem It was not, once, and the failure is silent in the worst way: the previous
rem injection still has the old agent mapped, Windows refuses to overwrite a
rem mapped file, and a copy that fails leaves a stale openraven_agent.dll where
rem tools\launch\inject.ps1 looks for it. Every later injection then stages the
rem *old* agent -- and with it the old embedded jar -- while the log says the
rem injection succeeded, the loader is freshly built, and the source changes are
rem simply not in the game. What that looks like from the inside: a fix that
rem works offline and leaves no trace in the live log.
copy /y "%OUT%\native\openraven_native.dll" "%DIST%\openraven_agent.dll" >nul
if errorlevel 1 (
    echo ERROR: could not refresh %DIST%\openraven_agent.dll.
    echo A running game probably still has the old one mapped: close it, or run
    echo "build.cmd native" again before injecting.
    exit /b 1
)
for %%F in ("%DIST%\openraven_agent.dll") do set "AGENT_SIZE=%%~zF"
for %%F in ("%OUT%\native\openraven_native.dll") do set "AGENT_BUILT=%%~zF"
if not "%AGENT_SIZE%"=="%AGENT_BUILT%" (
    echo ERROR: %DIST%\openraven_agent.dll is %AGENT_SIZE% bytes but the build is
    echo %AGENT_BUILT% -- the copy above did not take.
    exit /b 1
)
echo built %DIST%\openraven_agent.dll

:done
echo.
echo === done ===
dir /b "%DIST%"
endlocal
exit /b 0
