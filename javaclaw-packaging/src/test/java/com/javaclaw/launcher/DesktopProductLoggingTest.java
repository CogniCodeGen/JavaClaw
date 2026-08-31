package com.javaclaw.launcher;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopProductLoggingTest {
    @TempDir
    Path temporary;

    @Test
    void explicitLogRootWinsAndFailureSummaryRedactsCredentialShapes() {
        Path explicit = temporary.resolve("日志 logs");
        assertEquals(
                explicit.toAbsolutePath().normalize(),
                DesktopProductLogging.logDirectory(
                        Map.of(
                                "JAVACLAW_LOG_DIR",
                                explicit.toString(),
                                "JAVACLAW_CONFIG_DIR",
                                temporary.resolve("ignored").toString()),
                        Map.of()));

        String summary = DesktopProductLogging.summarize(
                new IllegalStateException("authorization=Bearer-secret", new IOExceptionLike("api_key=private")));
        assertTrue(summary.contains("IllegalStateException"));
        assertTrue(summary.contains("IOExceptionLike"));
        assertFalse(summary.contains("Bearer-secret"));
        assertFalse(summary.contains("private"));
    }

    @Test
    void defaultLogDirectoryStaysUnderTheProgramDirectory() {
        Path program = temporary.resolve("程序 program");
        assertEquals(
                program.resolve(".javaclaw/config-v4/logs").toAbsolutePath().normalize(),
                DesktopProductLogging.logDirectory(Map.of(), Map.of("JAVACLAW_PROGRAM_DIR", program.toString())));
    }

    private static final class IOExceptionLike extends RuntimeException {
        private IOExceptionLike(String message) {
            super(message);
        }
    }
}
