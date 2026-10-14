@echo off
rem Runs the loader and keeps its console output, because a loader started by
rem anything other than a console has nowhere to print.
set "HERE=%~dp0"
"%~dp0..\..\dist\openraven_loader.exe" %* > "%~dp0..\..\dist\loader-run.txt" 2>&1
echo exit=%errorlevel% >> "%~dp0..\..\dist\loader-run.txt"
