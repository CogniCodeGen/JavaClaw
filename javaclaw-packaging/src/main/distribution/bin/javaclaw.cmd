@echo off
setlocal
set "APP_ROOT=%~dp0.."
"%APP_ROOT%\runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -Djavaclaw.tray.launcher=true -Djavaclaw.program.dir="%APP_ROOT%" -cp "%APP_ROOT%\lib\*" com.javaclaw.launcher.JavaClawLauncher %*
exit /b %ERRORLEVEL%
