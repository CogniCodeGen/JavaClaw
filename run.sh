#!/bin/sh
set -eu

usage() {
    cat <<'EOF'
Usage: ./run.sh [--no-build]
  Default: build the local distribution with JDK 25 and Maven 3.9+, then start JavaClaw.
  --no-build: start an existing distribution without Maven.
  --help: show this help without building or starting the application.
EOF
}

JAVACLAW_BUILD=true
if [ "$#" -gt 1 ]; then
    usage >&2
    exit 2
fi
case "${1:-}" in
    "")
        ;;
    --no-build)
        JAVACLAW_BUILD=false
        ;;
    --help|-h)
        usage
        exit 0
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac

JAVACLAW_PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$JAVACLAW_PROJECT_ROOT"
if [ "$JAVACLAW_BUILD" = true ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        printf '%s\n' 'Maven 3.9+ is required. Install it and select JDK 25, then retry.' >&2
        exit 1
    fi
    # 只跳过开发启动时的测试执行；格式、文档和依赖门禁仍正常执行，失败不能回退到旧产物。
    mvn -B -DskipTests -Djavaclaw.distribution -pl javaclaw-packaging -am package
fi

JAVACLAW_DISTRIBUTION="$JAVACLAW_PROJECT_ROOT/javaclaw-packaging/target/distribution"
if [ ! -x "$JAVACLAW_DISTRIBUTION/runtime/bin/java" ] \
    || [ ! -f "$JAVACLAW_DISTRIBUTION/lib/com.javaclaw.javaclaw-packaging.jar" ] \
    || [ ! -f "$JAVACLAW_DISTRIBUTION/bin/javaclaw" ]; then
    printf '%s\n' 'JavaClaw distribution is missing or incomplete. Run ./run.sh without --no-build.' >&2
    exit 1
fi
exec /bin/sh "$JAVACLAW_DISTRIBUTION/bin/javaclaw"
