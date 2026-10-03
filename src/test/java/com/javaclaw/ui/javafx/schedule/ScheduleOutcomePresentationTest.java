package com.javaclaw.ui.javafx.schedule;

import com.javaclaw.application.schedule.ScheduleExecutionStatus;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleOutcomePresentationTest {
    @Test
    void distinguishesRunCompletionFromPromptFulfilmentAndRejectsDisplayText() {
        assertEquals("运行完成 · 历史未核验", ScheduleOutcomePresentation.label(
                ScheduleExecutionStatus.fromStored("SUCCESS"), null));
        assertEquals("运行完成 · 未验证", ScheduleOutcomePresentation.label(
                ScheduleExecutionStatus.SUCCESS, TaskResult.unverified("no observation")));
        assertEquals("任务已完成", ScheduleOutcomePresentation.label(ScheduleExecutionStatus.SUCCESS,
                new TaskResult(TaskOutcome.VERIFIED_COMPLETE, List.of(), "", List.of("receipt:1"))));
        assertEquals("答复已交付", ScheduleOutcomePresentation.label(ScheduleExecutionStatus.SUCCESS,
                TaskResult.delivered()));
        assertEquals(ScheduleExecutionStatus.UNKNOWN,
                ScheduleExecutionStatus.fromStored("文本里说运行完成"));
        assertEquals(ScheduleExecutionStatus.UNKNOWN,
                ScheduleExecutionStatus.fromStored("运行完成"));
    }
}
