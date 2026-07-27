package com.javaclaw.chat;

import com.javaclaw.config.WorkspaceManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatHistoryMetricsTest {

    @BeforeAll
    static void initWorkspace() {
        WorkspaceManager.getInstance().init();
    }

    @Test
    void 消息终态与本轮计量可持久化且旧记录允许为空() {
        String sessionId = "metrics-" + UUID.randomUUID();
        ChatMessage completed = new ChatMessage(ChatMessage.Role.ASSISTANT, "done");
        completed.setDeliveryState(DeliveryState.COMPLETE);
        completed.setMetrics(new TurnMetrics(12, 7, 345));
        ChatMessage legacy = new ChatMessage(ChatMessage.Role.ASSISTANT, "legacy");
        legacy.setDeliveryState(null);
        legacy.setMetrics(null);

        ChatHistoryManager history = new ChatHistoryManager();
        history.saveSessionMessages(sessionId, List.of(completed, legacy));
        List<ChatMessage> loaded = history.loadSessionMessages(sessionId);

        assertEquals(2, loaded.size());
        assertEquals(DeliveryState.COMPLETE, loaded.getFirst().getDeliveryState());
        assertEquals(new TurnMetrics(12, 7, 345), loaded.getFirst().getMetrics());
        assertNull(loaded.get(1).getDeliveryState());
        assertNull(loaded.get(1).getMetrics());
        history.deleteSession(sessionId);
    }

    @Test
    void 确定性命令计量为零() {
        assertEquals(0, new TurnMetrics(0, 0, 15).totalTokens());
    }
}
