package com.javaclaw.application.chat;

import java.util.List;
import java.util.Objects;

/** Application boundary for complete chat-session and message snapshots. */
public final class ChatHistoryUseCase implements ChatHistoryApplicationService {

    private final ChatHistoryPort history;

    public ChatHistoryUseCase(ChatHistoryPort history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override
    public List<SessionSnapshot> sessions(String workspaceId) {
        return List.copyOf(history.sessions(requireWorkspaceId(workspaceId)));
    }

    @Override
    public void saveSessions(String workspaceId, List<SessionSnapshot> sessions) {
        history.saveSessions(requireWorkspaceId(workspaceId), List.copyOf(sessions));
    }

    @Override
    public boolean hasMessages(String workspaceId, String sessionId) {
        return history.hasMessages(
                requireWorkspaceId(workspaceId), requireSessionId(sessionId));
    }

    @Override
    public List<MessageSnapshot> messages(String workspaceId, String sessionId) {
        return List.copyOf(history.messages(
                requireWorkspaceId(workspaceId), requireSessionId(sessionId)));
    }

    @Override
    public void saveMessages(
            String workspaceId, String sessionId, List<MessageSnapshot> messages) {
        history.saveMessages(
                requireWorkspaceId(workspaceId),
                requireSessionId(sessionId),
                List.copyOf(messages));
    }

    @Override
    public void delete(String workspaceId, String sessionId) {
        history.delete(requireWorkspaceId(workspaceId), requireSessionId(sessionId));
    }

    private static String requireWorkspaceId(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("工作区 id 不能为空");
        }
        return workspaceId;
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 id 不能为空");
        }
        return sessionId;
    }
}
