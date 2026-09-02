@echo off
setlocal
set "APP_ROOT=%~dp0.."
if not defined JAVACLAW_STATE_ROOT set "JAVACLAW_STATE_ROOT=%USERPROFILE%\.javaclaw"
if not defined JAVACLAW_DATA_ROOT set "JAVACLAW_DATA_ROOT=%JAVACLAW_STATE_ROOT%\data-v5"
if not exist "%JAVACLAW_DATA_ROOT%\logs" mkdir "%JAVACLAW_DATA_ROOT%\logs" || exit /b 6
"%APP_ROOT%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -Djavaclaw.data.root="%JAVACLAW_DATA_ROOT%" -Djavaclaw.log.dir="%JAVACLAW_DATA_ROOT%\logs" -Djavaclaw.program.dir="%APP_ROOT%" -cp "%APP_ROOT%\lib\*" com.javaclaw.launcher.AppServerLauncher --pipe-default
exit /b %ERRORLEVEL%
