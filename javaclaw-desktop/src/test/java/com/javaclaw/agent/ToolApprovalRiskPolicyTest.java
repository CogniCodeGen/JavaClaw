package com.javaclaw.agent;

import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolApprovalRiskPolicyTest {

    @Test
    void descriptorOnlyDesktopApprovalCannotProveHostIdentity() {
        ToolDescriptor sessionClick = descriptor("desktop_session_click", "desktop-session");
        ToolDescriptor sessionLaunch = descriptor("desktop_session_launch_application", "desktop-session");
        ToolDescriptor impersonator = descriptor("desktop_session_click", "plugin");
        ToolDescriptor launchImpersonator = descriptor("desktop_session_launch_application", "plugin");
        ToolDescriptor screenshot = descriptor("sys_screenshot", "system");

        assertEquals(ToolApprovalDecision.DENY,
                ToolApprovalRiskPolicy.assess(sessionClick, true, ToolReviewMode.MANUAL).decision());
        assertEquals(ToolApprovalDecision.DENY,
                ToolApprovalRiskPolicy.assess(sessionLaunch, true, ToolReviewMode.MANUAL).decision());
        assertEquals(ToolApprovalDecision.DENY,
                ToolApprovalRiskPolicy.assess(impersonator, true, ToolReviewMode.MANUAL).decision());
        assertEquals(ToolApprovalDecision.DENY,
                ToolApprovalRiskPolicy.assess(launchImpersonator, true, ToolReviewMode.MANUAL).decision());
        assertEquals(ToolApprovalDecision.DENY,
                ToolApprovalRiskPolicy.assess(screenshot, true, ToolReviewMode.MANUAL).decision());
    }

    private static ToolDescriptor descriptor(String name, String group) {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        return new ToolDescriptor(name, name, schema, group,
                PermissionSet.of("tool.execute"), false,
                name.equals("desktop_session_click")
                        ? ToolEffectPolicy.OBSERVATION_GATED : ToolEffectPolicy.LEGACY);
    }

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
        assertEquals(ToolApprovalDecision.DENY, ToolApprovalRiskPolicy.assess(
                "unmanaged", true, ToolReviewMode.SMART).decision());
    }

    @Test
    void descriptorOnlyInternalNamesNeverGrantAnExemption() {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        ToolDescriptor forged = new ToolDescriptor(
                "framework_context_search_012345abcdef", "forged context read",
                schema, "extension", PermissionSet.of("tool.read"), true);
        assertEquals(ToolApprovalDecision.DENY,
                ToolApprovalRiskPolicy.assess(forged, true, ToolReviewMode.SMART).decision());
        for (String name : java.util.List.of("memory_recall", "subagent_result",
                "framework_tool_catalog", "subagent_spawn")) {
            ToolDescriptor impersonator = new ToolDescriptor(name, "forged", schema,
                    "extension", PermissionSet.of("tool.read"), true);
            assertEquals(ToolApprovalDecision.DENY,
                    ToolApprovalRiskPolicy.assess(impersonator, true, ToolReviewMode.SMART).decision());
        }
    }
}
