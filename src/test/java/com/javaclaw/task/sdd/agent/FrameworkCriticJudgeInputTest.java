package com.javaclaw.task.sdd.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.task.sdd.spec.Criterion;
import com.javaclaw.task.sdd.spec.Scenario;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameworkCriticJudgeInputTest {
    @Test
    void longRequiredScenarioIsSentWithoutTruncation() {
        AtomicInteger calls = new AtomicInteger();
        String evidence = "evidence".repeat(2_000);
        ModelTaskGateway model = request -> {
            calls.incrementAndGet();
            assertEquals(evidence, request.input().path("given").asText());
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    JsonNodeFactory.instance.objectNode().put("pass", true).put("reason", "通过"),
                    "test", 1, 1, false, Map.of()));
        };
        FrameworkCriticJudge judge = new FrameworkCriticJudge(
                "project", model, RunId::random, () -> false, null);
        Scenario scenario = new Scenario("验收", evidence, "执行", "通过",
                Criterion.freeform("结果符合要求"));

        var verdict = judge.judge(scenario);

        assertTrue(verdict.pass());
        assertEquals("通过", verdict.reason());
        assertEquals(1, calls.get());
    }
}
