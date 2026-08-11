package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ChatService;
import com.javaclaw.agent.PlanModeService;
import com.javaclaw.agent.ShellCommandService;
import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.api.conversation.ModeRegistry;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.agent.AgentManagementApplicationService;
import com.javaclaw.application.knowledge.KnowledgeApplicationService;
import com.javaclaw.application.knowledge.KnowledgePort;
import com.javaclaw.application.knowledge.KnowledgeSettingsPort;
import com.javaclaw.application.knowledge.KnowledgeUseCase;
import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.application.memory.MemoryPort;
import com.javaclaw.application.memory.MemoryUseCase;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementPort;
import com.javaclaw.application.skill.SkillManagementUseCase;
import com.javaclaw.application.task.SddTaskApplicationService;
import com.javaclaw.infrastructure.knowledge.AgentConfigKnowledgeSettingsAdapter;
import com.javaclaw.infrastructure.knowledge.KnowledgeExpertAdapter;
import com.javaclaw.infrastructure.memory.MemoryServiceAdapter;
import com.javaclaw.infrastructure.skill.LegacySkillManagementAdapter;
import com.javaclaw.loop.LoopService;
import com.javaclaw.mode.ChatMode;
import com.javaclaw.mode.LoopMode;
import com.javaclaw.mode.PlanMode;
import com.javaclaw.mode.ShellMode;
import com.javaclaw.mode.TaskMode;
import com.javaclaw.mode.WorkflowCenterMode;
import com.javaclaw.mode.WorkflowMode;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.json.JsonCodec;
import com.javaclaw.platform.storage.AtomicContentStore;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.schedule.ScheduleBuiltinActions;
import com.javaclaw.schedule.ScheduleManager;
import com.javaclaw.skill.SkillInstaller;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.skill.SkillRuntimeServices;
import com.javaclaw.skill.SkillUsageTracker;
import com.javaclaw.skill.curation.SkillCurator;
import com.javaclaw.skill.curation.SkillProposalQueue;
import com.javaclaw.ui.javafx.knowledge.JavaFxKnowledgeImportPicker;
import com.javaclaw.ui.javafx.knowledge.KnowledgeCenterViewFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeDocumentCellFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeImportPicker;
import com.javaclaw.ui.javafx.knowledge.KnowledgePreviewCellFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeSearchHitCellFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeTextImportDialogFactory;
import com.javaclaw.ui.javafx.memory.MemoryComponentFactory;
import com.javaclaw.ui.javafx.memory.MemoryFactDialogFactory;
import com.javaclaw.ui.javafx.memory.MemoryViewFactory;
import com.javaclaw.ui.javafx.skill.SkillBundleCellFactory;
import com.javaclaw.ui.javafx.skill.SkillCenterViewFactory;
import com.javaclaw.ui.javafx.skill.SkillListCellFactory;
import com.javaclaw.ui.javafx.skill.SkillProposalCardFactory;
import com.javaclaw.ui.javafx.skill.SkillScriptNameDialogFactory;
import com.javaclaw.workflow.service.WorkflowService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Workspace intelligence, knowledge and conversation runtime.
 *
 * <p>This configuration is imported explicitly by {@link WorkspaceSpringConfiguration}; it does
 * not perform component scanning. Beans share the owning child context and therefore close when
 * the workspace is replaced.</p>
 */
@Configuration(proxyBeanMethods = false)
class WorkspaceIntelligenceConfiguration {

    @Bean
    SkillManager skillManager(
            WorkspaceContext workspace,
            ObjectMapper json,
            com.javaclaw.config.AgentConfig settings) {
        return new SkillManager(workspace.globalDataRoot().resolve("skills"), json, settings);
    }

    @Bean(destroyMethod = "close")
    SkillUsageTracker skillUsageTracker(
            WorkspaceContext workspace,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            com.javaclaw.config.AgentConfig settings,
            ManagedTaskExecutor scheduler,
            @Qualifier("workspaceTaskScope") TaskScope tasks) {
        return new SkillUsageTracker(workspace.workspaceId(), jdbc, transactionManager,
                settings, scheduler, tasks);
    }

