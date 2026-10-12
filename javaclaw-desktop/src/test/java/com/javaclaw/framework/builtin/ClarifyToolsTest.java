package com.javaclaw.framework.builtin;

import com.javaclaw.framework.core.ToolInputRequiredException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClarifyToolsTest {

    @Test
    void rejectsAnEmptyClarificationWithoutSuspendingTheRun() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClarifyTools().askUserClarification(" ", null));
    }

    @Test
    void validClarificationCarriesAFrameworkWaitingInputPayload() {
        ToolInputRequiredException waiting = assertThrows(
                ToolInputRequiredException.class,
                () -> new ClarifyTools().askUserClarification(
                        "缺少目标格式", "请选择 PDF 或 Markdown"));

        assertEquals("clarify_request", waiting.context().path("kind").asText());
        assertEquals("缺少目标格式",
                waiting.context().path("payload").path("reason").asText());
        assertEquals("请选择 PDF 或 Markdown",
                waiting.context().path("payload").path("question").asText());
    }
}
