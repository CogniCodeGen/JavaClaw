package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InteractionTask;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Read-only provenance for a human amendment to this parent's actual interaction task. */
public final class InteractionHumanAmendment {
    private InteractionHumanAmendment() { }

    public record Revision(long contractSequence, String commandId, long revision,
            String childRunId, String taskId, String invocationId, String text) { }

    public static Optional<Revision> latest(RunStore runs, ObjectMapper json, RunId parentId) {
        return latest(runs, json, parentId, Long.MAX_VALUE);
    }

    public static Optional<Revision> latest(RunStore runs, ObjectMapper json, RunId parentId,
            long throughSequence) {
        StoredRun parent = runs.find(parentId).orElse(null);
        if (parent == null || !runs.readable(parent.request().scope())
                || !InteractionExecutionPolicy.isMain(parent.request())) return Optional.empty();
        List<RunEventEnvelope> events = runs.eventsAfter(parentId, 0).stream()
                .filter(event -> parentId.value().equals(event.runId())
                        && event.sequence() <= throughSequence).toList();
        RunEventEnvelope revised = latestContract(events, Long.MAX_VALUE);
        if (revised == null || !trusted(revised, "core.task.contract_revised", 3)) return Optional.empty();
        try {
            TaskContractV3 contract = json.treeToValue(revised.payload(), TaskContractV3.class);
            if (!contract.applicable() || !contract.reliable() || !"model".equals(contract.source())
                    || contract.intentStatus() != TaskContractV3.IntentStatus.RESOLVED) return Optional.empty();
            InteractionExecutionPolicy.requireContiguousInteractionBlock(contract);
            for (int index = events.size() - 1; index >= 0; index--) {
                RunEventEnvelope applied = events.get(index);
                JsonNode value = applied.payload();
                if (!trusted(applied, "core.interaction.control_applied", 1)
                        || !"AMEND".equals(value.path("type").asText())
                        || !positiveLong(value.path("revision")) || value.path("revision").asLong() <= 1
                        || !text(value.path("commandId")) || !text(value.path("childRunId"))) continue;
                String command = value.path("commandId").asText();
                String childId = value.path("childRunId").asText();
                long revision = value.path("revision").asLong();
                RunEventEnvelope wait = events.stream().filter(event ->
                        event.sequence() > revised.sequence() && event.sequence() < applied.sequence()
                                && trusted(event, "core.run.waiting_child", 1)
                                && command.equals(event.payload().path("commandId").asText())
                                && childId.equals(event.payload().path("childRunId").asText())
                                && event.payload().path("revision").asLong() == revision)
                        .findFirst().orElse(null);
                RunEventEnvelope accepted = events.stream().filter(event ->
                        event.sequence() < revised.sequence()
                                && trusted(event, "core.interaction.control_accepted", 1)
                                && command.equals(event.payload().path("commandId").asText())
                                && "AMEND".equals(event.payload().path("type").asText())
                                && positiveLong(event.payload().path("revision"))
                                && event.payload().path("revision").asLong() == revision - 1
                                && text(event.payload().path("text"))
                                && text(event.payload().path("childRunId")))
                        .findFirst().orElse(null);
                if (wait == null || accepted == null) continue;
                // A second accepted control cannot be silently attributed to this revision.
                if (events.stream().anyMatch(event -> event.sequence() > accepted.sequence()
                        && event.sequence() < applied.sequence()
                        && trusted(event, "core.interaction.control_accepted", 1))) continue;
                JsonNode output = wait.payload().path("output");
                if (!"interaction.waiting_child".equals(output.path("kind").asText())
                        || !childId.equals(output.path("childRunId").asText())
                        || output.path("revision").asLong() != revision
                        || !text(output.path("taskId")) || !text(output.path("invocationId"))) continue;
                String oldId = accepted.payload().path("childRunId").asText();
                RunEventEnvelope oldWait = events.stream().filter(event ->
                        event.sequence() < accepted.sequence() && trusted(event, "core.run.waiting_child", 1))
                        .reduce((first, second) -> second).orElse(null);
                JsonNode oldOutput = oldWait == null ? JsonNodeFactory.instance.objectNode()
                        : oldWait.payload().path("output");
                if (!oldId.equals(oldOutput.path("childRunId").asText())
                        || oldOutput.path("revision").asLong() != revision - 1
                        || !output.path("taskId").equals(oldOutput.path("taskId"))
                        || !output.path("invocationId").equals(oldOutput.path("invocationId"))) continue;
                StoredRun old = runs.find(new RunId(oldId)).orElse(null);
                StoredRun child = runs.find(new RunId(childId)).orElse(null);
                if (!owned(parentId, parent.request(), old) || !owned(parentId, parent.request(), child)
                        || !runs.readable(old.request().scope()) || !runs.readable(child.request().scope())
                        || !old.request().scope().equals(child.request().scope())
                        || !old.request().source().equals(child.request().source())) continue;
                InteractionTask previous = json.treeToValue(old.request().attributes()
                        .get(InteractionExecutionPolicy.TASK_ATTRIBUTE), InteractionTask.class);
                InteractionTask task = json.treeToValue(child.request().attributes()
                        .get(InteractionExecutionPolicy.TASK_ATTRIBUTE), InteractionTask.class);
                String human = accepted.payload().path("text").asText();
                String goal = previous.goal() + "\n用户修订：" + human;
                if (previous.revision() != revision - 1 || task.revision() != revision
                        || !task.taskId().equals(previous.taskId())
                        || !task.taskId().equals(output.path("taskId").asText())
                        || task.mode() != previous.mode() || !task.goal().equals(goal)
                        || !task.necessaryData().equals(previous.necessaryData())
                        || !task.constraints().equals(previous.constraints())) continue;
                RunEventEnvelope priorEvent = latestContract(events, accepted.sequence());
                if (priorEvent == null) continue;
                TaskContractV3 prior = json.treeToValue(priorEvent.payload(), TaskContractV3.class);
                TaskContractV3 frozen = json.treeToValue(child.request().attributes()
                        .get("framework.taskContract"), TaskContractV3.class);
                List<TaskCriterionV3> interaction = contract.criteria().stream()
                        .filter(InteractionHumanAmendment::interaction).toList();
                if (interaction.isEmpty() || !frozen.reliable() || !frozen.applicable()
                        || !frozen.originalRequest().equals(goal)
                        || !frozen.criteria().equals(interaction)
                        || frozen.desktopObservationPolicy() != contract.desktopObservationPolicy()
                        || frozen.intentStatus() != contract.intentStatus()
                        || !task.acceptanceCriterionIds().equals(interaction.stream().map(TaskCriterionV3::id).toList())
                        || interaction.stream().anyMatch(criterion -> !criterion.id().startsWith("ir" + revision + "."))
                        || !contract.originalRequest().equals(prior.originalRequest() + "\n交互任务修订：" + goal)
                        || !replaceInteraction(prior.criteria(), interaction).equals(contract.criteria())) continue;
                // Historical revisions are checked against their own frozen facts above. Only
                // the current child can be checked against the parent's current contract.
                RunEventEnvelope current = latestContract(runs.eventsAfter(parentId, 0), Long.MAX_VALUE);
                if (current != null && current.sequence() == revised.sequence())
                    InteractionRequestGuard.validate(runs, json, child.request());
                return Optional.of(new Revision(revised.sequence(), command, revision, childId,
                        task.taskId(), output.path("invocationId").asText(), human));
            }
        } catch (Exception invalid) {
            // Incomplete or foreign provenance cannot authorize replay with a different task snapshot.
        }
        return Optional.empty();
    }

