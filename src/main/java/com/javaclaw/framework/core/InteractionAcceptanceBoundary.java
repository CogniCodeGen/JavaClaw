package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.StepId;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;

import java.util.Comparator;
import java.util.List;

/** Acceptance-only revision boundary; raw history and effect-safety inheritance stay complete. */
record InteractionAcceptanceBoundary(boolean restricted, String childRunId, long childContractSequence) {
    private static final InteractionAcceptanceBoundary UNRESTRICTED =
            new InteractionAcceptanceBoundary(false, "", 0);
    private static final InteractionAcceptanceBoundary PENDING =
            new InteractionAcceptanceBoundary(true, "", 0);

    static InteractionAcceptanceBoundary current(RunStore runs, RunId parentId) {
        StoredRun parent = runs.find(parentId).orElse(null);
        if (parent == null || !InteractionExecutionPolicy.isMain(parent.request())) return UNRESTRICTED;
        List<RunEventEnvelope> events = runs.eventsAfter(parentId, 0).stream()
                .filter(event -> parentId.value().equals(event.runId())).toList();
        RunEventEnvelope accepted = latest(events.stream().filter(event ->
                host(event, "core.interaction.control_accepted", 1)
                        && event.payload().path("type").asText().equals("AMEND")).toList());
        if (accepted == null) return UNRESTRICTED;
        // As soon as AMEND is accepted, prior interaction evidence cannot satisfy its replacement.
        if (!runs.readable(parent.request().scope())) return PENDING;
        try {
            RunEventEnvelope revised = latestContract(events);
            JsonNode command = accepted.payload();
            if (revised == null || !host(revised, "core.task.contract_revised", 3)
                    || revised.sequence() <= accepted.sequence()
                    || !positive(command.path("revision")) || !text(command.path("commandId"))
                    || !text(command.path("childRunId")) || !text(command.path("text"))) return PENDING;
            long revision = Math.addExact(command.path("revision").longValue(), 1);
            RunEventEnvelope applied = latest(events.stream().filter(event ->
                    host(event, "core.interaction.control_applied", 1)
                            && event.sequence() > revised.sequence()
                            && event.payload().path("type").asText().equals("AMEND")
                            && event.payload().path("commandId").equals(command.path("commandId"))
                            && positive(event.payload().path("revision"))
                            && event.payload().path("revision").longValue() == revision).toList());
            if (applied == null || !text(applied.payload().path("childRunId"))
                    || events.stream().anyMatch(event -> event.sequence() > accepted.sequence()
                        && event.sequence() < applied.sequence()
                        && host(event, "core.interaction.control_accepted", 1))) return PENDING;
            String childId = applied.payload().path("childRunId").textValue();
            RunEventEnvelope waiting = latest(events.stream().filter(event ->
                    host(event, "core.run.waiting_child", 1)
                            && event.sequence() > revised.sequence() && event.sequence() < applied.sequence()
                            && event.payload().path("commandId").equals(command.path("commandId"))
                            && event.payload().path("childRunId").asText().equals(childId)
                            && positive(event.payload().path("revision"))
                            && event.payload().path("revision").longValue() == revision).toList());
            RunEventEnvelope oldWait = latest(events.stream().filter(event ->
                    host(event, "core.run.waiting_child", 1)
                            && event.sequence() < accepted.sequence()).toList());
            if (waiting == null || oldWait == null) return PENDING;
            JsonNode wait = waiting.payload().path("output"), previousWait = oldWait.payload().path("output");
            if (!wait.path("kind").asText().equals("interaction.waiting_child")
                    || !wait.path("childRunId").asText().equals(childId)
                    || !positive(wait.path("revision")) || wait.path("revision").longValue() != revision
                    || !text(wait.path("taskId")) || !text(wait.path("invocationId"))
                    || !previousWait.path("kind").asText().equals("interaction.waiting_child")
                    || !previousWait.path("childRunId").equals(command.path("childRunId"))
                    || !previousWait.path("revision").equals(command.path("revision"))
                    || !previousWait.path("taskId").equals(wait.path("taskId"))
                    || !previousWait.path("invocationId").equals(wait.path("invocationId"))) return PENDING;
            StoredRun old = runs.find(new RunId(command.path("childRunId").textValue())).orElse(null);
            StoredRun child = runs.find(new RunId(childId)).orElse(null);
            if (!owned(runs, parent, old) || !owned(runs, parent, child)
                    || !old.request().scope().equals(child.request().scope())
                    || !old.request().source().equals(child.request().source())) return PENDING;
            JsonNode task = child.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
            JsonNode oldTask = old.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
            JsonNode frozen = child.request().attributes().get("framework.taskContract");
            JsonNode contract = revised.payload();
            if (task == null || oldTask == null || frozen == null
                    || task.path("version").asInt() != 1 || oldTask.path("version").asInt() != 1
                    || !task.path("taskId").equals(wait.path("taskId"))
                    || !task.path("taskId").equals(oldTask.path("taskId"))
                    || !positive(task.path("revision")) || task.path("revision").longValue() != revision
                    || !oldTask.path("revision").equals(command.path("revision"))
                    || !task.path("mode").equals(oldTask.path("mode"))
                    || !task.path("necessaryData").equals(oldTask.path("necessaryData"))
                    || !task.path("constraints").equals(oldTask.path("constraints"))
                    || !task.path("goal").asText().equals(oldTask.path("goal").asText()
                        + "\n用户修订：" + command.path("text").textValue())) return PENDING;
            String invocation = wait.path("invocationId").textValue();
            if (!StepId.tool(parentId, invocation).value().equals(child.request().attributes()
                    .getOrDefault("framework.parentStepId", JsonNodeFactory.instance.nullNode()).asText())
                    || events.stream().filter(event -> host(event, "core.tool.started", 1)
                        && event.sequence() < oldWait.sequence()
                        && event.payload().path("tool").asText().equals(InteractionExecutionPolicy.DELEGATE_TOOL)
                        && event.payload().path("invocationId").asText().equals(invocation)).count() != 1)
                return PENDING;
            var expected = JsonNodeFactory.instance.arrayNode();
            var ids = JsonNodeFactory.instance.arrayNode();
            if (!contract.path("criteria").isArray()) return PENDING;
            for (JsonNode criterion : contract.path("criteria")) {
                if (!interactionCapability(criterion.path("capabilityId").asText())) continue;
                if (!text(criterion.path("id"))
                        || !criterion.path("id").textValue().startsWith("ir" + revision + ".")) return PENDING;
                expected.add(criterion); ids.add(criterion.path("id"));
            }
            if (expected.isEmpty() || !validContract(contract) || !validContract(frozen)
                    || !frozen.path("source").asText().equals("host.interaction")
                    || !frozen.path("originalRequest").equals(task.path("goal"))
                    || !expected.equals(frozen.path("criteria"))
                    || !ids.equals(task.path("acceptanceCriterionIds"))
                    || !frozen.path("desktopObservationPolicy").equals(contract.path("desktopObservationPolicy"))
                    || !frozen.path("intentStatus").equals(contract.path("intentStatus"))) return PENDING;
            RunEventEnvelope actual = latestContract(runs.eventsAfter(child.snapshot().id(), 0).stream()
                    .filter(event -> childId.equals(event.runId())).toList());
            if (actual == null || !validContract(actual.payload())
                    || !actual.payload().path("criteria").equals(expected)
                    || !actual.payload().path("desktopObservationPolicy").equals(frozen.path("desktopObservationPolicy"))
                    || !actual.payload().path("intentStatus").equals(frozen.path("intentStatus"))) return PENDING;
            return new InteractionAcceptanceBoundary(true, childId, actual.sequence());
        } catch (RuntimeException malformed) {
            // Missing, pending or foreign amendment provenance can only remove acceptance evidence.
            return PENDING;
        }
    }

