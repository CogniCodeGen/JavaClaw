package com.javaclaw.server.security;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionPresetDescriptor;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.Workspace;

/** 将只读权限预设实例化为特定 Workspace 的普通 PermissionProfile。 */
public final class PermissionPresetCatalog {
    private static final long REVISION = 1;
    private static final PermissionPresetDescriptor REVIEW = new PermissionPresetDescriptor(
            "workspace-review", REVISION, "Workspace 只读审阅", "只能读取当前 Workspace，禁止写入、删除、网络、进程和 PTY。", false);
    private static final PermissionPresetDescriptor DEVELOPER = new PermissionPresetDescriptor(
            "workspace-developer", REVISION, "Workspace 开发", "可在当前 Workspace 读写；写入、删除和高风险工具仍需人工审批。", true);
    private static final List<PermissionPresetDescriptor> PRESETS = List.of(REVIEW, DEVELOPER);

    /**
     * 返回当前发行版的只读权限预设目录。
     *
     * @return 稳定顺序的不可变预设
     */
    public List<PermissionPresetDescriptor> list() {
        return PRESETS;
    }

    /**
     * 针对固定 Workspace 构造但不持久化权限配置。
     *
     * @param request 包含精确预设版本与用户确认允许列表的请求
     * @param workspace 请求中 Workspace 的最新快照
     * @return 可供用户确认的完整普通 PermissionProfile
     */
    public PermissionPresetPreview preview(PermissionPresetInstantiationRequest request, Workspace workspace) {
        PermissionPresetInstantiationRequest checked = Objects.requireNonNull(request, "request");
        Workspace checkedWorkspace = Objects.requireNonNull(workspace, "workspace");
        if (!checked.workspaceId().equals(checkedWorkspace.id())) {
            throw new IllegalArgumentException("Permission preset Workspace 不匹配");
        }
        PermissionPresetDescriptor preset = require(checked.presetId(), checked.presetRevision());
        PermissionProfile proposed =
                switch (preset.id()) {
                    case "workspace-review" -> review(checked, checkedWorkspace);
                    case "workspace-developer" -> developer(checked, checkedWorkspace);
                    default -> throw new IllegalStateException("未处理的 Permission preset: " + preset.id());
                };
        return new PermissionPresetPreview(preset, checkedWorkspace.id(), proposed, warnings(preset, checked));
    }

    /**
     * 读取精确预设版本。
     *
     * @param id 预设标识
     * @param revision 预设版本
     * @return 精确预设
     * @throws IllegalArgumentException 预设或版本不存在
     */
    public PermissionPresetDescriptor require(String id, long revision) {
        String checkedId = Objects.requireNonNull(id, "id").strip();
        return PRESETS.stream()
                .filter(preset -> preset.id().equals(checkedId) && preset.revision() == revision)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Permission preset 不存在"));
    }

    private static PermissionProfile review(PermissionPresetInstantiationRequest request, Workspace workspace) {
        if (!request.executables().isEmpty()) {
            throw new IllegalArgumentException("Workspace 只读审阅预设不允许可执行文件");
        }
        return profile(
                request,
                new FilePermission(List.of(workspace.root()), List.of(), false, false),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                ToolRisk.READ_ONLY,
                ApprovalRequirement.RISKY,
                new ResourceLimits(256L * 1024 * 1024, 16L * 1024 * 1024, 1, 32));
    }

    private static PermissionProfile developer(PermissionPresetInstantiationRequest request, Workspace workspace) {
        return profile(
                request,
                new FilePermission(List.of(workspace.root()), List.of(workspace.root()), true, false),
                new ProcessPermission(request.executables(), false, Duration.ofMinutes(5)),
                ToolRisk.PROCESS,
                ApprovalRequirement.RISKY,
                new ResourceLimits(1024L * 1024 * 1024, 64L * 1024 * 1024, 8, 128));
    }

    private static PermissionProfile profile(
            PermissionPresetInstantiationRequest request,
            FilePermission files,
            ProcessPermission processes,
            ToolRisk risk,
            ApprovalRequirement approval,
            ResourceLimits resources) {
        return new PermissionProfile(
                request.profileId(),
                1,
                files,
                new NetworkPermission(Set.of(), Set.of(), true),
                processes,
                new ToolPermission(request.allowedTools(), risk, approval),
                resources);
    }

    private static List<String> warnings(
            PermissionPresetDescriptor preset, PermissionPresetInstantiationRequest request) {
        if ("workspace-developer".equals(preset.id()) && !request.executables().isEmpty()) {
            return List.of("已选可执行文件仅在受控 Sandbox 中运行，且高风险调用仍需审批。");
        }
        return List.of();
    }
}
