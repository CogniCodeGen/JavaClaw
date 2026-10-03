#!/usr/bin/env bash
set -euo pipefail

# Linux 镜像包含主程序、运行依赖与 JDK；此版本的桌面原生 Provider 不可用。
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$project_dir/target/portable/JavaClaw"
host_jar="${JAVACLAW_HOST_JAR:-}"
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
[[ -f "$host_jar" && ! -L "$host_jar" ]] || fail "set JAVACLAW_HOST_JAR to a built host JAR"
[[ ! -e "$output" && ! -L "$output" ]] || fail "output already exists: $output"
command -v mvn >/dev/null || fail "Maven is required"
command -v jpackage >/dev/null || fail "JDK 25 jpackage is required"
[[ "$(jpackage --version)" == 25* ]] || fail "JDK 25 jpackage is required"

mkdir -p "$project_dir/target" "$(dirname "$output")"
stage="$(mktemp -d "$project_dir/target/.portable-linux.XXXXXXXX")"
trap 'rm -rf "$stage"' EXIT
input="$stage/input"
portable_home="$stage/home"
mkdir -p "$input" "$portable_home/runtime" "$portable_home/plugins" "$portable_home/data"
cp "$host_jar" "$input/javaclaw.jar"
maven_args=(--batch-mode --no-transfer-progress -q -f "$project_dir/pom.xml")
if "$offline"; then maven_args+=(-o); fi
mvn "${maven_args[@]}" dependency:copy-dependencies -DincludeScope=runtime "-DoutputDirectory=$input"
jpackage --type app-image --dest "$stage/image" --name JavaClaw \
  --input "$input" --main-jar javaclaw.jar --main-class com.javaclaw.app.Launcher \
  --jlink-options '--strip-debug --no-man-pages --no-header-files' \
  --java-options '--add-modules=jdk.incubator.vector,jdk.httpserver' \
  --java-options '--enable-native-access=ALL-UNNAMED'
mv "$stage/image/JavaClaw" "$portable_home/runtime/JavaClaw"
plugins="$project_dir/target/distribution/plugins"
if [[ -d "$plugins" ]]; then cp -R "$plugins/." "$portable_home/plugins/"; fi
[[ -z "$(find "$portable_home/plugins" "$portable_home/data" -type l -print -quit)" ]] \
  || fail "managed writable directories contain a symbolic link"
runtime_root="$(realpath "$portable_home/runtime")"
while IFS= read -r link; do
  resolved="$(realpath "$link")" || fail "broken runtime symbolic link: $link"
  case "$resolved" in "$runtime_root/"*) ;; *) fail "runtime symbolic link escapes: $link" ;; esac
done < <(find "$portable_home/runtime" -type l -print)
java="$portable_home/runtime/JavaClaw/lib/runtime/bin/java"
[[ -f "$java" && ! -L "$java" && -x "$java" ]] || fail "missing regular bundled Java executable: $java"
"$java" --add-modules jdk.httpserver,jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED -version
chmod -R a-w "$portable_home/runtime"
mv "$portable_home" "$output"
[[ -w "$output" && -w "$output/plugins" && -w "$output/data" ]] || fail "the outer home must remain writable"
echo "$output"
