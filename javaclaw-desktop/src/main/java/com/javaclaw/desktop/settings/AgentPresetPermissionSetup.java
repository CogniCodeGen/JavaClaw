package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionPresetPreview;
import com.javaclaw.api.ToolDescriptor;

/**
 * 向导中的权限预览、确认和工具候选快照。
 *
 * @param reviewPreview 只读审阅权限预览
 * @param developerPreview 开发权限预览
 * @param reviewCandidates 只读权限能力上限内的工具
 * @param developerCandidates 开发权限能力上限内的工具
 * @param confirmed 用户是否显式确认过本轮预览
 * @param toolsLoaded 是否已按持久化权限版本读取工具目录
 */
public record AgentPresetPermissionSetup(
        Optional<PermissionPresetPreview> reviewPreview,
        Optional<PermissionPresetPreview> developerPreview,
        List<ToolDescriptor> reviewCandidates,
        List<ToolDescriptor> developerCandidates,
        boolean confirmed,
        boolean toolsLoaded) {
    /** 复制列表并校验可空值。 */
    public AgentPresetPermissionSetup {
        reviewPreview = Objects.requireNonNull(reviewPreview, "reviewPreview");
        developerPreview = Objects.requireNonNull(developerPreview, "developerPreview");
        reviewCandidates = List.copyOf(Objects.requireNonNull(reviewCandidates, "reviewCandidates"));
        developerCandidates = List.copyOf(Objects.requireNonNull(developerCandidates, "developerCandidates"));
    }

    /** @return 尚未预览权限时的初始值 */
    public static AgentPresetPermissionSetup empty() {
        return new AgentPresetPermissionSetup(Optional.empty(), Optional.empty(), List.of(), List.of(), false, false);
    }

    /** @return 两份权限预览是否都已读取 */
    public boolean previewsReady() {
        return reviewPreview.isPresent() && developerPreview.isPresent();
    }
}