    @Bean(destroyMethod = "close")
    SkillProposalQueue skillProposalQueue(
            WorkspaceContext workspace,
            SkillManager skills,
            com.javaclaw.config.AgentConfig settings,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            JsonCodec json,
            ManagedTaskExecutor scheduler,
            @Qualifier("workspaceTaskScope") TaskScope tasks) {
        return new SkillProposalQueue(workspace.workspaceId(), skills, settings, jdbc,
                transactionManager, json, scheduler, tasks);
    }

    @Bean
    SkillRuntimeServices skillRuntimeServices(
            SkillManager manager,
            SkillUsageTracker usage,
            SkillProposalQueue proposals) {
        return new SkillRuntimeServices(manager, usage, proposals);
    }

    @Bean
    SkillCurator skillCurator(
            AgentRuntime runtime,
            SkillRuntimeServices skills,
            com.javaclaw.config.AgentConfig settings,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            UserInteractionPort interaction) {
        return new SkillCurator(runtime.getModelFactory(), runtime.getTokenTracker(),
                skills.manager(), skills.usage(), skills.proposals(), settings, tasks,
                () -> interaction);
    }

    @Bean
    SkillInstaller skillInstaller(SkillManager skills) {
        return new SkillInstaller(skills);
    }

    @Bean
    SkillManagementPort skillManagementPort(
            SkillManager skills,
            SkillUsageTracker usage,
            SkillProposalQueue proposals,
            SkillInstaller installer,
            com.javaclaw.config.AgentConfig settings,
            com.javaclaw.system.JShellRunner jshellRunner) {
        return new LegacySkillManagementAdapter(
                skills, usage, proposals, installer, settings, jshellRunner);
    }

    @Bean
    SkillManagementApplicationService skillManagementApplicationService(
            SkillManagementPort skills) {
        return new SkillManagementUseCase(skills);
    }

    @Bean
    SkillListCellFactory skillListCellFactory() {
        return new SkillListCellFactory();
    }

    @Bean
    SkillBundleCellFactory skillBundleCellFactory() {
        return new SkillBundleCellFactory();
    }

    @Bean
    SkillProposalCardFactory skillProposalCardFactory() {
        return new SkillProposalCardFactory();
    }

