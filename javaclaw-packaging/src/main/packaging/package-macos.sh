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
WORKERS=${10}
APP_IMAGE="$APP_IMAGE_ROOT/JavaClaw.app"
APP_LAUNCHER="$APP_IMAGE/Contents/MacOS/JavaClaw"
SMOKE_LOG="$DESTINATION/JavaClaw-app-image-smoke.log"

case "$TYPE" in
    dmg|pkg) ;;
    *) echo "unsupported macOS package type: $TYPE" >&2; exit 2 ;;
esac

test -f "$WORKERS/knowledge/worker-image-v1.capability"
test -f "$WORKERS/skill/worker-image-v1.capability"
test -f "$WORKERS/browser/worker-image-v1.capability"
test -f "$WORKERS/browser/browser-login-v1.capability"
test -f "$WORKERS/browser/browser-oauth-v1.capability"

if [ ! -x "$APP_LAUNCHER" ]; then
    mkdir -p "$APP_IMAGE_ROOT"
    "$JPACKAGE" --type app-image --name JavaClaw --app-version "$VERSION" \
        --vendor JavaClaw --input "$INPUT" --runtime-image "$RUNTIME" \
        --main-jar "$MAIN_JAR" --main-class "$MAIN_CLASS" \
        --java-options '--enable-native-access=ALL-UNNAMED' \
        --java-options '-Djavaclaw.program.dir=$APPDIR' --dest "$APP_IMAGE_ROOT"
fi

# 安装镜像必须包含真实启动器；协议健康检查由发行目录中的 javaclaw-health 独立执行。
test -x "$APP_LAUNCHER"
rm -rf -- "$APP_IMAGE/Contents/app/workers"
cp -R "$WORKERS" "$APP_IMAGE/Contents/app/workers"
test -f "$APP_IMAGE/Contents/app/workers/browser/browser-login-v1.capability"
test -f "$APP_IMAGE/Contents/app/workers/browser/browser-oauth-v1.capability"
# 许可证与发布证据必须进入最终安装镜像，并在签名前完成复制。
DISTRIBUTION_ROOT=$(CDPATH= cd -- "$INPUT/.." && pwd)
for DIRECTORY in legal evidence; do
    test -d "$DISTRIBUTION_ROOT/$DIRECTORY"
    rm -rf -- "$APP_IMAGE/Contents/app/$DIRECTORY"
    cp -R "$DISTRIBUTION_ROOT/$DIRECTORY" "$APP_IMAGE/Contents/app/$DIRECTORY"
done
if [ -n "${JAVACLAW_APPLE_SIGN_IDENTITY:-}" ]; then
    /usr/bin/codesign --force --deep --options runtime --timestamp \
        --sign "$JAVACLAW_APPLE_SIGN_IDENTITY" "$APP_IMAGE"
    /usr/bin/codesign --verify --deep --strict "$APP_IMAGE"
fi
: >"$SMOKE_LOG"

# App image 在创建时签名；外层 DMG/PKG 由独立脚本用对应证书签名并完成公证。
exec "$JPACKAGE" --type "$TYPE" --name JavaClaw --app-image "$APP_IMAGE" --dest "$DESTINATION"
