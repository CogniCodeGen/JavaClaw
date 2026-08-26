package com.javaclaw.framework.builtin.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.AdvisorRuntimeContext;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.util.TokenEstimator;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One bounded LIGHT-model summary call plus its schema/rendering policy. */
final class ConversationSummaryGenerator {
    static final int SOURCE_TOKEN_LIMIT = 4_500;
    private static final int CONTENT_TOKEN_LIMIT = 1_150;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final AdvisorRuntimeContext runtime;

    ConversationSummaryGenerator(AdvisorRuntimeContext runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    GeneratedSummary summarize(
            String previousSummary, List<ConversationHistoryMessage> messages) {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("instruction", "Summarize only durable conversation state. Preserve goals, facts, "
                + "constraints, decisions, and unresolved questions. Omit chit-chat and repetition. "
                + "Each array item must be concise. Do not invent information.");
        if (previousSummary != null && !previousSummary.isBlank()) {
            input.put("previousSummary", previousSummary);
        }
        ArrayNode source = input.putArray("newMessages");
        for (ConversationHistoryMessage message : messages) {
            ObjectNode item = source.addObject();
            item.put("messageId", message.messageId());
            item.put("role", message.role().name().toLowerCase());
            item.put("text", boundedMessage(message.content()));
        }
        ModelTaskRequest task = new ModelTaskRequest(
                "conversation-context-summary", ModelTier.LIGHT, input, summarySchema(),
                runtime.runId(), "context.compaction", TIMEOUT, 0,
                runtime.cancellation(), false);
        CompletableFuture<ModelTaskResult> future = runtime.modelTasks().execute(task)
                .toCompletableFuture();
        try {
            JsonNode output = future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).output();
            return new GeneratedSummary(renderWithinBudget(output), output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("context summary interrupted", interrupted);
        } catch (Exception failure) {
            future.cancel(true);
            throw new IllegalStateException("context summary failed", failure);
        }
    }

    static String boundedMessage(String content) {
        if (TokenEstimator.estimate(content) <= SOURCE_TOKEN_LIMIT) return content;
        return TokenEstimator.truncateToTokens(content, SOURCE_TOKEN_LIMIT - 12)
                + "\n[message truncated to summary budget]";
    }

    private static ObjectNode summarySchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (String field : List.of(
                "goals", "facts", "constraints", "decisions", "openQuestions")) {
            ObjectNode array = properties.putObject(field);
            array.put("type", "array");
            array.put("maxItems", 12);
            ObjectNode items = array.putObject("items");
            items.put("type", "string");
            items.put("maxLength", 300);
        }
        schema.putArray("required").add("goals").add("facts").add("constraints")
                .add("decisions").add("openQuestions");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static String renderWithinBudget(JsonNode output) {
        StringBuilder rendered = new StringBuilder();
        appendSection(rendered, "Goals", output.path("goals"));
        appendSection(rendered, "Facts", output.path("facts"));
        appendSection(rendered, "Constraints", output.path("constraints"));
        appendSection(rendered, "Decisions", output.path("decisions"));
        appendSection(rendered, "Open questions", output.path("openQuestions"));
        String value = rendered.toString().strip();
        if (TokenEstimator.estimate(value) <= CONTENT_TOKEN_LIMIT) return value;
        return TokenEstimator.truncateToTokens(value, CONTENT_TOKEN_LIMIT - 10).stripTrailing()
                + "\n[summary truncated to budget]";
    }

    private static void appendSection(StringBuilder target, String label, JsonNode values) {
        target.append(label).append(":\n");
        if (values.isArray() && !values.isEmpty()) {
            for (JsonNode value : values) target.append("- ").append(value.asText()).append('\n');
        } else {
            target.append("- (none)\n");
        }
    }

    record GeneratedSummary(String rendered, JsonNode structured) {
        GeneratedSummary {
            rendered = rendered == null ? "" : rendered;
            structured = Objects.requireNonNull(structured, "structured").deepCopy();
        }

        @Override public JsonNode structured() {
            return structured.deepCopy();
        }
    }
}