    @Bean
    SkillScriptNameDialogFactory skillScriptNameDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.platform.fx.FxDispatcher fx,
            com.javaclaw.app.UIHelper ui) {
        return new SkillScriptNameDialogFactory(loader, fx, ui);
    }

    @Bean
    SkillCenterViewFactory skillCenterViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.platform.fx.FxDispatcher fx) {
        return new SkillCenterViewFactory(loader, fx);
    }

    @Bean(destroyMethod = "shutdown")
    ChatService chatService(
            AgentRuntime runtime,
            WorkflowService workflows,
            SkillCurator skillCurator,
            @Qualifier("workspaceTaskScope") TaskScope taskScope) {
        return new ChatService(runtime, workflows, skillCurator, taskScope);
    }

    @Bean(destroyMethod = "")
    com.javaclaw.memory.MemoryService memoryService(ChatService chats) {
        return chats.getMemoryService();
    }

    @Bean(destroyMethod = "")
    KnowledgeExpert knowledgeExpert(AgentRuntime runtime) {
        return runtime.getKnowledgeExpert();
    }

    @Bean
    KnowledgePort knowledgePort(KnowledgeExpert expert) {
        return new KnowledgeExpertAdapter(expert);
    }

    @Bean
    KnowledgeSettingsPort knowledgeSettingsPort(com.javaclaw.config.AgentConfig settings) {
        return new AgentConfigKnowledgeSettingsAdapter(settings);
    }

    @Bean
    KnowledgeApplicationService knowledgeApplicationService(
            KnowledgePort knowledge,
            KnowledgeSettingsPort settings,
            WorkspaceContext workspace) {
        return new KnowledgeUseCase(knowledge, settings, workspace.workspaceName());
    }

    @Bean
    KnowledgeImportPicker knowledgeImportPicker() {
        return new JavaFxKnowledgeImportPicker();
    }

    @Bean
    KnowledgeDocumentCellFactory knowledgeDocumentCellFactory() {
        return new KnowledgeDocumentCellFactory();
    }

    @Bean
    KnowledgePreviewCellFactory knowledgePreviewCellFactory() {
        return new KnowledgePreviewCellFactory();
    }

    @Bean
    KnowledgeSearchHitCellFactory knowledgeSearchHitCellFactory() {
        return new KnowledgeSearchHitCellFactory();
    }

    @Bean
    KnowledgeTextImportDialogFactory knowledgeTextImportDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.platform.fx.FxDispatcher fx,
            com.javaclaw.app.UIHelper ui) {
        return new KnowledgeTextImportDialogFactory(loader, fx, ui);
    }

    @Bean
    KnowledgeCenterViewFactory knowledgeCenterViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new KnowledgeCenterViewFactory(loader);
    }

    @Bean
    MemoryPort memoryPort(
            com.javaclaw.memory.MemoryService memory,
            KnowledgeExpert knowledge,
            AtomicContentStore files) {
        return new MemoryServiceAdapter(memory, knowledge, files);
    }

    @Bean
    MemoryApplicationService memoryApplicationService(MemoryPort memory) {
        return new MemoryUseCase(memory);
    }

    @Bean
    MemoryComponentFactory memoryComponentFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new MemoryComponentFactory(loader);
    }

    @Bean
    MemoryFactDialogFactory memoryFactDialogFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader,
            com.javaclaw.app.UIHelper ui) {
        return new MemoryFactDialogFactory(loader, ui);
    }

    @Bean
    MemoryViewFactory memoryViewFactory(
            @Qualifier("workspaceFxmlLoader") SpringFxmlLoader loader) {
        return new MemoryViewFactory(loader);
    }

    @Bean(destroyMethod = "close")
    ScheduleBuiltinActions scheduleBuiltinActions(
            ScheduleManager schedules,
            com.javaclaw.system.CommandSessionManager commandSessions,
            ChatService chats) {
        return new ScheduleBuiltinActions(schedules, commandSessions, chats.getMemoryService());
    }

    @Bean(destroyMethod = "shutdown")
    PlanModeService planModeService(AgentRuntime runtime, WorkflowService workflows) {
        return new PlanModeService(runtime, workflows);
    }

    @Bean(destroyMethod = "shutdown")
    LoopService loopService(
            AgentRuntime runtime,
            WorkflowService workflows,
            com.javaclaw.platform.process.ProcessRunner processes) {
        return new LoopService(runtime, workflows, processes);
    }

    @Bean
    ShellCommandService shellCommandService(
            AgentManagementApplicationService agents,
            ScheduleApplicationService schedules,
            SddTaskApplicationService sddTasks,
            @Qualifier("workspaceTaskScope") TaskScope taskScope) {
        return new ShellCommandService(agents, schedules, sddTasks, taskScope);
    }

    @Bean
    ChatMode chatMode(ChatService service) {
        return new ChatMode(service);
    }

    @Bean
    PlanMode planMode(PlanModeService service) {
        return new PlanMode(service);
    }

    @Bean
    LoopMode loopMode(LoopService service) {
        return new LoopMode(service);
    }

    @Bean
    WorkflowMode workflowMode(WorkflowService service) {
        return new WorkflowMode(service);
    }

    @Bean
    ShellMode shellMode(ShellCommandService service) {
        return new ShellMode(service);
    }

    @Bean
    TaskMode taskMode(WorkspaceRuntimeOptions options) {
        return new TaskMode(options.openTaskView());
    }

    @Bean
    WorkflowCenterMode workflowCenterMode(WorkspaceRuntimeOptions options) {
        return new WorkflowCenterMode(options.openWorkflowView(), options.closeWorkflowView());
    }

    @Bean(destroyMethod = "shutdownAll")
    ModeRegistry modeRegistry(
            WorkspaceRuntimeOptions options,
            ChatMode chat,
            PlanMode plan,
            LoopMode loop,
            WorkflowMode workflow,
            ShellMode shell,
            TaskMode task,
            WorkflowCenterMode workflowCenter) {
        ModeRegistry registry = new ModeRegistry(options.disabledModes());
        registry.register(chat);
        registry.register(plan);
        registry.register(loop);
        registry.register(workflow);
        registry.register(shell);
        registry.register(task);
        registry.register(workflowCenter);
        return registry;
    }
}
