package com.javaclaw.server.extension.thirdparty;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 将签名权限请求限制在平台硬上限内，并为每次调用生成最小 Sandbox 权限。 */
final class ThirdPartyPermissionPolicy {
    private static final Duration MAX_RUN_TIME = Duration.ofMinutes(2);
    private static final long MAX_MEMORY_BYTES = 512L * 1024 * 1024;
    private static final long MAX_OUTPUT_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CHILD_PROCESSES = 4;
    private static final int MAX_OPEN_FILES = 64;

    private ThirdPartyPermissionPolicy() {}

    static void validate(ThirdPartyBundleManifest manifest, CanonicalJson json) {
        java.util.Objects.requireNonNull(json, "json");
        var request = manifest.permissions();
        if (request.maxRunTime().isZero()
                || request.maxRunTime().isNegative()
                || request.maxRunTime().compareTo(MAX_RUN_TIME) > 0) {
            throw new IllegalArgumentException("third-party maxRunTime exceeds platform ceiling");
        }
        ResourceLimits limits = request.resources();
        if (limits.memoryBytes() > MAX_MEMORY_BYTES
                || limits.outputBytes() > MAX_OUTPUT_BYTES
                || limits.childProcesses() > MAX_CHILD_PROCESSES
                || limits.openFiles() > MAX_OPEN_FILES) {
            throw new IllegalArgumentException("third-party resource request exceeds platform ceiling");
        }
        if (request.networkPorts().contains(NetworkPermission.ANY_PORT)) {
            throw new IllegalArgumentException("third-party network ports must be explicit");
        }
        new NetworkPermission(request.networkHosts(), request.networkPorts(), request.tlsOnly());
        manifest.contributions().stream()
                .filter(contribution -> contribution.kind() == ContributionKind.TOOL)
                .map(contribution -> contribution.descriptor().orElseThrow())
                .map(payload -> json.decode(payload, ThirdPartyToolDefinition.class))
                .filter(tool ->
                        tool.risk().ordinal() > request.maximumToolRisk().ordinal())
                .findFirst()
                .ifPresent(tool -> {
                    throw new IllegalArgumentException("tool risk exceeds requested maximum: " + tool.name());
                });
    }

    static BundleRpcContracts.PermissionReview review(ThirdPartyBundleManifest manifest) {
        var request = manifest.permissions();
        ResourceLimits limits = request.resources();
        return new BundleRpcContracts.PermissionReview(
                request.workspaceRead(),
                request.workspaceWrite(),
                request.allowDelete(),
                request.networkHosts(),
                request.networkPorts(),
                request.tlsOnly(),
                manifest.entryPoint().executableName(),
                request.maxRunTime(),
                limits.memoryBytes(),
                limits.outputBytes(),
                limits.childProcesses(),
                limits.openFiles());
    }

    static PermissionProfile processPermission(
            ThirdPartyBundleManifest manifest,
            Path bundleRoot,
            Optional<Path> workspaceRoot,
            Optional<PermissionProfile> caller,
            CanonicalJson json) {
        Path bundle = bundleRoot.toAbsolutePath().normalize();
        PermissionProfile requested = requestedWorkspacePermission(manifest, workspaceRoot, json);
        PermissionProfile narrowed = caller.map(profile -> PermissionResolver.intersect(List.of(profile, requested)))
                .orElse(requested);
        ArrayList<Path> readRoots = new ArrayList<>(narrowed.files().readRoots());
        readRoots.add(bundle);
        return new PermissionProfile(
                "third-party:" + manifest.id(),
                narrowed.version(),
                new FilePermission(
                        readRoots,
                        narrowed.files().writeRoots(),
                        narrowed.files().allowDelete(),
                        false),
                new NetworkPermission(Set.of(), Set.of(), true),
                narrowed.processes(),
                narrowed.tools(),
                narrowed.resources());
    }

    static PermissionProfile descriptorPermission(
            ThirdPartyBundleManifest manifest, Path bundleRoot, CanonicalJson json) {
        PermissionProfile process = processPermission(manifest, bundleRoot, Optional.empty(), Optional.empty(), json);
        var request = manifest.permissions();
        return new PermissionProfile(
                process.id(),
                process.version(),
                process.files(),
                new NetworkPermission(request.networkHosts(), request.networkPorts(), request.tlsOnly()),
                process.processes(),
                process.tools(),
                process.resources());
    }

    static PermissionProfile brokerPermission(ThirdPartyBundleManifest manifest, Optional<PermissionProfile> caller) {
        var request = manifest.permissions();
        PermissionProfile approved = new PermissionProfile(
                "third-party-network:" + manifest.id(),
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(request.networkHosts(), request.networkPorts(), request.tlsOnly()),
                new ProcessPermission(Set.of(), false, request.maxRunTime()),
                new ToolPermission(Set.of(), request.maximumToolRisk(), ApprovalRequirement.RISKY),
                request.resources());
        return caller.map(profile -> PermissionResolver.intersect(List.of(profile, approved)))
                .orElse(approved);
    }

    private static PermissionProfile requestedWorkspacePermission(
            ThirdPartyBundleManifest manifest, Optional<Path> workspaceRoot, CanonicalJson json) {
        java.util.Objects.requireNonNull(json, "json");
        var request = manifest.permissions();
        List<Path> readable = request.workspaceRead() ? workspaceRoot.stream().toList() : List.of();
        List<Path> writable = request.workspaceWrite() ? workspaceRoot.stream().toList() : List.of();
        Set<String> toolNames = manifest.contributions().stream()
                .filter(contribution -> contribution.kind() == ContributionKind.TOOL)
                .map(contribution -> contribution.descriptor().orElseThrow())
                .map(payload -> json.decode(payload, ThirdPartyToolDefinition.class))
                .map(ThirdPartyToolDefinition::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new PermissionProfile(
                "third-party-request:" + manifest.id(),
                1,
                new FilePermission(readable, writable, request.allowDelete(), false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(manifest.entryPoint().executableName()), false, request.maxRunTime()),
                new ToolPermission(toolNames, request.maximumToolRisk(), ApprovalRequirement.RISKY),
                request.resources());
    }
}
