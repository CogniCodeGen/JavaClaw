package com.javaclaw.application.chat;

import java.time.LocalDateTime;
import java.util.List;

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
            TurnUsage usage) {
        public MessageSnapshot {
            if (role == null) throw new IllegalArgumentException("消息角色不能为空");
            content = content == null ? "" : content;
            timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
            imagePaths = imagePaths == null ? List.of() : List.copyOf(imagePaths);
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

    record TurnUsage(long inputTokens, long outputTokens, long durationMs) {
        public TurnUsage {
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            durationMs = Math.max(0, durationMs);
        }
    }
}
