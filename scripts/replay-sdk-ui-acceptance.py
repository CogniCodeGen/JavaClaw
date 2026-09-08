#!/usr/bin/env python3
"""以固定模型和临时工作区重放生产 FXML→SDK→UDS→App Server；先完成 Maven verify。"""
import pathlib
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


def classpath(root, module):
    reports = sorted((root / module / 'target/surefire-reports').glob('TEST-*.xml'))
    if not reports:
        raise SystemExit('请先执行 Maven verify，生成两个模块的测试 classpath')
    properties = ET.parse(reports[0]).getroot().find('properties')
    return next(value.attrib['value'] for value in properties if value.attrib['name'] == 'java.class.path')


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    output = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / 'javaclaw-desktop/target/acceptance/sdk-ui'
    output.mkdir(parents=True, exist_ok=True)
    server_cp = classpath(root, 'javaclaw-app-server')
    desktop_cp = classpath(root, 'javaclaw-desktop')
    # macOS 默认用户临时根较长；UDS 的路径长度有限，使用系统短临时根并只清理自己的随机目录。
    with tempfile.TemporaryDirectory(prefix='javaclaw-sdk-ui-', dir=pathlib.Path('/tmp').resolve()) as temporary:
        fixture = pathlib.Path(temporary) / 'fixture'
        with (output / 'server.log').open('w') as server_log:
            server = subprocess.Popen([
                'java', '--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=true', '-cp', server_cp,
                'com.javaclaw.server.extension.MemoryUiAcceptanceServer', str(fixture)
            ], cwd=root, stdout=server_log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while not (fixture / 'ready.json').exists():
                    if server.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('验收服务未就绪；请检查 server.log')
                    time.sleep(.1)
                with (output / 'desktop.log').open('w') as desktop_log:
                    return subprocess.call([
                        'java', '--enable-native-access=ALL-UNNAMED',
                        '-Djavaclaw.server.socket=' + str(fixture / 'rpc/server.sock'), '-cp', desktop_cp,
                        'com.javaclaw.desktop.settings.SdkUiAcceptanceDesktop', str(output), 'suite'
                    ], cwd=root, stdout=desktop_log, stderr=subprocess.STDOUT, timeout=180)
            finally:
                stop(server)


if __name__ == '__main__':
    raise SystemExit(main())
