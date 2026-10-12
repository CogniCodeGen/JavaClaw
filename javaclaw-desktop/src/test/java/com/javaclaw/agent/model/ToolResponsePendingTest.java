package com.javaclaw.agent.model;

import com.javaclaw.framework.spi.ToolEffectCapture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResponsePendingTest {

    @Test
    void pendingIsNotReportedAsCompletedSuccess() {
        try (var capture = ToolEffectCapture.begin("skill_create")) {
            String response = ToolResponse.pending("skill_create", "提案已提交，尚未生效");
            assertEquals(ToolEffectCapture.Signal.PENDING, capture.signal());
            assertTrue(response.contains("[待审]"));
        }
    }
}
