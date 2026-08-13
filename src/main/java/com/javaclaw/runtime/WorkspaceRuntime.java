package com.javaclaw.runtime;

import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.config.DatabaseAccess;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.platform.spring.WorkspaceContextHandle;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.workflow.service.WorkflowService;
import com.javaclaw.ui.javafx.mcp.McpCenterViewFactory;
import com.javaclaw.ui.javafx.settings.SettingsViewFactory;
import com.javaclaw.ui.javafx.schedule.ScheduleViewFactory;
import com.javaclaw.ui.javafx.memory.MemoryViewFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeCenterViewFactory;
import com.javaclaw.ui.javafx.workflow.WorkflowViewFactory;
import com.javaclaw.ui.javafx.skill.SkillCenterViewFactory;
import com.javaclaw.ui.javafx.task.SddTaskViewFactory;

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
    private final AgentConfig agentConfig;
    private final com.javaclaw.memory.MemoryService memoryService;
    private final com.javaclaw.skill.SkillRuntimeServices skillRuntimeServices;
    private final com.javaclaw.agent.TokenTracker tokenTracker;
    private final com.javaclaw.memory.embed.EmbeddingGateway embeddingGateway;
    private final com.javaclaw.agent.expert.KnowledgeExpert knowledgeExpert;
    private final ChatService chatService;
    private final PlanModeService planModeService;
    private final com.javaclaw.schedule.FrameworkScheduledTaskRunner scheduledTaskRunner;
    private final WorkflowService workflowService;
    private final ModeRegistry modeRegistry;
    private final McpCenterViewFactory mcpCenters;
    private final SettingsViewFactory settingsViews;
    private final ScheduleViewFactory scheduleViews;
    private final MemoryViewFactory memoryViews;
    private final KnowledgeCenterViewFactory knowledgeViews;
    private final WorkflowViewFactory workflowViews;
    private final SkillCenterViewFactory skillViews;
    private final ScheduleManager scheduleManager;
    private final ScheduleApplicationService schedules;
    private final SkillManagementApplicationService skills;
    private final SddTaskApplicationService sddTasks;
    private final SddTaskViewFactory sddTaskViews;

    WorkspaceRuntime(WorkspaceContextHandle springContext) {
        this.springContext = Objects.requireNonNull(springContext, "springContext");
        context = springContext.workspace();
        databaseAccess = springContext.bean(DatabaseAccess.class);
        agentConfig = springContext.bean(AgentConfig.class);
        memoryService = springContext.bean(com.javaclaw.memory.MemoryService.class);
        skillRuntimeServices = springContext.bean(com.javaclaw.skill.SkillRuntimeServices.class);
        tokenTracker = springContext.bean(com.javaclaw.agent.TokenTracker.class);
        embeddingGateway = springContext.bean(com.javaclaw.memory.embed.EmbeddingGateway.class);
        knowledgeExpert = springContext.bean(com.javaclaw.agent.expert.KnowledgeExpert.class);
        chatService = springContext.bean(ChatService.class);
        planModeService = springContext.bean(PlanModeService.class);
        scheduledTaskRunner = springContext.bean(
                com.javaclaw.schedule.FrameworkScheduledTaskRunner.class);
        workflowService = springContext.bean(WorkflowService.class);
        modeRegistry = springContext.bean(ModeRegistry.class);
        mcpCenters = springContext.bean(McpCenterViewFactory.class);
        settingsViews = springContext.bean(SettingsViewFactory.class);
        scheduleViews = springContext.bean(ScheduleViewFactory.class);
        memoryViews = springContext.bean(MemoryViewFactory.class);
        knowledgeViews = springContext.bean(KnowledgeCenterViewFactory.class);
        workflowViews = springContext.bean(WorkflowViewFactory.class);
        skillViews = springContext.bean(SkillCenterViewFactory.class);
        scheduleManager = springContext.bean(ScheduleManager.class);
        schedules = springContext.bean(ScheduleApplicationService.class);
        skills = springContext.bean(SkillManagementApplicationService.class);
        sddTasks = springContext.bean(SddTaskApplicationService.class);
        sddTaskViews = springContext.bean(SddTaskViewFactory.class);
    }

    public WorkspaceContext context() {
        return context;
    }

    public DatabaseAccess databaseAccess() {
        return databaseAccess;
    }

    public AgentConfig agentConfig() {
        return agentConfig;
    }

    public com.javaclaw.memory.MemoryService memoryService() { return memoryService; }

    public com.javaclaw.skill.SkillRuntimeServices skillRuntimeServices() {
        return skillRuntimeServices;
    }

    public com.javaclaw.agent.TokenTracker tokenTracker() { return tokenTracker; }

    public com.javaclaw.memory.embed.EmbeddingGateway embeddingGateway() {
        return embeddingGateway;
    }

    public com.javaclaw.agent.expert.KnowledgeExpert knowledgeExpert() {
        return knowledgeExpert;
    }

    public ChatService chatService() {
        return chatService;
    }

    public PlanModeService planModeService() {
        return planModeService;
    }

    public com.javaclaw.schedule.ScheduledTaskRunner scheduledTaskRunner() {
        return scheduledTaskRunner;
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

    public MemoryViewFactory memoryViews() {
        return memoryViews;
    }

    public KnowledgeCenterViewFactory knowledgeViews() {
        return knowledgeViews;
    }

    public WorkflowViewFactory workflowViews() {
        return workflowViews;
    }

    public SkillCenterViewFactory skillViews() {
        return skillViews;
    }

    public ScheduleManager scheduleManager() {
        return scheduleManager;
    }

    public ScheduleApplicationService schedules() {
        return schedules;
    }

    public SkillManagementApplicationService skills() {
        return skills;
    }

    public SddTaskApplicationService sddTasks() {
        return sddTasks;
    }

    public SddTaskViewFactory sddTaskViews() {
        return sddTaskViews;
    }

    public boolean isClosed() {
        return springContext.isClosed();
    }

    @Override
    public void close() {
        springContext.close();
    }
}
