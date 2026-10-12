package com.javaclaw.loop.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameworkCompletionJudgeInputTest {
    @Test
    void longRequiredTranscriptIsSentWithoutTruncation() {
        AtomicInteger calls = new AtomicInteger();
        String transcript = "evidence".repeat(2_000);
        ModelTaskGateway model = request -> {
            calls.incrementAndGet();
            assertEquals(transcript, request.input().path("transcript").asText());
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    JsonNodeFactory.instance.objectNode().put("met", true).put("reason", "完成"),
                    "test", 1, 1, false, Map.of()));
        };
        FrameworkCompletionJudge judge = new FrameworkCompletionJudge(
                model, RunId::random, () -> false, "project");

        var verdict = judge.goalMet("完成目标", transcript);

        assertTrue(verdict.met());
        assertEquals("完成", verdict.reason());
        assertEquals(1, calls.get());
    }
}
