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
        this.browsers = Objects.requireNonNull(browsers, "browsers");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.siteCredentials = Objects.requireNonNull(siteCredentials, "siteCredentials");
        Objects.requireNonNull(skillCurator, "skillCurator");
        Objects.requireNonNull(taskScope, "taskScope");
        this.runs = new AgentConversationRunner(agents, executor);
        this.requests = new RunRequestFactory(workspace);

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
        browsers.activateScope(
                com.javaclaw.browser.PlaywrightBrowserManager
                        .conversationScopeId(request.sessionId()));
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
        // Sessions are part of RunScope and need no private Agent checkpoint.
    }

    public void loadSession(String sessionId) {
        // Context is reconstructed by framework context/memory extensions for each run.
    }

    public void deleteSession(String sessionId) {
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
