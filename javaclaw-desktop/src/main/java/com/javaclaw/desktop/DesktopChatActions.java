package com.javaclaw.desktop;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.state.DesktopState;

/** 在冻结目标上编排模型应用与日常偏好；配置通知只能在 SDK 成功返回后投递。 */
final class DesktopChatActions {
    private final DesktopPresenter desktop;
    private final Supplier<DesktopState> state;
    private final DesktopModelApplication applications = new DesktopModelApplication();

    DesktopChatActions(DesktopPresenter desktop, Supplier<DesktopState> state) {
        this.desktop = desktop;
        this.state = state;
    }

    CompletionStage<ExecutionConfiguration> remember(
            WorkspaceId workspace, ThreadId thread, ExecutionOverrides selected, CommandOptions options, boolean remember) {
        CompletionStage<Void> defaults = remember
                ? desktop.submitSettingsRequest(client -> {
                    DesktopModelPreferences.remember(client, selected);
                    return (Void) null;
                }).thenRun(() -> changed(Optional.empty(), Optional.empty()))
                : CompletableFuture.completedFuture(null);
        return defaults.thenCompose(ignored -> desktop.submitSettingsRequest(client ->
                client.executions().updateThread(workspace, thread, selected, options))).thenApply(saved -> {
                    changed(Optional.of(workspace), Optional.of(thread));
                    return saved;
                });
    }

    CompletionStage<Void> useModel(Optional<WorkspaceId> workspace, Optional<ThreadId> thread, ProviderRef model) {
        if (workspace.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("模型已保存，请先选择或创建工作区"));
        }
        var before = state.get().threads();
        Optional<ThreadId> current = before.selectedThread().map(ConversationThread::id);
        Optional<ThreadId> targetThread = thread.or(() -> before.selectedThread()
                .filter(value -> value.workspaceId().equals(workspace.orElseThrow())).map(ConversationThread::id));
        var target = new DesktopModelApplication.Target(workspace.orElseThrow(), targetThread, model);
        return desktop.submitSettingsRequest(client -> applications.apply(client, target)).thenCompose(applied -> {
            changed(Optional.empty(), Optional.empty());
            changed(workspace, Optional.of(applied));
            boolean samePage = current.equals(state.get().threads().selectedThread().map(ConversationThread::id))
                    && before.selectedWorkspace().map(Workspace::id)
                    .equals(state.get().threads().selectedWorkspace().map(Workspace::id));
            CompletionStage<?> navigation = samePage
                    ? desktop.navigateToThread(applied) : CompletableFuture.completedFuture(null);
            return navigation.thenRun(() -> applications.complete(target));
        });
    }

    CompletionStage<Workspace> createWorkspace(String name, Path root) {
        return desktop.submitSettingsRequest(client -> client.workspaces().create(name, root, CommandOptions.create(0)))
                .thenApply(created -> {
                    desktop.configurationEvents().publish(new DesktopConfigurationChange(
                            DesktopConfigurationChange.Kind.WORKSPACES, Optional.of(created.id()), Optional.empty()));
                    return created;
                });
    }

    private void changed(Optional<WorkspaceId> workspace, Optional<ThreadId> thread) {
        desktop.configurationEvents().publish(new DesktopConfigurationChange(
                DesktopConfigurationChange.Kind.EXECUTION, workspace, thread));
    }
}
