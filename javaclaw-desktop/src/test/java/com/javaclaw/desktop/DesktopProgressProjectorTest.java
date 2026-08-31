package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.sdk.model.CommandItemContent;
import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopProgressProjectorTest {
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    @Test
    void projectsTypedExecutionAndLatestUsageWithoutRawJson() {
        var thread = thread(7);
        var turn = new TurnInfo(
                "turn", "thread", "RUNNING", "attempt", List.of(), JsonDocument.EMPTY_OBJECT, null, NOW, null);
        var command = new CommandItemContent(
                List.of("mvn", "test"),
                0,
                "secret raw output",
                "",
                false,
                false,
                new JsonDocument("{\"private\":\"raw\"}"));
        var item = new ItemInfo("item", "thread", "turn", 1, "STARTED", command, NOW, NOW);
        var usage = new EventInfo(
                "event",
                "thread",
                "turn",
                7,
                "usage/updated",
                1,
                null,
                null,
                new JsonDocument("{\"inputTokens\":\"120\",\"outputTokens\":\"34\",\"reasoningTokens\":\"5\"}"),
                NOW);

        DesktopProgressSnapshot result = DesktopProgressProjector.project(
                new ThreadSnapshot(thread, List.of(turn), List.of(item)), List.of(usage));

        assertEquals("执行", result.phase());
        assertTrue(result.active());
        assertEquals(1, result.entries().size());
        assertEquals("mvn test", result.entries().getFirst().detail());
        assertFalse(result.entries().getFirst().detail().contains("private"));
        assertEquals(120, result.usage().inputTokens());
        assertEquals(34, result.usage().outputTokens());
        assertEquals(5, result.usage().reasoningTokens());
    }

    @Test
    void missingUsageIsUnknownInsteadOfFabricatedZeroCostEvidence() {
        DesktopProgressSnapshot result =
                DesktopProgressProjector.project(new ThreadSnapshot(thread(0), List.of(), List.of()), List.of());

        assertEquals("等待任务", result.phase());
        assertFalse(result.active());
        assertFalse(result.usage().available());
    }

    @Test
    void failedTurnNeverUsesTheCompletedPhaseLabel() {
        var failed = new TurnInfo(
                "turn", "thread", "FAILED", "attempt", List.of(), JsonDocument.EMPTY_OBJECT, "失败", NOW, NOW);

        DesktopProgressSnapshot result =
                DesktopProgressProjector.project(new ThreadSnapshot(thread(1), List.of(failed), List.of()), List.of());

        assertEquals("处理失败", result.phase());
        assertEquals("处理失败", result.status());
        assertFalse(result.active());
    }

    private static ThreadInfo thread(long sequence) {
        return new ThreadInfo("thread", "workspace", null, null, "测试", "ACTIVE", 0, sequence, 1, NOW, NOW);
    }
}
