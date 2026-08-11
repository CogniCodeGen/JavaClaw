package com.javaclaw.chat;

import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatHistoryMetricsTest {

    private static org.springframework.context.annotation.AnnotationConfigApplicationContext root;
    private static String previousDataDirectory;

    @TempDir
    static Path tempDirectory;

    @BeforeAll
    static void initWorkspace() {
        previousDataDirectory = System.getProperty(DataRoot.DATA_DIR_PROPERTY);
        System.setProperty(DataRoot.DATA_DIR_PROPERTY,
                tempDirectory.resolve("data-v3").toString());
        root = ApplicationContexts.createRoot(DataRoot.resolve());
    }

    @AfterAll
    static void closeContext() {
        if (root != null) {
            root.close();
        }
        if (previousDataDirectory == null) {
            System.clearProperty(DataRoot.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(DataRoot.DATA_DIR_PROPERTY, previousDataDirectory);
        }
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
