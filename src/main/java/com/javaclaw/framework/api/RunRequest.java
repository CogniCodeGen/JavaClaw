package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The only command accepted by every Agent entry point. */
public record RunRequest(
        AgentDefinitionRef agent,
        RunProfileRef profile,
        InvocationSource source,
        RunScope scope,
        List<InputBlock> inputs,
        RunLinkage linkage,
        PermissionSet permissionCeiling,
        RunBudget budget,
        String idempotencyKey,
        Map<String, JsonNode> attributes) {

    public RunRequest {
        agent = Objects.requireNonNull(agent, "agent");
        profile = Objects.requireNonNull(profile, "profile");
        source = Objects.requireNonNull(source, "source");
        scope = Objects.requireNonNull(scope, "scope");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        linkage = linkage == null ? RunLinkage.root(null) : linkage;
        permissionCeiling = permissionCeiling == null ? PermissionSet.NONE : permissionCeiling;
        budget = budget == null ? RunBudget.UNBOUNDED : budget;
        idempotencyKey = normalizeOptional(idempotencyKey);
        LinkedHashMap<String, JsonNode> copied = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> copied.put(
                    Objects.requireNonNull(key, "attribute key"),
                    Objects.requireNonNull(value, "attribute value").deepCopy()));
        }
        attributes = Map.copyOf(copied);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("a run requires at least one input block");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns a copy with one framework-owned, immutable attribute. */
    public RunRequest withAttribute(String key, JsonNode value) {
        LinkedHashMap<String, JsonNode> merged = new LinkedHashMap<>(attributes);
        merged.put(Objects.requireNonNull(key, "attribute key"),
                Objects.requireNonNull(value, "attribute value"));
        return new RunRequest(agent, profile, source, scope, inputs, linkage,
                permissionCeiling, budget, idempotencyKey, merged);
    }

    @Override public Map<String, JsonNode> attributes() {
        LinkedHashMap<String, JsonNode> copied = new LinkedHashMap<>();
        attributes.forEach((key, value) -> copied.put(key, value.deepCopy()));
        return Map.copyOf(copied);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    public static final class Builder {
        private AgentDefinitionRef agent;
        private RunProfileRef profile;
        private InvocationSource source;
        private RunScope scope;
        private List<InputBlock> inputs = List.of();
        private RunLinkage linkage;
        private PermissionSet permissions;
        private RunBudget budget;
        private String idempotencyKey;
        private Map<String, JsonNode> attributes = Map.of();

        public Builder agent(AgentDefinitionRef value) { agent = value; return this; }
        public Builder profile(RunProfileRef value) { profile = value; return this; }
        public Builder source(InvocationSource value) { source = value; return this; }
        public Builder scope(RunScope value) { scope = value; return this; }
        public Builder inputs(List<InputBlock> value) { inputs = value; return this; }
        public Builder input(InputBlock value) { inputs = List.of(value); return this; }
        public Builder linkage(RunLinkage value) { linkage = value; return this; }
        public Builder permissionCeiling(PermissionSet value) { permissions = value; return this; }
        public Builder budget(RunBudget value) { budget = value; return this; }
        public Builder idempotencyKey(String value) { idempotencyKey = value; return this; }
        public Builder attributes(Map<String, JsonNode> value) { attributes = value; return this; }

        public RunRequest build() {
            return new RunRequest(agent, profile, source, scope, inputs, linkage,
                    permissions, budget, idempotencyKey, attributes);
        }
    }
}
