package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.javaclaw.api.AttachmentScope;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.DependencyEvidenceRepository;

/** 通过断网 Worker 观察项目；宿主只保存已返回字节，不递归读取项目或可写缓存。 快照可能包含外部并发修改，差异只表达观察事实，不给安装器归因，也不从缓存文件名推断依赖图。 */
final class CodingDependencyEvidence {
    private static final int SCAN_BYTES = 64 * 1024 * 1024;
    private static final int ARTIFACT_BYTES = 16 * 1024 * 1024;
    private static final List<String> EXCLUDED = List.of("node_modules", ".gradle", ".venv", "venv", "target", "build");
    private final CodingPlatform.Dependencies dependencies;
    private final DependencyEvidenceRepository repository;

    CodingDependencyEvidence(CodingPlatform.Dependencies dependencies) {
        this.dependencies = dependencies;
        repository = new DependencyEvidenceRepository(dependencies.database(), dependencies.json());
    }

    Session begin(WorkspaceFileAccess files, CodingInvocation invocation, CodingContracts.DependenciesPrepare input)
            throws Exception {
        Observation observation = observe(files, invocation, input, "before");
        var evidence = new DependencyEvidence(
                invocation.id(),
                input.manager(),
                observation.inventory(),
                Optional.empty(),
                List.of(),
                observation.artifacts(),
                false);
        repository.record(invocation.workspaceId(), evidence);
        if (observation.manifests().size()
                != NativeDependencyPlan.manifests(input.manager()).size()) {
            throw new IllegalStateException("依赖准备的根 manifest 无法完整读取，已保留不完整证据");
        }
        return new Session(evidence, observation.manifests());
    }

    DependencyEvidence finish(
            Session session,
            WorkspaceFileAccess files,
            CodingInvocation invocation,
            CodingContracts.DependenciesPrepare input,
            Optional<CodingResults.CommandResult> command)
            throws Exception {
        Observation after = observe(files, invocation, input, "after");
        List<DependencyEvidence.Artifact> artifacts =
                new ArrayList<>(session.evidence().artifacts());
        artifacts.addAll(after.artifacts());
        List<String> omissions = new ArrayList<>(after.inventory().omissions());
        addExecution(invocation, input, command, artifacts, omissions);
        var resulting = new DependencyEvidence.Inventory(
                after.inventory().files(),
                after.inventory().scannedBytes(),
                after.inventory().complete() && omissions.isEmpty(),
                limited(omissions));
        List<DependencyEvidence.Change> changes = manifestChanges(
                changes(session.evidence().before(), resulting), session.manifests(), after.manifests());
        boolean complete = session.evidence().before().complete()
                && resulting.complete()
                && artifacts.stream().allMatch(DependencyEvidence.Artifact::complete);
        var evidence = new DependencyEvidence(
                invocation.id(),
                input.manager(),
                session.evidence().before(),
                Optional.of(resulting),
                changes,
                artifacts,
                complete);
        repository.record(invocation.workspaceId(), evidence);
        return evidence;
    }

    private Observation observe(
            WorkspaceFileAccess files,
            CodingInvocation invocation,
            CodingContracts.DependenciesPrepare input,
            String phase)
            throws Exception {
        var cancellation = new CodingCancellation(invocation, dependencies.authority());
        DependencyEvidence.Inventory inventory = inventory(files, input, cancellation, invocation);
        List<String> omissions = new ArrayList<>(inventory.omissions());
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        List<String> roots = NativeDependencyPlan.manifests(input.manager()).stream()
                .map(name -> NativeDependencyPlan.relative(input.workingDirectory(), name))
                .toList();
        paths.addAll(roots);
        inventory.files().stream()
                .map(DependencyEvidence.FileDigest::path)
                .filter(path -> manifest(path, input.manager()))
                .forEach(paths::add);
        List<DependencyEvidence.Artifact> artifacts = new ArrayList<>();
        Map<String, Optional<String>> manifests = new LinkedHashMap<>();
        int remaining = ARTIFACT_BYTES;
        int attempted = 0;
        for (String path : paths) {
            if (attempted++ >= 120 || remaining <= 0 || cancellation.isCancelled()) {
                omissions.add("manifest/lock 原文达到 120 文件、16 MiB 保存上限或观察已取消");
                break;
            }
            try {
                var snapshot = files.read(path, Math.min(remaining, 4 * 1024 * 1024), cancellation);
                if (roots.contains(path)) {
                    manifests.put(path, snapshot.exists() ? Optional.of(snapshot.sha256()) : Optional.empty());
                }
                if (snapshot.exists()) {
                    artifacts.add(store(invocation, path, lock(path) ? "lock" : "manifest", phase, snapshot.content()));
                    remaining -= snapshot.content().length;
                }
            } catch (Exception failure) {
                requireRestored(invocation, failure);
                omissions.add(path + ": " + failure.getClass().getSimpleName());
            }
        }
        return new Observation(
                new DependencyEvidence.Inventory(
                        inventory.files(),
                        inventory.scannedBytes(),
                        inventory.complete() && omissions.isEmpty(),
                        limited(omissions)),
                Map.copyOf(manifests),
                artifacts);
    }

