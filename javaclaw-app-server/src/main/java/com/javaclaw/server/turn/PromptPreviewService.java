package com.javaclaw.server.turn;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ProfileLifecycle;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.persistence.AgentProfileService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PersistenceException;

/** 为管理中心构造下一 Turn 的只读 Prompt provenance 预览。 */
public final class PromptPreviewService {
    private final CoreCommandService core;
    private final AgentProfileService profiles;
    private final ProjectInstructionResolver instructions;
    private final PromptManifestAssembler assembler;
    private final CanonicalJson json;

    /**
     * 创建服务。
     *
     * @param core Workspace 查询服务
     * @param profiles Agent Profile 版本服务
     * @param instructions 与 Turn 创建共用的项目约定解析器
     * @param coreInstruction 当前审阅过的 Core system instruction
     * @param json 规范 JSON codec
     */
    public PromptPreviewService(
            CoreCommandService core,
            AgentProfileService profiles,
            ProjectInstructionResolver instructions,
            String coreInstruction,
            CanonicalJson json) {
        this.core = Objects.requireNonNull(core, "core");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        assembler = new PromptManifestAssembler(coreInstruction);
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 按精确 Profile 和 Workspace 当前约定生成预览。
     *
     * <p>结果不包含项目约定正文；文件变化只会反映在下一次调用中。
     *
     * @param workspaceId Workspace
     * @param profileRef 精确 Agent Profile
     * @return Prompt 来源、摘要与 token 估算
     */
    public PromptManifestPreview preview(WorkspaceId workspaceId, AgentProfileRef profileRef) {
        Workspace workspace = core.findWorkspace(Objects.requireNonNull(workspaceId, "workspaceId"))
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
        if (workspace.lifecycle() != WorkspaceLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("已归档 Workspace 不能预览下一 Turn");
        }
        AgentProfileRef checkedRef = Objects.requireNonNull(profileRef, "profileRef");
        AgentProfile profile = profiles.require(checkedRef.id(), checkedRef.revision());
        if (profile.lifecycle() != ProfileLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("已归档或停用 Agent Profile 不能用于下一 Turn");
        }
        Optional<String> fallback =
                core.workspaceInstructionSettings(workspace.id()).fallbackBasename();
        var resolved = instructions.resolve(workspace.root(), workspace.root(), fallback);
        return assembler.preview(profile, resolved, json);
    }
}
