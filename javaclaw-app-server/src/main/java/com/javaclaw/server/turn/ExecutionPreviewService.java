package com.javaclaw.server.turn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionBlocker;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderReadiness;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderStatus;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderService;

/**
 * 与启动 Turn 共用解析器的轻量配置查询；只读取权威配置和本地 Vault 可用性。
 *
 * <p>该服务不依赖模型网关、工具目录或项目资料解析器，因此查询不会创建执行快照、读取 Prompt 或计费。
 */
public final class ExecutionPreviewService {
    private final CoreCommandService core;
    private final ManagedWorktreeService worktrees;
    private final ProviderService providers;
    private final AgentConfigurationResolver resolver;

    /**
     * 创建执行预览服务。
     *
     * @param core Workspace 与 Thread 权威服务
     * @param roles 精确 Agent 版本服务
     * @param configurations 执行配置服务
     * @param permissions 权限求交服务
     * @param worktrees Thread 执行根服务
     * @param providers 本地连接可用性服务
     */
    public ExecutionPreviewService(
            CoreCommandService core, AgentRoleService roles, ExecutionConfigurationService configurations,
            PermissionProfileService permissions, ManagedWorktreeService worktrees, ProviderService providers) {
        this.core = Objects.requireNonNull(core, "core");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.providers = Objects.requireNonNull(providers, "providers");
        resolver = new AgentConfigurationResolver(roles, configurations, permissions, core);
    }

    /**
     * 返回当前精确配置与可修复阻塞项；查询期间不修改任何业务状态。
     *
     * @param workspaceId 固定目标 Workspace，不可空
     * @param threadId 可选目标 Thread；不能查询其他 Workspace 的配置
     * @param execution 临时执行覆盖，不可空
     * @return 正常返回配置阻塞；存储故障仍作为错误返回，不能误报为用户配置问题
     */
    public ExecutionPreview preview(
            WorkspaceId workspaceId, Optional<ThreadId> threadId, ExecutionOverrides execution) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(execution, "execution");
        Optional<Workspace> workspace = core.findWorkspace(workspaceId);
        if (workspace.isEmpty() || workspace.orElseThrow().lifecycle() != WorkspaceLifecycle.ACTIVE) {
            return blocked(ExecutionBlocker.Code.WORKSPACE_UNAVAILABLE, "工作区不存在或已归档，请选择可用工作区");
        }
        if (threadId.isPresent() && !belongsToWorkspace(threadId.orElseThrow(), workspaceId)) {
            return blocked(ExecutionBlocker.Code.THREAD_UNAVAILABLE, "对话不存在或不属于当前工作区");
        }
        ThreadExecutionScope scope;
        try {
            scope = threadId.map(id -> ThreadExecutionScope.resolve(core, worktrees, id))
                    .orElseGet(() -> ThreadExecutionScope.workspace(workspace.orElseThrow()));
        } catch (PersistenceException failure) {
            requireInvalidRequest(failure);
            return blocked(ExecutionBlocker.Code.THREAD_UNAVAILABLE, failure.getMessage());
        }
        ExecutionPreview preview = resolver.preview(scope, threadId, execution);
        return preview.provider().map(provider -> inspectProvider(preview, provider)).orElse(preview);
    }

    private boolean belongsToWorkspace(ThreadId threadId, WorkspaceId workspaceId) {
        Optional<ConversationThread> thread = core.findThread(threadId);
        return thread.filter(value -> value.workspaceId().equals(workspaceId)).isPresent();
    }

    private ExecutionPreview inspectProvider(ExecutionPreview preview, ProviderRef provider) {
        List<ExecutionBlocker> blockers = new ArrayList<>(preview.blockers());
        try {
            ProviderStatus status = providers.probe(provider);
            if (status.readiness() != ProviderReadiness.READY) {
                blockers.add(new ExecutionBlocker(
                        ExecutionBlocker.Code.valueOf(status.readiness().name()),
                        status.detail().orElse("连接配置尚未就绪")));
            } else if (!status.capabilities().purposes().contains(ProviderModelPurpose.CHAT)) {
                blockers.add(new ExecutionBlocker(ExecutionBlocker.Code.MODEL_UNAVAILABLE, "所选模型不支持聊天，请选择聊天模型"));
            }
        } catch (PersistenceException failure) {
            requireInvalidRequest(failure);
            blockers.add(new ExecutionBlocker(ExecutionBlocker.Code.MODEL_UNAVAILABLE, "模型的指定版本不可用，请重新选择模型"));
        }
        return new ExecutionPreview(
                preview.role(), preview.provider(), preview.reasoning(), preview.modelLocked(),
                preview.reasoningLocked(), preview.provenance(), blockers);
    }

    private static void requireInvalidRequest(PersistenceException failure) {
        if (failure.kind() != PersistenceException.Kind.INVALID_REQUEST) {
            throw failure;
        }
    }

    private static ExecutionPreview blocked(ExecutionBlocker.Code code, String message) {
        return new ExecutionPreview(
                Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                List.of(), List.of(new ExecutionBlocker(code, message)));
    }
}
