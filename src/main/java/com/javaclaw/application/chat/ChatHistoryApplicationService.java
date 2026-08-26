package com.javaclaw.application.chat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Workspace-scoped chat history commands and queries.
 *
 * <p>All methods may block on JDBC and therefore must run on a managed I/O task, except during the
 * serialized application startup sequence. Callers must capture the workspace id before queuing
 * asynchronous work; this prevents a delayed save from following a later workspace switch. Writes
 * publish only complete snapshots; interruption is observed at the surrounding managed-task
 * boundary.</p>
 */
public interface ChatHistoryApplicationService {

    List<SessionSnapshot> sessions(String workspaceId);

    void saveSessions(String workspaceId, List<SessionSnapshot> sessions);

    boolean hasMessages(String workspaceId, String sessionId);

    List<MessageSnapshot> messages(String workspaceId, String sessionId);

    void saveMessages(String workspaceId, String sessionId, List<MessageSnapshot> messages);

    void delete(String workspaceId, String sessionId);

    record SessionSnapshot(String id, String title, LocalDateTime createdAt) {
        public SessionSnapshot {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("会话 id 不能为空");
            title = title == null || title.isBlank() ? "新的对话" : title;
            createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        }
    }

    record MessageSnapshot(
            MessageRole role,
            String content,
            LocalDateTime timestamp,
            List<String> imagePaths,
            boolean adopted,
            DeliveryStatus deliveryStatus,
            TurnUsage usage,
            String messageId) {
        public MessageSnapshot {
            if (role == null) throw new IllegalArgumentException("消息角色不能为空");
            content = content == null ? "" : content;
            timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
            imagePaths = imagePaths == null ? List.of() : List.copyOf(imagePaths);
            messageId = messageId == null || messageId.isBlank()
                    ? UUID.randomUUID().toString() : messageId.strip();
        }

        /** Compatibility constructor for callers that do not yet persist message identity. */
        public MessageSnapshot(
                MessageRole role,
                String content,
                LocalDateTime timestamp,
                List<String> imagePaths,
                boolean adopted,
                DeliveryStatus deliveryStatus,
                TurnUsage usage) {
            this(role, content, timestamp, imagePaths, adopted, deliveryStatus, usage, null);
        }
    }

    enum MessageRole {
        USER,
        ASSISTANT,
        SYSTEM
    }

    enum DeliveryStatus {
        COMPLETE,
        CANCELLED,
        FAILED
    }

    record TurnUsage(
            long inputTokens,
            long cacheReadInputTokens,
            long cacheWriteInputTokens,
            long outputTokens,
            long reasoningTokens,
            long modelCalls,
            long durationMs) {
        public TurnUsage {
            inputTokens = Math.max(0, inputTokens);
            cacheReadInputTokens = Math.min(inputTokens, Math.max(0, cacheReadInputTokens));
            cacheWriteInputTokens = Math.min(
                    inputTokens - cacheReadInputTokens, Math.max(0, cacheWriteInputTokens));
            outputTokens = Math.max(0, outputTokens);
            reasoningTokens = Math.min(outputTokens, Math.max(0, reasoningTokens));
            modelCalls = Math.max(0, modelCalls);
            durationMs = Math.max(0, durationMs);
        }

        public TurnUsage(long inputTokens, long outputTokens, long durationMs) {
            this(inputTokens, 0, 0, outputTokens, 0,
                    inputTokens > 0 || outputTokens > 0 ? 1 : 0, durationMs);
        }
    }
}
