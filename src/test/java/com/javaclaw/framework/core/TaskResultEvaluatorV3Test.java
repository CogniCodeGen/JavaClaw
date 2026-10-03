package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.ModelDecisionV1;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskCriterion;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskResultEvaluatorV3Test {
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();

    @Test
    void anExactHostCapabilityAndReceiptCanBeVerified() {
        TaskContractV3 contract = contract("email.send", "person@example.com",
                EffectReceiptV1.Status.ACCEPTED);
        var receipt = receipt(1, "email_send", "send", "person@example.com", "ACCEPTED",
                "framework.core");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluateV3(contract, List.of(receipt), "", CAPABILITIES).outcome());
    }

    @Test
    void aSimilarStringOrDifferentCapabilityCannotSatisfyTheContract() {
        TaskContractV3 contract = contract("email.send", "person@example.com",
                EffectReceiptV1.Status.ACCEPTED);
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV3(contract,
                List.of(receipt(1, "email_send", "send", "person@example.com.evil", "ACCEPTED",
                        "framework.core")), "", CAPABILITIES).outcome());
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV3(contract,
                List.of(receipt(1, "custom_send", "send", "person@example.com", "ACCEPTED",
                        "framework.core")), "", CAPABILITIES).outcome());
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV3(contract,
                List.of(receipt(1, "email_send", "send", "person@example.com", "ACCEPTED",
                        "plugin")), "", CAPABILITIES).outcome());
    }

    @Test
    void aCriterionWithTheWrongTargetTypeCannotUseAnOtherwiseMatchingReceipt() {
        TaskContractV3 contract = new TaskContractV3(3, "request", List.of(
                new TaskCriterionV3("condition", "Requested effect", "email.send",
                        CapabilityMetadata.TargetKind.FILE, "person@example.com",
                        EffectReceiptV1.Status.ACCEPTED, "")), true, true, "definition");
        var matchingReceipt = receipt(1, "email_send", "send", "person@example.com",
                "ACCEPTED", "framework.core");

        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV3(contract,
                List.of(matchingReceipt), "", CAPABILITIES).outcome());
    }

    @Test
    void desktopApplicationIdMatchesAcrossDisplayNamesInV3AndLinkedV2Proof() {
        TaskContractV3 contract = new TaskContractV3(3, "open Notes", List.of(
                new TaskCriterionV3("open", "Notes window", "desktop.open",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                        "com.example.notes", EffectReceiptV1.Status.ACCEPTED, "")),
                true, true, "definition");
        var payload = JsonNodeFactory.instance.objectNode()
                .put("invocationId", "open-1").put("tool", "desktop_session_open")
                .put("operation", "open").put("target", "Notes")
                .put("status", "ACCEPTED").put("evidenceRef", "desktop:open-1")
                .put("observedAt", Instant.EPOCH.toString());
        payload.putObject("metadata").put("applicationId", "com.example.notes")
                .put("sessionId", "session-1").put("targetId", "window-1");
        var open = event(1, "core.tool.receipt", "framework.core", payload);
        TaskContractV2 linked = new TaskContractV2(2, "open Notes", "", List.of(
                new TaskCriterion("open", "Notes window", "com.example.notes",
                        "open", "ACCEPTED", "")), true, true, "definition");

        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluateV2(linked, List.of(open), "").outcome());
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluateV3(contract, List.of(open), "", CAPABILITIES).outcome());
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("metadata"))
                .put("applicationId", "com.other.notes");
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluateV3(contract,
                        List.of(event(1, "core.tool.receipt", "framework.core", payload)),
                        "", CAPABILITIES).outcome());
    }

    @Test
    void desktopRollbackUsesCriterionIdWhenDescriptionsRepeat() {
        TaskContractV3 contract = new TaskContractV3(3, "request", List.of(
                new TaskCriterionV3("file", "Shared label", "file.write",
                        CapabilityMetadata.TargetKind.FILE, "result.txt",
                        EffectReceiptV1.Status.VERIFIED, ""),
                new TaskCriterionV3("desktop", "Shared label", "desktop.observe",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "Notes",
                        EffectReceiptV1.Status.OBSERVED, "")), true, true, "definition");
        var fileReceipt = receipt(1, "sys_file_write", "write", "result.txt",
                "VERIFIED", "framework.core");

        var result = TaskResultEvaluator.evaluateV3(contract,
                List.of(fileReceipt), "", CAPABILITIES);

        assertEquals(TaskOutcome.PARTIAL, result.outcome());
        assertEquals(List.of("Shared label"), result.satisfiedCriteria());
        assertEquals(List.of("receipt-1"), result.evidenceRefs());
        assertEquals(1, result.unmetCriteria().size());
    }

    @Test
    void aCapabilityCannotClaimEvidenceBeyondItsHostCeiling() {
        TaskContractV3 contract = contract("command.execute", "command",
                EffectReceiptV1.Status.VERIFIED);
        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV3(contract,
                List.of(receipt(1, "cmd_execute", "execute", "command", "VERIFIED",
                        "framework.core")), "", CAPABILITIES).outcome());
    }

    @Test
    void aBudgetStopWithoutAnIndependentBlockedDecisionRemainsUnverified() {
        TaskContractV3 contract = contract("email.send", "person@example.com",
                EffectReceiptV1.Status.ACCEPTED);
        var evidence = TaskResultEvaluator.evaluateV3(contract, List.of(),
                "BUDGET_EXHAUSTED", CAPABILITIES);

        assertEquals(TaskOutcome.UNVERIFIED, evidence.outcome());
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.gateWithModelDecision(evidence, List.of()).outcome());
    }

    @Test
    void anUnreliableQuestionContractCannotBecomeDeliveredFromClaimDone() {
        TaskContractV3 contract = new TaskContractV3(3, "answer", List.of(),
                false, false, "unreliable");
        var controlInput = JsonNodeFactory.instance.objectNode().put("phase", "harness.decision")
                .put("modelStepId", "model-1").put("invocationId", "decision-1");
        var decision = new ModelDecisionV1(ModelDecisionV1.Decision.CLAIM_DONE,
                "answer", List.of());
        var events = List.of(
                event(1, "core.step.completed", "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", "model-1")),
                event(2, "core.step.started", "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", "control-1")
                                .put("kind", "ORCHESTRATION").set("input", controlInput)),
                event(3, "core.harness.decision_submitted", "framework.springai",
                        JsonNodeFactory.instance.objectNode().put("modelStepId", "model-1")
                                .put("invocationId", "decision-1").set("value", decision.toJson())),
                event(4, "core.step.completed", "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", "control-1")));

        var evidence = TaskResultEvaluator.evaluateV3(contract, events, "", CAPABILITIES);
        assertEquals(TaskOutcome.UNVERIFIED, evidence.outcome());
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.gateWithModelDecision(evidence, events).outcome());
    }

    @Test
    void ordinaryCompletionWordsCannotReplaceTheControlDecision() {
        TaskContractV3 contract = contract("email.send", "person@example.com",
                EffectReceiptV1.Status.ACCEPTED);
        var evidence = TaskResultEvaluator.evaluateV3(contract,
                List.of(receipt(1, "email_send", "send", "person@example.com", "ACCEPTED",
                        "framework.core")), "", CAPABILITIES);
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.gateWithModelDecision(evidence, List.of()).outcome());
        assertFalse(ModelDecisionV1.schema().path("properties").has("completionClaim"));
    }

    @Test
    void anEarlierDecisionCannotBeReusedAfterAnotherModelStepStarts() {
        var controlInput = JsonNodeFactory.instance.objectNode().put("phase", "harness.decision")
                .put("modelStepId", "model-1").put("invocationId", "decision-1");
        var decision = new ModelDecisionV1(ModelDecisionV1.Decision.CLAIM_DONE,
                "done", List.of());
        var events = List.of(
                event(1, "core.step.completed", "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", "model-1")),
                event(2, "core.step.started", "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", "control-1")
                                .put("kind", "ORCHESTRATION").set("input", controlInput)),
                event(3, "core.harness.decision_submitted", "framework.springai",
                        JsonNodeFactory.instance.objectNode().put("modelStepId", "model-1")
                                .put("invocationId", "decision-1").set("value", decision.toJson())),
                event(4, "core.step.completed", "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", "control-1")),
                event(5, "core.step.started", "framework.core",
                        JsonNodeFactory.instance.objectNode().put("stepId", "model-2")
                                .put("kind", "MODEL")));
        assertFalse(TaskResultEvaluator.latestModelDecision(events).isPresent());
    }

    private static RunEventEnvelope event(long sequence, String type, String producer,
            com.fasterxml.jackson.databind.JsonNode payload) {
        return new RunEventEnvelope("run", sequence, Instant.EPOCH,
                type, 1, producer, "correlation", null, payload);
    }

    private static TaskContractV3 contract(String capability, String target,
            EffectReceiptV1.Status required) {
        CapabilityMetadata.TargetKind targetType = CAPABILITIES.find(capability)
                .map(descriptor -> CapabilityMetadata.TargetKind.valueOf(
                        descriptor.targetKind().name())).orElse(CapabilityMetadata.TargetKind.RESOURCE);
        return new TaskContractV3(3, "request", List.of(new TaskCriterionV3(
                "condition", "Requested effect", capability, targetType, target, required, "")),
                true, true, "definition");
    }

    private static RunEventEnvelope receipt(long sequence, String tool, String operation,
            String target, String status, String producer) {
        var payload = JsonNodeFactory.instance.objectNode().put("invocationId", "invocation-" + sequence)
                .put("tool", tool).put("operation", operation).put("target", target)
                .put("status", status).put("observedAt", Instant.EPOCH.toString())
                .put("evidenceRef", "receipt-" + sequence).put("subject", "");
        return new RunEventEnvelope("run", sequence, Instant.EPOCH,
                "core.tool.receipt", 1, producer, "correlation", null, payload);
    }
}
