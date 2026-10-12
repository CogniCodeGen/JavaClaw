@echo off
rem 主程序的类路径显式排除独立 Runner。
java --add-modules jdk.incubator.vector,jdk.httpserver --enable-native-access=ALL-UNNAMED -cp "%~dp0desktop.jar;%~dp0lib\*" com.javaclaw.app.Launcher %*
