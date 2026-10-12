#!/usr/bin/env python3
"""为可选发行插件生成通用清单；输入是已经签包完成的 ZIP。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("packages", nargs="+")
parser.add_argument("--runtime-dir", type=Path,
                    default=Path(__file__).resolve().parent.parent / "distribution/target/runtime")
args = parser.parse_args()
runtime = Path(args.runtime_dir).resolve()
destination = runtime / "plugin-packages"
destination.mkdir(parents=True, exist_ok=True)
entries = []
identities = set()
for package in args.packages:
    source = Path(package).resolve(strict=True)
    if not source.is_file() or source.suffix.lower() != ".zip":
        raise ValueError(f"插件包必须是 ZIP 普通文件: {source}")
    with zipfile.ZipFile(source) as archive:
        if archive.namelist().count("plugin.json") != 1:
            raise ValueError(f"插件包缺少唯一 plugin.json: {source}")
        manifest = json.loads(archive.read("plugin.json"))
    identity = (manifest["id"], manifest["version"])
    if identity in identities:
        raise ValueError(f"发行清单包含重复插件版本: {identity}")
    identities.add(identity)
    with source.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    name = digest + ".zip"
    temporary = destination / (name + ".tmp")
    shutil.copyfile(source, temporary)
    os.replace(temporary, destination / name)
    entries.append({"path": "runtime/plugin-packages/" + name, "sha256": digest})
content = {"schemaVersion": 2, "packages": entries}
temporary = runtime / "plugin-distribution.json.tmp"
temporary.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
os.replace(temporary, runtime / "plugin-distribution.json")
print(runtime / "plugin-distribution.json")
