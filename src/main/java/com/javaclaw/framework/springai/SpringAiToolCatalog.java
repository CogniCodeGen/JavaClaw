package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.StepContextPolicy;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.spi.FrameworkTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 构造并限制单个 Provider Step 可见的工具目录。 */
final class SpringAiToolCatalog {
    private static final Set<String> DISCOVERY_NAMES = Set.of(
            "memory_recall", "web_get_title", "web_get_url", "web_tab_list",
            "plugin_list_tools", "mcp_list_tools", "skill_read");
    private static final Set<String> DISCOVERY_GROUPS = Set.of("memory", "discovery");

    private SpringAiToolCatalog() { }

    static List<ToolCallback> createCallbacks(
            ReasoningRequest request,
            List<FrameworkTool> runTools,
            ToolInvocationGateway gateway,
            ObjectMapper json,
            ModelStepJournal journal) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (FrameworkTool tool : runTools) {
            callbacks.add(new SpringAiToolCallback(tool, request, gateway, json, journal));
        }
        ensureUniqueNames(callbacks);
        return List.copyOf(callbacks);
    }

    static void ensureUniqueNames(List<ToolCallback> callbacks) {
        long uniqueNames = callbacks.stream().map(callback ->
                callback.getToolDefinition().name()).distinct().count();
        if (uniqueNames != callbacks.size()) {
            throw new IllegalStateException("duplicate tool names in execution plan");
        }
    }

    static ToolCatalogProjection project(
            List<ToolCallback> callbacks,
            Set<String> requiredNames,
            StepContextPolicy policy) {
        return project(callbacks, requiredNames, Set.of(), policy);
    }

    static ToolCatalogProjection project(
            List<ToolCallback> callbacks,
            Set<String> requiredNames,
            Set<String> explicitGroups,
            StepContextPolicy policy) {
        List<ToolCallback> control = callbacks.stream()
                .filter(HarnessDecisionToolCallback.class::isInstance).toList();
        if (control.size() != 1) {
            throw new IllegalStateException("exactly one trusted harness decision callback is required");
        }
        List<ToolCallback> business = callbacks.stream()
                .filter(callback -> !(callback instanceof HarnessDecisionToolCallback)).toList();
        if (policy == null) {
            return new ToolCatalogProjection(callbacks,
                    callbacks.stream().mapToInt(SpringAiToolCatalog::schemaCharacters).sum(),
                    callbacks.size());
        }
        List<ToolCallback> selected = new ArrayList<>();
        Set<String> foundRequired = new HashSet<>();
        int characters = 0;
        for (ToolCallback callback : business) {
            String name = callback.getToolDefinition().name();
            if (!requiredNames.contains(name)) continue;
            int next = schemaCharacters(callback);
            ensureFits(selected, characters, next, policy, name);
            selected.add(callback);
            foundRequired.add(name);
            characters += next;
        }
        Set<String> missing = new LinkedHashSet<>(requiredNames);
        missing.remove(HarnessDecisionToolCallback.NAME);
        missing.removeAll(foundRequired);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "recovery tool is absent from the locked execution plan: " + missing);
        }

        List<ToolCallback> optional = new ArrayList<>(business);
        optional.removeIf(callback -> foundRequired.contains(callback.getToolDefinition().name()));
        optional.sort(Comparator.comparingInt((ToolCallback callback) ->
                priority(callback, explicitGroups)));
        for (ToolCallback callback : optional) {
            if (selected.size() >= policy.maxTools()) break;
            int next = schemaCharacters(callback);
            if (characters + next > policy.maxToolSchemaCharacters()) continue;
            selected.add(callback);
            characters += next;
        }
        selected.addAll(control);
        return new ToolCatalogProjection(selected,
                characters + schemaCharacters(control.getFirst()), callbacks.size());
    }

    static Set<String> preferredGroups(
            ReasoningRequest request,
            List<Message> messages,
            List<ToolCallback> callbacks) {
        Set<String> groups = new LinkedHashSet<>(explicitGroups(request));
        Set<String> usedNames = requiredNames(messages);
        for (ToolCallback callback : callbacks) {
            if (!callback.getToolDefinition().name().equals(ToolCatalogSession.NAME)
                    && usedNames.contains(callback.getToolDefinition().name())) {
                groups.add(group(callback));
            }
        }
        groups.remove("");
        return Set.copyOf(groups);
    }

    static Set<String> explicitGroups(ReasoningRequest request) {
        JsonNode configured = request.runRequest().attributes()
                .get("framework.allowedToolGroups");
        if (configured == null || !configured.isArray()) return Set.of();
        Set<String> groups = new LinkedHashSet<>();
        for (JsonNode value : configured) {
            if (value.isTextual() && !value.asText().equals("*")) {
                groups.add(value.asText());
            }
        }
        return Set.copyOf(groups);
    }

    static Set<String> requiredNames(List<Message> messages) {
        Set<String> names = new LinkedHashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                assistant.getToolCalls().forEach(call -> names.add(call.name()));
            }
        }
        return names;
    }

    static int priority(ToolCallback callback, Set<String> explicitGroups) {
        String group = group(callback);
        if (explicitGroups.contains(group)) return 0;
        if (DISCOVERY_GROUPS.contains(group)
                || DISCOVERY_NAMES.contains(callback.getToolDefinition().name())) return 1;
        return 2;
    }

    private static String group(ToolCallback callback) {
        return callback instanceof GroupedCallback grouped ? grouped.group() : "";
    }

    interface GroupedCallback {
        String group();
    }

    private static void ensureFits(
            List<ToolCallback> selected,
            int characters,
            int next,
            StepContextPolicy policy,
            String name) {
        if (selected.size() >= policy.maxTools()
                || characters + next > policy.maxToolSchemaCharacters()) {
            throw new IllegalStateException(
                    "recovery tool catalog exceeds model context policy: " + name);
        }
    }

    static int schemaCharacters(ToolCallback callback) {
        var definition = callback.getToolDefinition();
        return definition.name().length() + definition.description().length()
                + definition.inputSchema().length();
    }
}
