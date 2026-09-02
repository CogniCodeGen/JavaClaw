#!/bin/sh
set -eu

JPACKAGE=$1
TYPE=$2
DESTINATION=$3
APP_IMAGE_ROOT=$4
INPUT=$5
RUNTIME=$6
MAIN_JAR=$7
MAIN_CLASS=$8
VERSION=$9
WORKERS=${10}

case "$TYPE" in
    deb|rpm) ;;
    *) echo "unsupported Linux package type: $TYPE" >&2; exit 2 ;;
esac

APP_IMAGE="$APP_IMAGE_ROOT/JavaClaw"
APP_LAUNCHER="$APP_IMAGE/bin/JavaClaw"
mkdir -p "$APP_IMAGE_ROOT" "$DESTINATION"

test -f "$WORKERS/knowledge/worker-image-v1.capability"
test -f "$WORKERS/skill/worker-image-v1.capability"
test -f "$WORKERS/browser/worker-image-v1.capability"
test -f "$WORKERS/browser/browser-login-v1.capability"
test -f "$WORKERS/browser/browser-oauth-v1.capability"

"$JPACKAGE" --type app-image --name JavaClaw --app-version "$VERSION" \
    --vendor JavaClaw --input "$INPUT" --runtime-image "$RUNTIME" \
    --main-jar "$MAIN_JAR" --main-class "$MAIN_CLASS" \
    --java-options '--enable-native-access=ALL-UNNAMED' \
    --java-options '-Djavaclaw.program.dir=$APPDIR' --dest "$APP_IMAGE_ROOT"

# 只有真实启动器存在时才允许继续生成系统包，避免发布空壳安装器。
test -x "$APP_LAUNCHER"
rm -rf -- "$APP_IMAGE/lib/app/workers"
cp -R "$WORKERS" "$APP_IMAGE/lib/app/workers"
test -f "$APP_IMAGE/lib/app/workers/browser/browser-login-v1.capability"
test -f "$APP_IMAGE/lib/app/workers/browser/browser-oauth-v1.capability"

"$JPACKAGE" --type "$TYPE" --name JavaClaw --app-image "$APP_IMAGE" \
    --linux-package-name javaclaw --dest "$DESTINATION"

FOUND=0
for ARTIFACT in "$DESTINATION"/*."$TYPE"; do
    [ -f "$ARTIFACT" ] || continue
    FOUND=1
done
[ "$FOUND" -eq 1 ] || { echo "jpackage did not create a Linux $TYPE package" >&2; exit 2; }
