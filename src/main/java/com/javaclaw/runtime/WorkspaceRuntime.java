package com.javaclaw.runtime;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.platform.spring.WorkspaceContextHandle;
import com.javaclaw.workflow.service.WorkflowService;
import com.javaclaw.ui.javafx.mcp.McpCenterViewFactory;
import com.javaclaw.ui.javafx.settings.SettingsViewFactory;
import com.javaclaw.ui.javafx.schedule.ScheduleViewFactory;

import java.util.Objects;

/**
 * 工作区子 Context 的类型安全门面。
 *
 * <p>所有暴露对象均由同一个子 Context 创建；关闭句柄会先取消工作区任务，再按 Spring
 * 依赖反序销毁服务。实例关闭后不得继续缓存或调用此前取得的 Bean。</p>
 */
public final class WorkspaceRuntime implements AutoCloseable {

    private final WorkspaceContextHandle springContext;
    private final WorkspaceContext context;
    private final DatabaseAccess databaseAccess;
    private final AgentRuntime agentRuntime;
    private final ChatService chatService;
    private final PlanModeService planModeService;
    private final WorkflowService workflowService;
    private final ModeRegistry modeRegistry;
    private final McpCenterViewFactory mcpCenters;
    private final SettingsViewFactory settingsViews;
    private final ScheduleViewFactory scheduleViews;

    WorkspaceRuntime(WorkspaceContextHandle springContext) {
        this.springContext = Objects.requireNonNull(springContext, "springContext");
        context = springContext.workspace();
        databaseAccess = springContext.bean(DatabaseAccess.class);
        agentRuntime = springContext.bean(AgentRuntime.class);
        chatService = springContext.bean(ChatService.class);
        planModeService = springContext.bean(PlanModeService.class);
        workflowService = springContext.bean(WorkflowService.class);
        modeRegistry = springContext.bean(ModeRegistry.class);
        mcpCenters = springContext.bean(McpCenterViewFactory.class);
        settingsViews = springContext.bean(SettingsViewFactory.class);
        scheduleViews = springContext.bean(ScheduleViewFactory.class);
    }

    public WorkspaceContext context() {
        return context;
    }

    public DatabaseAccess databaseAccess() {
        return databaseAccess;
    }

    public AgentRuntime agentRuntime() {
        return agentRuntime;
    }

    public ChatService chatService() {
        return chatService;
    }

    public PlanModeService planModeService() {
        return planModeService;
    }

    public WorkflowService workflowService() {
        return workflowService;
    }

    public ModeRegistry modeRegistry() {
        return modeRegistry;
    }

    public McpCenterViewFactory mcpCenters() {
        return mcpCenters;
    }

    public SettingsViewFactory settingsViews() {
        return settingsViews;
    }

    public ScheduleViewFactory scheduleViews() {
        return scheduleViews;
    }

    public boolean isClosed() {
        return springContext.isClosed();
    }

    @Override
    public void close() {
        springContext.close();
    }
}
