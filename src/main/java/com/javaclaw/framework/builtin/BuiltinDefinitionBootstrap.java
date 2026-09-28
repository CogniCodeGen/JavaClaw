package com.javaclaw.framework.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.api.ModelPolicyRefs;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.extension.ExtensionRegistrySnapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Idempotently seeds read-only system definitions and profiles for one workspace. */
public final class BuiltinDefinitionBootstrap {
    public static final String SYSTEM_AGENT_ID = "system.default";
    public static final Set<String> SYSTEM_PROFILE_IDS = Set.of(
            "chat", "plan", "schedule", "plugin", "loop", "sdd", "subagent");

    private final String workspaceId;
    private final JdbcAgentDefinitionStore definitions;
    private final ModelPolicyRefs models;
    private final ExtensionManager extensions;

    public BuiltinDefinitionBootstrap(
            String workspaceId,
            JdbcAgentDefinitionStore definitions,
            ModelPolicyRefs models,
            ExtensionManager extensions) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.models = Objects.requireNonNull(models, "models");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        ensureInstalled();
    }

    /** Reinstalls the complete built-in set atomically when a latest reference is missing. */
    public synchronized void ensureInstalled() {
        var extensionLease = extensions.acquireCurrent();
        try {
            install(extensionLease.snapshot());
        } finally {
            extensionLease.close();
        }
    }

    private void install(ExtensionRegistrySnapshot snapshot) {
        RunBudget interactiveBudget = new RunBudget(
                Duration.ofMinutes(30), 250_000, 80_000, 120, new BigDecimal("100"));
        RunBudget unattendedBudget = new RunBudget(
                Duration.ofHours(4), 1_000_000, 300_000, 500, new BigDecimal("500"));

        List<RunProfileDraft> profiles = new ArrayList<>();
        profiles.add(new RunProfileDraft(
                "chat", "Interactive Chat", PermissionSet.UNRESTRICTED,
                interactiveBudget, Map.of(), object()));
        profiles.add(new RunProfileDraft(
                "plan", "Read-only Plan", PermissionSet.UNRESTRICTED,
                interactiveBudget, Map.of(new CapabilityId("plan.readonly"), enabled()), object()));
        profiles.add(new RunProfileDraft(
                "schedule", "Unattended Schedule", PermissionSet.UNRESTRICTED,
                unattendedBudget, Map.of(), object()));
        profiles.add(new RunProfileDraft(
                "plugin", "Plugin Invocation", PermissionSet.UNRESTRICTED,
                interactiveBudget, Map.of(), object()));
        profiles.add(new RunProfileDraft(
                "loop", "Loop Workflow Agent", PermissionSet.UNRESTRICTED,
                unattendedBudget, Map.of(), object()));
        profiles.add(new RunProfileDraft(
                "sdd", "SDD Workflow Agent", PermissionSet.UNRESTRICTED,
                unattendedBudget, Map.of(), object()));
        profiles.add(new RunProfileDraft(
                "subagent", "Child Agent", PermissionSet.UNRESTRICTED,
                interactiveBudget, Map.of(new CapabilityId("subagent.run"), enabled()), object()));

        snapshot.contributions().runProfiles().forEach(owned -> {
            RunProfileDraft contributed = owned.value().profile();
            if (SYSTEM_PROFILE_IDS.contains(contributed.id())) {
                throw new IllegalStateException("extension cannot replace system profile: "
                        + contributed.id());
            }
            if (profiles.stream().anyMatch(profile -> profile.id().equals(contributed.id()))) {
                throw new IllegalStateException("duplicate extension run profile: "
                        + contributed.id());
            }
            profiles.add(contributed);
        });

        Map<CapabilityId, JsonNode> capabilities = new LinkedHashMap<>();
        capabilities.put(new CapabilityId("memory.graph"), enabled());
        capabilities.put(new CapabilityId("memory.recall"), enabled());
        capabilities.put(new CapabilityId("memory.correction"), enabled());
        capabilities.put(new CapabilityId("memory.distillation"), enabled());
        capabilities.put(new CapabilityId("memory.habit"), enabled());
        capabilities.put(new CapabilityId("gepa.evaluate"), enabled());
        capabilities.put(new CapabilityId("gepa.revise"), enabled());
        capabilities.put(new CapabilityId("gepa.goal"), enabled());
        capabilities.put(new CapabilityId("knowledge.rag"), enabled());
        capabilities.put(new CapabilityId("skill.runtime"), enabled());
        capabilities.put(new CapabilityId("context.compaction"), contextCompaction());
        capabilities.put(new CapabilityId("context.on_demand"), contextOnDemand());
        capabilities.put(new CapabilityId("tool.result-eviction"), enabled());
        capabilities.put(new CapabilityId("mcp.tools"), enabled());
        capabilities.put(new CapabilityId("host.tools"), enabled());
        capabilities.put(new CapabilityId("subagent.run"), enabled());

        Map<String, String> ranges = new LinkedHashMap<>();
        capabilities.keySet().forEach(id -> ranges.put(id.value(), ">=2.0.0 <3.0.0"));
        AgentDefinitionDraft agent = new AgentDefinitionDraft(
                SYSTEM_AGENT_ID, "JavaClaw Assistant", models.high(),
                Map.of(
                        "identity", "You are JavaClaw, a capable workspace assistant.",
                        "execution", "Use tools only when needed. Keep every action within the run permissions and budget.",
                        "framework", "You run inside one durable AgentEngine. Do not invent another runtime or hidden plan state."),
                capabilities, object(), object(), interactiveBudget, object(), ranges);
        definitions.installBuiltinDefinitions(workspaceId, profiles, agent);
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static ObjectNode contextCompaction() {
        ObjectNode value = enabled();
        value.put("maxMessageCharacters", 48_000);
        value.put("maxToolSchemaCharacters", 48_000);
        value.put("retainedToolExchanges", 4);
        value.put("maxToolResultCharacters", 16_000);
        value.put("maxTools", 64);
        return value;
    }

    private static ObjectNode contextOnDemand() {
        ObjectNode value = enabled();
        value.put("searches", 2);
        value.put("fetches", 3);
        value.put("candidates", 32);
        value.put("plannerInputChars", 8_000);
        value.put("selectedBodyChars", 12_000);
        value.put("selectedTools", 8);
        return value;
    }

    private static ObjectNode enabled() {
        ObjectNode value = object();
        value.put("enabled", true);
        return value;
    }
}
