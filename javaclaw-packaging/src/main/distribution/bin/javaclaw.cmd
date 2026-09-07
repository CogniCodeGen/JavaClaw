@echo off
setlocal
set "APP_ROOT=%~dp0.."
if not defined JAVACLAW_DATA_ROOT set "JAVACLAW_DATA_ROOT=%APP_ROOT%\data-v6"
"%APP_ROOT%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -Djavaclaw.tray.launcher=true -Djavaclaw.data.root="%JAVACLAW_DATA_ROOT%" -Djavaclaw.program.dir="%APP_ROOT%" -cp "%APP_ROOT%\lib\*" com.javaclaw.launcher.JavaClawLauncher %*
exit /b %ERRORLEVEL%
