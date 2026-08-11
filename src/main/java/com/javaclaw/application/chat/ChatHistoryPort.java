package com.javaclaw.application.chat;

import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageSnapshot;
import com.javaclaw.application.chat.ChatHistoryApplicationService.SessionSnapshot;

import java.util.List;

/** Durable workspace-scoped storage boundary for chat history snapshots. */
public interface ChatHistoryPort {

    List<SessionSnapshot> sessions();

    void saveSessions(List<SessionSnapshot> sessions);

    boolean hasMessages(String sessionId);

    List<MessageSnapshot> messages(String sessionId);

    void saveMessages(String sessionId, List<MessageSnapshot> messages);

    void delete(String sessionId);
}
