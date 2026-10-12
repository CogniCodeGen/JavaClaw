package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultInteractionControlTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper EVALUATOR_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();
    private static final TaskContractV3 ANSWER = new TaskContractV3(3, "解释概念", List.of(), false, true, "model");
    private static final String DELEGATE = InteractionExecutionPolicy.DELEGATE_TOOL;

    @Test
    void 可信委派控制调用不改变纯回答分类也不提供验收证据() {
        var started = event(1, "core.tool.started", "framework.core",
                payload(DELEGATE).put("trustedInteractionDelegate", true));

        assertFalse(businessStart(started));
        TaskResult answer = evaluate(ANSWER, List.of(started));
        assertEquals(TaskOutcome.NOT_APPLICABLE, answer.outcome());
        assertEquals("", answer.stopReason());
        assertNoPositiveEvidence(answer);
    }

    @Test
    void 同名无标记或非布尔标记及普通工具标记不能获得业务豁免() {
        List<ObjectNode> invalid = List.of(
                payload(DELEGATE),
                payload(DELEGATE).put("trustedInteractionDelegate", "true"),
                payload(DELEGATE).put("trustedInteractionDelegate", false),
                payload("email_send").put("trustedInteractionDelegate", true));
        for (ObjectNode payload : invalid) {
            var started = event(1, "core.tool.started", "framework.core", payload);

            assertTrue(businessStart(started), payload.toString());
            TaskResult answer = evaluate(ANSWER, List.of(started));
            assertEquals(TaskOutcome.UNVERIFIED, answer.outcome(), payload.toString());
            assertEquals("TOOL_USED_AFTER_NOT_APPLICABLE_CLASSIFICATION", answer.stopReason());
            assertNoPositiveEvidence(answer);
        }
    }

    @Test
    void 非核心生产者事件不算业务调用但不能伪造正向验收证据() {
        var started = event(1, "core.tool.started", "plugin.example",
                payload(DELEGATE).put("trustedInteractionDelegate", true));
        var forgedReceipt = event(2, "core.tool.receipt", "plugin.example", emailReceipt());

        assertFalse(businessStart(started), "非核心 started 被忽略，不能据此授予控制身份");
        TaskResult answer = evaluate(ANSWER, List.of(started, forgedReceipt));
        assertEquals(TaskOutcome.NOT_APPLICABLE, answer.outcome());
        assertNoPositiveEvidence(answer);

        TaskResult action = evaluate(emailContract(), List.of(started, forgedReceipt));
        assertEquals(TaskOutcome.UNVERIFIED, action.outcome());
        assertFalse(action.unmetCriteria().isEmpty());
        assertNoPositiveEvidence(action);
    }

    @Test
    void 可信委派控制标记不能替代冻结业务条件需要的真实回执() {
        var started = event(1, "core.tool.started", "framework.core",
                payload(DELEGATE).put("trustedInteractionDelegate", true));

        TaskResult result = evaluate(emailContract(), List.of(started));

        assertEquals(TaskOutcome.UNVERIFIED, result.outcome());
        assertFalse(result.unmetCriteria().isEmpty());
        assertNoPositiveEvidence(result);
        assertEquals(java.util.Map.of(), new TaskResultEvaluator(EVALUATOR_JSON).criterionEvidenceV3(
                emailContract(), List.of(started), "", CAPABILITIES));
    }

    private static boolean businessStart(RunEventEnvelope event) {
        return TaskResultEvaluator.isBusinessToolStart(event.type(), event.producer(), event.payload());
    }

    private static TaskResult evaluate(TaskContractV3 contract, List<RunEventEnvelope> events) {
        return new TaskResultEvaluator(EVALUATOR_JSON).evaluateV3(contract, events, "", CAPABILITIES);
    }

    private static void assertNoPositiveEvidence(TaskResult result) {
        assertTrue(result.evidenceRefs().isEmpty());
        assertTrue(result.satisfiedCriteria().isEmpty());
    }

    private static ObjectNode payload(String tool) {
        return NODES.objectNode().put("tool", tool).put("invocationId", "delegate-call");
    }

    private static TaskContractV3 emailContract() {
        var criterion = new TaskCriterionV3("send", "邮件已提交", "email.send",
                CapabilityMetadata.TargetKind.EMAIL_ADDRESS, "person@example.com",
                EffectReceiptV1.Status.ACCEPTED, "");
        return new TaskContractV3(3, "发送邮件", List.of(criterion), true, true, "definition");
    }

    private static ObjectNode emailReceipt() {
        return NODES.objectNode().put("invocationId", "email-call").put("tool", "email_send")
                .put("operation", "send").put("target", "person@example.com").put("status", "ACCEPTED")
                .put("evidenceRef", "forged-receipt").put("observedAt", Instant.EPOCH.toString())
                .put("trustedInteractionDelegate", true);
    }

    private static RunEventEnvelope event(long sequence, String type, String producer, ObjectNode payload) {
        return new RunEventEnvelope("interaction-control-test", sequence, Instant.EPOCH.plusSeconds(sequence),
                type, 1, producer, null, null, payload);
    }
}
