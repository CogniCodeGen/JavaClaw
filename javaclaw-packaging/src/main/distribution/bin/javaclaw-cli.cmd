@echo off
setlocal
set "APP_ROOT=%~dp0.."
if not defined JAVACLAW_DATA_ROOT set "JAVACLAW_DATA_ROOT=%APP_ROOT%\data-v6"
"%APP_ROOT%\runtime\bin\java.exe" -cp "%APP_ROOT%\lib\*" com.javaclaw.client.cli.JavaClawCli %* -- "%APP_ROOT%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -Djavaclaw.data.root="%JAVACLAW_DATA_ROOT%" -Djavaclaw.program.dir="%APP_ROOT%" -cp "%APP_ROOT%\lib\*" com.javaclaw.launcher.AppServerLauncher --stdio
exit /b %ERRORLEVEL%
