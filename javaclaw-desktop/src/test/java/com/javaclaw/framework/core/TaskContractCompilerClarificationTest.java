package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.util.ProjectAccessPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContractCompilerClarificationTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void 首次明确需要人类信息时保留类型和问题且不调用修复() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            assertEquals(1, calls.incrementAndGet());
            assertEquals(ModelTier.LIGHT, task.tier());
            assertTrue(task.input().path("instruction").asText().contains("request's language"));
            return CompletableFuture.completedFuture(result(clarification()));
        };

        TaskContractV3 contract = compile(planner);

        assertEquals(1, calls.get());
        assertClarification(contract, "model");
        assertTrue(contract.criteria().isEmpty());
        TaskContractV3 restored = JSON.treeToValue(JSON.valueToTree(contract), TaskContractV3.class);
        assertEquals(contract, restored);
        assertEquals(TaskContractV3.IntentStatus.NEEDS_HUMAN, restored.intentStatus());
    }

    @Test
    void 修复返回明确澄清结果时不把它标记为修复耗尽() {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            if (calls.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(result(clarification().put("applicable", "true")));
            }
            assertEquals("task.contract.repair.v3", task.purpose());
            assertEquals(ModelTier.NORMAL, task.tier());
            assertTrue(task.input().path("validationReasons").toString().contains("INVALID_PLAN"));
            assertTrue(task.input().path("repairInstruction").asText().contains("request's language"));
            return CompletableFuture.completedFuture(result(clarification()));
        };

        TaskContractV3 contract = compile(planner);

        assertEquals(2, calls.get());
        assertClarification(contract, "model-repair");
    }

    @Test
    void 缺少具体问题或状态互相矛盾的澄清计划仍必须修复() {
        List<Consumer<ObjectNode>> malformed = List.of(
                plan -> plan.putArray("unresolvedInputs").add("   "),
                plan -> plan.remove("reasonCodes"),
                plan -> plan.put("reliable", true));
        for (Consumer<ObjectNode> corrupt : malformed) {
            AtomicInteger calls = new AtomicInteger();
            ModelTaskGateway planner = task -> {
                if (calls.incrementAndGet() == 1) {
                    ObjectNode plan = clarification();
                    corrupt.accept(plan);
                    return CompletableFuture.completedFuture(result(plan));
                }
                assertTrue(task.input().path("validationReasons").toString().contains("INVALID_PLAN"));
                return CompletableFuture.completedFuture(result(resolved()));
            };

            TaskContractV3 contract = compile(planner);

            assertEquals(2, calls.get());
            assertTrue(contract.reliable());
            assertEquals(TaskContractV3.IntentStatus.RESOLVED, contract.intentStatus());
            assertEquals("model-repair", contract.source());
        }
    }

    @Test
    void 澄清类型不能绕过目标能力证据和观察主体校验() {
        var outside = ProjectAccessPolicy.projectRoot().getParent().resolve("outside-task.txt");
        List<InvalidCriterion> cases = List.of(
                new InvalidCriterion(criterion("plugin.claim", "FILE", "output.txt", "VERIFIED", ""),
                        "UNSUPPORTED_CAPABILITY"),
                new InvalidCriterion(criterion("file.write", "FILE", outside.toString(), "VERIFIED", ""),
                        "INVALID_FILE_TARGET"),
                new InvalidCriterion(criterion("file.write", "URL", "output.txt", "VERIFIED", ""),
                        "TARGET_KIND_MISMATCH"),
                new InvalidCriterion(criterion("desktop.launch", "DESKTOP_APPLICATION", "应用", "VERIFIED", ""),
                        "EVIDENCE_CEILING_EXCEEDED"),
                new InvalidCriterion(criterion("desktop.observe", "DESKTOP_APPLICATION", "应用", "OBSERVED", ""),
                        "MISSING_OBSERVABLE_SUBJECT"));
        for (InvalidCriterion invalid : cases) {
            AtomicInteger calls = new AtomicInteger();
            ModelTaskGateway planner = task -> {
                if (calls.incrementAndGet() == 1) {
                    ObjectNode plan = clarification();
                    plan.withArray("criteria").add(invalid.criterion());
                    return CompletableFuture.completedFuture(result(plan));
                }
                assertTrue(task.input().path("validationReasons").toString().contains(invalid.reason()));
                return CompletableFuture.completedFuture(result(resolved()));
            };

            TaskContractV3 contract = compile(planner);

            assertEquals(2, calls.get(), invalid.reason());
            assertTrue(contract.reliable(), invalid.reason());
            assertEquals(TaskContractV3.IntentStatus.RESOLVED, contract.intentStatus());
        }
    }

    @Test
    void 重复无效澄清计划必须降级类型而不能等待人类来绕过校验() {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            calls.incrementAndGet();
            ObjectNode plan = clarification();
            plan.withArray("criteria").add(criterion("plugin.claim", "FILE", "output.txt", "VERIFIED", ""));
            return CompletableFuture.completedFuture(result(plan));
        };

        TaskContractV3 contract = compile(planner);

        assertEquals(2, calls.get());
        assertFalse(contract.reliable());
        assertEquals(TaskContractV3.IntentStatus.UNKNOWN, contract.intentStatus());
        assertTrue(contract.reasonCodes().contains("UNSUPPORTED_CAPABILITY"));
        assertTrue(contract.reasonCodes().contains("PLAN_REPAIR_EXHAUSTED"));
    }

    @Test
    void 不支持的计划即使附带问题文字也不能伪装为澄清结果() {
        AtomicInteger calls = new AtomicInteger();
        ModelTaskGateway planner = task -> {
            calls.incrementAndGet();
            ObjectNode plan = clarification().put("intentStatus", "UNSUPPORTED");
            plan.withArray("criteria").add(criterion("file.write", "FILE", "output.txt", "VERIFIED", ""));
            return CompletableFuture.completedFuture(result(plan));
        };

        TaskContractV3 contract = compile(planner);

        assertEquals(2, calls.get());
        assertEquals(TaskContractV3.IntentStatus.UNSUPPORTED, contract.intentStatus());
        assertFalse(contract.reliable());
        assertTrue(contract.reasonCodes().contains("PLAN_REPAIR_EXHAUSTED"));
    }

    @Test
    void 澄清结果不能吞掉首次规划或修复后的取消() {
        for (int cancelledAt : List.of(1, 2)) {
            AtomicInteger calls = new AtomicInteger();
            AtomicBoolean cancelled = new AtomicBoolean();
            ModelTaskGateway planner = task -> {
                if (calls.incrementAndGet() < cancelledAt) {
                    return CompletableFuture.completedFuture(result(clarification().put("applicable", "true")));
                }
                cancelled.set(true);
                return CompletableFuture.completedFuture(result(clarification()));
            };

            assertThrows(RunCancelledException.class, () -> new TaskContractCompiler(planner, JSON)
                    .compileV3(RunId.random(), request(), cancelled::get));
            assertEquals(cancelledAt, calls.get());
        }
    }

    @Test
    void 显式定义保留类型但不会触发模型规划() {
        TaskContractV3 definition = new TaskContractV3(3, "旧请求", List.of(
                new TaskCriterionV3("write", "文件存在", "file.write", CapabilityMetadata.TargetKind.FILE,
                        "output.txt", EffectReceiptV1.Status.VERIFIED, "")), true, false, "definition",
                List.of("MISSING_TARGET_LOCATION"), List.of("请问项目应保存在哪个目录？"),
                TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT, TaskContractV3.IntentStatus.NEEDS_HUMAN);
        ModelTaskGateway planner = task -> { throw new AssertionError("显式定义不应触发模型规划"); };

        TaskContractV3 contract = new TaskContractCompiler(planner, JSON).compileV3(RunId.random(),
                request().withAttribute(TaskContractCompiler.ATTRIBUTE, JSON.valueToTree(definition)), () -> false);

        assertEquals("definition", contract.source());
        assertEquals(TaskContractV3.IntentStatus.NEEDS_HUMAN, contract.intentStatus());
        assertFalse(contract.reliable());
    }

    private static void assertClarification(TaskContractV3 contract, String source) {
        assertEquals(TaskContractV3.IntentStatus.NEEDS_HUMAN, contract.intentStatus());
        assertEquals(source, contract.source());
        assertFalse(contract.reliable());
        assertEquals(List.of("请问项目应保存在哪个目录？"), contract.unresolvedInputs());
        assertTrue(contract.reasonCodes().contains("MISSING_TARGET_LOCATION"));
        assertFalse(contract.reasonCodes().contains("EMPTY_CRITERIA"));
        assertFalse(contract.reasonCodes().contains("MODEL_UNRELIABLE"));
        assertFalse(contract.reasonCodes().contains("PLAN_REPAIR_EXHAUSTED"));
    }

    private static TaskContractV3 compile(ModelTaskGateway planner) {
        return new TaskContractCompiler(planner, JSON).compileV3(RunId.random(), request(), () -> false);
    }

    private static ObjectNode clarification() {
        ObjectNode plan = JSON.createObjectNode().put("applicable", true).put("intentStatus", "NEEDS_HUMAN");
        plan.putArray("reasonCodes").add("MISSING_TARGET_LOCATION");
        plan.putArray("unresolvedInputs").add("请问项目应保存在哪个目录？");
        plan.putArray("criteria");
        return plan;
    }

    private static ObjectNode resolved() {
        ObjectNode plan = JSON.createObjectNode().put("applicable", false).put("intentStatus", "RESOLVED");
        plan.putArray("criteria");
        return plan;
    }

    private static ObjectNode criterion(String capability, String targetType, String target,
                                        String evidence, String subject) {
        return JSON.createObjectNode().put("id", "criterion").put("description", "验收条件")
                .put("capabilityId", capability).put("targetType", targetType).put("target", target)
                .put("requiredEvidence", evidence).put("requiredSubject", subject);
    }

    private static ModelTaskResult result(ObjectNode plan) {
        return new ModelTaskResult(plan, "fixture", 0, 0, false, Map.of());
    }

    private static RunRequest request() {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "thread"))
                .input(InputBlock.text("生成一个 Java 项目")).build();
    }

    private record InvalidCriterion(ObjectNode criterion, String reason) { }
}
