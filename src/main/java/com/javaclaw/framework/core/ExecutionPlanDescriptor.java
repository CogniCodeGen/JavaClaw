package com.javaclaw.framework.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.spi.AdvisorSpec;
import com.javaclaw.framework.spi.ExtensionLock;

import java.util.List;
import java.util.Map;

/** Run 启动时锁定的可序列化计划；上下文策略为 null 时关闭该能力。 */
public record ExecutionPlanDescriptor(
        String id,
        AgentDefinitionRef definition,
        RunProfileRef profile,
        String definitionChecksum,
        String profileChecksum,
        long extensionGeneration,
        List<ExtensionLock> extensionLocks,
        String modelPolicyRef,
        Map<String, String> promptSections,
        String promptFingerprint,
        Map<CapabilityId, JsonNode> compiledCapabilities,
        JsonNode toolPolicy,
        PermissionSet permissions,
        RunBudget budget,
        @JsonProperty(required = true) StepContextPolicy stepContextPolicy,
        @JsonProperty(required = true) OnDemandContextPolicy onDemandContextPolicy,
        @JsonProperty(required = true) List<String> fixedContextSourceIds,
        List<AdvisorSpec> advisors,
        JsonNode outputContract,
        String checksum) {

    public ExecutionPlanDescriptor {
        extensionLocks = List.copyOf(extensionLocks);
        promptSections = Map.copyOf(promptSections);
        java.util.LinkedHashMap<CapabilityId, JsonNode> capabilityCopies =
                new java.util.LinkedHashMap<>();
        compiledCapabilities.forEach((capabilityId, value) ->
                capabilityCopies.put(capabilityId, value.deepCopy()));
        compiledCapabilities = Map.copyOf(capabilityCopies);
        toolPolicy = toolPolicy.deepCopy();
        fixedContextSourceIds = List.copyOf(fixedContextSourceIds);
        advisors = List.copyOf(advisors);
        outputContract = outputContract.deepCopy();
    }

    @Override public Map<CapabilityId, JsonNode> compiledCapabilities() {
        java.util.LinkedHashMap<CapabilityId, JsonNode> copied = new java.util.LinkedHashMap<>();
        compiledCapabilities.forEach((id, value) -> copied.put(id, value.deepCopy()));
        return Map.copyOf(copied);
    }

    @Override public JsonNode toolPolicy() { return toolPolicy.deepCopy(); }
    @Override public JsonNode outputContract() { return outputContract.deepCopy(); }
}
