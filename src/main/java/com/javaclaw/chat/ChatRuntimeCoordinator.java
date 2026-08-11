package com.javaclaw.chat;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.agent.hook.LoopDetectionHook;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.runtime.ApplicationKernel;
import com.javaclaw.runtime.WorkspaceRuntime;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuSnapshot;
import com.javaclaw.ui.javafx.theme.FontSelectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates workspace-scoped runtime replacement for the chat presentation.
 *
 * <p>Potentially blocking transitions run in the supplied managed task scope. UI state is mutated
 * only through {@link FxDispatcher}. A transition rejects session mutations until its terminal UI
 * callback runs; rebuild requests arriving during a transition are coalesced into one later pass.</p>
 */
final class ChatRuntimeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ChatRuntimeCoordinator.class);

    private final ApplicationKernel kernel;
    private final FxDispatcher fx;
    private final TaskScope backgroundTasks;
    private final FontSelectionService fonts;
    private final AtomicBoolean transitioning = new AtomicBoolean();
    private final AtomicBoolean rebuildQueued = new AtomicBoolean();

    private volatile AgentRuntime runtime;
    private volatile ChatService chatService;
    private volatile PlanModeService planModeService;
    private volatile ModeRegistry modeRegistry;
    private volatile LoopDetectionHook.LoopInteractiveHandler loopHandler;
    private UiBindings ui;

    ChatRuntimeCoordinator(
            ApplicationKernel kernel,
            FxDispatcher fx,
            TaskScope backgroundTasks,
            FontSelectionService fonts) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.backgroundTasks = Objects.requireNonNull(backgroundTasks, "backgroundTasks");
        this.fonts = Objects.requireNonNull(fonts, "fonts");
        adopt(kernel.current());
    }

    void bind(
            ChatTurnController turns,
            ChatSessionCoordinator sessions,
            ChatHeaderController header,
            ChatModeController modes,
            SidebarController sidebar,
            WorkspaceSwitchOverlayController overlay,
            ChatStatusController status,
            LoopDetectionHook.LoopInteractiveHandler handler) {
        if (ui != null) throw new IllegalStateException("ChatRuntimeCoordinator 已绑定");
        ui = new UiBindings(turns, sessions, header, modes, sidebar, overlay, status);
        loopHandler = Objects.requireNonNull(handler, "handler");
        chatService.setLoopInteractiveHandler(loopHandler);
    }

    AgentRuntime runtime() {
        return runtime;
    }

    ChatService chatService() {
        return chatService;
    }

    PlanModeService planModeService() {
        return planModeService;
    }

    ModeRegistry modeRegistry() {
        return modeRegistry;
    }

    boolean isTransitioning() {
        return transitioning.get();
    }

    boolean rejectIfTransitioning(String action) {
        if (!transitioning.get()) return false;
        log.warn("运行时变更进行中，忽略操作：{}", action);
        notifyUser("系统", "服务重建中，请稍候再" + action);
        return true;
    }

    KnowledgeMenuSnapshot knowledgeMenuSnapshot() {
        KnowledgeExpert expert = runtime.getKnowledgeExpert();
        if (!expert.isRagEnabled()) return KnowledgeMenuSnapshot.ragDisabled();
        List<KnowledgeMenuSnapshot.Document> global = documents(
                expert, KnowledgeExpert.Scope.GLOBAL);
        List<KnowledgeMenuSnapshot.Document> workspace = documents(
                expert, KnowledgeExpert.Scope.WORKSPACE);
        return new KnowledgeMenuSnapshot(true, global, workspace, expert.getEnabledDocs());
    }

    void applyKnowledgeSelection(Set<String> selectedNames) {
        KnowledgeExpert expert = runtime.getKnowledgeExpert();
        List<String> names = new ArrayList<>(
                expert.getDocumentNames(KnowledgeExpert.Scope.GLOBAL));
        names.addAll(expert.getDocumentNames(KnowledgeExpert.Scope.WORKSPACE));
        names.forEach(name -> expert.setDocEnabled(name, selectedNames.contains(name)));
    }

    void rebuild() {
        UiBindings view = requireUi();
        stopStreamForTransition(view);
        if (!transitioning.compareAndSet(false, true)) {
            rebuildQueued.set(true);
            log.info("运行时变更已在进行，合并一次后续重建");
            return;
        }
        view.turns().setTransitionBlocked(true);
        try {
            backgroundTasks.submit(TaskSpec.io("agent-service-rebuild"), context -> {
                performRebuild();
                return null;
            });
        } catch (RuntimeException rejected) {
            transitioning.set(false);
            view.turns().setTransitionBlocked(false);
            throw rejected;
        }
    }

    void switchWorkspace(String targetWorkspaceId) {
        Objects.requireNonNull(targetWorkspaceId, "targetWorkspaceId");
        if (targetWorkspaceId.equals(kernel.current().context().workspaceId())) return;
        UiBindings view = requireUi();
        stopStreamForTransition(view);
        if (!transitioning.compareAndSet(false, true)) {
            view.sidebar().refreshWorkspaceCombo();
            notifyUser("系统", "服务重建中，工作区切换未执行，请稍候重试");
            return;
        }
        view.sessions().saveChatHistory();
        view.overlay().show("正在切换工作区...");
        view.turns().setTransitionBlocked(true);
        try {
            backgroundTasks.submit(TaskSpec.io("workspace-switch"), context -> {
                performWorkspaceSwitch(targetWorkspaceId);
                return null;
            });
        } catch (RuntimeException rejected) {
            transitioning.set(false);
            view.turns().setTransitionBlocked(false);
            view.overlay().hide();
            throw rejected;
        }
    }

    private void performRebuild() {
        boolean runtimeReady = false;
        try {
            adopt(kernel.rebuildCurrent());
            runtimeReady = true;
        } catch (RuntimeException failure) {
            log.error("重建运行时失败", failure);
            runtimeReady = recoverCurrentRuntime();
        } finally {
            boolean ready = runtimeReady;
            fx.dispatch(() -> finishRebuild(ready));
        }
    }

    private boolean recoverCurrentRuntime() {
        try {
            adopt(kernel.current());
            return true;
        } catch (IllegalStateException unavailable) {
            log.error("运行时恢复失败", unavailable);
            return false;
        }
    }

    private void finishRebuild(boolean runtimeReady) {
        UiBindings view = requireUi();
        try {
            if (runtimeReady) {
                view.header().resetKnowledgeMenu();
                view.header().refreshKnowledgeMenu();
                view.status().bind(kernel.current());
                view.modes().refreshModes(view.modes().selectedModeId());
                view.modes().refreshWorkflows();
            }
        } finally {
            transitioning.set(false);
            view.turns().setTransitionBlocked(!runtimeReady);
            if (!runtimeReady) {
                notifyUser("系统", "运行时恢复失败，请修正设置后再次保存或重启应用");
            }
            if (rebuildQueued.getAndSet(false)) rebuild();
        }
    }

    private void performWorkspaceSwitch(String targetWorkspaceId) {
        try {
            adopt(kernel.switchWorkspace(targetWorkspaceId));
            fx.dispatch(() -> finishWorkspaceSwitch(targetWorkspaceId));
        } catch (RuntimeException failure) {
            log.error("工作区切换失败", failure);
            boolean recovered = recoverCurrentRuntime();
            fx.dispatch(() -> finishWorkspaceFailure(recovered));
        } finally {
            fx.dispatch(this::releaseWorkspaceTransition);
        }
    }

    private void finishWorkspaceSwitch(String targetWorkspaceId) {
        UiBindings view = requireUi();
        try {
            WorkspaceRuntime workspace = kernel.current();
            view.status().bind(workspace);
            view.sessions().reloadWorkspace();
            view.header().resetKnowledgeMenu();
            view.modes().refreshModes("chat");
            view.modes().refreshWorkflows();
            view.sidebar().refreshWorkspaceCombo();
            view.header().reloadTheme();
            fonts.reloadFromWorkspace();
            view.modes().refreshReviewMode();
            log.info("工作区切换完成: {} ({})",
                    workspace.context().workspaceName(), targetWorkspaceId);
        } finally {
            view.turns().setTransitionBlocked(false);
            view.overlay().hide();
        }
    }

    private void finishWorkspaceFailure(boolean recovered) {
        UiBindings view = requireUi();
        try {
            if (recovered) view.status().bind(kernel.current());
        } finally {
            view.sidebar().refreshWorkspaceCombo();
            view.turns().setTransitionBlocked(!recovered);
            view.overlay().hide();
            notifyUser("系统", recovered
                    ? "工作区切换失败，已恢复原工作区"
                    : "工作区切换失败，运行时未恢复，请重启应用");
        }
    }

    private void releaseWorkspaceTransition() {
        transitioning.set(false);
        if (rebuildQueued.getAndSet(false)) rebuild();
    }

    private void stopStreamForTransition(UiBindings view) {
        if (view.turns().isStreaming()) {
            view.turns().stop(CancellationReason.RUNTIME_REBUILD, false,
                    ChatTurnController.StopPolicy.DISCARD_AND_INVALIDATE);
        }
    }

    private void adopt(WorkspaceRuntime workspace) {
        runtime = workspace.agentRuntime();
        chatService = workspace.chatService();
        planModeService = workspace.planModeService();
        modeRegistry = workspace.modeRegistry();
        LoopDetectionHook.LoopInteractiveHandler handler = loopHandler;
        if (handler != null) chatService.setLoopInteractiveHandler(handler);
    }

    private UiBindings requireUi() {
        UiBindings bindings = ui;
        if (bindings == null) throw new IllegalStateException("ChatRuntimeCoordinator 尚未绑定 UI");
        return bindings;
    }

    private static List<KnowledgeMenuSnapshot.Document> documents(
            KnowledgeExpert expert, KnowledgeExpert.Scope scope) {
        return expert.getDocumentNames(scope).stream()
                .map(name -> new KnowledgeMenuSnapshot.Document(
                        name, expert.getDocumentChunkCount(name)))
                .toList();
    }

    private static void notifyUser(String title, String message) {
        var port = ToolConfirmationManager.getPort();
        if (port != null) port.notify(new ToastRequest(title, message));
    }

    private record UiBindings(
            ChatTurnController turns,
            ChatSessionCoordinator sessions,
            ChatHeaderController header,
            ChatModeController modes,
            SidebarController sidebar,
            WorkspaceSwitchOverlayController overlay,
            ChatStatusController status) {

        private UiBindings {
            Objects.requireNonNull(turns, "turns");
            Objects.requireNonNull(sessions, "sessions");
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(modes, "modes");
            Objects.requireNonNull(sidebar, "sidebar");
            Objects.requireNonNull(overlay, "overlay");
            Objects.requireNonNull(status, "status");
        }
    }
}
