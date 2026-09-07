@echo off
setlocal
set "APP_ROOT=%~dp0.."
set "HEALTH_ROOT=%TEMP%\javaclaw-health-%RANDOM%-%RANDOM%"
if exist "%HEALTH_ROOT%" exit /b 6
mkdir "%HEALTH_ROOT%" || exit /b 6
"%APP_ROOT%\runtime\bin\java.exe" ^
    -cp "%APP_ROOT%\lib\*" ^
    com.javaclaw.client.cli.JavaClawCli workspace-list -- ^
    "%APP_ROOT%\runtime\bin\java.exe" ^
    --enable-native-access=ALL-UNNAMED ^
    "-Djavaclaw.program.dir=%APP_ROOT%" ^
    "-Djavaclaw.data.root=%HEALTH_ROOT%\data-v6" ^
    "-Djavaclaw.log.dir=%HEALTH_ROOT%\data-v6\logs" ^
    -Djavaclaw.log.process=health-server ^
    -cp "%APP_ROOT%\lib\*" ^
    com.javaclaw.launcher.AppServerLauncher --health-check
set "RESULT=%ERRORLEVEL%"
rmdir /s /q "%HEALTH_ROOT%"
exit /b %RESULT%
