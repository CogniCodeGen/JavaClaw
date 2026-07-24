package com.javaclaw.runtime;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.ShellCommandService;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.loop.LoopService;
import com.javaclaw.mode.ChatMode;
import com.javaclaw.mode.LoopMode;
import com.javaclaw.mode.PlanMode;
import com.javaclaw.mode.ShellMode;
import com.javaclaw.mode.TaskMode;
import com.javaclaw.mode.WorkflowCenterMode;
import com.javaclaw.mode.WorkflowMode;
import com.javaclaw.workflow.node.PublicNodeCatalog;
import com.javaclaw.workflow.service.SystemGraphRegistry;
import com.javaclaw.workflow.service.WorkflowService;
import com.javaclaw.workflow.store.H2GraphCheckpointStore;
import com.javaclaw.workflow.store.H2WorkflowDefinitionStore;

import java.util.Objects;
import java.util.Set;

/** 创建完整工作区运行时的唯一工厂。 */
public final class RuntimeFactory {

    private final PlaywrightBrowserManager browserManager;
    private final Runnable openTaskView;
    private final Runnable openWorkflowView;
    private final Runnable closeWorkflowView;
    private final Set<String> disabledModes;

    public RuntimeFactory(PlaywrightBrowserManager browserManager,
                          Runnable openTaskView,
                          Runnable openWorkflowView,
                          Runnable closeWorkflowView,
                          Set<String> disabledModes) {
        this.browserManager = Objects.requireNonNull(browserManager, "browserManager");
        this.openTaskView = Objects.requireNonNull(openTaskView, "openTaskView");
        this.openWorkflowView = Objects.requireNonNull(openWorkflowView, "openWorkflowView");
        this.closeWorkflowView = Objects.requireNonNull(closeWorkflowView, "closeWorkflowView");
        this.disabledModes = disabledModes == null ? Set.of() : Set.copyOf(disabledModes);
    }

    /**
     * 事务式创建运行时。任何构造步骤失败都会释放已经创建的对象，避免 EclipseStore 锁、
     * MCP 连接或循环服务泄漏。
     */
    public WorkspaceRuntime create(WorkspaceContext context) {
        Objects.requireNonNull(context, "context");
        AgentRuntime runtime = null;
        ChatService chat = null;
        PlanModeService plan = null;
        ModeRegistry modes = null;
        WorkflowService workflows = null;
        try {
            runtime = new AgentRuntime(browserManager);
            var nodeRegistry = PublicNodeCatalog.createRegistry(runtime);
            var checkpoints = new H2GraphCheckpointStore(context.workspaceId());
            var definitions = new H2WorkflowDefinitionStore(context.workspaceId());
            var systemGraphs = new SystemGraphRegistry();
            systemGraphs.register(com.javaclaw.workflow.service.SystemGraphFactory.sdd());
            workflows = new WorkflowService(context.workspaceId(), runtime, nodeRegistry,
                    definitions, checkpoints, systemGraphs);
            chat = new ChatService(runtime, workflows);
            plan = new PlanModeService(runtime, workflows);

            modes = new ModeRegistry(disabledModes);
            modes.register(new ChatMode(chat));
            modes.register(new PlanMode(plan));
            modes.register(new LoopMode(new LoopService(runtime, workflows)));
            modes.register(new WorkflowMode(workflows));
            modes.register(new ShellMode(new ShellCommandService(chat)));
            modes.register(new TaskMode(openTaskView));
            modes.register(new WorkflowCenterMode(openWorkflowView, closeWorkflowView));
            return new WorkspaceRuntime(context, runtime, chat, plan, workflows, modes);
        } catch (RuntimeException | Error failure) {
            if (modes != null) safeClose(modes::shutdownAll);
            if (chat != null) safeClose(chat::shutdown);
            if (plan != null) safeClose(plan::shutdown);
            if (workflows != null) safeClose(workflows::close);
            if (runtime != null) safeClose(runtime::shutdown);
            throw failure;
        }
    }

    private static void safeClose(Runnable closer) {
        try {
            closer.run();
        } catch (Throwable ignored) {
            // 保留最初的创建异常。
        }
    }
}
