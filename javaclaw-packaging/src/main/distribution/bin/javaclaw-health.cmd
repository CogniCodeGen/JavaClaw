@echo off
setlocal
set "APP_ROOT=%~dp0.."
set "HEALTH_ROOT=%TEMP%\javaclaw-health-%RANDOM%-%RANDOM%"
if exist "%HEALTH_ROOT%" exit /b 6
mkdir "%HEALTH_ROOT%" || exit /b 6
call "%APP_ROOT%\bin\javaclaw-cli.cmd" --json --data-dir "%HEALTH_ROOT%\data" --config-dir "%HEALTH_ROOT%\config" --cache-dir "%HEALTH_ROOT%\cache" server-capabilities
set "RESULT=%ERRORLEVEL%"
rmdir /s /q "%HEALTH_ROOT%"
exit /b %RESULT%
