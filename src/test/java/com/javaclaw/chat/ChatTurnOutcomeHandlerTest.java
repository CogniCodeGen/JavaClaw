package com.javaclaw.chat;

import com.javaclaw.framework.api.BudgetExceededException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTurnOutcomeHandlerTest {

    @Test
    void rendersStructuredInputBudgetAsAnActionableChineseSafetyStop() {
        BudgetExceededException failure = BudgetExceededException.modelInputTokens(
                255_392, 250_000);

        assertTrue(ChatTurnOutcomeHandler.isBudgetExceeded(failure));
        assertEquals("本轮模型累计输入已达到安全上限（已用 255,392 / 上限 250,000 token）。"
                        + "请缩小任务范围，或在新一轮中继续。",
                ChatTurnOutcomeHandler.extractErrorMessage(failure));
    }

    @Test
    void recognizesPersistedLegacyBudgetFailureWithoutShowingItsJavaClassName() {
        IllegalStateException failure = new IllegalStateException(
                "com.javaclaw.framework.core.BudgetExceededException: "
                        + "model usage budget exceeded");

        assertTrue(ChatTurnOutcomeHandler.isBudgetExceeded(failure));
        assertEquals("本轮模型累计用量已达到安全上限。请缩小任务范围，或在新一轮中继续。",
                ChatTurnOutcomeHandler.extractErrorMessage(failure));
    }

    @Test
    void leavesOrdinaryFailuresOnTheExistingFailurePath() {
        IllegalArgumentException failure = new IllegalArgumentException("上游拒绝");

        assertFalse(ChatTurnOutcomeHandler.isBudgetExceeded(failure));
        assertEquals("上游拒绝", ChatTurnOutcomeHandler.extractErrorMessage(failure));
    }

    @Test
    void costAndToolCallBudgetStopsDoNotExposeAmountsOrCallCountsInTheUi() {
        String cost = ChatTurnOutcomeHandler.extractErrorMessage(
                BudgetExceededException.modelCost(
                        new BigDecimal("1.25"), new BigDecimal("1.00")));
        String calls = ChatTurnOutcomeHandler.extractErrorMessage(
                BudgetExceededException.toolCalls(11, 10));

        assertEquals("本轮模型成本预算已达到安全上限。请缩小任务范围，或在新一轮中继续。", cost);
        assertFalse(cost.contains("¥"));
        assertFalse(cost.contains("1.25"));
        assertEquals("本轮工具调用预算已达到安全上限。请缩小任务范围后重试。", calls);
        assertFalse(calls.contains("次数"));
        assertFalse(calls.contains("11"));
    }
}
