#!/usr/bin/env python3
"""以生产 JPMS 模式运行真实主壳回放；可指定隔离编译产物，比较同一夹具的前后版本。"""
import argparse
import os
import pathlib
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=pathlib.Path)
    parser.add_argument('--baseline', type=pathlib.Path)
    parser.add_argument('--scenario')
    parser.add_argument('--jfr', action='store_true', help='记录 JFR，按 JSON 中的阶段时间排除启动与截图')
    parser.add_argument('--jfr-settings', default='profile', help='JFR 配置名或 .jfc 路径，仅在 --jfr 时使用')
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    if args.baseline:
        classpath = (args.baseline / 'classpath.txt').read_text()
        desktop = args.baseline / 'classes'
        tests = args.baseline / 'test-classes'
    else:
        report = sorted((root / 'javaclaw-desktop/target/surefire-reports').glob('TEST-*.xml'))[0]
        properties = ET.parse(report).getroot().find('properties')
        classpath = next(p.attrib['value'] for p in properties if p.attrib['name'] == 'java.class.path')
        desktop = root / 'javaclaw-desktop/target/classes'
        tests = root / 'javaclaw-desktop/target/test-classes'
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    probe = output / 'probe-classes'
    probe.mkdir(exist_ok=True)
    sources = [root / 'javaclaw-desktop/src/test/java/com/javaclaw/desktop' / (name + '.java')
               for name in ('DesktopChatReplayFixture', 'DesktopReplayMetrics', 'DesktopChatPerformanceReplay')]
    subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(probe),
                    *map(str, sources)], check=True, cwd=root)
    entries = [str(desktop)] + [p for p in classpath.split(os.pathsep)
                                if p != str(desktop) and p != str(tests)]
    command = ['java', '--enable-native-access=ALL-UNNAMED,javafx.graphics,javafx.media,javafx.web',
               '--module-path', os.pathsep.join(entries), '--add-modules', 'ALL-MODULE-PATH',
               '--patch-module', 'com.javaclaw.desktop=' + os.pathsep.join((str(probe), str(tests))),
               '--add-reads', 'com.javaclaw.desktop=ALL-UNNAMED',
               '--add-reads', 'com.javaclaw.desktop=org.junit.jupiter.api',
               '-cp', classpath, '-m',
               'com.javaclaw.desktop/com.javaclaw.desktop.DesktopChatPerformanceReplay', str(output)]
    if args.jfr:
        command.insert(1, '-XX:StartFlightRecording=settings=' + args.jfr_settings
                       + ',filename=' + str(output / 'replay.jfr'))
    if args.scenario:
        command.append(args.scenario)
    raise SystemExit(subprocess.call(command, cwd=root))


if __name__ == '__main__':
    main()
