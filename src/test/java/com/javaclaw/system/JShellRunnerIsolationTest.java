package com.javaclaw.system;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JShellRunnerIsolationTest {

    @Test
    void directRunnerCannotBypassStrictIsolation() {
        JShellRunner.ExecResult result = JShellRunner.run(
                "java.nio.file.Files.readString(java.nio.file.Path.of(\"/etc/passwd\"));",
                List.of(), 5);

        assertFalse(result.success());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("严格项目文件隔离")));
    }
}
