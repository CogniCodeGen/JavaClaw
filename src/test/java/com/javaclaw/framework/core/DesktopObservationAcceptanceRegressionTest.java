package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterion;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.spi.EffectReceiptV1;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 回归真实桌面观察已成功，但未派发输入使验收反复补做的故障。 */
class DesktopObservationAcceptanceRegressionTest {
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();
    private static final String FIRST = "123e4567-e89b-42d3-a456-426614174000";
    private static final String SECOND = "123e4567-e89b-42d3-a456-426614174001";

    @Test
    void 未派发的失败点击不抹掉已观察到的模型列表() {
        var result = TaskResultEvaluator.evaluateV3(contract(), List.of(
                launch(1), observe(2, FIRST), action(3, "FAILED", "false", "NOT_SENT")),
                "", CAPABILITIES);

        assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
        assertEquals(List.of("receipt-1", "receipt-2"), result.evidenceRefs());
    }

    @ParameterizedTest
    @CsvSource({"STALE_FRAME,PLATFORM_FAILURE", "FAILED,STALE_OBSERVATION",
            "FAILED,INVALID_TARGET", "DENIED,DELIVERY_UNCERTAIN", "FAILED,"})
    void 已知画面失效的拒绝仍需重观察且新观察可恢复验收(String desktopStatus, String reasonCode) {
        ObjectNode rejectedPayload = (ObjectNode) action(3, "FAILED", "false", "NOT_SENT").payload();
        ((ObjectNode) rejectedPayload.path("metadata"))
                .put("desktopStatus", desktopStatus).put("reasonCode", reasonCode);
        var rejection = event(3, rejectedPayload);
        var before = List.of(launch(1), observe(2, FIRST), rejection);

        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluateV3(contract(), before, "", CAPABILITIES).outcome());
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV3(contract(),
                List.of(launch(1), observe(2, FIRST), rejection, observe(4, SECOND)),
                "", CAPABILITIES).outcome());
    }

    @Test
    void 已派发点击之后选择后续有效观察而不是旧回执() {
        var result = TaskResultEvaluator.evaluateV3(contract(), List.of(
                launch(1), observe(2, FIRST), action(3, "ACCEPTED", "true", "SENT"),
                observe(4, SECOND)), "", CAPABILITIES);

        assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
        assertEquals(List.of("receipt-1", "receipt-4"), result.evidenceRefs());
        assertFalse(result.evidenceRefs().contains("receipt-2"));
    }

    @ParameterizedTest
    @CsvSource({"FAILED,true,SENT", "FAILED,false,MAYBE_SENT", "UNKNOWN,false,NOT_SENT",
            "ACCEPTED,false,NOT_SENT", "FAILED,,NOT_SENT", "FAILED,false,"})
    void 只有完整可信的未派发失败回执才能保留此前观察(
            String status, String dispatchAttempted, String delivery) {
        var result = TaskResultEvaluator.evaluateV3(contract(), List.of(
                launch(1), observe(2, FIRST), action(3, status, dispatchAttempted, delivery)),
                "", CAPABILITIES);

        assertEquals(TaskOutcome.PARTIAL, result.outcome());
        assertEquals(List.of("receipt-1"), result.evidenceRefs());
    }

    @Test
    void 会话内未派发失败不会阻断后续的新观察() {
        TaskContractV2 contract = new TaskContractV2(2, "打开应用查看模型", "LM Studio", List.of(
                new TaskCriterion("open", "打开窗口", "LM Studio", "open", "ACCEPTED"),
                new TaskCriterion("view", "查看模型列表", "LM Studio", "observe", "OBSERVED",
                        "Available models")), true, true, "definition");
        ObjectNode open = payload(1, "open", "ACCEPTED");
        open.putObject("metadata").put("sessionId", "session-1").put("targetId", "window-1");

        var result = TaskResultEvaluator.evaluateV2(contract, List.of(event(1, open),
                observe(2, FIRST), action(3, "FAILED", "false", "NOT_SENT"),
                observe(4, SECOND)), "");

        assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
    }

    @Test
    void 未派发失败不能满足明确的点击条件() {
        TaskContractV3 contract = new TaskContractV3(3, "点击模型列表", List.of(
                new TaskCriterionV3("click", "点击模型列表", "desktop.click",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "LM Studio",
                        EffectReceiptV1.Status.ACCEPTED, "")), true, true, "definition");

        assertEquals(TaskOutcome.UNVERIFIED, TaskResultEvaluator.evaluateV3(contract,
                List.of(observe(2, FIRST), action(3, "FAILED", "false", "NOT_SENT")),
                "", CAPABILITIES).outcome());
    }

    private static TaskContractV3 contract() {
        return new TaskContractV3(3, "打开 LM Studio 看有哪些模型", List.of(
                new TaskCriterionV3("launch", "启动应用", "desktop.launch",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "LM Studio",
                        EffectReceiptV1.Status.ACCEPTED, ""),
                new TaskCriterionV3("view", "查看模型列表", "desktop.observe",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "LM Studio",
                        EffectReceiptV1.Status.OBSERVED, "Available models")), true, true, "definition");
    }

    private static RunEventEnvelope launch(long sequence) {
        return event(sequence, payload(sequence, "launch_application", "ACCEPTED"));
    }

    private static RunEventEnvelope observe(long sequence, String observationId) {
        ObjectNode payload = payload(sequence, "observe", "OBSERVED")
                .put("subject", "My Models");
        ObjectNode proof = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1)
                .put("sessionId", "session-1").put("targetId", "window-1")
                .put("observationId", observationId).put("windowGeneration", 1)
                .put("contentRevision", 2).put("capturedAtMillis", sequence * 1000 + 100)
                .put("frameWidth", 100).put("frameHeight", 100);
        ObjectNode condition = proof.putArray("conditions").addObject().put("criterionId", "view")
                .put("subject", "Available models").put("region", "main-content")
                .put("confidence", 0.95);
        condition.putObject("content").put("label", "Fixture model 7B").put("role", "list")
                .put("x", 1).put("y", 20).put("width", 90).put("height", 40)
                .put("confidence", 0.95);
        payload.putObject("metadata").put("sessionId", "session-1").put("targetId", "window-1")
                .put("observationId", observationId).put("windowGeneration", "1")
                .put("contentRevision", "2").put("capturedAtMillis", sequence * 1000 + 100)
                .put("viewEvidence", "heading:1,1,20,10|content:1,20,90,40")
                .put("conditionEvidence", proof.toString());
        return event(sequence, payload);
    }

    private static RunEventEnvelope action(long sequence, String status,
            String dispatchAttempted, String delivery) {
        ObjectNode payload = payload(sequence, "click", status);
        ObjectNode metadata = payload.putObject("metadata").put("sessionId", "session-1")
                .put("targetId", "window-1").put("observationId", FIRST)
                .put("windowGeneration", "1").put("reasonCode", "NO_SEMANTIC_PATH");
        if (dispatchAttempted != null) metadata.put("dispatchAttempted", dispatchAttempted);
        if (delivery != null) metadata.put("delivery", delivery);
        return event(sequence, payload);
    }

    private static ObjectNode payload(long sequence, String operation, String status) {
        return JsonNodeFactory.instance.objectNode().put("invocationId", "call-" + sequence)
                .put("tool", "desktop_session_" + operation).put("operation", operation)
                .put("target", "LM Studio").put("status", status).put("subject", "")
                .put("observedAt", Instant.ofEpochMilli(sequence * 1000).toString())
                .put("evidenceRef", "receipt-" + sequence);
    }

    private static RunEventEnvelope event(long sequence, ObjectNode payload) {
        return new RunEventEnvelope("run", sequence, Instant.ofEpochMilli(sequence * 1000),
                "core.tool.receipt", 1, "framework.core", null, null, payload);
    }
}
