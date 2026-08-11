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
    public List<SessionSnapshot> sessions() {
        return List.copyOf(history.sessions());
    }

    @Override
    public void saveSessions(List<SessionSnapshot> sessions) {
        history.saveSessions(List.copyOf(sessions));
    }

    @Override
    public boolean hasMessages(String sessionId) {
        return history.hasMessages(requireSessionId(sessionId));
    }

    @Override
    public List<MessageSnapshot> messages(String sessionId) {
        return List.copyOf(history.messages(requireSessionId(sessionId)));
    }

    @Override
    public void saveMessages(String sessionId, List<MessageSnapshot> messages) {
        history.saveMessages(requireSessionId(sessionId), List.copyOf(messages));
    }

    @Override
    public void delete(String sessionId) {
        history.delete(requireSessionId(sessionId));
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 id 不能为空");
        }
        return sessionId;
    }
}
