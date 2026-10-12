#!/usr/bin/env sh
# 主程序的类路径显式排除独立 Runner。
set -eu
APP_RUNTIME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java --add-modules jdk.incubator.vector,jdk.httpserver --enable-native-access=ALL-UNNAMED -cp "$APP_RUNTIME/desktop.jar:$APP_RUNTIME/lib/*" com.javaclaw.app.Launcher "$@"
