@echo off
setlocal DisableDelayedExpansion
if not "%~2"=="" goto invalid
if /i "%~1"=="--help" goto help
if /i "%~1"=="-h" goto help
if /i "%~1"=="--no-build" goto launch
if not "%~1"=="" goto invalid

where mvn.cmd >nul 2>nul
if errorlevel 1 (
    echo Maven 3.9+ is required. Install it and select JDK 25, then retry. 1>&2
    exit /b 1
)
pushd "%~dp0"
if errorlevel 1 exit /b 1
call mvn.cmd -B -DskipTests -Djavaclaw.distribution -pl javaclaw-packaging -am package
set "JAVACLAW_BUILD_RESULT=%ERRORLEVEL%"
popd
if not "%JAVACLAW_BUILD_RESULT%"=="0" exit /b %JAVACLAW_BUILD_RESULT%

:launch
set "JAVACLAW_DISTRIBUTION=%~dp0javaclaw-packaging\target\distribution"
if not exist "%JAVACLAW_DISTRIBUTION%\runtime\bin\java.exe" goto missing
if not exist "%JAVACLAW_DISTRIBUTION%\lib\com.javaclaw.javaclaw-packaging.jar" goto missing
if not exist "%JAVACLAW_DISTRIBUTION%\bin\javaclaw.cmd" goto missing
pushd "%~dp0"
if errorlevel 1 exit /b 1
call "%JAVACLAW_DISTRIBUTION%\bin\javaclaw.cmd"
set "JAVACLAW_RUN_RESULT=%ERRORLEVEL%"
popd
exit /b %JAVACLAW_RUN_RESULT%

:missing
echo JavaClaw distribution is missing or incomplete. Run run.cmd without --no-build. 1>&2
exit /b 1

:invalid
echo Usage: run.cmd [--no-build] 1>&2
exit /b 2

:help
echo Usage: run.cmd [--no-build]
echo Default: build with JDK 25 and Maven 3.9+, then start JavaClaw.
echo --no-build: start an existing distribution without Maven.
exit /b 0
