#!/usr/bin/env bash
set -euo pipefail

# Linux 镜像包含主程序、运行依赖与 JDK；此版本的桌面原生 Provider 不可用。
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$project_dir/target/portable/JavaClaw"
runtime_dir="${JAVACLAW_DISTRIBUTION_RUNTIME:-$project_dir/distribution/target/runtime}"
host_jar="${JAVACLAW_HOST_JAR:-$runtime_dir/desktop.jar}"
runner_jar="$runtime_dir/plugin-runner.jar"
desktop_libs="$runtime_dir/lib"
# 链接完整 JDK 模块，避免遗漏反射或服务发现依赖；插件启用模块仍须单独声明并批准。
runtime_modules="ALL-MODULE-PATH"
offline=false
fail() { echo "portable Linux assembly: $*" >&2; exit 1; }

while (( $# > 0 )); do
  case "$1" in
    --offline) offline=true ;;
    --output) shift; (( $# > 0 )) || fail "--output needs a path"; output="$1" ;;
    *) fail "unknown argument: $1" ;;
  esac
  shift
done

[[ "$(uname -s)" == Linux ]] || fail "requires Linux"

[[ -f "$host_jar" && ! -L "$host_jar" ]] || fail "set JAVACLAW_HOST_JAR to a built host JAR"
[[ ! -e "$output" && ! -L "$output" ]] || fail "output already exists: $output"
[[ -f "$runner_jar" && -d "$desktop_libs" ]] || fail "run mvn package to assemble independent Desktop and Runner artifacts"
command -v jpackage >/dev/null || fail "JDK 25 jpackage is required"
[[ "$(jpackage --version)" == 25* ]] || fail "JDK 25 jpackage is required"

mkdir -p "$project_dir/target" "$(dirname "$output")"
stage="$(mktemp -d "$project_dir/target/.portable-linux.XXXXXXXX")"
trap 'rm -rf "$stage"' EXIT
input="$stage/input"
portable_home="$stage/home"
mkdir -p "$input" "$portable_home/runtime" "$portable_home/plugins-v2" "$portable_home/data-v5"
cp "$host_jar" "$input/javaclaw.jar"
# Desktop 的输入只包含宿主与协议库；Runner 位于输入目录之外。
cp "$desktop_libs"/*.jar "$input/"
cp "$runner_jar" "$portable_home/runtime/plugin-runner.jar"
jpackage --type app-image --dest "$stage/image" --name JavaClaw \
  --input "$input" --main-jar javaclaw.jar --add-modules "$runtime_modules" --main-class com.javaclaw.app.Launcher \
  --jlink-options '--strip-debug --no-man-pages --no-header-files' \
  --java-options '--add-modules=jdk.incubator.vector,jdk.httpserver' \
  --java-options '--enable-native-access=ALL-UNNAMED'
mv "$stage/image/JavaClaw" "$portable_home/runtime/JavaClaw"
packages="$runtime_dir/plugin-packages"
if [[ -d "$packages" ]]; then cp -R "$packages" "$portable_home/runtime/plugin-packages"; fi
manifest="$runtime_dir/plugin-distribution.json"
if [[ -f "$manifest" ]]; then cp "$manifest" "$portable_home/runtime/plugin-distribution.json"; fi

[[ -z "$(find "$portable_home/plugins-v2" "$portable_home/data-v5" -type l -print -quit)" ]] \
  || fail "managed writable directories contain a symbolic link"
runtime_root="$(realpath "$portable_home/runtime")"
while IFS= read -r link; do
  resolved="$(realpath "$link")" || fail "broken runtime symbolic link: $link"
  case "$resolved" in "$runtime_root/"*) ;; *) fail "runtime symbolic link escapes: $link" ;; esac
done < <(find "$portable_home/runtime" -type l -print)
java="$portable_home/runtime/JavaClaw/lib/runtime/bin/java"
[[ -f "$java" && ! -L "$java" && -x "$java" ]] || fail "missing regular bundled Java executable: $java"
"$java" -version
chmod -R a-w "$portable_home/runtime"
mv "$portable_home" "$output"
[[ -w "$output" && -w "$output/plugins-v2" && -w "$output/data-v5" ]] || fail "the outer home must remain writable"
echo "$output"
