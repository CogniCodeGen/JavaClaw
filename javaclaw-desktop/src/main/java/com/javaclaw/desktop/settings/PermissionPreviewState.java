package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.EffectivePermissionPreview;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.Workspace;

/**
 * 权威有效权限预览的不可变页面状态。
 *
 * @param phase 异步阶段
 * @param workspaces 可选作用域目录
 * @param selectedWorkspace 当前作用域
 * @param candidates 可用于模拟窄化层的权限版本
 * @param turnGrant 可选 Turn grant
 * @param toolDeclaration 可选工具声明
 * @param preview 最近一次五层预览
 * @param message 状态或错误说明
 * @param epoch 请求代次
 */
public record PermissionPreviewState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> selectedWorkspace,
        List<PermissionProfile> candidates,
        Optional<PermissionProfile> turnGrant,
        Optional<PermissionProfile> toolDeclaration,
        Optional<EffectivePermissionPreview> preview,
        String message,
        long epoch) {
    /** 复制目录并校验状态。 */
    public PermissionPreviewState {
        phase = Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(workspaces);
        selectedWorkspace = Objects.requireNonNull(selectedWorkspace, "selectedWorkspace");
        candidates = List.copyOf(candidates);
        turnGrant = Objects.requireNonNull(turnGrant, "turnGrant");
        toolDeclaration = Objects.requireNonNull(toolDeclaration, "toolDeclaration");
        preview = Objects.requireNonNull(preview, "preview");
        message = Objects.requireNonNullElse(message, "");
    }

    /** @return 初始状态 */
    public static PermissionPreviewState initial() {
        return new PermissionPreviewState(
                SettingsLoadState.INITIAL,
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "",
                0);
    }
}
