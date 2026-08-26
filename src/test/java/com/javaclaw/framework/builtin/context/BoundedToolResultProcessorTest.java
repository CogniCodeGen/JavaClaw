package com.javaclaw.framework.builtin.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class BoundedToolResultProcessorTest {
    @Test
    void appliesPerToolAndRunBudgetsButLeavesSkillPaginationIntact() {
        BoundedToolResultProcessor processor = new BoundedToolResultProcessor();
        RunId run = new RunId("bounded-results");
        ToolExecutionContext context = new ToolExecutionContext(
                run, "call", () -> false, Instant.now().plusSeconds(30));
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("test")).build();

        JsonNode defaultView = processor.process(text(10_000), descriptor("email_read"),
                context, request);
        assertEquals(4_000, defaultView.asText().length());
        assertTrue(defaultView.asText().contains("middle evicted"));

        JsonNode largeView = processor.process(text(10_000), largeDescriptor("web_snapshot"),
                context, request);
        assertEquals(8_000, largeView.asText().length());

        JsonNode commandView = new BoundedToolResultProcessor().process(
                text(10_000), largeDescriptor("cmd_session_list"),
                context("bounded-command-result"), request);
        assertEquals(8_000, commandView.asText().length());

        int total = defaultView.asText().length() + largeView.asText().length();
        for (int i = 0; i < 5; i++) {
            JsonNode next = processor.process(text(10_000), largeDescriptor("code_read"),
                    context, request);
            total += next.asText().length();
        }
        assertEquals(BoundedToolResultProcessor.RUN_RESULT_CHARACTERS, total);

        JsonNode skillPage = processor.process(text(8_000), selfBoundedDescriptor("skill_read"),
                context, request);
        assertEquals(8_000, skillPage.asText().length());
    }

    @Test
    void concurrentResultsCannotOverrunTheSharedRunBudget() {
        BoundedToolResultProcessor processor = new BoundedToolResultProcessor();
        RunId run = new RunId("bounded-concurrent-results");
        ToolExecutionContext context = new ToolExecutionContext(
                run, "call", () -> false, Instant.now().plusSeconds(30));
        RunRequest request = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("test")).build();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> processor.process(
                            text(10_000), largeDescriptor("web_snapshot"), context, request), executor))
                    .toList();
            int total = futures.stream().map(CompletableFuture::join)
                    .mapToInt(value -> value.asText().length()).sum();
            assertEquals(BoundedToolResultProcessor.RUN_RESULT_CHARACTERS, total);
        }
    }

    @Test
    void honorsLegacyAndSplitCapabilityLimitsWithSafeClamping() {
        BoundedToolResultProcessor processor = new BoundedToolResultProcessor();
        ToolExecutionContext legacyContext = context("bounded-legacy-config");
        RunRequest legacy = configured(1_500, null);
        assertEquals(1_500, processor.process(text(10_000), descriptor("email_read"),
                legacyContext, legacy).asText().length());
        assertEquals(1_500, processor.process(text(10_000), largeDescriptor("web_snapshot"),
                legacyContext, legacy).asText().length());

        ToolExecutionContext splitContext = context("bounded-split-config");
        RunRequest split = configured(2_000, 6_000);
        assertEquals(2_000, processor.process(text(10_000), descriptor("email_read"),
                splitContext, split).asText().length());
        assertEquals(6_000, processor.process(text(10_000), largeDescriptor("code_read"),
                splitContext, split).asText().length());

        ToolExecutionContext oldOversizedContext = context("bounded-old-oversized-config");
        RunRequest oldOversized = configured(16_000, null);
        assertEquals(8_000, processor.process(text(10_000), descriptor("email_read"),
                oldOversizedContext, oldOversized).asText().length());
        assertEquals(8_000, processor.process(text(10_000), largeDescriptor("web_snapshot"),
                oldOversizedContext, oldOversized).asText().length());
    }

    @Test
    void publishesEffectiveLimitsAndRunConsumption() {
        BoundedToolResultProcessor processor = new BoundedToolResultProcessor();
        ToolExecutionContext context = context("bounded-observation");
        RunRequest request = configured(2_000, 6_000);
        processor.process(text(10_000), largeDescriptor("web_snapshot"), context, request);

        JsonNode observation = processor.budgetObservation(
                largeDescriptor("web_snapshot"), context, request);
        assertEquals("large", observation.path("resultClass").asText());
        assertEquals(2_000, observation.path("requestedDefaultLimitCharacters").asInt());
        assertEquals(6_000, observation.path("requestedLargeLimitCharacters").asInt());
        assertEquals(2_000, observation.path("defaultLimitCharacters").asInt());
        assertEquals(6_000, observation.path("largeLimitCharacters").asInt());
        assertTrue(observation.path("defaultLimitExplicit").asBoolean());
        assertTrue(observation.path("largeLimitExplicit").asBoolean());
        assertEquals(6_000, observation.path("effectiveLimitCharacters").asInt());
        assertEquals(6_000, observation.path("actualLimitCharacters").asInt());
        assertEquals(6_000, observation.path("resultCharacters").asInt());
        assertEquals(6_000, observation.path("runConsumedCharacters").asInt());
    }

    @Test
    void headTailEvictionNeverSplitsSupplementaryUnicode() {
        BoundedToolResultProcessor processor = new BoundedToolResultProcessor();
        String value = "😀".repeat(5_000);

        String output = processor.process(JsonNodeFactory.instance.textNode(value),
                descriptor("email_read"), context("bounded-unicode"), request()).asText();

        assertTrue(output.length() <= BoundedToolResultProcessor.DEFAULT_RESULT_CHARACTERS);
        assertFalse(hasUnpairedSurrogate(output));
    }

    @Test
    void restoresCumulativeAndLegacyRunConsumptionFromDurableEvents() {
        RunId cumulativeRun = new RunId("bounded-restored-cumulative");
        var standalonePayload = JsonNodeFactory.instance.objectNode()
                .put("runConsumedCharacters", 12_000)
                .put("resultCharacters", 4_000);
        var nestedPayload = JsonNodeFactory.instance.objectNode()
                .put("runConsumedCharacters", 18_000)
                .put("resultCharacters", 8_000);
        var completedPayload = JsonNodeFactory.instance.objectNode()
                .put("invocationId", "nested-call")
                .set("resultBudget", nestedPayload);
        var cumulativeStore = new HistoryRunStore(List.of(
                event(cumulativeRun, 1, "core.tool.result.budget", standalonePayload),
                event(cumulativeRun, 2, "core.tool.completed", completedPayload)));
        JsonNode cumulative = new BoundedToolResultProcessor(cumulativeStore).process(
                text(10_000), largeDescriptor("web_snapshot"),
                context(cumulativeRun.value()), request());
        assertEquals(6_000, cumulative.asText().length());

        RunId legacyRun = new RunId("bounded-restored-legacy");
        var legacyStore = new HistoryRunStore(List.of(
                event(legacyRun, 1, JsonNodeFactory.instance.objectNode()
                        .put("modelCharacters", 7_000)),
                event(legacyRun, 2, JsonNodeFactory.instance.objectNode()
                        .put("resultCharacters", 9_000))));
        JsonNode legacy = new BoundedToolResultProcessor(legacyStore).process(
                text(10_000), largeDescriptor("code_read"),
                context(legacyRun.value()), request());
        assertEquals(8_000, legacy.asText().length());
    }

    private static ToolExecutionContext context(String runId) {
        return new ToolExecutionContext(new RunId(runId), "call", () -> false,
                Instant.now().plusSeconds(30));
    }

    private static RunRequest configured(int maxCharacters, Integer largeMaxCharacters) {
        var configuration = JsonNodeFactory.instance.objectNode()
                .put("enabled", true)
                .put("maxCharacters", maxCharacters);
        if (largeMaxCharacters != null) {
            configuration.put("largeMaxCharacters", largeMaxCharacters);
        }
        var capabilities = JsonNodeFactory.instance.objectNode()
                .set("tool.result-eviction", configuration);
        return request().withAttribute("framework.compiledCapabilities", capabilities);
    }

    private static RunRequest request() {
        return RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("chat"))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("test")).build();
    }

    private static JsonNode text(int size) {
        return JsonNodeFactory.instance.textNode("x".repeat(size));
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static com.javaclaw.framework.api.RunEventEnvelope event(
            RunId runId, long sequence, JsonNode payload) {
        return event(runId, sequence, "core.tool.result.budget", payload);
    }

    private static com.javaclaw.framework.api.RunEventEnvelope event(
            RunId runId, long sequence, String type, JsonNode payload) {
        return new com.javaclaw.framework.api.RunEventEnvelope(
                runId.value(), sequence, Instant.EPOCH.plusSeconds(sequence),
                type, 2, "test", "correlation", null, payload);
    }

    private record HistoryRunStore(
            List<com.javaclaw.framework.api.RunEventEnvelope> events)
            implements com.javaclaw.framework.spi.RunStore {
        @Override public com.javaclaw.framework.spi.CreateRunResult create(
                RunId id, RunRequest request, String executionPlanId,
                com.javaclaw.framework.spi.RunEventDraft createdEvent) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<com.javaclaw.framework.spi.StoredRun> find(RunId id) {
            return Optional.empty();
        }
        @Override public Optional<com.javaclaw.framework.spi.StoredRun> findByIdempotencyKey(
                String workspaceId, String idempotencyKey) { return Optional.empty(); }
        @Override public List<com.javaclaw.framework.spi.StoredRun> nonTerminalRuns() {
            return List.of();
        }
        @Override public List<com.javaclaw.framework.api.RunEventEnvelope> eventsAfter(
                RunId id, long afterSequence) {
            return events.stream().filter(event -> event.runId().equals(id.value())
                    && event.sequence() > afterSequence).toList();
        }
        @Override public Optional<com.javaclaw.framework.api.RunEventEnvelope> append(
                RunId id, Set<RunState> expectedStates, RunState nextState,
                com.javaclaw.framework.spi.RunEventDraft event, JsonNode output, String error) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<com.javaclaw.framework.api.RunEventEnvelope> appendEvent(
                RunId id, com.javaclaw.framework.spi.RunEventDraft event) {
            throw new UnsupportedOperationException();
        }
    }

    private static ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(name, name,
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                "test", PermissionSet.NONE, true);
    }

    private static ToolDescriptor largeDescriptor(String name) {
        return new ToolDescriptor(name, name,
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                "test", PermissionSet.NONE, true,
                com.javaclaw.framework.spi.ToolResultClass.LARGE);
    }

    private static ToolDescriptor selfBoundedDescriptor(String name) {
        return new ToolDescriptor(name, name,
                JsonNodeFactory.instance.objectNode().put("type", "object"),
                "test", PermissionSet.NONE, true,
                com.javaclaw.framework.spi.ToolResultClass.SELF_BOUNDED);
    }
}
