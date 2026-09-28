package com.javaclaw.chat;

import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.TurnPausedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

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
    void leavesOrdinaryFailuresOnTheExistingFailurePath() {
        IllegalArgumentException failure = new IllegalArgumentException("上游拒绝");

        assertFalse(ChatTurnOutcomeHandler.isBudgetExceeded(failure));
        assertEquals("上游拒绝", ChatTurnOutcomeHandler.extractErrorMessage(failure));
    }

    @Test
    void explainsPausedUnauthorizedContextWithoutClaimingTheApiKeyExpired() {
        RuntimeException wrapped = new IllegalStateException("运行失败",
                new TurnPausedException("planner selected an unauthorized context source: web"));

        assertEquals("上下文规划选择了不可用的来源 web，请重新发起请求；若持续出现，请检查来源权限",
                ChatTurnOutcomeHandler.extractErrorMessage(wrapped));
    }

    @Test
    void leavesUnstructuredUnauthorizedOr401MessagesAsTheirOriginalFailure() {
        assertEquals("unknown or unauthorized tool name: web",
                ChatTurnOutcomeHandler.extractErrorMessage(
                        new IllegalStateException("unknown or unauthorized tool name: web")));
        assertEquals("工具返回 401 条结果",
                ChatTurnOutcomeHandler.extractErrorMessage(new IllegalStateException("工具返回 401 条结果")));
    }

    @Test
    void mapsStructuredHttp401ToCredentialOrPermissionFailure() {
        RestClientResponseException unauthorized = new RestClientResponseException(
                "401 Unauthorized", 401, "Unauthorized", new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8);

        assertEquals("服务返回 HTTP 401，请检查对应服务的凭据或访问权限",
                ChatTurnOutcomeHandler.extractErrorMessage(new RuntimeException("上游调用失败", unauthorized)));
    }

    @Test
    void mapsStructuredReactiveHttp401ButNotOtherStatuses() {
        WebClientResponseException unauthorized = new WebClientResponseException(
                401, "Unauthorized", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        RestClientResponseException forbidden = new RestClientResponseException(
                "403 Unauthorized", 403, "Forbidden", new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8);

        assertEquals("服务返回 HTTP 401，请检查对应服务的凭据或访问权限",
                ChatTurnOutcomeHandler.extractErrorMessage(unauthorized));
        assertEquals("403 Unauthorized", ChatTurnOutcomeHandler.extractErrorMessage(forbidden));
    }
}
