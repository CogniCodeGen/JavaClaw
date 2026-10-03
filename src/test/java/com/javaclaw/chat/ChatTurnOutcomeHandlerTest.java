package com.javaclaw.chat;

import com.javaclaw.api.conversation.CancellationReason;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.api.TurnPausedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTurnOutcomeHandlerTest {

    @Test
    void timeoutShowsItsCauseEvenWithoutPartialReplyOrTaskResult() {
        String text = ChatTurnOutcomeHandler.cancellationText("",
                new ConversationOutcome.Cancelled(CancellationReason.RUN_TIMEOUT, false), "打开应用");

        assertEquals("> ⏱ 运行超时，本轮已停止", text);
        assertFalse(text.contains("任务已完成"));
    }

    @Test
    void timeoutIsVisibleBeforeTheIndependentUnreliableAcceptanceResult() {
        TaskResult result = TaskResult.unverified("TASK_CONTRACT_UNRELIABLE");
        String text = ChatTurnOutcomeHandler.cancellationText(null,
                new ConversationOutcome.Cancelled(CancellationReason.RUN_TIMEOUT, false,
                        result, "Run deadline exceeded"), "打开应用");

        assertTrue(text.startsWith("> ⏱ 运行超时，本轮已停止"));
        assertTrue(text.contains("任务结果未验证"));
        assertTrue(text.contains("验收条件未建立"));
        assertTrue(text.contains("系统未能生成可靠的任务完成条件"));
        assertFalse(text.contains("任务已完成"));
        assertEquals(TaskOutcome.UNVERIFIED, result.outcome());
    }

    @Test
    void supersededTaskPreservesPartialReplyAndUnverifiedConditions() {
        TaskResult result = new TaskResult(TaskOutcome.PARTIAL,
                List.of("观察联系人"), "TASK_SUPERSEDED", List.of("receipt:open"),
                List.of("启动应用"));
        String text = ChatTurnOutcomeHandler.cancellationText("应用已启动",
                new ConversationOutcome.Cancelled(CancellationReason.TASK_SUPERSEDED, false,
                        result, ""), "打开应用并观察联系人");

        assertTrue(text.startsWith("应用已启动\n\n> ⏹ 任务已被新请求替换，本轮已停止"));
        assertTrue(text.contains("任务部分完成"));
        assertTrue(text.contains("已做：启动应用"));
        assertTrue(text.contains("未做或未确认：观察联系人"));
        assertTrue(text.contains("receipt:open"));
        assertFalse(text.contains("任务已完成"));
    }

    @Test
    void supersededTaskWithoutPriorReplyStillDisplaysItsReason() {
        assertEquals("> ⏹ 任务已被新请求替换，本轮已停止",
                ChatTurnOutcomeHandler.cancellationText(null,
                        new ConversationOutcome.Cancelled(CancellationReason.TASK_SUPERSEDED, false), ""));
    }

    @Test
    void approvalCancellationKeepsItsExistingFeedback() {
        assertEquals("> ⛔ 授权未通过，任务已停止",
                ChatTurnOutcomeHandler.cancellationText(null,
                        new ConversationOutcome.Cancelled(CancellationReason.APPROVAL_DENIED, false), ""));
    }

    @Test
    void ordinaryCancellationKeepsItsExistingBlankAndPartialReplyBehavior() {
        for (CancellationReason reason : List.of(CancellationReason.USER_REQUEST,
                CancellationReason.UNKNOWN, CancellationReason.MODE_SWITCH,
                CancellationReason.SESSION_SWITCH, CancellationReason.SCHEDULE_DISABLED,
                CancellationReason.RUNTIME_REBUILD, CancellationReason.SHUTDOWN)) {
            var cancelled = new ConversationOutcome.Cancelled(reason,
                    reason == CancellationReason.USER_REQUEST);
            assertEquals("", ChatTurnOutcomeHandler.cancellationText(null, cancelled, ""));
            assertEquals("尚未完成的回复\n\n> ⏹ 已停止",
                    ChatTurnOutcomeHandler.cancellationText("尚未完成的回复", cancelled, ""));
        }
    }

    @Test
    void cancellationPreservesPreviouslyVerifiedEvidenceRatherThanReclassifyingIt() {
        TaskResult verified = new TaskResult(TaskOutcome.VERIFIED_COMPLETE,
                List.of(), "", List.of("receipt:verified"));
        String text = ChatTurnOutcomeHandler.cancellationText(null,
                new ConversationOutcome.Cancelled(CancellationReason.RUN_TIMEOUT, false, verified, ""), "");

        assertTrue(text.startsWith("> ⏱ 运行超时，本轮已停止"));
        assertTrue(text.contains("任务已完成（完成条件已核验）"));
        assertTrue(text.contains("receipt:verified"));
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, verified.outcome());
    }

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
                TurnPausedException.unauthorizedContextSource("web"));

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
