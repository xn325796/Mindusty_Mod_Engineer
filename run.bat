@echo off
rem ============================================================
rem  Mindustry ModMaker launcher (double-click to start)
rem
rem  Optional arguments are forwarded to the application, e.g.:
rem    --import=C:\path\to\mod.zip --projectDir=build\my-import
rem    --import=C:\path\to\mod-folder --projectDir=build\my-import
rem    --open=build\my-import\<project-name>
rem    --snapshot=snapshots\check.png   (screenshot, then exit)
rem  Keep arguments free of spaces (Gradle splits on whitespace).
rem
rem  NOTE: keep this file ASCII-only and CRLF; cmd parses .bat
rem  files in the legacy ANSI codepage on Chinese Windows.
rem ============================================================
setlocal enableextensions
cd /d "%~dp0"

rem --- locate a JDK. Any 17+ starts the wrapper; the build itself
rem     is pinned to JDK 21 via gradle.properties. ---
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDK=%JAVA_HOME%"
if not defined JDK if exist "C:\Program Files\Java\jdk-21.0.12.1\bin\java.exe" set "JDK=C:\Program Files\Java\jdk-21.0.12.1"
if not defined JDK if exist "C:\Users\%USERNAME%\.jdks\ms-21.0.12.1\bin\java.exe" set "JDK=C:\Users\%USERNAME%\.jdks\ms-21.0.12.1"
if not defined JDK (
    for /d %%D in ("C:\Program Files\Java\jdk-*") do if not defined JDK if exist "%%D\bin\java.exe" set "JDK=%%D"
)
if not defined JDK (
    echo [ERROR] No JDK found. Install JDK 17+ or set JAVA_HOME.
    pause
    exit /b 1
)
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"
echo Using JDK: %JDK%

rem --- first run only: scan the Mindustry sources to build the schemas ---
if not exist "schemas\fields.json" (
    echo First run: scanning Mindustry sources for schemas ^(takes a few seconds^) ...
    call gradlew.bat schemaBootstrap
    if errorlevel 1 (
        echo [ERROR] schemaBootstrap failed - is the Mindustry-160.5 source tree next to this project?
        pause
        exit /b 1
    )
)

rem --- launch. The first start compiles incrementally; expect ~10s. ---
if "%~1"=="" (
    call gradlew.bat run
) else (
    call gradlew.bat run --args="%*"
)
if errorlevel 1 pause
endlocal
exit /b 0
