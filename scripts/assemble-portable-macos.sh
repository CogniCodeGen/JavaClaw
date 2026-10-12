#!/usr/bin/env bash
set -euo pipefail

# Assemble a signed, self-contained macOS app image in a writable outer home.
# Existing output is deliberately never replaced.
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$project_dir/target/portable/JavaClaw"
runtime_dir="${JAVACLAW_DISTRIBUTION_RUNTIME:-$project_dir/distribution/target/runtime}"
host_jar="${JAVACLAW_HOST_JAR:-$runtime_dir/desktop.jar}"
runner_jar="$runtime_dir/plugin-runner.jar"
desktop_libs="$runtime_dir/lib"
# 链接完整 JDK 模块，避免遗漏反射或服务发现依赖；插件启用模块仍须单独声明并批准。
runtime_modules="ALL-MODULE-PATH"
identity="${CODESIGN_IDENTITY:-}"
development_unsigned=false

fail() { echo "portable macOS assembly: $*" >&2; exit 1; }

verify_bundled_java() {
  local java="$app/Contents/runtime/Contents/Home/bin/java"
  local runtime_root resolved
  [[ -f "$java" && ! -L "$java" && -x "$java" ]] \
    || fail "bundled Java must be a regular executable: $java"
  runtime_root="$(realpath "$portable_home/runtime")" || fail "cannot resolve runtime directory"
  resolved="$(realpath "$java")" || fail "cannot resolve bundled Java: $java"
  case "$resolved" in "$runtime_root/"*) ;; *) fail "bundled Java escapes runtime: $java" ;; esac
  # 检查内置 Java 可以启动；插件模块权限由各自清单声明。
  "$java" -version \
    || fail "bundled Java cannot start"
}

while (( $# > 0 )); do
  case "$1" in
    --development-unsigned) development_unsigned=true ;;
    --offline)
      # 兼容历史参数；装配仅使用已有工件，无需执行额外操作。
      : ;;
    --output) shift; (( $# > 0 )) || fail "--output needs a path"; output="$1" ;;
    *) fail "unknown argument: $1" ;;
  esac
  shift
done

[[ "$(uname -s)" == Darwin ]] || fail "requires macOS"

if ! "$development_unsigned"; then
  [[ "$identity" == "Developer ID Application:"* ]] \
    || fail "set CODESIGN_IDENTITY to a Developer ID Application identity"
fi
[[ -f "$host_jar" && ! -L "$host_jar" ]] \
  || fail "missing host JAR; run 'mvn -DskipTests package' first or set JAVACLAW_HOST_JAR"
[[ ! -e "$output" && ! -L "$output" ]] || fail "output already exists: $output"
[[ -f "$runner_jar" && -d "$desktop_libs" ]] || fail "run mvn package to assemble independent Desktop and Runner artifacts"
command -v jpackage >/dev/null || fail "JDK 25 jpackage is required"
command -v codesign >/dev/null || fail "codesign is required"
command -v plutil >/dev/null || fail "plutil is required"
[[ "$(jpackage --version)" == 25* ]] || fail "JDK 25 jpackage is required"

mkdir -p "$project_dir/target" "$(dirname "$output")"
stage="$(mktemp -d "$project_dir/target/.portable-macos.XXXXXXXX")"
trap 'rm -rf "$stage"' EXIT
input="$stage/input"
portable_home="$stage/home"
mkdir -p "$input" "$portable_home/runtime" "$portable_home/plugins" "$portable_home/data"

cp "$host_jar" "$input/javaclaw.jar"
# Desktop 的输入只包含宿主与协议库；Runner 位于输入目录之外。
cp "$desktop_libs"/*.jar "$input/"
cp "$runner_jar" "$portable_home/runtime/plugin-runner.jar"

jpackage_args=(--type app-image --dest "$stage/image" --name JavaClaw
  --input "$input" --main-jar javaclaw.jar --add-modules "$runtime_modules"
  --main-class com.javaclaw.app.Launcher
  # 保留服务插件需要的 bin/java，继续使用其他默认裁剪选项。
  --jlink-options '--strip-debug --no-man-pages --no-header-files'
  --java-options '--add-modules=jdk.incubator.vector,jdk.httpserver'
  --java-options '--enable-native-access=ALL-UNNAMED')
if ! "$development_unsigned"; then
  jpackage_args+=(--mac-sign --mac-app-image-sign-identity "$identity")
fi
jpackage "${jpackage_args[@]}"

app="$stage/image/JavaClaw.app"
[[ -d "$app" ]] || fail "jpackage did not produce JavaClaw.app"
mv "$app" "$portable_home/runtime/JavaClaw.app"
app="$portable_home/runtime/JavaClaw.app"
verify_bundled_java
bundled_java="$app/Contents/runtime/Contents/Home/bin/java"
plist="$app/Contents/Info.plist"
[[ -f "$plist" && ! -L "$plist" ]] || fail "jpackage did not produce Info.plist"
screen_capture_purpose="JavaClaw 需要读取你指定的应用窗口画面，以便智能体观察和操作该应用。"
plutil -replace NSScreenCaptureUsageDescription -string "$screen_capture_purpose" "$plist"
[[ "$(plutil -extract NSScreenCaptureUsageDescription raw "$plist")" == "$screen_capture_purpose" ]] \
  || fail "screen capture usage description was not written"
# Editing Info.plist invalidates jpackage's app signature. Re-sign the outer
# bundle after the edit; its nested runtime signatures remain untouched.
if "$development_unsigned"; then
  codesign --force --sign - "$app"
else
  codesign --force --options runtime --timestamp --sign "$identity" "$app"
fi

packages="$runtime_dir/plugin-packages"
if [[ -d "$packages" ]]; then cp -R "$packages" "$portable_home/runtime/plugin-packages"; fi
manifest="$runtime_dir/plugin-distribution.json"
if [[ -f "$manifest" ]]; then cp "$manifest" "$portable_home/runtime/plugin-distribution.json"; fi

[[ -z "$(find "$portable_home/plugins" "$portable_home/data" -type l -print -quit)" ]] \
  || fail "managed writable directories contain a symbolic link"
# jpackage's bundled JDK has legitimate internal legal/ symlinks. Keep them
# only if their canonical targets remain inside the signed runtime tree.
while IFS= read -r link; do
  resolved="$(realpath "$link")" || fail "broken runtime symbolic link: $link"
  case "$resolved" in "$portable_home/runtime/"*) ;; *) fail "runtime symbolic link escapes: $link" ;; esac
done < <(find "$portable_home/runtime" -type l -print)
codesign --verify --deep --strict --verbose=2 "$app"
codesign --verify --strict --verbose=2 "$bundled_java"
if ! "$development_unsigned"; then
  for signed in "$app" "$bundled_java"; do
    signature_details="$(codesign --display --verbose=4 "$signed" 2>&1)" \
      || fail "cannot inspect signature: $signed"
    [[ "$signature_details" == *"Authority=Developer ID Application:"* ]] \
      || fail "missing a Developer ID Application signature: $signed"
  done
fi
verify_bundled_java

# 运行时保持只读；plugins/ 与 data/ 保存可写用户数据。
chmod -R a-w "$portable_home/runtime"
mv "$portable_home" "$output"
[[ -w "$output" && -w "$output/plugins" && -w "$output/data" ]] \
  || fail "the outer home must remain writable: $output"
echo "$output"
