package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionPlanDescriptorTest {

    @Test
    void 缺少上下文策略字段的计划被拒绝() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var persisted = json.valueToTree(descriptor(StepContextPolicy.DEFAULT));
        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted).remove("stepContextPolicy");
        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted).remove("onDemandContextPolicy");

        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(persisted, ExecutionPlanDescriptor.class));
    }

    @Test
    void 显式空策略持久化后保持未启用状态() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        ExecutionPlanDescriptor restored = json.readValue(
                json.writeValueAsBytes(descriptor(null)), ExecutionPlanDescriptor.class);

        assertNull(restored.stepContextPolicy());
        assertNull(restored.onDemandContextPolicy());
    }

    @Test
    void 缺少按需字段的裁剪计划被拒绝() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var persisted = json.valueToTree(descriptor(StepContextPolicy.DEFAULT));
        ((com.fasterxml.jackson.databind.node.ObjectNode) persisted).remove("onDemandContextPolicy");

        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(persisted, ExecutionPlanDescriptor.class));
    }

    @Test
    void 按需策略随执行计划持久化() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        OnDemandContextPolicy onDemand = new OnDemandContextPolicy(3, 4, 24, 9_000, 14_000, 6);
        ExecutionPlanDescriptor initial = descriptor(StepContextPolicy.DEFAULT, onDemand);

        ExecutionPlanDescriptor restored = json.readValue(
                json.writeValueAsBytes(initial), ExecutionPlanDescriptor.class);

        assertEquals(onDemand, restored.onDemandContextPolicy());
    }

    @Test
    void 缺少工具选择版本的按需计划被拒绝() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var persisted = json.valueToTree(descriptor(
                StepContextPolicy.DEFAULT, OnDemandContextPolicy.DEFAULT));
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                persisted.path("onDemandContextPolicy")).remove("toolSelectionVersion");

        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(persisted, ExecutionPlanDescriptor.class));
    }

    @Test
    void 固定来源必须随计划持久化() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ExecutionPlanDescriptor current = descriptor(StepContextPolicy.DEFAULT,
                OnDemandContextPolicy.DEFAULT);
        var saved = json.valueToTree(current);
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved)
                .putArray("fixedContextSourceIds").add("memory.persona");
        ExecutionPlanDescriptor restored = json.treeToValue(saved, ExecutionPlanDescriptor.class);
        assertEquals(List.of("memory.persona"), restored.fixedContextSourceIds());

        ((com.fasterxml.jackson.databind.node.ObjectNode) saved).remove("fixedContextSourceIds");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> json.treeToValue(saved, ExecutionPlanDescriptor.class));
    }

    @Test
    void 上下文策略可完整持久化和恢复() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        StepContextPolicy policy = new StepContextPolicy(8_000, 9_000, 3, 2_000, 12);

        ExecutionPlanDescriptor restored = json.readValue(
                json.writeValueAsBytes(descriptor(policy)), ExecutionPlanDescriptor.class);

        assertEquals(policy, restored.stepContextPolicy());
        assertFalse(restored.compiledCapabilities().isEmpty());
    }

    private static ExecutionPlanDescriptor descriptor(StepContextPolicy policy) {
        return descriptor(policy, null);
    }

    private static ExecutionPlanDescriptor descriptor(
            StepContextPolicy policy, OnDemandContextPolicy onDemand) {
        var capability = JsonNodeFactory.instance.objectNode().put("enabled", true);
        return new ExecutionPlanDescriptor(
                "plan-test",
                new AgentDefinitionRef("agent", 1L),
                new RunProfileRef("profile", 1L),
                "definition-checksum",
                "profile-checksum",
                1,
                List.of(),
                "model",
                Map.of("system", "prompt"),
                "prompt-checksum",
                Map.of(new CapabilityId("context.compaction"), capability),
                JsonNodeFactory.instance.objectNode(),
                PermissionSet.NONE,
                RunBudget.UNBOUNDED,
                policy,
                onDemand,
                List.of(),
                List.of(),
                JsonNodeFactory.instance.objectNode(),
                "plan-checksum");
    }
}
