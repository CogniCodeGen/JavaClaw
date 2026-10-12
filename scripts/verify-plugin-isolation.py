#!/usr/bin/env python3
"""检查组装后的 Desktop、Runner 和可选插件 ZIP 的实际类路径隔离。"""

import argparse
from io import BytesIO
import json
from pathlib import Path
import re
import sys
import zipfile


RUNNER_PACKAGES = (
    "com/javaclaw/plugin/sdk/", "com/javaclaw/plugin/protocol/", "com/javaclaw/plugin/runner/",
)
PLUGIN_PACKAGES = ("com/javaclaw/plugins/", "com/example/hello/")
UI_PACKAGES = (
    "org/springframework/", "javafx/", "jfx/", "com/sun/javafx/", "com/sun/glass/", "com/sun/prism/",
    "com/sun/scenario/", "com/sun/pisces/", "com/sun/openpisces/", "com/sun/marlin/", "com/sun/webkit/",
    "com/sun/media/jfxmedia/", "com/sun/media/jfxmediaimpl/",
)


def desktop_forbidden(name):
    return name.startswith(("com/javaclaw/plugin/sdk/", "com/javaclaw/plugin/runner/") + PLUGIN_PACKAGES)


def runner_forbidden(name):
    return (name.startswith("com/javaclaw/") and not name.startswith(RUNNER_PACKAGES)
            or name.startswith(UI_PACKAGES + PLUGIN_PACKAGES))


def plugin_forbidden(name):
    # com.javaclaw.plugins 是示例实现的私有命名空间，其他 com.javaclaw 类由平台提供。
    return name.startswith("com/javaclaw/") and not name.startswith("com/javaclaw/plugins/")


def class_path(name):
    name = re.sub(r"^META-INF/versions/\d+/", "", name)
    for prefix in ("BOOT-INF/classes/", "WEB-INF/classes/"):
        if name.startswith(prefix):
            name = name[len(prefix):]
    return name


def scan_jar(source, label, forbidden, violations, depth=0):
    if depth > 4:
        raise ValueError(f"嵌套 JAR 层级过深: {label}")
    classes = set()
    with zipfile.ZipFile(source) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            name = class_path(entry.filename)
            if name.endswith(".class"):
                classes.add(name)
                if forbidden(name):
                    violations.append(f"{label}!/{entry.filename}")
            elif entry.filename.lower().endswith(".jar"):
                classes.update(scan_jar(BytesIO(archive.read(entry)), f"{label}!/{entry.filename}",
                                        forbidden, violations, depth + 1))
    return classes


def verify_plugin(path, violations):
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("plugin.json"))
        artifact = manifest["artifact"]
        entrypoint = manifest["entrypoint"].replace(".", "/") + ".class"
        jars = [entry for entry in archive.infolist()
                if not entry.is_dir() and entry.filename.lower().endswith(".jar")]
        if not any(entry.filename == artifact for entry in jars):
            raise ValueError(f"插件 ZIP 缺少入口 JAR: {path}!/{artifact}")
        for entry in jars:
            classes = scan_jar(BytesIO(archive.read(entry)), f"{path}!/{entry.filename}", plugin_forbidden, violations)
            if entry.filename == artifact and entrypoint not in classes:
                raise ValueError(f"插件 JAR 缺少声明的入口类: {path}!/{entrypoint}")
    return len(jars)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-dir", type=Path,
                        default=Path(__file__).resolve().parent.parent / "distribution/target/runtime")
    parser.add_argument("packages", type=Path, nargs="*", help="可选插件 ZIP，包含的私有 lib/*.jar 也会扫描")
    args = parser.parse_args()
    runtime = args.runtime_dir.resolve(strict=True)
    desktop, runner, libraries = runtime / "desktop.jar", runtime / "plugin-runner.jar", runtime / "lib"
    if not desktop.is_file() or not runner.is_file() or not libraries.is_dir():
        raise ValueError(f"发行目录不完整，需要 desktop.jar、plugin-runner.jar 和 lib/: {runtime}")
    jars = sorted(libraries.glob("*.jar"))
    if not jars:
        raise ValueError(f"Desktop 依赖目录为空: {libraries}")
    violations = []
    desktop_classes = scan_jar(desktop, str(desktop), desktop_forbidden, violations)
    if "com/javaclaw/app/Launcher.class" not in desktop_classes:
        raise ValueError(f"Desktop JAR 缺少启动类: {desktop}")
    for jar in jars:
        scan_jar(jar, str(jar), desktop_forbidden, violations)
    runner_classes = scan_jar(runner, str(runner), runner_forbidden, violations)
    if "com/javaclaw/plugin/runner/PluginRunnerMain.class" not in runner_classes:
        raise ValueError(f"Runner JAR 缺少启动类: {runner}")
    plugin_jars = sum(verify_plugin(path.resolve(strict=True), violations) for path in args.packages)
    if violations:
        print(f"发现 {len(violations)} 个跨进程实现类泄漏:", file=sys.stderr)
        for violation in violations[:30]:
            print(f"  {violation}", file=sys.stderr)
        if len(violations) > 30:
            print("  其余违规已省略。", file=sys.stderr)
        return 1
    print(f"隔离检查通过：Desktop {len(jars) + 1} 个 JAR，Runner 1 个 JAR，"
          f"插件 {len(args.packages)} 个 ZIP / {plugin_jars} 个 JAR。")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as failure:
        print(f"隔离检查失败: {failure}", file=sys.stderr)
        sys.exit(1)
