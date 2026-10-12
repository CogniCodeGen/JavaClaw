package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.fixture.DesktopProofFixtures;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopEvidenceJsonConfigurationTest {
    private static final String OBSERVATION = "123e4567-e89b-42d3-a456-426614174000";
    private static final String APPLICATION = "com.example.models";
    private static final String SUBJECT = "模型列表";
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();

    @Test
    void injectedParserConfigurationReachesBothContractSchemas() {
        ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var evaluator = new TaskResultEvaluator(json);
        ObjectNode proof = proof();
        String encoded = DesktopProofFixtures.withTrueDecisions(proof);

        assertAcceptedByBoth(evaluator, receipt(encoded));
        assertRejectedByBoth(evaluator, receipt(encoded + " {}"));
        assertAcceptedByBoth(evaluator, receipt(encoded));
    }

    @Test
    void explicitDecisionAndFrameBindingRemainMandatoryWithTheSharedParser() {
        var evaluator = new TaskResultEvaluator(new ObjectMapper());
        ObjectNode proof = proof();
        assertRejectedByBoth(evaluator, receipt(proof.toString()));
        DesktopProofFixtures.withTrueDecisions(proof);
        proof.put("targetId", "another-window");
        assertRejectedByBoth(evaluator, receipt(proof.toString()));
        assertThrows(NullPointerException.class, () -> new TaskResultEvaluator(null));
        assertThrows(NullPointerException.class, () -> new DesktopConditionProof(null));
    }

    private static void assertAcceptedByBoth(TaskResultEvaluator evaluator, RunEventEnvelope receipt) {
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                evaluator.evaluateV3(contract(), List.of(receipt), "", CAPABILITIES).outcome());
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                evaluator.evaluateV2(TaskResultEvaluator.desktopContract(contract(), CAPABILITIES),
                        List.of(receipt), "").outcome());
    }

    private static void assertRejectedByBoth(TaskResultEvaluator evaluator, RunEventEnvelope receipt) {
        assertEquals(TaskOutcome.UNVERIFIED,
                evaluator.evaluateV3(contract(), List.of(receipt), "", CAPABILITIES).outcome());
        assertEquals(TaskOutcome.UNVERIFIED,
                evaluator.evaluateV2(TaskResultEvaluator.desktopContract(contract(), CAPABILITIES),
                        List.of(receipt), "").outcome());
    }

    private static TaskContractV3 contract() {
        return new TaskContractV3(3, "查看模型列表", List.of(new TaskCriterionV3(
                "models", "模型列表可见", "desktop.observe", CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                APPLICATION, EffectReceiptV1.Status.OBSERVED, SUBJECT)), true, true, "definition");
    }

    private static ObjectNode proof() {
        var proof = frame().put("schemaVersion", 2).put("frameWidth", 100).put("frameHeight", 100);
        var condition = proof.putArray("conditions").addObject().put("criterionId", "models")
                .put("subject", SUBJECT).put("region", "main-content").put("confidence", 0.95);
        condition.putObject("content").put("label", SUBJECT).put("role", "list").put("confidence", 0.95)
                .put("x", 10).put("y", 10).put("width", 40).put("height", 40);
        return proof;
    }

    private static ObjectNode frame() {
        return JsonNodeFactory.instance.objectNode().put("observationId", OBSERVATION)
                .put("sessionId", "session").put("targetId", "window")
                .put("windowGeneration", 1).put("contentRevision", 1).put("capturedAtMillis", 1000);
    }

    private static RunEventEnvelope receipt(String proof) {
        var payload = JsonNodeFactory.instance.objectNode().put("invocationId", "observe")
                .put("tool", "desktop_session_observe").put("operation", "observe").put("target", APPLICATION)
                .put("status", "OBSERVED").put("evidenceRef", "core.tool.completed:run:observe")
                .put("observedAt", Instant.ofEpochMilli(1000).toString()).put("subject", "");
        payload.set("metadata", frame().put("applicationId", APPLICATION).put("conditionEvidence", proof));
        return new RunEventEnvelope("run", 1, Instant.ofEpochMilli(1000), "core.tool.receipt", 1,
                "framework.core", "correlation", null, payload);
    }
}
