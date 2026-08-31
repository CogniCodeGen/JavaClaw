@echo off
setlocal
set "APP_ROOT=%~dp0.."
if not defined JAVACLAW_PROGRAM_DIR set "JAVACLAW_PROGRAM_DIR=%APP_ROOT%"
"%APP_ROOT%\runtime\bin\java.exe" -cp "%APP_ROOT%\lib\*" com.javaclaw.launcher.JavaClawLauncher %*
exit /b %ERRORLEVEL%
