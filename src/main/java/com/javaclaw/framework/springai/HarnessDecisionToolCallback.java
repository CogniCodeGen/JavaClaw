package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.ModelDecisionV1;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;

/** Host-owned control channel; its arguments are never inferred from assistant prose. */
final class HarnessDecisionToolCallback implements ToolCallback, SpringAiToolCatalog.GroupedCallback {
    static final String NAME = "harness_submit_decision";
    private final ModelStepJournal journal;
    private final ObjectMapper json;
    private final ToolDefinition definition;

    HarnessDecisionToolCallback(ModelStepJournal journal, ObjectMapper json) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.json = Objects.requireNonNull(json, "json");
        this.definition = ToolDefinition.builder().name(NAME)
                .description("Submit one structured turn decision. This control call must be the only "
                        + "tool call in its model response. CLAIM_DONE is only a proposal: the host "
                        + "independently verifies trusted evidence. userMessage is display content, "
                        + "never a status marker. unmetCriterionIds is required and contains "
                        + "only IDs from the frozen task contract that remain unmet. evidenceRefs "
                        + "is optional: copy exact host-issued IDs from tool response evidenceRefs; "
                        + "never write tool names or result summaries there. Use [] or omit it when "
                        + "no suitable evidence is available, including NEEDS_INPUT or BLOCKED. "
                        + "After rejected arguments, correct this control call without repeating "
                        + "business tools unless the task itself needs further work.")
                .inputSchema(ModelDecisionV1.schema().toString()).build();
    }

    @Override public String group() { return "harness"; }
    @Override public ToolDefinition getToolDefinition() { return definition; }
    @Override public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(true).build();
    }
    @Override public String call(String toolInput) { return call(toolInput, null); }
    @Override public String call(String toolInput, ToolContext ignored) {
        try {
            JsonNode parsed = json.readTree(toolInput);
            ModelDecisionV1 decision = ModelDecisionV1.fromJson(parsed);
            return journal.submitDecision(decision, parsed).toString();
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return journal.rejectInvalidDecision(toolInput, invalid).toString();
        }
    }
}
