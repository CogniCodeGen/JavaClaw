package com.javaclaw.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.model.AgentCheckpoint;
import com.javaclaw.workflow.service.WorkflowService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Owns normal-chat checkpoints and the last delivered reply used by correction detection. */
final class ChatSessionStateStore {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionStateStore.class);

    private final AgentRuntime runtime;
    private final WorkflowService workflows;
    private final MemoryService memory;
    private final Supplier<ReActAgent> orchestrator;
    private final ConcurrentMap<String, String> lastReplies = new ConcurrentHashMap<>();

    ChatSessionStateStore(
            AgentRuntime runtime,
            WorkflowService workflows,
            MemoryService memory,
            Supplier<ReActAgent> orchestrator) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.workflows = workflows;
        this.memory = Objects.requireNonNull(memory, "memory");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    String lastReply(String sessionId) {
        return lastReplies.getOrDefault(sessionKey(sessionId), "");
    }

    void rememberReply(String sessionId, String reply) {
        if (reply != null && !reply.isBlank()) lastReplies.put(sessionKey(sessionId), reply);
    }

    void clearTrackedReplies() {
        lastReplies.clear();
    }

    void save(String sessionId) {
        try {
            List<Msg> messages = orchestrator.get().getMemory().getMessages();
            String json = JsonUtils.getJsonCodec().toJson(messages);
            memory.checkpoint(sessionId, json);
            log.info("会话已检查点入记忆库: {} ({} 条消息)", sessionId, messages.size());
        } catch (Exception failure) {
            log.error("保存会话检查点失败: {}", sessionId, failure);
        }
    }

    void load(String sessionId) {
        try {
            AgentCheckpoint checkpoint = memory.loadCheckpoint(sessionId);
            if (checkpoint == null || checkpoint.messagesJson == null
                    || checkpoint.messagesJson.isBlank()) {
                log.info("无会话检查点，使用空白状态: {}", sessionId);
                return;
            }
            List<Msg> messages = JsonUtils.getJsonCodec().fromJson(
                    checkpoint.messagesJson, new TypeReference<List<Msg>>() { });
            Memory target = orchestrator.get().getMemory();
            target.clear();
            messages.forEach(target::addMessage);
            latestAssistantReply(messages).ifPresent(reply ->
                    lastReplies.put(sessionKey(sessionId), reply));
            runtime.getMemoryManager().healDanglingToolCalls(target, "orchestrator");
            log.info("会话已从记忆库检查点恢复: {} ({} 条消息)", sessionId, messages.size());
        } catch (Exception failure) {
            log.warn("恢复会话检查点失败（使用空白状态）: {}", sessionId, failure);
        }
    }

    void delete(String sessionId) {
        memory.deleteCheckpoint(sessionId);
        lastReplies.remove(sessionKey(sessionId));
        String browserScope = PlaywrightBrowserManager.conversationScopeId(sessionId);
        runtime.getBrowserManager().releaseScope(browserScope);
        runtime.getSiteCredentialManager().clearScopeBindings(browserScope);
        if (workflows != null) workflows.forgetConversationBrowserScope(sessionId);
    }

    private static java.util.Optional<String> latestAssistantReply(List<Msg> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (message.getRole() == MsgRole.ASSISTANT
                    && message.getTextContent() != null
                    && !message.getTextContent().isBlank()) {
                return java.util.Optional.of(message.getTextContent());
            }
        }
        return java.util.Optional.empty();
    }

    static String sessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "__default__" : sessionId;
    }
}
