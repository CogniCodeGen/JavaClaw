package com.javaclaw.framework.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.extension.ExtensionContributions;
import com.javaclaw.framework.spi.ExtensionLock;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.extension.ExtensionRegistrySnapshot;
import com.javaclaw.framework.extension.OwnedContribution;
import com.javaclaw.framework.spi.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Resolves layered configuration and exact extension artifacts into an immutable plan. */
public final class AgentCompiler {
    private final AgentDefinitionResolver definitions;
    private final ExtensionManager extensions;
    private final ObjectMapper json;
    private final RunConstraints systemLimits;
    private final DefinitionProvisioner provisioner;

    public AgentCompiler(
            AgentDefinitionResolver definitions,
            ExtensionManager extensions,
            ObjectMapper json) {
        this(definitions, extensions, json,
                new RunConstraints(PermissionSet.UNRESTRICTED, RunBudget.UNBOUNDED),
                DefinitionProvisioner.NONE);
    }

    public AgentCompiler(
            AgentDefinitionResolver definitions,
            ExtensionManager extensions,
            ObjectMapper json,
            RunConstraints systemLimits) {
        this(definitions, extensions, json, systemLimits, DefinitionProvisioner.NONE);
    }

    public AgentCompiler(
            AgentDefinitionResolver definitions,
            ExtensionManager extensions,
            ObjectMapper json,
            RunConstraints systemLimits,
            DefinitionProvisioner provisioner) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.json = Objects.requireNonNull(json, "json");
        this.systemLimits = Objects.requireNonNull(systemLimits, "systemLimits");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    }

    public ExecutionPlan compile(RunRequest request) {
        String workspaceId = request.scope().workspaceId();
        AgentDefinition definition = resolveAgent(workspaceId, request.agent());
        RunProfile profile = resolveProfile(workspaceId, request.profile());
        ExtensionRegistrySnapshot.SnapshotLease lease = extensions.acquireCurrent();
        try {
            ExtensionRegistrySnapshot snapshot = lease.snapshot();
            Map<CapabilityId, JsonNode> merged = mergeCapabilities(definition, profile);
            merged.entrySet().removeIf(entry -> entry.getValue().path("enabled").isBoolean()
                    && !entry.getValue().path("enabled").asBoolean());
            Set<String> selectedIds = new LinkedHashSet<>();

            for (CapabilityId capability : merged.keySet().stream().sorted().toList()) {
                ExtensionContributions.CapabilityRegistration registration = snapshot.contributions()
                        .capabilities().get(capability);
                if (registration == null) {
                    throw new IllegalStateException("capability is not installed: " + capability);
                }
                selectedIds.add(registration.extensionId());
            }
            LinkedHashMap<String, String> rootRanges = new LinkedHashMap<>();
            selectedIds.stream().sorted().forEach(extensionId -> rootRanges.put(extensionId,
                    definition.compatibleExtensionRanges().getOrDefault(extensionId, "*")));
            // A definition may lock an event/policy-only extension that has no Capability.
            // Capability-owning extensions, however, become roots only while their binding is enabled.
            Set<String> capabilityOwners = snapshot.contributions().capabilities().values().stream()
                    .map(ExtensionContributions.CapabilityRegistration::extensionId)
                    .collect(java.util.stream.Collectors.toSet());
            definition.compatibleExtensionRanges().entrySet().stream()
                    .filter(entry -> !capabilityOwners.contains(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> rootRanges.putIfAbsent(entry.getKey(), entry.getValue()));
            List<ExtensionLock> locks = snapshot.resolveClosure(rootRanges);
            selectedIds.clear();
            locks.forEach(lock -> selectedIds.add(lock.extensionId()));
            ExtensionContributions selectedContributions = snapshot.contributionsFor(locks);

            CompilationContext context = new CompilationContext(definition, profile, snapshot.generation());
            LinkedHashMap<CapabilityId, JsonNode> compiled = new LinkedHashMap<>();
            for (var entry : merged.entrySet()) {
                CapabilityCompiler compiler = selectedContributions.capabilities()
                        .get(entry.getKey()).compiler();
                compiled.put(entry.getKey(), compiler.compile(entry.getKey(), entry.getValue(), context));
            }
            List<DefinitionValidationIssue> extensionIssues = new ArrayList<>();
            for (OwnedContribution<DefinitionValidator> owned
                    : selectedContributions.definitionValidators()) {
                if (!selectedIds.contains(owned.extensionId())) continue;
                List<DefinitionValidationIssue> found = Objects.requireNonNull(
                        owned.value().validate(definition, profile, context),
                        "definition validation result");
                extensionIssues.addAll(found);
            }
            List<DefinitionValidationIssue> extensionErrors = extensionIssues.stream()
                    .filter(issue -> issue.severity()
                            == DefinitionValidationIssue.Severity.ERROR).toList();
            if (!extensionErrors.isEmpty()) {
                throw new IllegalStateException(
                        "extension definition validation failed: " + extensionErrors);
            }

            PermissionSet permissions = systemLimits.permissions()
                    .intersect(request.permissionCeiling())
                    .intersect(profile.permissionCeiling());
            RunBudget budget = request.budget().restrictWith(profile.budget())
                    .restrictWith(definition.budgetPolicy())
                    .restrictWith(systemLimits.budget());
            for (OwnedContribution<PermissionPolicy> owned : selectedContributions.permissionPolicies()) {
                if (selectedIds.contains(owned.extensionId())) {
                    permissions = owned.value().restrict(permissions,
                            definition.permissionPolicy(), request).intersect(permissions);
                }
            }
            for (OwnedContribution<BudgetPolicy> owned : selectedContributions.budgetPolicies()) {
                if (selectedIds.contains(owned.extensionId())) {
                    budget = budget.restrictWith(owned.value().restrict(
                            budget, definition.toolPolicy(), request));
                }
            }
            if (request.linkage().parentRunId() != null) {
                for (OwnedContribution<SubAgentPolicy> owned
                        : selectedContributions.subAgentPolicies()) {
                    if (!selectedIds.contains(owned.extensionId())) continue;
                    RunConstraints current = new RunConstraints(permissions, budget);
                    RunConstraints restricted = Objects.requireNonNull(
                            owned.value().restrict(request, current),
                            "sub-agent policy result");
                    permissions = permissions.intersect(restricted.permissions());
                    budget = budget.restrictWith(restricted.budget());
                }
            }

            String modelPolicyRef = definition.modelPolicyRef();
            for (OwnedContribution<ModelPolicy> owned : selectedContributions.modelPolicies()) {
                if (!selectedIds.contains(owned.extensionId())) continue;
                var modelContext = json.createObjectNode();
                modelContext.put("extensionId", owned.extensionId());
                modelContext.put("invocationSource", request.source().kind());
                modelContext.set("attributes", json.valueToTree(request.attributes()));
                modelPolicyRef = Objects.requireNonNull(owned.value().select(
                        modelPolicyRef, ModelTier.NORMAL, modelContext), "model policy result").trim();
                if (modelPolicyRef.isEmpty()) {
                    throw new IllegalStateException("model policy returned a blank reference: "
                            + owned.extensionId());
                }
            }

            List<AdvisorSpec> advisors = new ArrayList<>();
            for (OwnedContribution<AdvisorSpecFactory> owned : selectedContributions.advisors()) {
                if (selectedIds.contains(owned.extensionId())) {
                    JsonNode configuration = merged.get(owned.value().capabilityId());
                    if (configuration != null) {
                        AdvisorSpec advisor = owned.value().create(configuration, context);
                        if (!advisor.id().equals(owned.value().advisorId())) {
                            throw new IllegalStateException("advisor factory ID mismatch: "
                                    + owned.value().advisorId() + " != " + advisor.id());
                        }
                        advisors.add(advisor);
                    }
                }
            }
            advisors.sort(Comparator.comparingInt(AdvisorSpec::order).thenComparing(AdvisorSpec::id));
            assertAdvisorSlots(advisors);

            String promptFingerprint = sha256(canonical(definition.promptSections()));
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("definition", new AgentDefinitionRef(definition.id(), definition.version()));
            identity.put("profile", new RunProfileRef(profile.id(), profile.version()));
            identity.put("definitionChecksum", definition.checksum());
            identity.put("profileChecksum", profile.checksum());
            identity.put("generation", snapshot.generation());
            identity.put("extensions", locks);
            identity.put("capabilities", compiled);
            identity.put("toolPolicy", definition.toolPolicy());
            identity.put("permissions", permissions);
            identity.put("budget", budget);
            identity.put("modelPolicyRef", modelPolicyRef);
            identity.put("advisors", advisors);
            identity.put("promptFingerprint", promptFingerprint);
            String checksum = sha256(canonical(identity));
            String planId = "plan-" + checksum.substring(0, 24);

            ExecutionPlanDescriptor descriptor = new ExecutionPlanDescriptor(
                    planId,
                    new AgentDefinitionRef(definition.id(), definition.version()),
                    new RunProfileRef(profile.id(), profile.version()),
                    definition.checksum(), profile.checksum(), snapshot.generation(), locks,
                    modelPolicyRef, definition.promptSections(), promptFingerprint,
                    compiled, definition.toolPolicy(), permissions, budget, advisors,
                    definition.outputContract(), checksum);
            return new ExecutionPlan(descriptor, lease);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private AgentDefinition resolveAgent(String workspaceId, AgentDefinitionRef reference) {
        try {
            return definitions.resolveAgent(workspaceId, reference);
        } catch (NoSuchElementException missing) {
            if (!provisioner.ensureAgent(workspaceId, reference)) throw missing;
            return definitions.resolveAgent(workspaceId, reference);
        }
    }

    private RunProfile resolveProfile(String workspaceId, RunProfileRef reference) {
        try {
            return definitions.resolveProfile(workspaceId, reference);
        } catch (NoSuchElementException missing) {
            if (!provisioner.ensureProfile(workspaceId, reference)) throw missing;
            return definitions.resolveProfile(workspaceId, reference);
        }
    }

    /** Rebuilds runtime contribution references without recompiling or upgrading a persisted plan. */
    public java.util.Optional<ExecutionPlan> restore(ExecutionPlanDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return extensions.acquireLocked(descriptor.extensionLocks()).map(lease -> {
            try {
                return new ExecutionPlan(descriptor, lease);
            } catch (RuntimeException failure) {
                lease.close();
                throw failure;
            }
        });
    }

    private static Map<CapabilityId, JsonNode> mergeCapabilities(
            AgentDefinition definition, RunProfile profile) {
        LinkedHashMap<CapabilityId, JsonNode> merged = new LinkedHashMap<>();
        definition.capabilityBindings().forEach((key, value) -> merged.put(key, value.deepCopy()));
        profile.capabilityOverrides().forEach((key, value) -> merged.put(key, value.deepCopy()));
        return merged;
    }

    private static void assertAdvisorSlots(List<AdvisorSpec> advisors) {
        Set<String> exclusive = new LinkedHashSet<>();
        for (AdvisorSpec advisor : advisors) {
            if (advisor.slot().startsWith("exclusive:") && !exclusive.add(advisor.slot())) {
                throw new IllegalStateException("multiple advisors occupy " + advisor.slot());
            }
            if (advisor.id().equals("spring-ai.tool-calling")) {
                throw new IllegalStateException("extensions cannot contribute the ToolCallingAdvisor");
            }
        }
    }

    private String canonical(Object value) {
        try {
            return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize execution plan", e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
