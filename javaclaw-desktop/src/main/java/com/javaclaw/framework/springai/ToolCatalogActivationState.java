package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.core.ToolRecoveryRequiredException;
import com.javaclaw.framework.spi.RunStore;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.function.Supplier;

import static com.javaclaw.framework.springai.ToolCatalogSession.NAME;

/** 重放工具目录激活与精确消费记录，不授予新工具身份或输入权限。 */
final class ToolCatalogActivationState {
    @FunctionalInterface
    interface ProviderBinding {
        Set<String> exposed(JsonNode input, Set<String> active, String modelStepId);
    }

    private final RunId runId;
    private final RunStore runs;
    private final Supplier<Map<String, ToolCallback>> authorized;
    private final ProviderBinding binding;
    private long lastEventSequence;
    private List<String> activeNames = List.of();
    private final Map<String, Set<String>> activeModelSteps = new LinkedHashMap<>();
    private final Map<String, StartedTool> startedTools = new HashMap<>();

    ToolCatalogActivationState(RunId runId, RunStore runs,
            Supplier<Map<String, ToolCallback>> authorized, ProviderBinding binding) {
        this.runId = runId;
        this.runs = runs;
        this.authorized = authorized;
        this.binding = binding;
    }

    List<String> names() { return activeNames; }

    /** 仅从持久化宿主工具事件重建激活，重启不需要另一份存储。 */
    synchronized void refresh() {
        for (var event : runs.eventsAfter(runId, lastEventSequence)) {
            long priorSequence = lastEventSequence;
            lastEventSequence = event.sequence();
            String type = event.type();
            JsonNode payload = event.payload();
            String stepId = payload.path("stepId").asText();
            if (type.equals("core.step.started")) {
                String kind = payload.path("kind").asText();
                if (kind.equals("MODEL") && !activeNames.isEmpty()) {
                    if (event.schemaVersion() != 1 || !event.producer().equals("framework.core")
                            || !event.runId().equals(runId.value())) {
                        lastEventSequence = priorSequence;
                        throw new ToolRecoveryRequiredException(stepId,
                                "catalog activation requires a trusted frozen provider directory");
                    }
                    try {
                        activeModelSteps.put(stepId, binding.exposed(payload.path("input"),
                                new LinkedHashSet<>(activeNames), stepId));
                    } catch (ToolRecoveryRequiredException invalid) {
                        lastEventSequence = priorSequence;
                        throw invalid;
                    }
                } else if (kind.equals("TOOL")) {
                    JsonNode input = payload.path("input");
                    startedTools.put(stepId, new StartedTool(input.path("tool").asText(),
                            input.path("arguments").path("action").asText()));
                }
                continue;
            }
            if (type.equals("core.step.completed")) {
                Set<String> consumed = activeModelSteps.get(stepId);
                if (consumed != null) {
                    if (event.schemaVersion() != 1 || !event.producer().equals("framework.core")
                            || !event.runId().equals(runId.value())) {
                        lastEventSequence = priorSequence;
                        throw new ToolRecoveryRequiredException(stepId,
                                "catalog activation requires a trusted provider completion");
                    }
                    activeModelSteps.remove(stepId);
                    activeNames = activeNames.stream().filter(name -> !consumed.contains(name)).toList();
                }
                StartedTool started = startedTools.remove(stepId);
                if (started != null) {
                    if (started.name().equals(NAME) && started.action().equals("activate")
                            && payload.path("credentialRedacted").asBoolean(false)) {
                        throw new ToolRecoveryRequiredException(stepId,
                                "persisted tool catalog activation was redacted: " + stepId);
                    }
                    JsonNode output = payload.path("output");
                    try {
                        applyToolCompletion(started.name(), output.path("rawOutput"),
                                output.path("waitingInput").asBoolean(false));
                    } catch (IllegalStateException invalid) {
                        throw new ToolRecoveryRequiredException(stepId,
                                "persisted tool completion cannot be restored: "
                                        + invalid.getMessage());
                    }
                }
                continue;
            }
            if (type.equals("core.step.failed")) {
                activeModelSteps.remove(stepId);
                startedTools.remove(stepId);
                continue;
            }
        }
    }

    private void applyToolCompletion(String name, JsonNode output, boolean waitingInput) {
        if (waitingInput || name.isBlank()) return;
        if (name.equals(NAME)) {
            if (!output.path("action").asText().equals("activate")
                    || !output.path("success").asBoolean(false)) return;
            JsonNode activated = output.path("activated");
            if (!activated.isArray() || activated.isEmpty()) {
                throw new IllegalStateException("persisted tool catalog activation has no tool names");
            }
            List<String> names = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (JsonNode value : activated) {
                String selected = value.asText("");
                if (selected.isBlank() || !unique.add(selected)) {
                    throw new IllegalStateException("persisted tool catalog activation is invalid");
                }
                if (!authorized.get().containsKey(selected)) {
                    throw new IllegalStateException("persisted activation names a tool no longer authorized: "
                            + selected);
                }
                names.add(selected);
            }
            activeNames = List.copyOf(names);
            activeModelSteps.clear();
        }
    }

    private record StartedTool(String name, String action) { }
}
