package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.ToolRuntimeContextProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Current requirements supplied by the host's frozen contract, never tool arguments. */
public final class TaskAcceptanceContext implements ToolRuntimeContextProvider {
    public static final String ATTRIBUTE = "framework.task.acceptance.v3";
    private final RunRequest request;
    private final ToolRuntimeContextProvider delegate;

    public TaskAcceptanceContext(RunRequest request, ToolRuntimeContextProvider delegate) {
        this.request = Objects.requireNonNull(request);
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public List<JsonNode> currentContext() {
        List<JsonNode> result = new ArrayList<>(delegate.currentContext());
        JsonNode contract = request.attributes().get(ATTRIBUTE);
        if (contract == null || contract.path("version").asInt() != 3
                || !contract.path("reliable").asBoolean()
                || !contract.path("applicable").asBoolean()
                || !contract.path("criteria").isArray()
                || contract.path("criteria").size() > 12) return List.copyOf(result);
        if ("REQUIRED_SUBJECT".equals(contract.path("desktopObservationPolicy").asText())
                && java.util.stream.StreamSupport.stream(contract.path("criteria").spliterator(), false)
                        .anyMatch(criterion -> "desktop.observe".equals(criterion.path("capabilityId").asText())
                                && criterion.path("requiredSubject").asText("").isBlank()))
            return List.copyOf(result);
        var context = JsonNodeFactory.instance.objectNode()
                .put("kind", "desktop.acceptance.conditions").put("source", "host");
        var conditions = context.putArray("conditions");
        for (JsonNode criterion : contract.path("criteria")) {
            String id = criterion.path("id").asText("");
            String subject = criterion.path("requiredSubject").asText("");
            if (!"desktop.observe".equals(criterion.path("capabilityId").asText())
                    || !"DESKTOP_APPLICATION".equals(criterion.path("targetType").asText())
                    || id.isBlank() || id.length() > 120
                    || subject.isBlank() || subject.length() > 240) continue;
            conditions.addObject().put("criterionId", id).put("subject", subject);
        }
        if (!conditions.isEmpty()) result.add(context);
        return List.copyOf(result);
    }
}
