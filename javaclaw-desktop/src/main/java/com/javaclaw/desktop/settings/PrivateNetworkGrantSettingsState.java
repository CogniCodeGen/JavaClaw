package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.Workspace;

/**
 * 私网授权页面的不可变状态。
 *
 * @param phase 异步阶段
 * @param workspaces Workspace 目录
 * @param workspace 当前 Workspace
 * @param grants 当前 Workspace 的授权最新版本
 * @param selected 当前授权
 * @param purpose 授权用途草稿
 * @param origin HTTPS Origin 草稿
 * @param dnsAddresses 每行一个数字地址的草稿
 * @param validityHours 有效小时数草稿
 * @param preview 服务端签发的待确认预览
 * @param decisions 脱敏权限决策记录
 * @param message 状态说明
 * @param epoch 请求代次
 */
public record PrivateNetworkGrantSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> workspace,
        List<PrivateNetworkGrant> grants,
        Optional<PrivateNetworkGrant> selected,
        PrivateNetworkPurpose purpose,
        String origin,
        String dnsAddresses,
        String validityHours,
        Optional<PrivateNetworkGrantPreview> preview,
        List<PermissionDecisionTrace> decisions,
        String message,
        long epoch) {
    /** 复制集合并校验页面状态。 */
    public PrivateNetworkGrantSettingsState {
        phase = Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        workspace = Objects.requireNonNull(workspace, "workspace");
        grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
        selected = Objects.requireNonNull(selected, "selected");
        purpose = Objects.requireNonNull(purpose, "purpose");
        origin = Objects.requireNonNullElse(origin, "");
        dnsAddresses = Objects.requireNonNullElse(dnsAddresses, "");
        validityHours = Objects.requireNonNullElse(validityHours, "1");
        preview = Objects.requireNonNull(preview, "preview");
        decisions = List.copyOf(Objects.requireNonNull(decisions, "decisions"));
        message = Objects.requireNonNullElse(message, "");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch 不能为负数");
        }
    }

    /** @return 空初始状态 */
    public static PrivateNetworkGrantSettingsState initial() {
        return new PrivateNetworkGrantSettingsState(
                SettingsLoadState.INITIAL,
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                PrivateNetworkPurpose.MCP,
                "",
                "",
                "1",
                Optional.empty(),
                List.of(),
                "",
                0);
    }

    /** @return 是否存在尚未提交的授权草稿或预览 */
    public boolean dirty() {
        return !origin.isBlank() || !dnsAddresses.isBlank() || preview.isPresent();
    }
}
