#!/usr/bin/env python3
"""只从 Git 与工作树源码重新编译，比较普通 Turn 准备延迟；不读取 target 类。"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile


def run(command, **kwargs):
    """运行确定性本地命令，失败立即退出，保留完整诊断。"""
    return subprocess.run(command, check=True, text=True, **kwargs)


def source_digest(source):
    """记录参与编译源码的内容摘要，避免把变化中的工作树误称为固定版本。"""
    digest = hashlib.sha256()
    for path in sorted(source.rglob("*")):
        if path.is_file():
            digest.update(str(path.relative_to(source)).encode())
            digest.update(path.read_bytes())
    return digest.hexdigest()


def classpath(repository):
    """只用已下载的固定依赖 JAR，绝不加入任意 target 目录。"""
    coordinates = [
        "com/h2database/h2/2.3.232/h2-2.3.232.jar",
        "com/fasterxml/jackson/core/jackson-core/2.18.3/jackson-core-2.18.3.jar",
        "com/fasterxml/jackson/core/jackson-databind/2.18.3/jackson-databind-2.18.3.jar",
        "com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar",
        "com/fasterxml/jackson/datatype/jackson-datatype-jdk8/2.18.3/jackson-datatype-jdk8-2.18.3.jar",
        "com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.18.3/jackson-datatype-jsr310-2.18.3.jar",
        "org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar",
        "org/tomlj/tomlj/1.1.1/tomlj-1.1.1-all.jar",
    ]
    jars = [repository / value for value in coordinates]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError(f"依赖尚未下载，请先完成项目正常 Maven 构建: {jar}")
    return os.pathsep.join(map(str, jars))


def generate(template, variant):
    """只生成基准装配；实际准备逻辑仍来自被测版本的 TurnCommandFactory。"""
    if variant == "v5":
        imports = """import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.ProfileBindingService;"""
        setup = """        AgentProfileService roles = new AgentProfileService(database, providers, permissions, JSON, clock);
        roles.create(identity("agentProfile/create", "role"), "benchmark-role", new AgentProfileSpec("Benchmark", "",
                provider, new PermissionProfileRef("standard", 1), Set.of(), budget));
        ProfileBindingService configurations = new ProfileBindingService(database, core, roles, JSON, clock);
        configurations.update(identity("profileBinding/update", "defaults"), workspace.id(), Optional.empty(),
                new AgentProfileRef("benchmark-role", 1));"""
        request = "new CoreRpcContracts.TurnStartPayload(thread.id(), Optional.empty(), message.text())"
        prompt = "prompt.systemInstruction()"
    else:
        imports = """import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;"""
        setup = """        AgentRoleService roles = new AgentRoleService(database, providers, JSON, clock);
        roles.create(identity("agent/role/create", "role"), "benchmark-role", new AgentRoleSpec("Benchmark", "", "",
                Optional.empty(), Optional.empty(), CapabilityNarrowing.inherit(), PermissionConstraint.INHERIT, Map.of()));
        ExecutionConfigurationService configurations = new ExecutionConfigurationService(database, core, roles, JSON, clock);
        configurations.update(identity("execution/default/update", "defaults"), Optional.of(workspace.id()), Optional.empty(),
                new ExecutionOverrides(Optional.of(new AgentRoleRef("benchmark-role", 1)), Optional.of(provider),
                        Optional.of(new PermissionProfileRef("standard", 1)), Optional.of(ApprovalPolicy.NONE),
                        Optional.of(budget), Optional.of(Set.of()), Optional.empty()));"""
        request = "new CoreRpcContracts.TurnStartPayload(thread.id(), ExecutionOverrides.empty(), message.text(), List.of())"
        prompt = ('prompt.modelInstructions().systemInstruction() + "\\n\\n" '
                  '+ prompt.modelInstructions().developerInstructions() + "\\n\\n" '
                  '+ prompt.modelInstructions().responseContract()')
    replacements = {"VERSION_IMPORTS": imports, "VERSION_SETUP": setup, "VERSION_REQUEST": request,
                    "VERSION_PROMPT": prompt, "VARIANT": variant, "DATA_NAME": f"data-{variant}"}
    for key, value in replacements.items():
        template = template.replace(f"@{key}@", value)
    return template


def compile_variant(source, generated, dependencies, variant):
    """javac 从源码解析依赖闭包，输出只进入新的临时目录。"""
    destination = generated / variant
    destination.mkdir()
    java_source = destination / "TurnPreparationBenchmark.java"
    template = Path(__file__).with_name("TurnPreparationBenchmark.java.template").read_text()
    java_source.write_text(generate(template, variant))
    original_roots = sorted(source.glob("javaclaw-*/src/main/java"))
    source_roots = []
    for index, original in enumerate(original_roots):
        copied = destination / "source" / str(index)
        for path in original.rglob("*.java"):
            if path.name == "module-info.java":
                continue
            target = copied / path.relative_to(original)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
        source_roots.append(copied)
    fixture_root = destination / "fixtures"
    fixture = Path("com/javaclaw/server/ProviderEndpointTestFixtures.java")
    (fixture_root / fixture).parent.mkdir(parents=True)
    shutil.copyfile(source / "javaclaw-app-server/src/test/java" / fixture, fixture_root / fixture)
    source_roots.append(fixture_root)
    run(["javac", "-cp", dependencies, "-sourcepath", os.pathsep.join(map(str, source_roots)),
         "-d", str(destination), str(java_source)])
    resources = []
    for index, original in enumerate(sorted(source.glob("javaclaw-*/src/main/resources"))):
        copied = destination / "resources" / str(index)
        shutil.copytree(original, copied)
        resources.append(copied)
    return os.pathsep.join([str(destination), *map(str, resources), dependencies])


def main():
    """固定 Git 基线、JDK、依赖、空目录与 Prompt；报告样本及真实限制。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="HEAD")
    parser.add_argument("--warmup", type=int, default=200)
    parser.add_argument("--samples", type=int, default=1000)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--current-only", action="store_true")
    parser.add_argument("--reference-report", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    scratch = Path(tempfile.mkdtemp(prefix="javaclaw-turn-benchmark-"))
    baseline = scratch / "baseline"
    baseline.mkdir()
    commit = run(["git", "rev-parse", args.baseline], cwd=root, capture_output=True).stdout.strip()
    archived = subprocess.check_output(["git", "archive", commit], cwd=root)
    with tarfile.open(fileobj=io.BytesIO(archived)) as archive:
        archive.extractall(baseline, filter="data")
    dependencies = classpath(Path.home() / ".m2/repository")
    classpaths = {}
    if not args.current_only:
        classpaths["v5"] = compile_variant(baseline, scratch, dependencies, "v5")
    classpaths["v6"] = compile_variant(root, scratch, dependencies, "v6")
    metadata = {"baselineCommit": commit, "compiledSourceSha256": {variant: source_digest(scratch / variant / "source") for variant in classpaths},
                "compiledResourceSha256": {variant: source_digest(scratch / variant / "resources") for variant in classpaths},
                "platform": platform.platform(), "java": run(["java", "-version"], capture_output=True).stderr,
                "scope": "TurnCommandFactory.resolve; no Turn INSERT, Harness execution, paid model or network",
                "warmup": args.warmup, "samplesPerFork": args.samples, "forks": args.forks,
                "scratch": str(scratch), "classpath": classpaths}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.prepare_only:
        metadata["status"] = "prepared_not_measured"
        args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
        return
    probe = scratch / "probe.json"
    run(["java", "-Xms256m", "-Xmx256m", "-cp", classpaths["v6"],
         "com.javaclaw.server.turn.TurnPreparationBenchmark", str(scratch / "probe-data"), "5", "5", str(probe)])
    prompt = json.loads(probe.read_text())["prompt"]
    if args.reference_report:
        reference = json.loads(args.reference_report.read_text())
        if hashlib.sha256(prompt.encode()).hexdigest() != reference["sameFlattenedPromptSha256"]:
            raise ValueError("候选与已记录基线的 Prompt 摘要不一致")
        metadata["referenceReport"] = str(args.reference_report)
        metadata["referenceReportSha256"] = hashlib.sha256(args.reference_report.read_bytes()).hexdigest()
    prompt_file = scratch / "same-prompt.txt"
    prompt_file.write_text(prompt)
    results = []
    for fork in range(args.forks):
        for variant in (["v5", "v6"] if fork % 2 == 0 else ["v6", "v5"]):
            if variant not in classpaths:
                continue
            output = scratch / f"{variant}-{fork}.json"
            command = ["java", "-Xms256m", "-Xmx256m", "-cp", classpaths[variant],
                       "com.javaclaw.server.turn.TurnPreparationBenchmark", str(scratch / f"data-{variant}-{fork}"),
                       str(args.warmup), str(args.samples), str(output)]
            if variant == "v5":
                command.append(str(prompt_file))
            run(command)
            result = json.loads(output.read_text())
            if result.pop("prompt") != prompt:
                raise ValueError("模型可见的拼接后 Prompt 不同，拒绝报告可比结果")
            result["fork"] = fork
            results.append(result)
            print(f"{variant} fork={fork} p50={result['p50Nanos']/1_000_000:.3f}ms "
                  f"p95={result['p95Nanos']/1_000_000:.3f}ms", flush=True)
    metadata["results"] = results
    metadata["sameFlattenedPromptSha256"] = hashlib.sha256(prompt.encode()).hexdigest()
    metadata["status"] = "measured_preparation_only_not_full_turn_acceptance"
    args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
    print(args.output)


if __name__ == "__main__":
    main()
