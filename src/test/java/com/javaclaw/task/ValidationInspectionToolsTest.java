package com.javaclaw.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationInspectionToolsTest {

    @Test
    void 构建命令拒绝换行拼接的第二条命令(@TempDir Path dir) {
        Path marker = dir.resolve("should-not-exist");
        ValidationInspectionTools tools = new ValidationInspectionTools(dir.toString());

        String result = tools.inspectCompile(
                "mvn --version\ntouch " + marker.getFileName(), null);

        assertTrue(result.contains("禁止") && result.contains("换行"), result);
        assertFalse(Files.exists(marker), "换行后的命令绝不能被执行");
    }
}