    private DependencyEvidence.Inventory inventory(
            WorkspaceFileAccess files,
            CodingContracts.DependenciesPrepare input,
            CodingCancellation cancellation,
            CodingInvocation invocation) {
        try {
            var inventory = files.inventory(input.workingDirectory(), 10_000, SCAN_BYTES, EXCLUDED, cancellation);
            List<String> omissions = new ArrayList<>(inventory.excludedDirectories());
            if (inventory.truncated()) {
                omissions.add("inventory 达到预算、遇到权限/链接边界或扫描期间文件变化");
            }
            return new DependencyEvidence.Inventory(
                    inventory.entries().stream()
                            .map(file ->
                                    new DependencyEvidence.FileDigest(file.path(), file.sizeBytes(), file.sha256()))
                            .toList(),
                    inventory.scannedBytes(),
                    omissions.isEmpty(),
                    limited(omissions));
        } catch (Exception failure) {
            requireRestored(invocation, failure);
            return new DependencyEvidence.Inventory(
                    List.of(),
                    0,
                    false,
                    List.of("inventory: " + failure.getClass().getSimpleName()));
        }
    }

    private void requireRestored(CodingInvocation invocation, Exception failure) {
        if (dependencies.authority().reportIsolationFailure(invocation.turn().id(), failure)) {
            throw new SecurityException("WORKSPACE_SECURITY_LOCKED: 项目观察的原生权限恢复失败", failure);
        }
    }

    private void addExecution(
            CodingInvocation invocation,
            CodingContracts.DependenciesPrepare input,
            Optional<CodingResults.CommandResult> command,
            List<DependencyEvidence.Artifact> artifacts,
            List<String> omissions) {
        if (command.isEmpty()) {
            omissions.add("原生命令未返回完整结果；不猜测安装结果或重放命令");
            return;
        }
        var result = command.orElseThrow();
        var output = new CodingCommandOutputRepository(dependencies.database(), dependencies.json())
                .read(invocation.workspaceId(), invocation.id());
        boolean complete = !result.output().truncated();
        artifacts.add(new DependencyEvidence.Artifact(
                "stdout", "native-output", "execution", output.stdoutDigest(), output.stdoutBytes(), complete));
        artifacts.add(new DependencyEvidence.Artifact(
                "stderr", "native-output", "execution", output.stderrDigest(), output.stderrBytes(), complete));
        if (!complete) {
            omissions.add("原生命令输出达到保存预算");
        }
        if (input.manager() == CodingContracts.PackageManager.PIP) {
            var report = CodingPipEvidence.report(dependencies.json(), result);
            if (report.isPresent()) {
                artifacts.add(store(invocation, "pip-report", "pip-report", "execution", report.orElseThrow()));
            } else {
                omissions.add("pip 原生报告缺失、不完整或固定包装 envelope 无效；仅保留原始输出");
            }
        }
    }

    private DependencyEvidence.Artifact store(
            CodingInvocation invocation, String source, String kind, String phase, byte[] bytes) {
        var identity = dependencies.json().encode(new ArtifactIdentity(invocation.id(), source, phase, bytes));
        var metadata = dependencies
                .attachments()
                .store(
                        AttachmentScope.workspace(invocation.workspaceId()),
                        new CommandIdentity(
                                "coding/dependency-evidence", "dependency-" + identity.sha256(), 0, identity.sha256()),
                        "application/octet-stream",
                        bytes);
        return new DependencyEvidence.Artifact(source, kind, phase, metadata.digest(), bytes.length, true);
    }

    static List<DependencyEvidence.Change> changes(
            DependencyEvidence.Inventory before, DependencyEvidence.Inventory after) {
        Map<String, String> left = new TreeMap<>();
        Map<String, String> right = new TreeMap<>();
        before.files().forEach(file -> left.put(file.path(), file.sha256()));
        after.files().forEach(file -> right.put(file.path(), file.sha256()));
        var paths = new java.util.TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
        List<DependencyEvidence.Change> changes = new ArrayList<>();
        for (String path : paths) {
            Optional<String> old = Optional.ofNullable(left.get(path));
            Optional<String> current = Optional.ofNullable(right.get(path));
            if (!old.equals(current)
                    && (old.isPresent() || before.complete())
                    && (current.isPresent() || after.complete())) {
                changes.add(new DependencyEvidence.Change(path, old, current));
            }
        }
        return List.copyOf(changes);
    }

    private static List<DependencyEvidence.Change> manifestChanges(
            List<DependencyEvidence.Change> observed,
            Map<String, Optional<String>> before,
            Map<String, Optional<String>> after) {
        var changes = new TreeMap<String, DependencyEvidence.Change>();
        observed.forEach(change -> changes.put(change.path(), change));
        before.forEach((path, digest) -> {
            Optional<String> current = after.get(path);
            if (current != null && !digest.equals(current)) {
                changes.put(path, new DependencyEvidence.Change(path, digest, current));
            }
        });
        return List.copyOf(changes.values());
    }

    private static boolean manifest(String path, CodingContracts.PackageManager manager) {
        return manager == CodingContracts.PackageManager.GRADLE && path.endsWith(".lockfile")
                || NativeDependencyPlan.manifests(manager).stream()
                        .anyMatch(name -> path.equals(name) || path.endsWith("/" + name));
    }

    private static boolean lock(String path) {
        String name = Path.of(path).getFileName().toString();
        return name.contains("lock") || name.equals("npm-shrinkwrap.json");
    }

    private static List<String> limited(List<String> omissions) {
        return omissions.stream()
                .limit(256)
                .map(value -> value.substring(0, Math.min(4096, value.length())))
                .toList();
    }

    record Session(DependencyEvidence evidence, Map<String, Optional<String>> manifests) {}

    private record Observation(
            DependencyEvidence.Inventory inventory,
            Map<String, Optional<String>> manifests,
            List<DependencyEvidence.Artifact> artifacts) {}

    private record ArtifactIdentity(String operationId, String source, String phase, byte[] content) {}
}
