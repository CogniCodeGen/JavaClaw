package com.javaclaw.agent;

import com.javaclaw.agent.execution.ExecutionMonitor;
import com.javaclaw.agent.hook.LoopDetectionHook;
import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationHandle;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.application.agent.AgentConversationRunner;
import com.javaclaw.application.agent.RunRequestFactory;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.runtime.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Interactive-chat application adapter. The service intentionally contains no model, advisor,
 * tool-calling loop, subscription state machine or checkpoint implementation; every invocation is
 * translated into a {@code RunRequest} for the process-wide {@link AgentClient}.
 */
public final class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final com.javaclaw.browser.PlaywrightBrowserManager browsers;
    private final com.javaclaw.workflow.service.WorkflowService workflows;
    private final com.javaclaw.site.SiteCredentialManager siteCredentials;
    private final AgentConversationRunner runs;
    private final RunRequestFactory requests;
    private final com.javaclaw.memory.MemoryService memoryService;
    private final com.javaclaw.framework.api.ThreadClient threads;
    private final WorkspaceContext workspace;
    private com.javaclaw.framework.api.StepClient steps;

    public ChatService bindStepClient(com.javaclaw.framework.api.StepClient stepClient) {
        steps = java.util.Objects.requireNonNull(stepClient, "stepClient");
        return this;
    }

    public java.util.List<com.javaclaw.framework.api.AgentStep> sessionSteps(
            String sessionId, com.javaclaw.framework.api.RunId turnId) {
        if (sessionTurns(sessionId).stream().noneMatch(turn -> turn.id().equals(turnId))) {
            throw new IllegalArgumentException("轮次不属于当前会话");
        }
        return steps == null ? java.util.List.of() : steps.steps(turnId);
    }

    public ChatService(
            com.javaclaw.browser.PlaywrightBrowserManager browsers,
            com.javaclaw.workflow.service.WorkflowService workflows,
            com.javaclaw.site.SiteCredentialManager siteCredentials,
            com.javaclaw.skill.curation.SkillCurator skillCurator,
            TaskScope taskScope,
            AgentClient agents,
            com.javaclaw.memory.MemoryService memoryService,
            WorkspaceContext workspace,
            Executor executor) {
        this(browsers, workflows, siteCredentials, skillCurator, taskScope, agents,
                memoryService, workspace, executor, null);
    }

    public ChatService(
            com.javaclaw.browser.PlaywrightBrowserManager browsers,
            com.javaclaw.workflow.service.WorkflowService workflows,
            com.javaclaw.site.SiteCredentialManager siteCredentials,
            com.javaclaw.skill.curation.SkillCurator skillCurator,
            TaskScope taskScope, AgentClient agents,
            com.javaclaw.memory.MemoryService memoryService,
            WorkspaceContext workspace, Executor executor,
            com.javaclaw.framework.api.ThreadClient threads) {
        this.browsers = Objects.requireNonNull(browsers, "browsers");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.siteCredentials = Objects.requireNonNull(siteCredentials, "siteCredentials");
        Objects.requireNonNull(skillCurator, "skillCurator");
        Objects.requireNonNull(taskScope, "taskScope");
        this.runs = new AgentConversationRunner(agents, executor);
        this.requests = new RunRequestFactory(workspace);
        this.workspace = workspace;
        this.threads = threads;

        this.memoryService = Objects.requireNonNull(memoryService, "memoryService");
        log.info("Chat 入口已接入统一 AgentEngine，profile=chat");
    }

    /** Built-in expert names remain presentation metadata; they no longer select runtimes. */
    public List<String> builtinAgentNames() {
        return List.of("coding_expert", "task_evaluator", "web_expert", "email_expert",
                "system_expert", "desktop_expert", "notification_expert", "command_expert");
    }

    public ConversationHandle streamChat(
            ConversationRequest request, ConversationCallbacks callbacks) {
        return runs.start(requests.conversation(
                        request, "chat", InvocationSource.chat(), PermissionSet.UNRESTRICTED),
                ToolCallOrigin.INTERACTIVE, callbacks);
    }

    public boolean cancelStream() {
        return runs.cancel(CancellationReason.USER_REQUEST);
    }

    public boolean cancelStream(String sessionId) {
        return runs.cancelSession(sessionId, CancellationReason.USER_REQUEST);
    }

    /** Run history is durable in RunStore; UI message history is owned by ChatHistoryPort. */
    public void clearHistory() {
        log.debug("ChatService.clearHistory: no private runtime history to clear");
    }

    public void saveSession(String sessionId) {
        startSession(sessionId, "新的对话");
    }

    public void loadSession(String sessionId) {
        if (threads != null) {
            startSession(sessionId, "新的对话");
            threads.resume(scope(sessionId));
        }
    }

    public void startSession(String sessionId, String title) {
        if (threads != null) threads.start(
                com.javaclaw.framework.api.ThreadStartRequest.root(scope(sessionId), title));
    }

    public void archiveSession(String sessionId) {
        if (threads == null) throw new IllegalStateException("会话生命周期服务未初始化");
        threads.archive(scope(sessionId));
    }

    public com.javaclaw.framework.api.ThreadSnapshot forkSession(String sessionId, String title) {
        if (threads == null) throw new IllegalStateException("会话生命周期服务未初始化");
        var turns = threads.turns(scope(sessionId));
        if (turns.stream().anyMatch(turn -> !turn.state().terminal())) {
            throw new IllegalStateException("会话仍有活动轮次，请结束后再创建分支");
        }
        var last = turns.stream().max(java.util.Comparator.comparing(
                com.javaclaw.framework.api.RunSnapshot::createdAt)).orElseThrow(
                () -> new IllegalStateException("会话还没有可分支的已完成轮次"));
        return threads.fork(scope(sessionId),
                com.javaclaw.framework.api.TurnId.from(last.id()), title);
    }

    public java.util.List<com.javaclaw.framework.api.ThreadSnapshot> sessions() {
        return threads == null ? java.util.List.of()
                : threads.list(workspace.workspaceId(), "local-user", true).stream()
                .filter(thread -> threads.events(thread.scope(), 0).stream()
                        .filter(event -> event.payload().has("request")).findFirst()
                        .map(event -> !event.payload().path("request").path("source")
                                .path("kind").asText().equals("maintenance")).orElse(true))
                .toList();
    }

    public java.util.List<com.javaclaw.framework.api.RunSnapshot> sessionTurns(String sessionId) {
        return threads == null ? java.util.List.of() : threads.turns(scope(sessionId));
    }

    public java.util.List<com.javaclaw.application.chat.ChatHistoryApplicationService.MessageSnapshot> persistedMessages(String sessionId) {
        return threads == null ? java.util.List.of()
                : com.javaclaw.application.chat.ChatThreadTranscript.project(threads.events(scope(sessionId), 0));
    }

    private com.javaclaw.framework.api.RunScope scope(String sessionId) {
        return new com.javaclaw.framework.api.RunScope(
                workspace.workspaceId(), "local-user", sessionId);
    }

    public void deleteSession(String sessionId) {
        if (threads != null) threads.delete(scope(sessionId));
        memoryService.deleteCheckpoint(sessionId);
        String scope = com.javaclaw.browser.PlaywrightBrowserManager
                .conversationScopeId(sessionId);
        browsers.releaseScope(scope);
        siteCredentials.clearScopeBindings(scope);
        workflows.forgetConversationBrowserScope(sessionId);
    }

    public com.javaclaw.memory.MemoryService getMemoryService() {
        return memoryService;
    }

    public String getCurrentPlanMarkdown() {
        return null;
    }

    /** Loop policy now belongs to RunControl; kept as a UI compatibility no-op. */
    public void setLoopInteractiveHandler(LoopDetectionHook.LoopInteractiveHandler handler) {
        // The framework emits a durable loop/budget event instead of retaining a UI callback.
    }

    /** @deprecated execution observations are available from RunEvent/EventStore. */
    @Deprecated(forRemoval = true)
    public ExecutionMonitor getExecutionMonitor() {
        return null;
    }

    public void shutdown() {
        runs.close();
    }
}
