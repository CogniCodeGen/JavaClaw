package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileExport;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptManifestPreview;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.RoleLifecycle;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopConfigurationChange;
import com.javaclaw.desktop.DesktopNotificationSubscription;

/** 角色与独立执行配置的 SDK 委托，共享管理中心会话和已有 Bundle 网关。 */
abstract class SdkRoleExecutionSettingsGateway extends SdkChatConfigurationGateway implements CoreSettingsGateway {
    private final DesktopPresenter desktop;

    SdkRoleExecutionSettingsGateway(DesktopPresenter desktop) {
        super(desktop);
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public DesktopNotificationSubscription onConfigurationChanged(Consumer<DesktopConfigurationChange> listener) {
        return desktop.configurationEvents().subscribe(listener);
    }

    /**
     * 在 SDK 成功回执后发布失效；失败和读取不会触发刷新，返回值保持原始权威回执。
     *
     * @param <T> 回执类型
     * @param operation SDK 写请求
     * @param kind 失效类别
     * @param workspaceId 受影响工作区，缺省表示全局
     * @param threadId 受影响 Thread，缺省表示非 Thread 写入
     * @return 发布失效后完成的成功回执，失败原因原样传播
     */
    protected <T> CompletionStage<T> changed(
            CompletionStage<T> operation,
            DesktopConfigurationChange.Kind kind,
            Optional<WorkspaceId> workspaceId,
            Optional<ThreadId> threadId) {
        return operation.thenApply(result -> {
            desktop.configurationEvents().publish(new DesktopConfigurationChange(kind, workspaceId, threadId));
            return result;
        });
    }

    /**
     * 发布全局目录写入后的失效。
     *
     * @param <T> 回执类型
     * @param operation SDK 写请求
     * @param kind 失效类别
     * @return 保留原始回执及失败语义的完成阶段
     */
    protected <T> CompletionStage<T> changed(CompletionStage<T> operation, DesktopConfigurationChange.Kind kind) {
        return changed(operation, kind, Optional.empty(), Optional.empty());
    }

    @Override
    public CompletionStage<List<AgentRole>> roles() {
        return desktop.submitSettingsRequest(client -> client.roles().list());
    }

    @Override
    public CompletionStage<AgentRole> role(AgentRoleRef reference) {
        AgentRoleRef checked = Objects.requireNonNull(reference, "reference");
        return desktop.submitSettingsRequest(client -> client.roles().read(checked.id(), checked.revision()));
    }

    @Override
    public CompletionStage<AgentRole> createRole(String id, AgentRoleSpec spec, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.roles().create(id, spec, options)),
                DesktopConfigurationChange.Kind.ROLES);
    }

    @Override
    public CompletionStage<AgentRole> updateRole(
            String id, AgentRoleSpec spec, RoleLifecycle lifecycle, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.roles().update(id, spec, lifecycle, options)),
                DesktopConfigurationChange.Kind.ROLES);
    }

    @Override
    public CompletionStage<AgentRole> archiveRole(String id, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.roles().archive(id, options)),
                DesktopConfigurationChange.Kind.ROLES);
    }

    @Override
    public CompletionStage<AgentRoleFilePreview> previewRoleImport(
            String id, String content, AgentRoleFileFormat format) {
        return desktop.submitSettingsRequest(client -> client.roles().importPreview(id, content, format));
    }

    @Override
    public CompletionStage<AgentRole> commitRoleImport(
            String previewId, Optional<ProviderRef> mapping, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.roles().importCommit(previewId, mapping, options)),
                DesktopConfigurationChange.Kind.ROLES);
    }

    @Override
    public CompletionStage<AgentRoleFileExport> exportRole(AgentRoleRef reference, AgentRoleFileFormat format) {
        return desktop.submitSettingsRequest(client -> client.roles().export(reference, format));
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> executionDefaults(Optional<WorkspaceId> workspaceId) {
        return desktop.submitSettingsRequest(client -> client.executions().readDefaults(workspaceId));
    }

    @Override
    public CompletionStage<ExecutionConfiguration> updateExecutionDefaults(
            Optional<WorkspaceId> workspaceId, ExecutionOverrides execution, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(
                client -> client.executions().updateDefaults(workspaceId, execution, options)),
                DesktopConfigurationChange.Kind.EXECUTION, workspaceId, Optional.empty());
    }

    @Override
    public CompletionStage<Optional<ExecutionConfiguration>> threadExecution(
            WorkspaceId workspaceId, ThreadId threadId) {
        return desktop.submitSettingsRequest(client -> client.executions().readThread(workspaceId, threadId));
    }

    @Override
    public CompletionStage<ExecutionConfiguration> updateThreadExecution(
            WorkspaceId workspaceId, ThreadId threadId, ExecutionOverrides execution, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(
                client -> client.executions().updateThread(workspaceId, threadId, execution, options)),
                DesktopConfigurationChange.Kind.EXECUTION, Optional.of(workspaceId), Optional.of(threadId));
    }

    @Override
    public CompletionStage<PromptManifestPreview> previewExecution(
            WorkspaceId workspaceId, ExecutionOverrides execution) {
        return desktop.submitSettingsRequest(
                client -> client.prompts().preview(workspaceId, Optional.empty(), execution));
    }

    @Override
    public CompletionStage<AgentRole> cloneRole(AgentRoleRef source, String id, String name, CommandOptions options) {
        return changed(desktop.submitSettingsRequest(client -> client.roles().clone(source, id, name, options)),
                DesktopConfigurationChange.Kind.ROLES);
    }
}