    boolean excludesChild(StoredRun child) {
        return restricted && InteractionExecutionPolicy.isInteraction(child.request())
                && !child.snapshot().id().value().equals(childRunId);
    }

    boolean excludesReceipt(RunEventEnvelope event) {
        if (!restricted || !event.type().equals("core.tool.receipt")) return false;
        String tool = event.payload().path("tool").asText();
        return (tool.startsWith("web_") || tool.startsWith("site_") || tool.startsWith("desktop_session_"))
                && (!event.runId().equals(childRunId) || event.sequence() <= childContractSequence);
    }

    private static boolean owned(RunStore runs, StoredRun parent, StoredRun child) {
        if (child == null || !InteractionExecutionPolicy.isInteraction(child.request())
                || !child.request().source().id().equals("interaction-executor")
                || !parent.snapshot().id().equals(child.request().linkage().parentRunId())
                || !InteractionDelegateCoordinator.childScope(parent.request().scope()).equals(child.request().scope())
                || !runs.readable(child.request().scope())
                || !parent.request().permissionCeiling().containsAll(child.request().permissionCeiling())) return false;
        JsonNode scope = child.request().attributes().get(InteractionExecutionPolicy.PARENT_SCOPE_ATTRIBUTE);
        JsonNode source = child.request().attributes().get(InteractionExecutionPolicy.PARENT_SOURCE_ATTRIBUTE);
        return scope != null && scope.isObject() && scope.size() == 3
                && scope.path("workspaceId").asText().equals(parent.request().scope().workspaceId())
                && scope.path("userId").asText().equals(parent.request().scope().userId())
                && scope.path("sessionId").asText().equals(parent.request().scope().sessionId())
                && source != null && source.isObject() && source.size() == 2
                && source.path("kind").asText().equals(parent.request().source().kind())
                && source.path("id").asText().equals(parent.request().source().id());
    }

    private static boolean validContract(JsonNode value) {
        return value.path("version").asInt() == 3
                && value.path("applicable").isBoolean() && value.path("applicable").booleanValue()
                && value.path("reliable").isBoolean() && value.path("reliable").booleanValue()
                && value.path("intentStatus").asText().equals("RESOLVED");
    }

    private static boolean interactionCapability(String capability) {
        return capability.startsWith("browser.") || capability.startsWith("desktop.");
    }

    private static RunEventEnvelope latestContract(List<RunEventEnvelope> events) {
        return latest(events.stream().filter(event -> host(event, "core.task.contract", 3)
                || host(event, "core.task.contract_revised", 3)).toList());
    }

    private static RunEventEnvelope latest(List<RunEventEnvelope> events) {
        return events.stream().max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
    }

    private static boolean host(RunEventEnvelope event, String type, int schema) {
        return event.type().equals(type) && event.schemaVersion() == schema
                && event.producer().equals("framework.core");
    }

    private static boolean positive(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private static boolean text(JsonNode value) { return value.isTextual() && !value.textValue().isBlank(); }
}
