#!/usr/bin/env python3
"""使用最近 desktop Maven 测试的准确 classpath 重放真实 WebKit；先运行 desktop -am test。"""
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    reports = root / 'javaclaw-desktop/target/surefire-reports'
    candidates = sorted(reports.glob('TEST-*.xml'))
    if not candidates:
        raise SystemExit('请先运行 mvn -pl javaclaw-desktop -am test，以生成准确测试 classpath')
    properties = ET.parse(candidates[0]).getroot().find('properties')
    classpath = next(p.attrib['value'] for p in properties if p.attrib['name'] == 'java.class.path')
    output = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / 'javaclaw-desktop/target/webview-replay'
    command = ['java', '--enable-native-access=ALL-UNNAMED', '-cp', classpath,
               'com.javaclaw.desktop.web.WebSurfaceReplayBenchmark', str(output)]
    raise SystemExit(subprocess.call(command, cwd=root))


if __name__ == '__main__':
    main()
