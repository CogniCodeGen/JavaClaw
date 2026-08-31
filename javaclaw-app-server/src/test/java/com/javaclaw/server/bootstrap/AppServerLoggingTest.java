package com.javaclaw.server.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppServerLoggingTest {
    @Test
    void startupFailureSummaryIsBoundedAndRedactsCredentialShapes() {
        Throwable failure =
                new IllegalArgumentException("password=do-not-log", new IllegalStateException("Bearer raw-token"));

        String summary = AppServerLogging.summarize(failure);

        assertTrue(summary.contains("IllegalArgumentException"));
        assertTrue(summary.contains("IllegalStateException"));
        assertFalse(summary.contains("do-not-log"));
        assertFalse(summary.contains("raw-token"));
    }
}
