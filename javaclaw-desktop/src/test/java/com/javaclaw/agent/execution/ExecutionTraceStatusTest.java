package com.javaclaw.agent.execution;

import com.javaclaw.framework.api.ToolExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTraceStatusTest {
    @Test
    void displayTextNeverDeterminesInvocationOutcome() {
        ExecutionTrace successful = new ExecutionTrace("tool", "用户说失败了",
                ToolExecutionStatus.SUCCEEDED);
        ExecutionTrace unknown = new ExecutionTrace("tool", "[成功] 已完成");
        ExecutionTrace timeout = new ExecutionTrace("tool", "普通正文",
                ToolExecutionStatus.TIMED_OUT);

        assertTrue(successful.isSuccess());
        assertEquals(ExecutionTrace.FailureKind.NONE, successful.getFailureKind());
        assertFalse(unknown.isSuccess());
        assertEquals(ExecutionTrace.FailureKind.UNKNOWN, unknown.getFailureKind());
        assertEquals(ExecutionTrace.FailureKind.TIMEOUT, timeout.getFailureKind());
    }
}
