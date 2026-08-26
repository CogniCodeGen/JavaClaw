package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Parsed exact-name and group restrictions shared by exposure and execution checks. */
public final class ToolAccessPolicy {
    private final Restriction groups;
    private final Restriction tools;

    private ToolAccessPolicy(Restriction groups, Restriction tools) {
        this.groups = groups;
        this.tools = tools;
    }

    public static ToolAccessPolicy from(RunRequest request) {
        Objects.requireNonNull(request, "request");
        return new ToolAccessPolicy(
                Restriction.parse(request.attributes().get(ToolGroupAccess.ATTRIBUTE),
                        ToolGroupAccess.ATTRIBUTE),
                Restriction.parse(request.attributes().get(ToolNameAccess.ATTRIBUTE),
                        ToolNameAccess.ATTRIBUTE));
    }

    public static ToolAccessPolicy restricted(Set<String> groups, Set<String> tools) {
        return new ToolAccessPolicy(
                Restriction.restricted(groups), Restriction.restricted(tools));
    }

    public boolean allowsGroup(String group) {
        return groups.allows(group);
    }

    public boolean allowsTool(String toolName) {
        return tools.allows(toolName);
    }

    public boolean allowsAnyTool(Set<String> toolNames) {
        if (!tools.restricted()) return true;
        return toolNames.stream().anyMatch(tools::allows);
    }

    public Set<String> configuredGroupsOrNull() {
        return groups.restricted() ? groups.values() : null;
    }

    public Set<String> configuredToolsOrNull() {
        return tools.restricted() ? tools.values() : null;
    }

    public void writeAttributes(Map<String, JsonNode> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        if (groups.restricted()) attributes.put(
                ToolGroupAccess.ATTRIBUTE, groups.toJson());
        if (tools.restricted()) attributes.put(
                ToolNameAccess.ATTRIBUTE, tools.toJson());
    }

    private record Restriction(boolean restricted, Set<String> values) {
        private Restriction {
            values = Set.copyOf(values == null ? Set.of() : values);
        }

        private static Restriction restricted(Set<String> values) {
            return new Restriction(true, Objects.requireNonNull(values, "values"));
        }

        private static Restriction parse(JsonNode configured, String attribute) {
            if (configured == null) return new Restriction(false, Set.of());
            if (!configured.isArray()) throw new SecurityException(attribute + " must be an array");
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonNode value : configured) {
                if (!value.isTextual()) {
                    throw new SecurityException(attribute + " entries must be strings");
                }
                String text = value.asText();
                if (text.equals("*")) return new Restriction(false, Set.of());
                values.add(text);
            }
            return restricted(values);
        }

        private boolean allows(String value) {
            return !restricted || values.contains(value);
        }

        private JsonNode toJson() {
            var array = JsonNodeFactory.instance.arrayNode();
            values.stream().sorted().forEach(array::add);
            return array;
        }
    }
}
