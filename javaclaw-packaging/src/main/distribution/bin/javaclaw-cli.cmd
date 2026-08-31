@echo off
setlocal
set "APP_ROOT=%~dp0.."
if not defined JAVACLAW_PROGRAM_DIR set "JAVACLAW_PROGRAM_DIR=%APP_ROOT%"
set "APP_ROOT_JSON=%APP_ROOT:\=/%"
set "JAVACLAW_APP_SERVER_CLASSPATH=%APP_ROOT%\lib\*"
set "JAVACLAW_SANDBOX_MODULE_PATH=%APP_ROOT%\native-module-path"
set "JAVACLAW_BROWSER_SERVICE_LIB=%APP_ROOT%\lib"
set "JAVACLAW_BROWSER_ASSET_DIR=%APP_ROOT%\lib\ms-playwright"
set "JAVACLAW_BROWSER_SERVICE_COMMAND_JSON=["%APP_ROOT_JSON%/runtime/bin/java.exe","-cp","%APP_ROOT_JSON%/lib/*","com.javaclaw.browser.BrowserServiceMain"]"
set "JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON=["%APP_ROOT_JSON%/runtime/bin/java.exe","--module-path","%APP_ROOT_JSON%/native-module-path","--add-modules","com.javaclaw.nativehosts","--enable-native-access=com.javaclaw.nativehosts","-m","com.javaclaw.nativehosts/com.javaclaw.nativehost.transport.windows.WindowsTransportHostMain"]"
"%APP_ROOT%\runtime\bin\java.exe" -cp "%APP_ROOT%\lib\*" com.javaclaw.cli.JavaClawCli %*
