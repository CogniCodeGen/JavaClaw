package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskModelDecisionGateBoundaryTest {
    @Test
    void trustedResumeAndContractRevisionInvalidateEarlierCompletionAndBlockedDecisions() {
        for (String type : List.of("core.run.resumed", "core.task.contract_revised")) {
            for (ModelDecisionV1.Decision decision : List.of(ModelDecisionV1.Decision.CLAIM_DONE,
                    ModelDecisionV1.Decision.BLOCKED)) {
                List<RunEventEnvelope> events = new ArrayList<>(decision(1, "old", decision));
                assertEquals(decision, TaskModelDecisionGate.latestModelDecision(events).orElseThrow());
                events.add(boundary(6, type, "framework.core", type.equals("core.run.resumed") ? 1 : 3));

                assertTrue(TaskModelDecisionGate.latestModelDecision(events).isEmpty());
                assertEquals(TaskOutcome.UNVERIFIED,
                        TaskModelDecisionGate.gateWithModelDecision(TaskResult.notApplicable(), events).outcome());
            }
        }
    }

    @Test
    void freshCompletedModelAndControlChainIsAcceptedAfterEachTrustedBoundary() {
        for (String type : List.of("core.run.resumed", "core.task.contract_revised")) {
            List<RunEventEnvelope> events = new ArrayList<>(decision(1, "old", ModelDecisionV1.Decision.BLOCKED));
            events.add(boundary(6, type, "framework.core", type.equals("core.run.resumed") ? 1 : 3));
            events.addAll(decision(7, "new", ModelDecisionV1.Decision.CLAIM_DONE));

            assertEquals(ModelDecisionV1.Decision.CLAIM_DONE,
                    TaskModelDecisionGate.latestModelDecision(events).orElseThrow());
            assertEquals(TaskOutcome.DELIVERED,
                    TaskModelDecisionGate.gateWithModelDecision(TaskResult.notApplicable(), events).outcome());
        }
    }

    @Test
    void untrustedOrWrongVersionBoundaryCannotInvalidateTheCurrentHostDecision() {
        for (String type : List.of("core.run.resumed", "core.task.contract_revised")) {
            for (boolean wrongProducer : List.of(true, false)) {
                List<RunEventEnvelope> events = new ArrayList<>(decision(1, "current", ModelDecisionV1.Decision.CLAIM_DONE));
                events.add(boundary(6, type, wrongProducer ? "plugin.untrusted" : "framework.core",
                        wrongProducer ? type.equals("core.run.resumed") ? 1 : 3 : 2));

                assertEquals(ModelDecisionV1.Decision.CLAIM_DONE,
                        TaskModelDecisionGate.latestModelDecision(events).orElseThrow());
            }
        }
    }

    @Test
    void technicalApprovalAndEmptyResumesPreserveTheExistingDurableControlDecision() {
        for (String type : List.of("delegation.continue", "tool.approval", "managed.continue", "user.input")) {
            List<RunEventEnvelope> events = new ArrayList<>(decision(1, "current", ModelDecisionV1.Decision.CLAIM_DONE));
            var payload = JsonNodeFactory.instance.objectNode().put("commandType", type);
            payload.putObject("command");
            events.add(event(6, "core.run.resumed", 1, "framework.core", payload));

            assertEquals(0, TaskModelDecisionGate.decisionBoundary(events));
            assertEquals(ModelDecisionV1.Decision.CLAIM_DONE,
                    TaskModelDecisionGate.latestModelDecision(events).orElseThrow());
        }
        for (boolean nonTextual : List.of(true, false)) {
            List<RunEventEnvelope> events = new ArrayList<>(decision(1, "current", ModelDecisionV1.Decision.BLOCKED));
            var payload = JsonNodeFactory.instance.objectNode().put("commandType", "user.input");
            var command = payload.putObject("command");
            if (nonTextual) command.put("text", 1);
            else command.put("text", "  ");
            events.add(event(6, "core.run.resumed", 1, "framework.core", payload));

            assertEquals(ModelDecisionV1.Decision.BLOCKED,
                    TaskModelDecisionGate.latestModelDecision(events).orElseThrow());
        }
    }

    @Test
    void aFreshSubmissionCannotReuseACompletedModelFromBeforeClarification() {
        List<RunEventEnvelope> events = new ArrayList<>(decision(1, "old", ModelDecisionV1.Decision.CLAIM_DONE));
        events.add(boundary(6, "core.run.resumed", "framework.core", 1));
        events.addAll(control(7, "new-control", "old-model", ModelDecisionV1.Decision.CLAIM_DONE));

        assertTrue(TaskModelDecisionGate.latestModelDecision(events).isEmpty());
    }

    @Test
    void lateCompletionOfAModelStartedBeforeClarificationCannotBecomeAFreshDecision() {
        List<RunEventEnvelope> events = new ArrayList<>();
        events.add(event(1, "core.step.started", 1, "framework.core",
                JsonNodeFactory.instance.objectNode().put("stepId", "old-model").put("kind", "MODEL")));
        events.add(boundary(2, "core.task.contract_revised", "framework.core", 3));
        events.add(event(3, "core.step.completed", 1, "framework.core",
                JsonNodeFactory.instance.objectNode().put("stepId", "old-model")));
        events.addAll(control(4, "new-control", "old-model", ModelDecisionV1.Decision.CLAIM_DONE));

        assertTrue(TaskModelDecisionGate.latestModelDecision(events).isEmpty());
    }

    @Test
    void unfinishedControlOrModelAndLaterBusinessActivityStillInvalidateDecision() {
        List<RunEventEnvelope> incompleteControl = new ArrayList<>(decision(1, "current", ModelDecisionV1.Decision.CLAIM_DONE));
        incompleteControl.removeLast();
        assertTrue(TaskModelDecisionGate.latestModelDecision(incompleteControl).isEmpty());

        List<RunEventEnvelope> incompleteModel = new ArrayList<>(decision(1, "current", ModelDecisionV1.Decision.CLAIM_DONE));
        incompleteModel.remove(1);
        assertTrue(TaskModelDecisionGate.latestModelDecision(incompleteModel).isEmpty());

        List<RunEventEnvelope> laterTool = new ArrayList<>(decision(1, "current", ModelDecisionV1.Decision.CLAIM_DONE));
        laterTool.add(event(6, "core.tool.started", 1, "framework.core",
                JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_observe")));
        assertTrue(TaskModelDecisionGate.latestModelDecision(laterTool).isEmpty());
    }

    private static List<RunEventEnvelope> decision(long start, String id, ModelDecisionV1.Decision choice) {
        var model = id + "-model";
        List<RunEventEnvelope> events = new ArrayList<>();
        events.add(event(start, "core.step.started", 1, "framework.core",
                JsonNodeFactory.instance.objectNode().put("stepId", model).put("kind", "MODEL")));
        events.add(event(start + 1, "core.step.completed", 1, "framework.core",
                JsonNodeFactory.instance.objectNode().put("stepId", model)));
        events.addAll(control(start + 2, id + "-control", model, choice));
        return events;
    }

    private static List<RunEventEnvelope> control(long start, String id, String model,
            ModelDecisionV1.Decision choice) {
        var input = JsonNodeFactory.instance.objectNode().put("phase", "harness.decision")
                .put("modelStepId", model).put("invocationId", id);
        var begun = JsonNodeFactory.instance.objectNode().put("stepId", id).put("kind", "ORCHESTRATION");
        begun.set("input", input);
        var submitted = JsonNodeFactory.instance.objectNode().put("modelStepId", model).put("invocationId", id);
        submitted.set("value", new ModelDecisionV1(choice, "current decision", List.of(), List.of()).toJson());
        return List.of(event(start, "core.step.started", 1, "framework.core", begun),
                event(start + 1, "core.harness.decision_submitted", 1, "framework.springai", submitted),
                event(start + 2, "core.step.completed", 1, "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", id)));
    }

    private static RunEventEnvelope boundary(long sequence, String type, String producer, int version) {
        var payload = JsonNodeFactory.instance.objectNode();
        if (type.equals("core.run.resumed")) {
            payload.put("commandType", "user.input");
            payload.putObject("command").put("text", "只查看联系人，不执行其他操作");
        }
        return event(sequence, type, version, producer, payload);
    }

    private static RunEventEnvelope event(long sequence, String type, int version,
            String producer, JsonNode payload) {
        return new RunEventEnvelope("run", sequence, Instant.parse("2026-10-03T00:00:00Z"),
                type, version, producer, null, null, payload);
    }
}
