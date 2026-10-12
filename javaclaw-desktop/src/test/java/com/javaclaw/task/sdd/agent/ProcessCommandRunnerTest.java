package com.javaclaw.task.sdd.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.process.ProcessRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessCommandRunnerTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void 严格隔离直接拒绝子进程() {
        try (ManagedTaskExecutor executor = new ManagedTaskExecutor()) {
            ProcessCommandRunner runner = new ProcessCommandRunner(
                    new ProcessRunner(executor), 1);

            var result = assertTimeoutPreemptively(Duration.ofSeconds(4),
                    () -> runner.run("exec sleep 20", null));

            assertEquals(-1, result.exitCode());
            assertTrue(result.output().contains("严格项目文件隔离"), result.output());
        }
    }
}
