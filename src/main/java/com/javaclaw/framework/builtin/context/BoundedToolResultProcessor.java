package com.javaclaw.framework.builtin.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityRuntime;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolResultPostProcessor;
import com.javaclaw.framework.spi.ToolResultClass;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.util.UnicodeText;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Run-scoped model-view budget while durable core.tool.completed keeps the full output. */
public final class BoundedToolResultProcessor implements ToolResultPostProcessor {
    public static final int DEFAULT_RESULT_CHARACTERS = 4_000;
    public static final int LARGE_RESULT_CHARACTERS = 8_000;
    public static final int RUN_RESULT_CHARACTERS = 24_000;
    private final ConcurrentHashMap<RunId, RunBudget> budgets = new ConcurrentHashMap<>();
    private final RunStore runStore;

    public BoundedToolResultProcessor() {
        this(null);
    }

    public BoundedToolResultProcessor(RunStore runStore) {
        this.runStore = runStore;
    }

    @Override
    public JsonNode process(JsonNode current, ToolDescriptor tool,
                            ToolExecutionContext context, RunRequest request) {
        // Skill pages own their 8K pagination cursor; generic head/tail eviction would corrupt it.
        if (tool.resultClass() == ToolResultClass.SELF_BOUNDED) return current;
        String rendered = current.isTextual() ? current.asText() : current.toString();
        RunBudget budget = budgets.computeIfAbsent(context.runId(), this::restoreBudget);
        ResultLimits limits = limits(request);
        boolean large = tool.resultClass() == ToolResultClass.LARGE;
        int perResult = large
                ? limits.largeCharacters() : limits.defaultCharacters();
        JsonNode result;
        // Multiple provider tool calls may execute concurrently. Reservation and rendering must be
        // one critical section or simultaneous 8K results can overrun the 24K run ceiling.
        synchronized (budget) {
            budget.lastAccessEpochSecond = Instant.now().getEpochSecond();
            int remaining = Math.max(0, RUN_RESULT_CHARACTERS - budget.characters.get());
            int allowed = Math.min(perResult, remaining);
            if (rendered.length() <= allowed) {
                budget.characters.addAndGet(rendered.length());
                result = current;
            } else if (allowed <= 0) {
                result = JsonNodeFactory.instance.textNode("");
            } else {
                String preview = headTail(rendered, allowed);
                budget.characters.addAndGet(preview.length());
                result = JsonNodeFactory.instance.textNode(preview);
            }
            budget.observations.put(context.invocationId(), new ResultObservation(
                    allowed, renderedCharacters(result), budget.characters.get()));
        }
        opportunisticCleanup();
        return result;
    }

    @Override
    public JsonNode budgetObservation(
            ToolDescriptor tool, ToolExecutionContext context, RunRequest request) {
        ResultLimits limits = limits(request);
        boolean selfBounded = tool.resultClass() == ToolResultClass.SELF_BOUNDED;
        boolean large = tool.resultClass() == ToolResultClass.LARGE;
        RunBudget budget = budgets.get(context.runId());
        ResultObservation processed = budget == null ? null
                : budget.observations.remove(context.invocationId());
        int configuredLimit = selfBounded ? 0
                : large ? limits.largeCharacters() : limits.defaultCharacters();
        ObjectNode observation = JsonNodeFactory.instance.objectNode();
        observation.put("resultClass", selfBounded ? "self_bounded" : large ? "large" : "default");
        observation.put("requestedDefaultLimitCharacters", limits.requestedDefaultCharacters());
        observation.put("requestedLargeLimitCharacters", limits.requestedLargeCharacters());
        observation.put("defaultLimitCharacters", limits.defaultCharacters());
        observation.put("largeLimitCharacters", limits.largeCharacters());
        observation.put("defaultLimitExplicit", limits.defaultConfigured());
        observation.put("largeLimitExplicit", limits.largeConfigured());
        observation.put("effectiveLimitCharacters", configuredLimit);
        observation.put("actualLimitCharacters", processed == null
                ? configuredLimit : processed.actualLimitCharacters());
        observation.put("resultCharacters", processed == null
                ? 0 : processed.resultCharacters());
        observation.put("runLimitCharacters", RUN_RESULT_CHARACTERS);
        observation.put("runConsumedCharacters", processed == null
                ? budget == null ? 0 : budget.characters.get()
                : processed.runConsumedCharacters());
        observation.put("selfBounded", selfBounded);
        return observation;
    }

