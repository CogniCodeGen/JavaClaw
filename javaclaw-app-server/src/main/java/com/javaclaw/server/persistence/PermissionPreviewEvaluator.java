package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionLayerKind;
import com.javaclaw.api.PermissionLayerResult;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;

/** 构造固定五层权限交集，并解释每层造成的收窄。 */
final class PermissionPreviewEvaluator {
    private static final long MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAX_OUTPUT_BYTES = 256L * 1024 * 1024;
    private static final Duration MAX_PROCESS_TIME = Duration.ofHours(1);

    private PermissionPreviewEvaluator() {}

    static EffectivePermissionPreview evaluate(
            PermissionProfile frozen,
            PermissionProfile current,
            Workspace workspace,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        List<PermissionProfile> candidates = candidates(frozen, current, turnGrant, toolDeclaration);
        PermissionProfile system = systemCeiling(workspace, candidates);
        List<PermissionLayerResult> layers = new ArrayList<>();
        layers.add(new PermissionLayerResult(
                PermissionLayerKind.SYSTEM_CEILING, Optional.empty(), true, system, systemReasons(candidates)));
        PermissionProfile afterWorkspace = intersect(system, workspaceCeiling(system, workspace.root(), true));
        layers.add(result(PermissionLayerKind.WORKSPACE, Optional.empty(), system, afterWorkspace));
        PermissionProfile afterProfile = intersect(afterWorkspace, frozen, current);
        layers.add(result(
                PermissionLayerKind.PROFILE,
                Optional.of(new PermissionProfileRef(frozen.id(), frozen.version())),
                afterWorkspace,
                afterProfile));
        PermissionProfile afterTurn = optionalLayer(layers, PermissionLayerKind.TURN_GRANT, afterProfile, turnGrant);
        PermissionProfile effective =
                optionalLayer(layers, PermissionLayerKind.TOOL_DECLARATION, afterTurn, toolDeclaration);
        List<String> reasons = layers.stream()
                .flatMap(layer -> layer.denialReasons().stream().map(reason -> layer.layer() + ": " + reason))
                .toList();
        return new EffectivePermissionPreview(effective, layers, reasons);
    }

    static EffectivePermissionPreview evaluateExecution(
            PermissionProfile frozen,
            PermissionProfile current,
            Workspace workspace,
            Path executionRoot,
            boolean writable) {
        Path workspaceRoot = workspace.root().toAbsolutePath().normalize();
        Path targetRoot = executionRoot.toAbsolutePath().normalize();
        PermissionProfile mappedFrozen = rebase(frozen, workspaceRoot, targetRoot, writable);
        PermissionProfile mappedCurrent = rebase(current, workspaceRoot, targetRoot, writable);
        List<PermissionProfile> candidates = List.of(mappedFrozen, mappedCurrent);
        PermissionProfile system = systemCeiling(workspace, candidates);
        List<PermissionLayerResult> layers = new ArrayList<>();
        layers.add(new PermissionLayerResult(
                PermissionLayerKind.SYSTEM_CEILING, Optional.empty(), true, system, systemReasons(candidates)));
        PermissionProfile afterWorkspace = intersect(system, workspaceCeiling(system, targetRoot, writable));
        layers.add(result(PermissionLayerKind.WORKSPACE, Optional.empty(), system, afterWorkspace));
        PermissionProfile effective = intersect(afterWorkspace, mappedFrozen, mappedCurrent);
        layers.add(result(
                PermissionLayerKind.PROFILE,
                Optional.of(new PermissionProfileRef(frozen.id(), frozen.version())),
                afterWorkspace,
                effective));
        addUnusedLayers(layers, effective);
        List<String> reasons = layers.stream()
                .flatMap(layer -> layer.denialReasons().stream().map(reason -> layer.layer() + ": " + reason))
                .toList();
        return new EffectivePermissionPreview(effective, layers, reasons);
    }

    private static void addUnusedLayers(List<PermissionLayerResult> layers, PermissionProfile effective) {
        layers.add(new PermissionLayerResult(
                PermissionLayerKind.TURN_GRANT, Optional.empty(), false, effective, List.of()));
        layers.add(new PermissionLayerResult(
                PermissionLayerKind.TOOL_DECLARATION, Optional.empty(), false, effective, List.of()));
    }

    private static PermissionProfile optionalLayer(
            List<PermissionLayerResult> layers,
            PermissionLayerKind kind,
            PermissionProfile current,
            Optional<PermissionProfile> constraint) {
        if (constraint.isEmpty()) {
            layers.add(new PermissionLayerResult(kind, Optional.empty(), false, current, List.of()));
            return current;
        }
        PermissionProfile source = constraint.orElseThrow();
        PermissionProfile narrowed = intersect(current, source);
        layers.add(
                result(kind, Optional.of(new PermissionProfileRef(source.id(), source.version())), current, narrowed));
        return narrowed;
    }

    private static PermissionLayerResult result(
            PermissionLayerKind kind,
            Optional<PermissionProfileRef> source,
            PermissionProfile before,
            PermissionProfile after) {
        return new PermissionLayerResult(kind, source, true, after, explain(before, after));
    }

