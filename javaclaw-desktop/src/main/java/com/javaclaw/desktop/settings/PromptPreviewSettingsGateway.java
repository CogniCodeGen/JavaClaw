package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;

/** Prompt provenance 页面访问 Workspace 与 Agent Profile SDK 的异步边界。 */
public interface PromptPreviewSettingsGateway {
    /** @return 当前可选择的 Workspace 目录 */
    CompletionStage<List<Workspace>> workspaces();

    /**
     * 读取下一 Turn 的权威 Prompt provenance 预览。
     *
     * @param workspaceId 用于解析项目约定的 Workspace
     * @param profile 精确 Agent Profile 引用
     * @return 脱敏来源、完整 manifest 摘要和 token 估算
     */
    CompletionStage<PromptManifestPreview> preview(WorkspaceId workspaceId, AgentProfileRef profile);
}
