#!/bin/sh
set -eu
JPACKAGE=$1
TYPE=$2
DESTINATION=$3
INPUT=$4
RUNTIME=$5
MAIN_JAR=$6
MAIN_CLASS=$7
VERSION=$8
APP_IMAGE_ROOT=$9
APP_IMAGE="$APP_IMAGE_ROOT/JavaClaw.app"
APP_LAUNCHER="$APP_IMAGE/Contents/MacOS/JavaClaw"
SMOKE_LOG="$DESTINATION/JavaClaw-app-image-smoke.log"

if [ ! -x "$APP_LAUNCHER" ]; then
    mkdir -p "$APP_IMAGE_ROOT"
    if [ -n "${JAVACLAW_APPLE_SIGN_IDENTITY:-}" ]; then
        "$JPACKAGE" --type app-image --name JavaClaw --app-version "$VERSION" \
            --vendor JavaClaw --input "$INPUT" --runtime-image "$RUNTIME" \
            --main-jar "$MAIN_JAR" --main-class "$MAIN_CLASS" \
            --java-options '-Djavaclaw.program.dir=$APPDIR' \
            --mac-sign --mac-signing-key-user-name "$JAVACLAW_APPLE_SIGN_IDENTITY" \
            --dest "$APP_IMAGE_ROOT"
    else
        "$JPACKAGE" --type app-image --name JavaClaw --app-version "$VERSION" \
            --vendor JavaClaw --input "$INPUT" --runtime-image "$RUNTIME" \
            --main-jar "$MAIN_JAR" --main-class "$MAIN_CLASS" \
            --java-options '-Djavaclaw.program.dir=$APPDIR' --dest "$APP_IMAGE_ROOT"
    fi
fi

# 先运行真实 .app 启动器，依赖布局错误会在 DMG/PKG 封装前失败并保留日志。
"$APP_LAUNCHER" --help >"$SMOKE_LOG" 2>&1
grep -q "JavaClaw 4.0" "$SMOKE_LOG"

if [ -n "${JAVACLAW_APPLE_SIGN_IDENTITY:-}" ]; then
    exec "$JPACKAGE" --type "$TYPE" --name JavaClaw --app-image "$APP_IMAGE" --mac-sign \
        --mac-signing-key-user-name "$JAVACLAW_APPLE_SIGN_IDENTITY" --dest "$DESTINATION"
fi

exec "$JPACKAGE" --type "$TYPE" --name JavaClaw --app-image "$APP_IMAGE" --dest "$DESTINATION"
