package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.Workspace;

/**
 * Prompt 优化面板的目录与选择快照。
 *
 * @param workspaces 可选择的活动 Workspace
 * @param workspace 当前 Workspace
 * @param profile Agent Profile 页面当前权威版本
 * @param drafts 当前 Workspace 和 Profile 的草稿目录
 * @param selected 当前草稿
 */
public record PromptOptimizationSelection(
        List<Workspace> workspaces,
        Optional<Workspace> workspace,
        Optional<AgentProfile> profile,
        List<PromptOptimizationDraft> drafts,
        Optional<PromptOptimizationDraft> selected) {
    /** 复制集合并校验选择属于目录。 */
    public PromptOptimizationSelection {
        workspaces = List.copyOf(workspaces);
        workspace = Objects.requireNonNull(workspace, "workspace");
        profile = Objects.requireNonNull(profile, "profile");
        drafts = List.copyOf(drafts);
        selected = Objects.requireNonNull(selected, "selected");
        if (workspace.filter(workspaces::contains).isEmpty() && workspace.isPresent()) {
            throw new IllegalArgumentException("selected 工作区 must be in catalog");
        }
        if (selected.filter(drafts::contains).isEmpty() && selected.isPresent()) {
            throw new IllegalArgumentException("selected 提示词 draft must be in catalog");
        }
    }

    /** @return 空初始选择 */
    public static PromptOptimizationSelection initial() {
        return new PromptOptimizationSelection(
                List.of(), Optional.empty(), Optional.empty(), List.of(), Optional.empty());
    }
}
