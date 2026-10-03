package com.javaclaw.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThinkingContentRendererResultKindTest {

    @Test
    void typedOutcomeDistinguishesFailureUncertaintyAndReobservation() {
        assertEquals(ThinkingContentRenderer.ResultKind.FAILED,
                ThinkingContentRenderer.resultKind(ToolExecutionStatus.FAILED));
        assertEquals(ThinkingContentRenderer.ResultKind.UNCERTAIN,
                ThinkingContentRenderer.resultKind(ToolExecutionStatus.UNCERTAIN));
        assertEquals(ThinkingContentRenderer.ResultKind.REOBSERVE,
                ThinkingContentRenderer.resultKind(ToolExecutionStatus.REOBSERVE));
    }

    @Test
    void displayTextCannotUpgradeUnknownOrOverrideTypedSuccess() {
        assertEquals(ThinkingContentRenderer.ResultKind.UNKNOWN,
                ThinkingContentRenderer.resultKind(ToolExecutionStatus.UNKNOWN));
        assertEquals(ThinkingContentRenderer.ResultKind.SUCCEEDED,
                ThinkingContentRenderer.resultKind(ToolExecutionStatus.SUCCEEDED));
        assertEquals(ThinkingContentRenderer.ResultKind.FAILED,
                ThinkingContentRenderer.resultKind(ToolExecutionStatus.TIMED_OUT));
    }

    @Test
    void receiptBadgeSeparatesInputDeliveryFromBusinessEffect() {
        var json = JsonNodeFactory.instance;
        assertEquals("输入未派发", ThinkingContentRenderer.receiptEffectLabel(
                EffectReceiptV1.Status.FAILED,
                json.objectNode().put("delivery", "NOT_SENT")));
        assertEquals("输入已派发·效果待核验",
                ThinkingContentRenderer.receiptEffectLabel(EffectReceiptV1.Status.ACCEPTED,
                        json.objectNode().put("delivery", "SENT")));
        assertEquals("输入可能已派发·效果待核验",
                ThinkingContentRenderer.receiptEffectLabel(EffectReceiptV1.Status.UNKNOWN,
                        json.objectNode().put("delivery", "MAYBE_SENT")));
        assertEquals("目标已验证", ThinkingContentRenderer.receiptEffectLabel(
                EffectReceiptV1.Status.VERIFIED,
                json.objectNode().put("delivery", "SENT")));
        assertEquals("效果未获确认", ThinkingContentRenderer.receiptEffectLabel(
                EffectReceiptV1.Status.FAILED,
                json.objectNode()));
        assertEquals("效果未知", ThinkingContentRenderer.receiptEffectLabel(
                EffectReceiptV1.Status.UNKNOWN,
                json.objectNode().put("delivery", "[完成]")));
        assertEquals(null, ThinkingContentRenderer.receiptEffectLabel(null,
                json.objectNode().put("delivery", "SENT")));
    }
}
