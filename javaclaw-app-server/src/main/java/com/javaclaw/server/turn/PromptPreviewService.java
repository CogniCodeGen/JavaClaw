package com.javaclaw.server.turn;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.PersistenceException;

/** 与 Turn 共用配置与项目约定解析，只返回允许用户审阅的 Prompt 来源和角色正文。 */
public final class PromptPreviewService {
    private final TurnPlatformServices services;
    private final AgentConfigurationResolver resolver;
    private final PromptManifestAssembler assembler;
    private final CanonicalJson json;
    private final com.javaclaw.runtime.ToolCatalogPort catalogs;

    /**
     * 创建下一 Turn 预览服务。
     *
     * @param services 与正式执行共用的平台权威服务
     * @param catalogs 正式执行的实际工具目录
     * @param coreInstruction 版本化平台说明
     * @param json 规范 JSON codec
     */
    public PromptPreviewService(
            TurnPlatformServices services,
            com.javaclaw.runtime.ToolCatalogPort catalogs,
            String coreInstruction,
            CanonicalJson json) {
        this.services = Objects.requireNonNull(services, "services");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        resolver = new AgentConfigurationResolver(
                services.roles(), services.configurations(), services.permissions(), services.core());
        assembler = new PromptManifestAssembler(coreInstruction);
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 按相同优先级与安全边界解析预览；不创建 Turn，不调用模型，也不保存项目正文。
     *
     * @param workspaceId 所属 Workspace
     * @param threadId 可选已有 Thread；有值时校验归属及执行根
     * @param execution 临时独立选择，均不能扩大权限或预算
     * @return 版本、来源、估算 token 与可审阅的 Role 文本
     */
    public PromptManifestPreview preview(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        Workspace workspace = services.core()
                .findWorkspace(workspaceId)
                .orElseThrow(() -> PersistenceException.invalidRequest("Workspace 不存在"));
        if (workspace.lifecycle() != WorkspaceLifecycle.ACTIVE) {
            throw PersistenceException.invalidRequest("已归档 Workspace 不能预览下一 Turn");
        }
        ThreadExecutionScope scope = threadId.map(
                        id -> ThreadExecutionScope.resolve(services.core(), services.worktrees(), id))
                .orElseGet(() -> ThreadExecutionScope.workspace(workspace));
        if (!scope.workspace().id().equals(workspaceId)) {
            throw PersistenceException.invalidRequest("Thread 不属于请求的 Workspace");
        }
        ResolvedAgentConfiguration configuration = resolver.resolve(scope, threadId, execution);
        configuration = configuration.withCatalog(catalogs.freeze(
                com.javaclaw.api.TurnId.random(),
                workspaceId,
                configuration.effectivePermissions(),
                new com.javaclaw.api.CancellationSource()));
        Optional<String> fallback =
                services.core().workspaceInstructionSettings(workspaceId).fallbackBasename();
        var instructions = services.instructions().resolve(scope.root(), scope.root(), fallback);
        return assembler.preview(configuration, instructions, json);
    }
}
