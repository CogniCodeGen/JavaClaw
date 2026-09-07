@echo off
setlocal
set "APP_ROOT=%~dp0.."
if not defined JAVACLAW_DATA_ROOT set "JAVACLAW_DATA_ROOT=%APP_ROOT%\data-v6"
if "%~1"=="" goto launch
if not "%~1"=="--data-root" exit /b 6
if "%~2"=="" exit /b 6
if not "%~3"=="" exit /b 6
set "JAVACLAW_DATA_ROOT=%~2"
:launch
"%APP_ROOT%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -Djavaclaw.data.root="%JAVACLAW_DATA_ROOT%" -Djavaclaw.log.dir="%JAVACLAW_DATA_ROOT%\logs" -Djavaclaw.program.dir="%APP_ROOT%" -cp "%APP_ROOT%\lib\*" com.javaclaw.launcher.AppServerLauncher --pipe-default
exit /b %ERRORLEVEL%