    public static boolean acceptedAfter(RunStore runs, RunId parentId, long sequence) {
        return runs.eventsAfter(parentId, sequence).stream().anyMatch(event ->
                parentId.value().equals(event.runId())
                        && trusted(event, "core.interaction.control_accepted", 1)
                        && "AMEND".equals(event.payload().path("type").asText()));
    }

    /** A reasoning-only input view; stored input, media blocks and all execution ceilings stay intact. */
    public static RunRequest projectHumanInput(RunRequest request, Revision revision) {
        List<InputBlock> inputs = new ArrayList<>();
        boolean inserted = false;
        for (InputBlock block : request.inputs()) {
            if (block.type().equals("core.text")) {
                if (!inserted) inputs.add(InputBlock.text(revision.text()));
                inserted = true;
            } else inputs.add(block);
        }
        if (!inserted) inputs.addFirst(InputBlock.text(revision.text()));
        return new RunRequest(request.agent(), request.profile(), request.source(), request.scope(), inputs,
                request.linkage(), request.permissionCeiling(), request.budget(), request.idempotencyKey(),
                request.attributes());
    }

    private static RunEventEnvelope latestContract(List<RunEventEnvelope> events, long before) {
        return events.stream().filter(event -> event.sequence() < before
                        && (trusted(event, "core.task.contract", 3)
                            || trusted(event, "core.task.contract_revised", 3)))
                .reduce((first, second) -> second).orElse(null);
    }

    private static boolean owned(RunId parentId, RunRequest parent, StoredRun child) {
        return child != null && parentId.equals(child.request().linkage().parentRunId())
                && InteractionExecutionPolicy.isInteraction(child.request())
                && InteractionDelegateCoordinator.childScope(parent.scope()).equals(child.request().scope());
    }

    private static List<TaskCriterionV3> replaceInteraction(List<TaskCriterionV3> prior,
            List<TaskCriterionV3> replacement) {
        List<TaskCriterionV3> result = new ArrayList<>();
        boolean inserted = false;
        for (TaskCriterionV3 criterion : prior) {
            if (interaction(criterion)) {
                if (!inserted) result.addAll(replacement);
                inserted = true;
            } else result.add(criterion);
        }
        return List.copyOf(result);
    }

    private static boolean interaction(TaskCriterionV3 criterion) {
        return criterion.capabilityId().startsWith("browser.") || criterion.capabilityId().startsWith("desktop.");
    }

    private static boolean trusted(RunEventEnvelope event, String type, int version) {
        return event.type().equals(type) && event.schemaVersion() == version
                && event.producer().equals("framework.core");
    }

    private static boolean positiveLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0;
    }

    private static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
}
