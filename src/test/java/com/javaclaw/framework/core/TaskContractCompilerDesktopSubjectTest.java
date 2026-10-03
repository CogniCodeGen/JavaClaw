package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContractCompilerDesktopSubjectTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();
    private static final String APPLICATION_ID = "com.example.modelviewer";
    private static final String CONTENT_SUBJECT = "本地模型列表";
    private static final String REQUEST = "打开模型查看器并查看本地模型列表";
    private static final String OBSERVATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 模型规划及修复只清除启动与会话条件的内容主题(boolean repair) {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 2) assertEquals("task.contract.repair.v3", task.purpose());
            return CompletableFuture.completedFuture(modelPlan(!repair || attempt == 2, CONTENT_SUBJECT));
        };

        TaskContractV3 compiled = compile(planner);

        assertTrue(compiled.reliable());
        assertEquals(repair ? "model-repair" : "model", compiled.source());
        assertEquals(repair ? 2 : 1, calls.get());
        assertTrue(compiled.reasonCodes().isEmpty());
        assertEquals(TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT,
                compiled.desktopObservationPolicy());
        for (String id : List.of("launch", "open")) {
            TaskCriterionV3 condition = criterion(compiled, id);
            assertEquals("", condition.requiredSubject());
            assertEquals(APPLICATION_ID, condition.target());
            assertEquals(CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, condition.targetType());
            assertEquals(EffectReceiptV1.Status.ACCEPTED, condition.requiredEvidence());
            assertEquals("desktop." + id, condition.capabilityId());
        }
        TaskCriterionV3 observe = criterion(compiled, "models");
        assertEquals(CONTENT_SUBJECT, observe.requiredSubject());
        assertEquals(APPLICATION_ID, observe.target());
        assertEquals(EffectReceiptV1.Status.OBSERVED, observe.requiredEvidence());
        assertEquals("desktop.observe", observe.capabilityId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "different-application", "empty-subject", "wrong-target-type",
            "unsupported-evidence", "snapshot-only"})
    void 缺少同一应用的受支持内容观察条件时保留启动与会话主题并拒绝(String observation) {
        List<TaskCriterionV3> proposed = new ArrayList<>(List.of(
                condition("launch", "desktop.launch", CONTENT_SUBJECT, EffectReceiptV1.Status.ACCEPTED),
                condition("open", "desktop.open", CONTENT_SUBJECT, EffectReceiptV1.Status.ACCEPTED)));
        if (!observation.equals("none")) {
            proposed.add(new TaskCriterionV3("models", "读取模型列表",
                    observation.equals("snapshot-only") ? "desktop.snapshot" : "desktop.observe",
                    observation.equals("wrong-target-type")
                            ? CapabilityMetadata.TargetKind.RESOURCE : CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                    observation.equals("different-application") ? "com.other.modelviewer" : APPLICATION_ID,
                    observation.equals("unsupported-evidence")
                            ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.OBSERVED,
                    observation.equals("empty-subject") ? "" : CONTENT_SUBJECT));
        }
        AtomicInteger calls = new AtomicInteger();

        TaskContractV3 compiled = compile(task -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(modelPlan(true, proposed));
        });

        assertFalse(compiled.reliable());
        assertEquals(2, calls.get(), "不受支持的主题必须触发一次规划修复");
        assertTrue(compiled.reasonCodes().contains("UNSUPPORTED_RECEIPT_SUBJECT"));
        assertTrue(compiled.reasonCodes().contains("PLAN_REPAIR_EXHAUSTED"));
        for (String id : List.of("launch", "open")) {
            assertEquals(CONTENT_SUBJECT, criterion(compiled, id).requiredSubject(),
                    "不能丢弃尚未由独立内容观察承载的唯一内容要求");
            assertEquals(APPLICATION_ID, criterion(compiled, id).target());
            assertEquals(EffectReceiptV1.Status.ACCEPTED, criterion(compiled, id).requiredEvidence());
        }
    }

    @Test
    void 独立观察的应用目标仅大小写不同仍可承载内容要求() {
        TaskCriterionV3 observation = new TaskCriterionV3("models", "读取模型列表", "desktop.observe",
                CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "COM.EXAMPLE.MODELVIEWER",
                EffectReceiptV1.Status.OBSERVED, CONTENT_SUBJECT);

        TaskContractV3 compiled = compile(task -> CompletableFuture.completedFuture(modelPlan(true, List.of(
                condition("launch", "desktop.launch", "应用窗口", EffectReceiptV1.Status.ACCEPTED),
                condition("open", "desktop.open", "应用窗口", EffectReceiptV1.Status.ACCEPTED), observation))));

        assertTrue(compiled.reliable());
        assertEquals("", criterion(compiled, "launch").requiredSubject());
        assertEquals("", criterion(compiled, "open").requiredSubject());
        assertEquals(observation, criterion(compiled, "models"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"desktop.launch", "desktop.open"})
    void 显式声明的启动或会话内容主题不能被静默弱化(String capability) {
        TaskCriterionV3 declared = new TaskCriterionV3("declared", "用户声明的窗口条件", capability,
                CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, APPLICATION_ID,
                EffectReceiptV1.Status.ACCEPTED, "指定的应用窗口");
        TaskContractV3 definition = new TaskContractV3(3, REQUEST, List.of(declared,
                condition("models", "desktop.observe", CONTENT_SUBJECT, EffectReceiptV1.Status.OBSERVED)),
                true, true, "workflow-definition", List.of(), List.of(),
                TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT);

        TaskContractV3 compiled = new TaskContractCompiler(null, JSON).compileV3(RunId.random(),
                request().withAttribute(TaskContractCompiler.ATTRIBUTE, JSON.valueToTree(definition)), () -> false);

        assertFalse(compiled.reliable());
        assertEquals("definition", compiled.source());
        assertTrue(compiled.reasonCodes().contains("UNSUPPORTED_RECEIPT_SUBJECT"));
        assertEquals(declared, compiled.criteria().getFirst());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 缺失观察内容主题仍然被拒绝且不能靠清除启动主题救活(boolean explicit) {
        TaskContractV3 compiled;
        if (explicit) {
            TaskContractV3 definition = new TaskContractV3(3, REQUEST, List.of(
                    condition("launch", "desktop.launch", "", EffectReceiptV1.Status.ACCEPTED),
                    condition("open", "desktop.open", "", EffectReceiptV1.Status.ACCEPTED),
                    condition("models", "desktop.observe", "", EffectReceiptV1.Status.OBSERVED)),
                    true, true, "definition", List.of(), List.of(),
                    TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT);
            compiled = new TaskContractCompiler(null, JSON).compileV3(RunId.random(),
                    request().withAttribute(TaskContractCompiler.ATTRIBUTE, JSON.valueToTree(definition)), () -> false);
        } else {
            AtomicInteger calls = new AtomicInteger();
            compiled = compile(task -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(modelPlan(true, ""));
            });
            assertEquals(2, calls.get());
            assertTrue(compiled.reasonCodes().contains("PLAN_REPAIR_EXHAUSTED"));
        }

        assertFalse(compiled.reliable());
        assertTrue(compiled.reasonCodes().contains("MISSING_OBSERVABLE_SUBJECT"));
        assertEquals("", criterion(compiled, "models").requiredSubject());
        assertEquals(TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT,
                compiled.desktopObservationPolicy());
    }

    @Test
    void 编译后空主题启动回执能通过但只有真实列表观察才算全部完成() {
        TaskContractV3 compiled = compile(task -> CompletableFuture.completedFuture(modelPlan(true, CONTENT_SUBJECT)));
        RunEventEnvelope launch = receipt(1, "launch_application", APPLICATION_ID, "ACCEPTED", "");
        RunEventEnvelope open = receipt(2, "open", APPLICATION_ID, "ACCEPTED", "");

        assertEquals(TaskOutcome.VERIFIED_COMPLETE, evaluate(single(compiled, "launch"), List.of(launch)).outcome());
        var withoutObservation = evaluate(compiled, List.of(launch, open));
        assertEquals(TaskOutcome.PARTIAL, withoutObservation.outcome());
        assertEquals(List.of("启动应用", "建立窗口会话"), withoutObservation.satisfiedCriteria());
        assertTrue(withoutObservation.unmetCriteria().stream().anyMatch(value -> value.contains("读取模型列表")));

        RunEventEnvelope unsupportedView = receipt(3, "observe", APPLICATION_ID, "OBSERVED", CONTENT_SUBJECT);
        assertEquals(TaskOutcome.PARTIAL, evaluate(compiled, List.of(launch, open, unsupportedView)).outcome(),
                "仅回执主题与条件相同，仍不能替代绑定实时帧的内容证明");

        var withObservation = evaluate(compiled, List.of(launch, open, modelListObservation(3)));
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, withObservation.outcome());
        assertEquals(List.of("启动应用", "建立窗口会话", "读取模型列表"), withObservation.satisfiedCriteria());
        assertTrue(withObservation.unmetCriteria().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"launch", "open"})
    void 清除模型生成主题不会允许错误应用身份通过验收(String id) {
        TaskContractV3 compiled = compile(task -> CompletableFuture.completedFuture(modelPlan(true, CONTENT_SUBJECT)));
        String operation = id.equals("launch") ? "launch_application" : "open";
        RunEventEnvelope wrongApplication = receipt(1, operation, "com.other.modelviewer", "ACCEPTED", "");

        assertEquals(TaskOutcome.UNVERIFIED, evaluate(single(compiled, id), List.of(wrongApplication)).outcome());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "FAILED"})
    void 清除模型生成主题不会把未知或失败启动及会话升级为成功(String status) {
        TaskContractV3 compiled = compile(task -> CompletableFuture.completedFuture(modelPlan(true, CONTENT_SUBJECT)));
        for (String id : List.of("launch", "open")) {
            String operation = id.equals("launch") ? "launch_application" : "open";
            assertEquals(TaskOutcome.UNVERIFIED, evaluate(single(compiled, id),
                    List.of(receipt(1, operation, APPLICATION_ID, status, ""))).outcome());
        }
        RunEventEnvelope launch = receipt(1, "launch_application", APPLICATION_ID, status, "");

        var all = evaluate(compiled, List.of(launch,
                receipt(2, "open", APPLICATION_ID, "ACCEPTED", ""), modelListObservation(3)));
        assertFalse(all.outcome() == TaskOutcome.VERIFIED_COMPLETE);
        assertTrue(all.unmetCriteria().stream().anyMatch(value -> value.contains("启动应用")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"core.task.contract", "core.task.contract_revised"})
    void 读取冻结的旧契约不会清除启动主题或把部分完成升级为完成(String eventType) {
        TaskContractV3 frozen = new TaskContractV3(3, REQUEST, List.of(
                condition("launch", "desktop.launch", "应用窗口", EffectReceiptV1.Status.ACCEPTED),
                condition("open", "desktop.open", "", EffectReceiptV1.Status.ACCEPTED),
                condition("models", "desktop.observe", CONTENT_SUBJECT, EffectReceiptV1.Status.OBSERVED)),
                true, true, "model", List.of(), List.of(),
                TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT);
        RunEventEnvelope persisted = new RunEventEnvelope("test-run", 1, Instant.EPOCH,
                eventType, 3, "framework.core", null, null, JSON.valueToTree(frozen));
        List<RunEventEnvelope> events = List.of(persisted,
                receipt(2, "launch_application", APPLICATION_ID, "ACCEPTED", ""),
                receipt(3, "open", APPLICATION_ID, "ACCEPTED", ""), modelListObservation(4));

        TaskContractV3 read = TaskResultEvaluator.latestContractV3(events, JSON).orElseThrow();
        var result = evaluate(read, events);

        assertEquals(frozen, read);
        assertEquals("应用窗口", criterion(read, "launch").requiredSubject());
        assertEquals(TaskOutcome.PARTIAL, result.outcome());
        assertEquals(List.of("建立窗口会话", "读取模型列表"), result.satisfiedCriteria());
        assertTrue(result.unmetCriteria().stream().anyMatch(value -> value.contains("启动应用")));
    }

    @Test
    void 桌面启动主题规范化不会改变其他能力的主题要求() {
        TaskCriterionV3 email = new TaskCriterionV3("email", "发送列表报告", "email.send",
                CapabilityMetadata.TargetKind.EMAIL_ADDRESS, "report@example.com",
                EffectReceiptV1.Status.ACCEPTED, "模型列表报告");
        TaskContractV3 compiled = compile(task -> CompletableFuture.completedFuture(modelPlan(true, List.of(
                condition("launch", "desktop.launch", "应用窗口", EffectReceiptV1.Status.ACCEPTED),
                condition("models", "desktop.observe", CONTENT_SUBJECT, EffectReceiptV1.Status.OBSERVED), email))));

        assertTrue(compiled.reliable());
        assertEquals("", criterion(compiled, "launch").requiredSubject());
        assertEquals(email, criterion(compiled, "email"));
        assertEquals(CONTENT_SUBJECT, criterion(compiled, "models").requiredSubject());
    }

    private static TaskContractV3 compile(ModelTaskGateway planner) {
        return new TaskContractCompiler(planner, JSON).compileV3(RunId.random(), request(), () -> false);
    }

    private static RunRequest request() {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.chat())
                .scope(new RunScope("test-workspace", "test-user", "test-thread"))
                .input(InputBlock.text(REQUEST)).build();
    }

    private static ModelTaskResult modelPlan(boolean reliable, String observationSubject) {
        return modelPlan(reliable, List.of(
                condition("launch", "desktop.launch", "应用窗口", EffectReceiptV1.Status.ACCEPTED),
                condition("open", "desktop.open", "应用窗口", EffectReceiptV1.Status.ACCEPTED),
                condition("models", "desktop.observe", observationSubject, EffectReceiptV1.Status.OBSERVED)));
    }

    private static ModelTaskResult modelPlan(boolean reliable, List<TaskCriterionV3> criteria) {
        ObjectNode output = JSON.createObjectNode().put("applicable", true).put("reliable", reliable);
        output.set("criteria", JSON.valueToTree(criteria));
        return new ModelTaskResult(output, "test-model", 0, 0, false, Map.of());
    }

    private static TaskCriterionV3 condition(String id, String capability, String subject,
            EffectReceiptV1.Status evidence) {
        String description = switch (id) {
            case "launch" -> "启动应用";
            case "open" -> "建立窗口会话";
            default -> "读取模型列表";
        };
        return new TaskCriterionV3(id, description, capability, CapabilityMetadata.TargetKind.DESKTOP_APPLICATION,
                APPLICATION_ID, evidence, subject);
    }

    private static TaskCriterionV3 criterion(TaskContractV3 contract, String id) {
        return contract.criteria().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static TaskContractV3 single(TaskContractV3 contract, String id) {
        return new TaskContractV3(3, contract.originalRequest(), List.of(criterion(contract, id)),
                true, contract.reliable(), contract.source(), contract.reasonCodes(), contract.unresolvedInputs(),
                contract.desktopObservationPolicy());
    }

    private static com.javaclaw.framework.api.TaskResult evaluate(TaskContractV3 contract,
            List<RunEventEnvelope> events) {
        return TaskResultEvaluator.evaluateV3(contract, events, "", CAPABILITIES);
    }

    private static RunEventEnvelope receipt(long sequence, String operation, String applicationId,
            String status, String subject) {
        ObjectNode payload = JSON.createObjectNode().put("invocationId", "test-call-" + sequence)
                .put("tool", "desktop_session_" + operation).put("operation", operation)
                .put("target", "模型查看器").put("status", status).put("subject", subject)
                .put("evidenceRef", "test-evidence:" + sequence)
                .put("observedAt", Instant.ofEpochSecond(sequence).toString());
        ObjectNode metadata = payload.putObject("metadata").put("applicationId", applicationId);
        if (!operation.equals("launch_application")) {
            metadata.put("sessionId", "test-session").put("targetId", "test-window");
        }
        if (operation.equals("observe")) {
            metadata.put("observationId", OBSERVATION_ID).put("windowGeneration", "1")
                    .put("contentRevision", "1").put("capturedAtMillis", Long.toString(sequence * 1_000));
        }
        return event(sequence, payload);
    }

    private static RunEventEnvelope modelListObservation(long sequence) {
        ObjectNode payload = (ObjectNode) receipt(sequence, "observe", APPLICATION_ID, "OBSERVED", "My Models").payload();
        ObjectNode metadata = (ObjectNode) payload.path("metadata");
        ObjectNode proof = JSON.createObjectNode().put("schemaVersion", 1).put("frameWidth", 800).put("frameHeight", 600);
        for (String field : List.of("sessionId", "targetId", "observationId", "windowGeneration",
                "contentRevision", "capturedAtMillis")) proof.set(field, metadata.path(field));
        ObjectNode content = proof.putArray("conditions").addObject().put("criterionId", "models")
                .put("subject", CONTENT_SUBJECT).put("region", "main-content").put("confidence", 0.95)
                .putObject("content");
        content.put("label", "模型甲 模型乙").put("role", "table").put("x", 10).put("y", 100)
                .put("width", 600).put("height", 300).put("confidence", 0.95);
        metadata.put("conditionEvidence", proof.toString());
        return event(sequence, payload);
    }

    private static RunEventEnvelope event(long sequence, ObjectNode payload) {
        return new RunEventEnvelope("test-run", sequence, Instant.ofEpochSecond(sequence),
                "core.tool.receipt", 1, "framework.core", null, null, payload);
    }
}
