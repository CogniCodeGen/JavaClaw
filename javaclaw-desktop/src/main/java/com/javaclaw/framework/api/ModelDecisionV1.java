package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.JsonSchemaValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A model's proposed turn control, never a trusted task-completion verdict. */
public record ModelDecisionV1(Decision decision, String userMessage,
        List<String> evidenceRefs, List<String> unmetCriterionIds) {
    public enum Decision { CLAIM_DONE, CONTINUE, NEEDS_INPUT, BLOCKED }

    private static final JsonNode SCHEMA = schemaDefinition();
    private static final JsonSchemaValidator VALIDATOR = new JsonSchemaValidator();

    public ModelDecisionV1 {
        decision = Objects.requireNonNull(decision, "decision");
        userMessage = Objects.requireNonNullElse(userMessage, "");
        evidenceRefs = List.copyOf(Objects.requireNonNullElse(evidenceRefs, List.of()));
        unmetCriterionIds = List.copyOf(Objects.requireNonNullElse(unmetCriterionIds, List.of()));
        if (userMessage.length() > 12_000 || evidenceRefs.size() > 32
                || evidenceRefs.stream().anyMatch(ref -> ref == null || ref.isBlank()
                        || ref.length() > 256)
                || unmetCriterionIds.size() > 32
                || unmetCriterionIds.stream().anyMatch(id -> id == null || id.isBlank()
                        || id.length() > 128)
                || unmetCriterionIds.stream().distinct().count() != unmetCriterionIds.size()) {
            throw new IllegalArgumentException("model decision exceeds protocol bounds");
        }
        if (decision != Decision.CONTINUE && userMessage.isBlank()) {
            throw new IllegalArgumentException("terminal model decision requires userMessage");
        }
        if (decision == Decision.CLAIM_DONE && !unmetCriterionIds.isEmpty()) {
            throw new IllegalArgumentException("completion claim cannot list unmet criteria");
        }
    }

    public ModelDecisionV1(Decision decision, String userMessage, List<String> evidenceRefs) {
        this(decision, userMessage, evidenceRefs, List.of());
    }

    public static JsonNode schema() { return SCHEMA.deepCopy(); }

    public static ModelDecisionV1 fromJson(JsonNode value) {
        if (value == null || !VALIDATOR.validate(SCHEMA, value, "/decision").isEmpty()) {
            throw new IllegalArgumentException("model decision does not match schema");
        }
        List<String> refs = new ArrayList<>();
        value.path("evidenceRefs").forEach(ref -> refs.add(ref.asText()));
        List<String> unmet = new ArrayList<>();
        value.path("unmetCriterionIds").forEach(id -> unmet.add(id.asText()));
        return new ModelDecisionV1(Decision.valueOf(value.path("decision").asText()),
                value.path("userMessage").asText(), refs, unmet);
    }

    public ObjectNode toJson() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("decision", decision.name());
        result.put("userMessage", userMessage);
        var refs = result.putArray("evidenceRefs");
        evidenceRefs.forEach(refs::add);
        var unmet = result.putArray("unmetCriterionIds");
        unmetCriterionIds.forEach(unmet::add);
        return result;
    }

    private static JsonNode schemaDefinition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode fields = schema.putObject("properties");
        var decision = fields.putObject("decision");
        decision.put("type", "string");
        var choices = decision.putArray("enum");
        for (Decision choice : Decision.values()) choices.add(choice.name());
        fields.putObject("userMessage").put("type", "string").put("maxLength", 12_000);
        ObjectNode evidence = fields.putObject("evidenceRefs");
        evidence.put("type", "array");
        evidence.put("description", "Optional exact reference strings copied from host-provided "
                + "evidenceRefs arrays in tool responses or control feedback. Never use tool names, "
                + "result descriptions, or invented IDs. Use [] when no suitable references exist, "
                + "including NEEDS_INPUT or BLOCKED after a failed tool call.");
        evidence.put("maxItems", 32);
        evidence.putObject("items").put("type", "string").put("minLength", 1)
                .put("maxLength", 256);
        ObjectNode unmet = fields.putObject("unmetCriterionIds");
        unmet.put("type", "array").put("maxItems", 32).put("uniqueItems", true);
        unmet.putObject("items").put("type", "string").put("minLength", 1)
                .put("maxLength", 128);
        schema.putArray("required").add("decision").add("userMessage")
                .add("unmetCriterionIds");
        schema.put("additionalProperties", false);
        return schema;
    }
}