    static ResultLimits limits(RunRequest request) {
        JsonNode configuration = CapabilityRuntime.configuration(request, "tool.result-eviction");
        boolean defaultConfigured = configuration.path("maxCharacters").isIntegralNumber();
        boolean largeConfigured = configuration.path("largeMaxCharacters").isIntegralNumber();
        int requestedDefault = defaultConfigured
                ? configuration.path("maxCharacters").asInt() : DEFAULT_RESULT_CHARACTERS;
        int requestedLarge = largeConfigured
                ? configuration.path("largeMaxCharacters").asInt()
                : defaultConfigured ? requestedDefault : LARGE_RESULT_CHARACTERS;
        return new ResultLimits(
                clamp(requestedDefault), clamp(requestedLarge),
                requestedDefault, requestedLarge, defaultConfigured, largeConfigured);
    }

    private static int clamp(int value) {
        return Math.max(1_000, Math.min(LARGE_RESULT_CHARACTERS, value));
    }

    private static int renderedCharacters(JsonNode value) {
        return value.isTextual() ? value.asText().length() : value.toString().length();
    }

    private static String headTail(String value, int limit) {
        if (limit <= 0) return "";
        if (value.length() <= limit) return value;
        String marker = "\n...[middle evicted]...\n";
        if (limit <= marker.length()) return UnicodeText.prefix(value, limit);
        int content = limit - marker.length();
        int head = (content * 3) / 5;
        int tail = content - head;
        return UnicodeText.prefix(value, head) + marker + UnicodeText.suffix(value, tail);
    }

    private void opportunisticCleanup() {
        if (budgets.size() < 512) return;
        long cutoff = Instant.now().minusSeconds(3_600).getEpochSecond();
        budgets.entrySet().removeIf(entry -> entry.getValue().lastAccessEpochSecond < cutoff);
    }

    private RunBudget restoreBudget(RunId runId) {
        if (runStore == null) return new RunBudget(0);
        int cumulative = 0;
        int legacySum = 0;
        boolean foundCumulative = false;
        java.util.Set<String> legacyInvocations = new java.util.HashSet<>();
        for (var event : runStore.eventsAfter(runId, 0)) {
            JsonNode eventPayload = event.payload();
            JsonNode payload;
            if (event.type().equals("core.tool.result.budget")) {
                payload = eventPayload;
            } else if (event.type().equals("core.tool.completed")
                    && eventPayload.path("resultBudget").isObject()) {
                payload = eventPayload.path("resultBudget");
            } else {
                continue;
            }
            JsonNode consumed = payload.get("runConsumedCharacters");
            if (consumed != null && consumed.isIntegralNumber()) {
                foundCumulative = true;
                cumulative = Math.max(cumulative, nonNegativeInt(consumed));
                continue;
            }
            JsonNode resultCharacters = payload.get("modelCharacters");
            if (resultCharacters == null || !resultCharacters.isIntegralNumber()) {
                resultCharacters = payload.get("resultCharacters");
            }
            if (resultCharacters != null && resultCharacters.isIntegralNumber()) {
                String invocationId = payload.path("invocationId").asText(
                        eventPayload.path("invocationId").asText("")).strip();
                if (!invocationId.isEmpty() && !legacyInvocations.add(invocationId)) continue;
                legacySum = (int) Math.min(RUN_RESULT_CHARACTERS,
                        (long) legacySum + nonNegativeInt(resultCharacters));
            }
        }
        int restored = foundCumulative ? cumulative : legacySum;
        return new RunBudget(Math.min(RUN_RESULT_CHARACTERS, restored));
    }

    private static int nonNegativeInt(JsonNode value) {
        long number = Math.max(0L, value.asLong());
        return (int) Math.min(Integer.MAX_VALUE, number);
    }

    private static final class RunBudget {
        private final AtomicInteger characters;
        private final ConcurrentHashMap<String, ResultObservation> observations =
                new ConcurrentHashMap<>();
        private volatile long lastAccessEpochSecond = Instant.now().getEpochSecond();

        private RunBudget(int characters) {
            this.characters = new AtomicInteger(characters);
        }
    }

    record ResultLimits(
            int defaultCharacters,
            int largeCharacters,
            int requestedDefaultCharacters,
            int requestedLargeCharacters,
            boolean defaultConfigured,
            boolean largeConfigured) { }

    private record ResultObservation(
            int actualLimitCharacters,
            int resultCharacters,
            int runConsumedCharacters) { }
}
