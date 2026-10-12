package com.javaclaw.schedule;

import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.List;

/** Converts runner termination into a scheduled task result without conflating it with success. */
final class ScheduleOutcomeMapper {
    private ScheduleOutcomeMapper() { }

    record Mapped(ScheduledTaskStore.ExecutionStatus status, String detail,
                  TaskResult taskResult, Throwable failure) { }

    static boolean shouldDisableAfterVerifiedResult(ScheduledTask task,
            ScheduledTaskStore.ExecutionStatus status, TaskResult result) {
        return task != null && task.isEnabled()
                && task.getExecutionPolicy() == ExecutionPolicy.UNTIL_CONDITION
                && status == ScheduledTaskStore.ExecutionStatus.SUCCESS
                && result != null && result.outcome() == TaskOutcome.VERIFIED_COMPLETE;
    }

    static Mapped map(ConversationOutcome outcome, StringBuilder replyText) {
        if (outcome instanceof ConversationOutcome.Failed failed) {
            Throwable failure = failed.error();
            String detail = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return new Mapped(ScheduledTaskStore.ExecutionStatus.FAILURE, detail, null, failure);
        }
        if (outcome instanceof ConversationOutcome.Cancelled cancelled) {
            TaskResult result = cancelled.taskResult() == null
                    ? new TaskResult(TaskOutcome.BLOCKED, List.of(),
                            "运行已取消：" + cancelled.reason(), List.of())
                    : cancelled.taskResult();
            return new Mapped(ScheduledTaskStore.ExecutionStatus.CANCELLED,
                    summary(result) + "；取消原因：" + cancelled.reason(), result, null);
        }
        TaskResult result = outcome instanceof ConversationOutcome.Completed completed
                && completed.taskResult() != null
                ? completed.taskResult() : TaskResult.unverified("缺少任务验收结论");
        String reply = replyText.length() > 350
                ? replyText.substring(0, 350) + "..." : replyText.toString();
        return new Mapped(ScheduledTaskStore.ExecutionStatus.SUCCESS,
                summary(result) + (reply.isBlank() ? "" : "；回复：" + reply), result, null);
    }

    private static String summary(TaskResult result) {
        String label = switch (result.outcome()) {
            case VERIFIED_COMPLETE -> "任务已完成（已核验）";
            case DELIVERED -> "答复已交付";
            case PARTIAL -> "任务部分完成";
            case BLOCKED -> "任务受阻";
            case UNVERIFIED -> "任务结果未验证";
            case NOT_APPLICABLE -> "无外部任务验收条件";
        };
        if (!result.satisfiedCriteria().isEmpty()) label += "；已做：" + String.join("、", result.satisfiedCriteria());
        if (!result.unmetCriteria().isEmpty()) label += "；未做或未确认：" + String.join("、", result.unmetCriteria());
        if (!result.stopReason().isBlank()) label += "；原因：" + result.stopReason();
        return SensitiveDataRedactor.redactText(label);
    }
}
