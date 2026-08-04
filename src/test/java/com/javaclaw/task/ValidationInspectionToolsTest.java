package com.javaclaw.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationInspectionToolsTest {

    @Test
    void 严格隔离直接禁用构建命令(@TempDir Path dir) {
        Path marker = dir.resolve("should-not-exist");
        ValidationInspectionTools tools = new ValidationInspectionTools(dir.toString());

        String result = tools.inspectCompile(
                "mvn --version\ntouch " + marker.getFileName(), null);

        assertTrue(result.contains("严格项目文件隔离") && result.contains("禁用"), result);
        assertFalse(Files.exists(marker), "严格隔离下任何命令都不能被执行");
    }
}
