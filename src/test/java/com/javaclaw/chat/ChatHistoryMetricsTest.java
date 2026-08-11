package com.javaclaw.chat;

import com.javaclaw.application.chat.ChatHistoryApplicationService;
import com.javaclaw.application.chat.ChatHistoryApplicationService.DeliveryStatus;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageRole;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageSnapshot;
import com.javaclaw.application.chat.ChatHistoryApplicationService.TurnUsage;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
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
        MessageSnapshot completed = new MessageSnapshot(
                MessageRole.ASSISTANT, "done", LocalDateTime.now(), List.of(), false,
                DeliveryStatus.COMPLETE, new TurnUsage(12, 7, 345));
        MessageSnapshot legacy = new MessageSnapshot(
                MessageRole.ASSISTANT, "legacy", LocalDateTime.now(), List.of(), false,
                null, null);

        ChatHistoryApplicationService history =
                root.getBean(ChatHistoryApplicationService.class);
        history.saveMessages(sessionId, List.of(completed, legacy));
        List<MessageSnapshot> loaded = history.messages(sessionId);

        assertEquals(2, loaded.size());
        assertEquals(DeliveryStatus.COMPLETE, loaded.getFirst().deliveryStatus());
        assertEquals(new TurnUsage(12, 7, 345), loaded.getFirst().usage());
        assertNull(loaded.get(1).deliveryStatus());
        assertNull(loaded.get(1).usage());
        history.delete(sessionId);
    }

    @Test
    void 确定性命令计量为零() {
        assertEquals(0, new TurnMetrics(0, 0, 15).totalTokens());
    }
}
