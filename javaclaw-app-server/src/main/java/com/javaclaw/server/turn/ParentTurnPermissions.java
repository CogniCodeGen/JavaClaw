package com.javaclaw.server.turn;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ToolCatalogSnapshot;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;

/** 执行前重放父级实时约束；Worktree 仅重定位已经允许的相对文件范围，绝不增加新根。 */
final class ParentTurnPermissions {
    private ParentTurnPermissions() {}

    static PermissionProfile intersect(
            TurnPlatformServices services, CanonicalJson json, AgentTurn child, PermissionProfile permissions) {
        return intersect(
                new Sources(services.core(), services.permissions(), services.worktrees(), json), child, permissions);
    }

    static PermissionProfile intersect(Sources sources, AgentTurn child, PermissionProfile permissions) {
        return intersect(sources, child.threadId(), child.executionRoot(), permissions);
    }

    /** 子 Thread 的预算关联已提交后，即使尚未创建 Turn，也按权威执行根重放同一父级约束。 */
    static PermissionProfile intersect(
            TurnPlatformServices services,
            CanonicalJson json,
            ThreadExecutionScope child,
            PermissionProfile permissions) {
        return intersect(
                new Sources(services.core(), services.permissions(), services.worktrees(), json),
                child.thread().orElseThrow().id(),
                child.root(),
                permissions);
    }

    private static PermissionProfile intersect(
            Sources sources, ThreadId childThreadId, Path childRoot, PermissionProfile permissions) {
        Optional<AgentTurn> parent = sources.core().parentTurn(childThreadId);
        if (parent.isEmpty()) {
            return permissions;
        }
        AgentTurn ancestor = parent.orElseThrow();
        if (sources.core().cancellationRequested(ancestor.id())) {
            throw new TurnCancelledException("父 Turn 已取消");
        }
        ThreadExecutionScope scope =
                ThreadExecutionScope.resolve(sources.core(), sources.worktrees(), ancestor.threadId());
        var configuration = sources.core().resolvedConfig(ancestor.id());
        PermissionProfile current = sources.permissions()
                .resolveForExecution(
                        configuration.permissionProfile().id(),
                        configuration.permissionProfile().version(),
                        scope.workspace(),
                        scope.root(),
                        scope.writable());
        current = AgentConfigurationResolver.constrain(
                current,
                Optional.of(configuration.effectiveCapabilities()),
                configuration.approvalPolicy(),
                configuration.permissionConstraint());
        ToolCatalogSnapshot frozen =
                sources.json().decode(sources.core().toolCatalogSnapshot(ancestor.id()), ToolCatalogSnapshot.class);
        PermissionProfile inherited = intersect(
                sources, ancestor, PermissionResolver.intersect(List.of(current, frozen.permissionCeiling())));
        return PermissionResolver.intersect(
                List.of(permissions, rebase(inherited, ancestor.executionRoot(), childRoot)));
    }

    private static PermissionProfile rebase(PermissionProfile source, Path parentRoot, Path childRoot) {
        FilePermission files = source.files();
        return new PermissionProfile(
                source.id(),
                source.version(),
                new FilePermission(
                        rebaseRoots(files.readRoots(), parentRoot, childRoot),
                        rebaseRoots(files.writeRoots(), parentRoot, childRoot),
                        files.allowDelete(),
                        false),
                source.network(),
                source.processes(),
                source.tools(),
                source.resources());
    }

    private static List<Path> rebaseRoots(List<Path> roots, Path parentRoot, Path childRoot) {
        return roots.stream()
                .filter(path -> path.startsWith(parentRoot))
                .map(path -> childRoot.resolve(parentRoot.relativize(path)).normalize())
                .distinct()
                .toList();
    }

    record Sources(
            CoreCommandService core,
            PermissionProfileService permissions,
            ManagedWorktreeService worktrees,
            CanonicalJson json) {}
}
