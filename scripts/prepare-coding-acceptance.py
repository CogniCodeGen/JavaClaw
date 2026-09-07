#!/usr/bin/env python3
"""为显式五 Runner 发行验收准备固定目录的真实归档，不安装或执行任何工具链。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import urllib.parse
import urllib.request


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    """发行镜像重定向仍需保持 HTTPS，不接收凭据或明文降级。"""

    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        require_https(new_url)
        return super().redirect_request(request, file_pointer, code, message, headers, new_url)


def require_https(url):
    """拒绝非 HTTPS 发行地址及 URL 内嵌凭据。"""
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("发行归档地址必须是无凭据的 HTTPS URL")


def digest(path):
    """以固定内存计算完整归档摘要。"""
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download(artifact, directory):
    """缓存命中也重新核验；失败只清理由本脚本创建的未晋升归档。"""
    reference = artifact["reference"]
    expected = reference["artifactSha256"]
    if len(expected) != 64 or any(character not in "0123456789abcdef" for character in expected):
        raise ValueError("目录包含非法 SHA-256")
    destination = directory / (expected + ".archive")
    maximum = artifact["downloadBytes"]
    if destination.exists() and destination.stat().st_size <= maximum and digest(destination) == expected:
        print(reference["kind"], reference["version"], "verified cache", flush=True)
        return
    require_https(artifact["downloadUri"])
    staging = directory / (expected + ".part")
    request = urllib.request.Request(artifact["downloadUri"], headers={"User-Agent": "JavaClaw-Acceptance/6"})
    try:
        with urllib.request.build_opener(HttpsRedirect()).open(request, timeout=120) as source:
            with staging.open("wb") as target:
                copied = 0
                while block := source.read(1024 * 1024):
                    copied += len(block)
                    if copied > maximum:
                        raise ValueError("发行归档超过目录大小上限")
                    target.write(block)
        if digest(staging) != expected:
            raise ValueError("发行归档 SHA-256 不匹配")
        os.replace(staging, destination)
        print(reference["kind"], reference["version"], "downloaded and verified", flush=True)
    finally:
        staging.unlink(missing_ok=True)


def main():
    """只选择当前 Runner 的发行缺省版本，保留不同版本的独立摘要文件。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[1]
                        / "javaclaw-app-server/src/main/resources/coding/toolchains-v1.json")
    arguments = parser.parse_args()
    operating_system = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}.get(platform.system())
    architecture = {"aarch64": "arm64", "arm64": "arm64", "x86_64": "x64", "amd64": "x64"}.get(
        platform.machine().lower())
    if not operating_system or not architecture:
        raise ValueError("发行验收不支持此 Runner 平台")
    catalog = json.loads(arguments.catalog.read_text(encoding="utf-8"))
    selected = {}
    for artifact in catalog["artifacts"]:
        if artifact["platform"] == operating_system and artifact["architecture"] == architecture:
            selected.setdefault(artifact["reference"]["kind"], artifact)
    if set(selected) != {"JDK", "MAVEN", "GRADLE", "NODE", "NPM", "PNPM", "PYTHON", "PIP"}:
        raise ValueError("当前 Runner 的发行工具链目录不完整")
    arguments.destination.mkdir(parents=True, exist_ok=True)
    for artifact in selected.values():
        download(artifact, arguments.destination)


if __name__ == "__main__":
    main()
