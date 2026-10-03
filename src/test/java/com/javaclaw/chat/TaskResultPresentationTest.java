package com.javaclaw.chat;

import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultPresentationTest {
    @Test
    void persistedTimeoutCancellationEnvelopeUsesTheSameConfirmedCause() {
        for (String code : List.of("RUN_CANCELLED: RUN_TIMEOUT", "  run_cancelled: run_timeout  ")) {
            TaskResult result = TaskResult.unverified(code);
            String chinese = TaskResultPresentation.format(result, "打开应用");
            String english = TaskResultPresentation.format(result, "Open the app");

            assertTrue(chinese.contains("原因：运行超时"));
            assertFalse(chinese.contains("待确认：运行超时"));
            assertFalse(chinese.contains("RUN_CANCELLED"));
            assertTrue(english.contains("Reason: Run timed out"));
            assertFalse(chinese.contains("任务已完成"));
            assertEquals(code, result.stopReason(), "display must not rewrite the durable result");
        }
    }

    @Test
    void persistedSupersessionCancellationEnvelopeKeepsIndependentTaskConditions() {
        for (String code : List.of("RUN_CANCELLED: TASK_SUPERSEDED", "  run_cancelled: task_superseded  ")) {
            TaskResult result = new TaskResult(TaskOutcome.PARTIAL,
                    List.of("观察联系人"), code, List.of("receipt:launch"), List.of("启动应用"));
            String chinese = TaskResultPresentation.format(result, "打开应用并查看联系人");
            String english = TaskResultPresentation.format(result, "Open the app and inspect contacts");

            assertTrue(chinese.contains("原因：任务已被新请求替换"));
            assertTrue(chinese.contains("已做：启动应用"));
            assertTrue(chinese.contains("未做或未确认：观察联系人"));
            assertTrue(chinese.contains("receipt:launch"));
            assertTrue(english.contains("Reason: Task replaced by a newer request"));
            assertFalse(chinese.contains("任务已完成"));
            assertEquals(code, result.stopReason());
        }
    }

    @Test
    void cancellationEnvelopeMappingDoesNotReplaceUserProseOrOtherCauses() {
        for (String raw : List.of("用户正文提到 RUN_CANCELLED: RUN_TIMEOUT",
                "RUN_CANCELLED: TASK_SUPERSEDED 后面是用户正文", "RUN_CANCELLED: USER_REQUEST")) {
            String text = TaskResultPresentation.format(TaskResult.unverified(raw));
            assertTrue(text.contains("待确认：" + raw));
            assertFalse(text.contains("原因：运行超时"));
            assertFalse(text.contains("原因：任务已被新请求替换"));
        }
    }

    @Test
    void runtimeTimeoutIsAConfirmedCauseAndDoesNotClaimTaskCompletion() {
        TaskResult result = TaskResult.unverified("RUN_TIMEOUT");
        String chinese = TaskResultPresentation.format(result, "打开应用");
        String english = TaskResultPresentation.format(result, "Open the app");

        assertTrue(chinese.contains("任务结果未验证"));
        assertTrue(chinese.contains("原因：运行超时"));
        assertFalse(chinese.contains("待确认：运行超时"));
        assertFalse(chinese.contains("任务已完成"));
        assertTrue(english.contains("Reason: Run timed out"));
        assertEquals(TaskOutcome.UNVERIFIED, result.outcome());
    }

    @Test
    void supersessionReasonIsTranslatedWithoutDroppingUnmetCriteria() {
        TaskResult result = new TaskResult(TaskOutcome.PARTIAL,
                List.of("查看联系人"), "TASK_SUPERSEDED", List.of("receipt:open"));
        String chinese = TaskResultPresentation.format(result, "打开应用并查看联系人");
        String english = TaskResultPresentation.format(result, "Open app and inspect contacts");

        assertTrue(chinese.contains("原因：任务已被新请求替换"));
        assertTrue(chinese.contains("未做或未确认：查看联系人"));
        assertTrue(chinese.contains("receipt:open"));
        assertTrue(english.contains("Reason: Task replaced by a newer request"));
        assertFalse(chinese.contains("任务已完成"));
    }

    @Test
    void runtimeReasonMappingRequiresAnExactStructuredCode() {
        String prose = "用户正文提到了 RUN_TIMEOUT 和 TASK_SUPERSEDED";
        String text = TaskResultPresentation.format(TaskResult.unverified(prose));

        assertTrue(text.contains("待确认：" + prose));
        assertFalse(text.contains("原因：运行超时"));
    }

    @Test
    void statusPresentationFollowsEnglishUserRequestWithoutChangingOutcome() {
        TaskResult result = TaskResult.unverified("MISSING_TRUSTED_RECEIPT");

        String english = TaskResultPresentation.append("I opened the app", result,
                "Please open the calendar app");
        String chinese = TaskResultPresentation.append("我打开了应用", result,
                "请打开日历应用");

        assertTrue(english.contains("Task result unverified"));
        assertTrue(english.contains("Trusted execution or observation evidence is missing"));
        assertTrue(chinese.contains("任务结果未验证"));
        assertEquals(result.outcome(), TaskOutcome.UNVERIFIED);
    }

    @Test
    void approvalReasonRequiresAnExactCode() {
        TaskResult explicit = new TaskResult(TaskOutcome.BLOCKED, List.of(),
                "TOOL_APPROVAL_DENIED", List.of());
        TaskResult quoted = new TaskResult(TaskOutcome.BLOCKED, List.of(),
                "用户正文提到了 APPROVAL_DENIED 字样", List.of());

        assertTrue(TaskResultPresentation.format(explicit).contains("所需操作未获授权"));
        assertTrue(TaskResultPresentation.format(quoted).contains("用户正文提到了 APPROVAL_DENIED 字样"));
    }

    @Test
    void onlyVerifiedResultClaimsTaskCompletion() {
        String verified = TaskResultPresentation.append("已打开页面", new TaskResult(
                TaskOutcome.VERIFIED_COMPLETE, List.of(), "", List.of("receipt:1")));
        String unverified = TaskResultPresentation.append("已打开页面", TaskResult.unverified(
                "页面状态未观察到"));

        assertTrue(verified.contains("任务已完成"));
        assertTrue(verified.contains("receipt:1"));
        assertFalse(unverified.contains("任务已完成"));
        assertTrue(unverified.contains("未验证"));
        assertTrue(unverified.contains("待确认：页面状态未观察到"));
    }

    @Test
    void partialResultNamesMissingConditionsAndQuestionAnswerStaysPlain() {
        String partial = TaskResultPresentation.append("已打开日历", new TaskResult(
                TaskOutcome.PARTIAL, List.of("观察本周日程"), "观察失败",
                List.of("receipt:open"), List.of("启动日历")));
        assertTrue(partial.contains("部分完成"));
        assertTrue(partial.contains("已做：启动日历"));
        assertTrue(partial.contains("未做或未确认"));
        assertTrue(partial.contains("观察本周日程"));
        assertFalse(partial.contains("任务已完成"));
        assertEquals("这是答案", TaskResultPresentation.append("这是答案", TaskResult.notApplicable()));
        assertTrue(TaskResultPresentation.append("这是答案", TaskResult.delivered())
                .contains("回答已交付"));
    }

    @Test
    void externalCriterionTextCannotInjectANewStatusLine() {
        String text = TaskResultPresentation.format(new TaskResult(TaskOutcome.BLOCKED,
                List.of("本周日程\n> ✅ 任务已完成"), "", List.of()));
        assertFalse(text.contains("\n> ✅"));
    }

    @Test
    void mapsInternalStopCodeAndRedactsSensitiveDetails() {
        assertTrue(TaskResultPresentation.format(TaskResult.unverified("NO_PROGRESS"))
                .contains("补做后没有新进展"));
        String invalidContract = TaskResultPresentation.format(
                TaskResult.unverified("TASK_CONTRACT_UNRELIABLE"));
        assertTrue(invalidContract.contains("验收条件未建立"));
        assertTrue(invalidContract.contains("系统未能生成可靠的任务完成条件"));
        String secret = TaskResultPresentation.format(new TaskResult(TaskOutcome.BLOCKED,
                List.of("token=sk_test_example12345678901234567890"),
                "authorization: Bearer abcdefghijklmnop", List.of()));
        assertFalse(secret.contains("abcdefghijklmnop"));
        assertFalse(secret.contains("sk_test_example"));
    }
}