    private static List<String> explain(PermissionProfile before, PermissionProfile after) {
        List<String> reasons = new ArrayList<>();
        addReason(reasons, before.files(), after.files(), "文件访问范围被收窄");
        addReason(reasons, before.network(), after.network(), "网络访问范围被收窄");
        addReason(reasons, before.processes(), after.processes(), "进程与 PTY 权限被收窄");
        addReason(reasons, before.tools(), after.tools(), "工具、风险或审批范围被收窄");
        addReason(reasons, before.resources(), after.resources(), "资源上限被收窄");
        return List.copyOf(reasons);
    }

    private static void addReason(List<String> reasons, Object before, Object after, String reason) {
        if (!before.equals(after)) {
            reasons.add(reason);
        }
    }

    private static PermissionProfile intersect(PermissionProfile first, PermissionProfile... rest) {
        List<PermissionProfile> profiles = new ArrayList<>();
        profiles.add(first);
        profiles.addAll(List.of(rest));
        return PermissionResolver.intersect(profiles);
    }

    private static List<PermissionProfile> candidates(
            PermissionProfile frozen,
            PermissionProfile current,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        List<PermissionProfile> result = new ArrayList<>(List.of(frozen, current));
        turnGrant.ifPresent(result::add);
        toolDeclaration.ifPresent(result::add);
        return result;
    }

    private static PermissionProfile systemCeiling(Workspace workspace, List<PermissionProfile> candidates) {
        Path hostRoot = workspace.root().getRoot();
        Set<Integer> ports = union(candidates, candidate -> candidate.network().ports());
        Set<String> executables =
                union(candidates, candidate -> candidate.processes().executables());
        Set<String> tools = union(candidates, candidate -> candidate.tools().allowedTools());
        return new PermissionProfile(
                "system-ceiling",
                1,
                new FilePermission(List.of(hostRoot), List.of(hostRoot), true, false),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), ports, true),
                new ProcessPermission(executables, true, MAX_PROCESS_TIME),
                new ToolPermission(tools, ToolRisk.EXTERNAL_EFFECT, ApprovalRequirement.NONE),
                new ResourceLimits(MAX_MEMORY_BYTES, MAX_OUTPUT_BYTES, 64, 1_024));
    }

    private static List<String> systemReasons(List<PermissionProfile> candidates) {
        List<String> reasons = new ArrayList<>();
        if (candidates.stream().anyMatch(candidate -> candidate.files().followSymbolicLinks())) {
            reasons.add("系统上限禁止跟随符号链接");
        }
        if (candidates.stream().anyMatch(candidate -> !candidate.network().tlsOnly())) {
            reasons.add("系统上限强制网络连接使用 TLS");
        }
        if (candidates.stream()
                .anyMatch(candidate -> candidate.processes().maxRunTime().compareTo(MAX_PROCESS_TIME) > 0)) {
            reasons.add("系统上限限制单次进程运行时间");
        }
        if (candidates.stream().anyMatch(PermissionPreviewEvaluator::exceedsResourceCeiling)) {
            reasons.add("系统上限限制内存、输出、子进程或打开文件数量");
        }
        return List.copyOf(reasons);
    }

    private static boolean exceedsResourceCeiling(PermissionProfile candidate) {
        ResourceLimits resources = candidate.resources();
        return resources.memoryBytes() > MAX_MEMORY_BYTES
                || resources.outputBytes() > MAX_OUTPUT_BYTES
                || resources.childProcesses() > 64
                || resources.openFiles() > 1_024;
    }

    private static <T> Set<T> union(List<PermissionProfile> profiles, Function<PermissionProfile, Set<T>> values) {
        LinkedHashSet<T> result = new LinkedHashSet<>();
        profiles.forEach(profile -> result.addAll(values.apply(profile)));
        return Set.copyOf(result);
    }

    private static PermissionProfile workspaceCeiling(PermissionProfile source, Path workspaceRoot, boolean writable) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        return new PermissionProfile(
                "workspace-ceiling",
                source.version(),
                new FilePermission(
                        List.of(root),
                        writable ? List.of(root) : List.of(),
                        writable && source.files().allowDelete(),
                        false),
                source.network(),
                source.processes(),
                source.tools(),
                source.resources());
    }

    private static PermissionProfile rebase(
            PermissionProfile source, Path workspaceRoot, Path executionRoot, boolean writable) {
        FilePermission files = source.files();
        return new PermissionProfile(
                source.id(),
                source.version(),
                new FilePermission(
                        rebaseRoots(files.readRoots(), workspaceRoot, executionRoot),
                        writable ? rebaseRoots(files.writeRoots(), workspaceRoot, executionRoot) : List.of(),
                        writable && files.allowDelete(),
                        false),
                source.network(),
                source.processes(),
                source.tools(),
                source.resources());
    }

    private static List<Path> rebaseRoots(List<Path> roots, Path workspaceRoot, Path executionRoot) {
        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            Path normalized = root.toAbsolutePath().normalize();
            if (normalized.startsWith(workspaceRoot)) {
                result.add(executionRoot
                        .resolve(workspaceRoot.relativize(normalized))
                        .normalize());
            } else if (workspaceRoot.startsWith(normalized)) {
                result.add(executionRoot);
            }
        }
        return result.stream().distinct().toList();
    }
}
