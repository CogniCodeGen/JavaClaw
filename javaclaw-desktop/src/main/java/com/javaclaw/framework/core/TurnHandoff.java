package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.TurnResult;
import java.util.ArrayList;
import java.util.List;

/** Transfers only explicitly supplied facts and durable evidence; never invents a summary. */
final class TurnHandoff {
    private TurnHandoff() { }
    static TurnResult from(JsonNode output, List<AgentStep> steps,
                           RunUsageLedger.UsageSnapshot direct, RunUsageLedger.UsageSnapshot aggregate) {
        String response = output == null ? "" : output.isTextual() ? output.asText()
                : output.path("text").asText(output.path("response").asText(output.path("value").asText("")));
        return new TurnResult(response, values(output, "facts"), values(output, "decisions"),
                values(output, "artifacts"), values(output, "pending"), steps.stream()
                .filter(step -> step.state() == AgentStep.State.COMPLETED).map(step -> step.id().value()).toList(),
                new TurnResult.Usage(direct.inputTokens(), direct.outputTokens(), direct.cost()),
                new TurnResult.Usage(aggregate.inputTokens(), aggregate.outputTokens(), aggregate.cost()));
    }
    private static List<JsonNode> values(JsonNode output, String field) {
        if (output == null || !output.path(field).isArray()) return List.of();
        List<JsonNode> values = new ArrayList<>();
        output.path(field).forEach(value -> values.add(value.deepCopy()));
        return values;
    }
}
