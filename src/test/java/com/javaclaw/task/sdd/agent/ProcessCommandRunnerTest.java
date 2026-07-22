package com.javaclaw.task.sdd.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandRunnerTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void 无输出长进程仍受超时约束() {
        ProcessCommandRunner runner = new ProcessCommandRunner(1);

        var result = assertTimeoutPreemptively(Duration.ofSeconds(4),
                () -> runner.run("exec sleep 20", null));

        assertEquals(-2, result.exitCode());
        assertTrue(result.output().contains("超时"), result.output());
    }
}
