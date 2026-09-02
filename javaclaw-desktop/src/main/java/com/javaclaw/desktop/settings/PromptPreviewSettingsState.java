package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.Workspace;

/**
 * Prompt provenance 面板的不可变状态。
 *
 * @param phase 当前读取阶段
 * @param workspaces 可选择的 Workspace
 * @param workspace 当前 Workspace
 * @param profile 当前已持久化 Agent Profile
 * @param preview 最近一次权威预览
 * @param message 用户可见反馈
 * @param epoch 异步请求世代；旧响应必须丢弃
 */
public record PromptPreviewSettingsState(
        SettingsLoadState phase,
        List<Workspace> workspaces,
        Optional<Workspace> workspace,
        Optional<AgentProfile> profile,
        Optional<PromptManifestPreview> preview,
        String message,
        long epoch) {
    /** 校验状态并复制集合。 */
    public PromptPreviewSettingsState {
        Objects.requireNonNull(phase, "phase");
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(preview, "preview");
        message = Objects.requireNonNull(message, "message");
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must not be negative");
        }
    }

    /** @return 尚未读取 Workspace 的初始状态 */
    public static PromptPreviewSettingsState initial() {
        return new PromptPreviewSettingsState(
                SettingsLoadState.INITIAL, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), "", 0);
    }
}
