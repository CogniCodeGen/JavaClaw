package com.javaclaw.application.chat;

import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageSnapshot;
import com.javaclaw.application.chat.ChatHistoryApplicationService.SessionSnapshot;

import java.util.List;

/** Durable workspace-scoped storage boundary for chat history snapshots. */
public interface ChatHistoryPort {

    List<SessionSnapshot> sessions(String workspaceId);

    void saveSessions(String workspaceId, List<SessionSnapshot> sessions);

    boolean hasMessages(String workspaceId, String sessionId);

    List<MessageSnapshot> messages(String workspaceId, String sessionId);

    void saveMessages(String workspaceId, String sessionId, List<MessageSnapshot> messages);

    void delete(String workspaceId, String sessionId);
}
