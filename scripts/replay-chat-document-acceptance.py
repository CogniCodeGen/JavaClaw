#!/usr/bin/env python3
"""从最近 Desktop 测试 classpath 重放同一组聊天与文档断言，保留原始截图及逐场景哈希。"""
import pathlib
import subprocess
import sys
import xml.etree.ElementTree as ET


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    reports = sorted((root / 'javaclaw-desktop/target/surefire-reports').glob('TEST-*.xml'))
    if not reports:
        raise SystemExit('请先运行 Desktop 测试，生成准确的测试 classpath')
    properties = ET.parse(reports[0]).getroot().find('properties')
    classpath = next(p.attrib['value'] for p in properties if p.attrib['name'] == 'java.class.path')
    output = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / 'javaclaw-desktop/target/acceptance/chat-document'
    command = ['java', '--enable-native-access=ALL-UNNAMED', '-cp', classpath,
               'com.javaclaw.desktop.acceptance.chatdocument.ChatDocumentAcceptanceReplay', str(output)]
    raise SystemExit(subprocess.call(command, cwd=root))


if __name__ == '__main__':
    main()
