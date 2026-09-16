package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;

/** 文件与目录 Worker 共用的冻结权限收窄；权限根须保持原真实目录身份，不能由模型扩展。 */
final class WorkspaceFilePermissionScope {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final Path root;
    private final Optional<PermissionProfile> effectivePermission;

    WorkspaceFilePermissionScope(Path root, Optional<PermissionProfile> effectivePermission) {
        this.root = root;
        this.effectivePermission = effectivePermission;
    }

    PermissionProfile permission(Path java, boolean write) throws IOException {
        FilePermission files = effectivePermission
                .map(PermissionProfile::files)
                .orElseGet(() -> new FilePermission(List.of(root), List.of(root), true, false));
        ResourceLimits limits = effectivePermission
                .map(PermissionProfile::resources)
                .orElseGet(() ->
                        new ResourceLimits(512L * 1024 * 1024, WorkspaceFileAccess.MAX_BYTES + 1024L * 1024, 4, 128));
        Duration timeout = effectivePermission
                .map(value -> value.processes().maxRunTime())
                .filter(value -> value.compareTo(TIMEOUT) < 0)
                .orElse(TIMEOUT);
        List<Path> reads = scopedRoots(Stream.concat(files.readRoots().stream(), files.writeRoots().stream())
                .toList());
        List<Path> writes = write ? scopedRoots(files.writeRoots()) : List.of();
        if (write && writes.isEmpty()) {
            throw new SecurityException("Workspace file operation has no effective write roots");
        }
        return new PermissionProfile(
                "workspace-file-worker",
                1,
                new FilePermission(reads, writes, write && files.allowDelete(), false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(java.getFileName().toString()), false, timeout),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.NONE),
                limits);
    }

    void requirePathPermission(String path, boolean allowRoot, boolean write) throws IOException {
        WorkspaceFileProtocol.requireRelative(path, allowRoot);
        WorkspaceFileMetadata.requireVisible(path);
        if (effectivePermission.isEmpty()) {
            return;
        }
        FilePermission files = effectivePermission.orElseThrow().files();
        List<Path> permitted = write
                ? scopedRoots(files.writeRoots())
                : scopedRoots(Stream.concat(files.readRoots().stream(), files.writeRoots().stream())
                        .toList());
        Path resolved = root.resolve(path).normalize();
        if (permitted.stream().noneMatch(resolved::startsWith)) {
            throw new SecurityException("Workspace path is outside the effective file permission");
        }
    }

    void requireDeletePermission(boolean delete) {
        if (delete
                && effectivePermission
                        .filter(value -> !value.files().allowDelete())
                        .isPresent()) {
            throw new SecurityException("Workspace file deletion is not permitted");
        }
    }

    private List<Path> scopedRoots(List<Path> permitted) throws IOException {
        ArrayList<Path> scoped = new ArrayList<>();
        for (Path path : permitted) {
            Path real = path.toRealPath();
            if (!real.equals(path)) {
                throw new IOException("Frozen Workspace permission root changed into a link or alias");
            }
            if (root.startsWith(real)) {
                scoped.add(root);
            } else if (real.startsWith(root)) {
                scoped.add(real);
            }
        }
        return scoped.stream().distinct().toList();
    }
}
