#!/usr/bin/env bash
set -euo pipefail

# Assemble a signed, self-contained macOS app image in a writable outer home.
# Existing output is deliberately never replaced.
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$project_dir/target/portable/JavaClaw"
host_jar="${JAVACLAW_HOST_JAR:-}"
identity="${CODESIGN_IDENTITY:-}"
development_unsigned=false
offline=false

fail() { echo "portable macOS assembly: $*" >&2; exit 1; }

verify_bundled_java() {
  local java="$app/Contents/runtime/Contents/Home/bin/java"
  local runtime_root resolved
  [[ -f "$java" && ! -L "$java" && -x "$java" ]] \
    || fail "bundled Java must be a regular executable: $java"
  runtime_root="$(realpath "$portable_home/runtime")" || fail "cannot resolve runtime directory"
  resolved="$(realpath "$java")" || fail "cannot resolve bundled Java: $java"
  case "$resolved" in "$runtime_root/"*) ;; *) fail "bundled Java escapes runtime: $java" ;; esac
  # 使用服务插件的固定模块与访问参数检查内置 Java。
  "$java" --add-modules jdk.httpserver,jdk.incubator.vector \
    --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED -version \
    || fail "bundled Java cannot start with service-plugin JVM options"
}

while (( $# > 0 )); do
  case "$1" in
    --development-unsigned) development_unsigned=true ;;
    --offline) offline=true ;;
    --output) shift; (( $# > 0 )) || fail "--output needs a path"; output="$1" ;;
    *) fail "unknown argument: $1" ;;
  esac
  shift
done

[[ "$(uname -s)" == Darwin ]] || fail "requires macOS"
if [[ -z "$host_jar" ]]; then
  for candidate in "$project_dir"/target/javaclaw-*.jar; do
    [[ -f "$candidate" ]] || continue
    case "$candidate" in
      *-service-plugin-api.jar|*-sources.jar|*-javadoc.jar|*-tests.jar) ;;
      *) [[ -z "$host_jar" ]] || fail "multiple host JARs found; set JAVACLAW_HOST_JAR"
         host_jar="$candidate" ;;
    esac
  done
fi
if ! "$development_unsigned"; then
  [[ "$identity" == "Developer ID Application:"* ]] \
    || fail "set CODESIGN_IDENTITY to a Developer ID Application identity"
fi
[[ -f "$host_jar" && ! -L "$host_jar" ]] \
  || fail "missing host JAR; run 'mvn -DskipTests package' first or set JAVACLAW_HOST_JAR"
[[ ! -e "$output" && ! -L "$output" ]] || fail "output already exists: $output"
command -v mvn >/dev/null || fail "Maven is required"
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
maven_args=(--batch-mode --no-transfer-progress -q -f "$project_dir/pom.xml")
if "$offline"; then maven_args+=(-o); fi
mvn "${maven_args[@]}" dependency:copy-dependencies \
  -DincludeScope=runtime "-DoutputDirectory=$input"

jpackage_args=(--type app-image --dest "$stage/image" --name JavaClaw
  --input "$input" --main-jar javaclaw.jar
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

plugins="$project_dir/target/distribution/plugins"
if [[ -d "$plugins" ]]; then
  cp -R "$plugins/." "$portable_home/plugins/"
fi

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

# Runtime artifacts are signed and read-only; only plugins/ and data/ remain writable.
chmod -R a-w "$portable_home/runtime"
mv "$portable_home" "$output"
[[ -w "$output" && -w "$output/plugins" && -w "$output/data" ]] \
  || fail "the outer home must remain writable: $output"
echo "$output"
