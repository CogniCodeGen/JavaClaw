package com.javaclaw.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResponsePendingTest {

    @Test
    void pendingIsNotReportedAsCompletedSuccess() {
        String response = ToolResponse.pending("skill_create", "提案已提交，尚未生效");

        assertTrue(ToolResponse.isPending(response));
        assertFalse(ToolResponse.isSuccess(response));
        assertTrue(response.contains("[待审]"));
    }
}
