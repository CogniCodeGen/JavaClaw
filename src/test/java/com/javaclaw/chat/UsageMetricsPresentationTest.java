package com.javaclaw.chat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageMetricsPresentationTest {

    @Test
    void messageMetadataShowsTokenSubsetsButNotTheRetainedBackendCallCount() {
        String metadata = ChatStreamRenderer.formatTurnMeta(
                new TurnMetrics(120, 40, 10, 30, 12, 7, 1_500),
                DeliveryState.COMPLETE);

        assertTrue(metadata.contains("150 tok"));
        assertTrue(metadata.contains("缓存读/写 40/10"));
        assertTrue(metadata.contains("推理 12"));
        assertFalse(metadata.contains("调用"));
        assertFalse(metadata.contains("7"));
        assertFalse(metadata.contains("¥"));
    }

    @Test
    void usageFxmlContainsNoAmountOrModelCallPresentation() throws IOException {
        for (String resource : new String[] {
                "/fxml/chat/thinking-panel.fxml", "/fxml/chat/chat-mode-bar.fxml"}) {
            String fxml;
            try (var input = getClass().getResourceAsStream(resource)) {
                if (input == null) throw new IOException("missing test resource " + resource);
                fxml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertFalse(fxml.contains("¥"), resource);
            assertFalse(fxml.contains("￥"), resource);
            assertFalse(fxml.contains("金额"), resource);
            assertFalse(fxml.contains("模型调用"), resource);
            assertFalse(fxml.contains("costValue"), resource);
        }
    }
}
