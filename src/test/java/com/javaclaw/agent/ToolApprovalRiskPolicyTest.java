package com.javaclaw.agent;

import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolApprovalRiskPolicyTest {

    @Test
    void preservesReviewModeAndDoubleConfirmationSemantics() {
        var smartDelete = ToolApprovalRiskPolicy.assess(
                "sys_file_delete", true, ToolReviewMode.SMART);
        assertEquals(ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL, smartDelete.decision());
        assertEquals("DOUBLE_CONFIRM", smartDelete.kind());

        var manualNotification = ToolApprovalRiskPolicy.assess(
                "sys_screenshot", true, ToolReviewMode.MANUAL);
        assertEquals(ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL,
                manualNotification.decision());
        assertEquals("CONFIRM", manualNotification.kind());

        assertEquals(ToolApprovalDecision.ALLOW, ToolApprovalRiskPolicy.assess(
                "sys_file_delete", true, ToolReviewMode.AUTO).decision());
        assertEquals(ToolApprovalDecision.ALLOW, ToolApprovalRiskPolicy.assess(
                "unmanaged", true, ToolReviewMode.SMART).decision());
    }
}
