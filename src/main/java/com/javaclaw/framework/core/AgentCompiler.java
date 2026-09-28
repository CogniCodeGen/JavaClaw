package com.javaclaw.framework.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.AgentDefinition;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.api.DefinitionValidationIssue;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfile;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.extension.ExtensionContributions;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.extension.ExtensionRegistrySnapshot;
import com.javaclaw.framework.extension.OwnedContribution;
import com.javaclaw.framework.spi.AdvisorSpec;
import com.javaclaw.framework.spi.AdvisorSpecFactory;
import com.javaclaw.framework.spi.AgentDefinitionResolver;
import com.javaclaw.framework.spi.BudgetPolicy;
import com.javaclaw.framework.spi.CapabilityCompiler;
import com.javaclaw.framework.spi.CompilationContext;
import com.javaclaw.framework.spi.DefinitionProvisioner;
import com.javaclaw.framework.spi.DefinitionValidator;
import com.javaclaw.framework.spi.ExtensionLock;
import com.javaclaw.framework.spi.ModelPolicy;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.OnDemandClassified;
import com.javaclaw.framework.spi.PermissionPolicy;
import com.javaclaw.framework.spi.RunConstraints;
import com.javaclaw.framework.spi.SubAgentPolicy;

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
    private static final CapabilityId CONTEXT_COMPACTION = new CapabilityId("context.compaction");
    private static final CapabilityId CONTEXT_ON_DEMAND = new CapabilityId("context.on_demand");

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
            configureOnDemand(merged, snapshot);
            merged.entrySet().removeIf(entry -> disabled(entry.getValue()));
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
            String threadModel = request.attributes().getOrDefault("framework.threadModelPolicy",
                    com.fasterxml.jackson.databind.node.TextNode.valueOf("")).asText();
            if (!threadModel.isBlank()) modelPolicyRef = threadModel;
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

            StepContextPolicy stepContextPolicy = contextPolicy(compiled);
            OnDemandContextPolicy onDemandContextPolicy = onDemandPolicy(compiled);
            List<String> fixedContextSourceIds = fixedContextSourceIds(
                    selectedContributions, onDemandContextPolicy, request, permissions, budget);
            if (onDemandContextPolicy != null) {
                validateOnDemandContributions(selectedContributions);
            }
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
            identity.put("stepContextPolicy", stepContextPolicy);
            identity.put("onDemandContextPolicy", onDemandContextPolicy);
            identity.put("fixedContextSourceIds", fixedContextSourceIds);
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
                    compiled, definition.toolPolicy(), permissions, budget, stepContextPolicy,
                    onDemandContextPolicy, fixedContextSourceIds,
                    advisors, definition.outputContract(), checksum);
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

    private static StepContextPolicy contextPolicy(Map<CapabilityId, JsonNode> compiled) {
        JsonNode configuration = compiled.get(CONTEXT_COMPACTION);
        return configuration == null ? null : StepContextPolicy.from(configuration);
    }

    private static OnDemandContextPolicy onDemandPolicy(Map<CapabilityId, JsonNode> compiled) {
        JsonNode configuration = compiled.get(CONTEXT_ON_DEMAND);
        return configuration == null ? null : OnDemandContextPolicy.from(configuration);
    }

    private static List<String> fixedContextSourceIds(
            ExtensionContributions contributions, OnDemandContextPolicy policy,
            RunRequest request, PermissionSet permissions, RunBudget budget) {
        JsonNode disabled = request.attributes().get("framework.disableTools");
        if (policy == null || budget.maxToolCalls() == 0
                || (disabled != null && disabled.asBoolean(false))) return List.of();
        return contributions.fixedContextSources().stream()
                .map(OwnedContribution::value)
                .filter(source -> ToolGroupAccess.allows(request, source.group())
                        && permissions.containsAll(source.requiredPermissions()))
                .map(com.javaclaw.framework.spi.FixedContextSource::id)
                .sorted().toList();
    }

    private static void configureOnDemand(
            Map<CapabilityId, JsonNode> merged, ExtensionRegistrySnapshot snapshot) {
        JsonNode compaction = merged.get(CONTEXT_COMPACTION);
        if (compaction == null || disabled(compaction)) {
            merged.remove(CONTEXT_ON_DEMAND);
        } else if (!merged.containsKey(CONTEXT_ON_DEMAND)
                && snapshot.contributions().capabilities().containsKey(CONTEXT_ON_DEMAND)) {
            merged.put(CONTEXT_ON_DEMAND,
                    JsonNodeFactory.instance.objectNode().put("enabled", true));
        }
    }

    private static boolean disabled(JsonNode configuration) {
        return configuration.path("enabled").isBoolean()
                && !configuration.path("enabled").asBoolean();
    }

    private static void validateOnDemandContributions(ExtensionContributions contributions) {
        Set<String> sources = contributions.deferredContextSources().stream()
                .map(owned -> owned.value().id())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> preparationOwners = contributions.turnPreparations().stream()
                .map(OwnedContribution::extensionId)
                .collect(java.util.stream.Collectors.toSet());
        validateClassifications(contributions.promptContributors(), "prompt contributor",
                true, sources, preparationOwners);
        validateClassifications(contributions.retrievers(), "retriever",
                false, sources, preparationOwners);
        validateClassifications(contributions.contextProviders(), "context provider",
                false, sources, preparationOwners);
    }

    private static void validateClassifications(
            List<? extends OwnedContribution<?>> contributions, String kind,
            boolean promptContributor, Set<String> sources, Set<String> preparationOwners) {
        for (OwnedContribution<?> owned : contributions) {
            if (!(owned.value() instanceof OnDemandClassified classified)
                    || classified.classification() == null) {
                throw new IllegalStateException("on-demand " + kind + " from "
                        + owned.extensionId() + " lacks explicit classification");
            }
            switch (classified.classification()) {
                case FIXED -> {
                    if (!promptContributor) {
                        throw new IllegalStateException("on-demand " + kind
                                + " cannot be fixed: " + owned.extensionId());
                    }
                }
                case DEFERRED -> {
                    if (!sources.contains(classified.deferredSourceId())) {
                        throw new IllegalStateException("on-demand " + kind + " from "
                                + owned.extensionId() + " references missing deferred source: "
                                + classified.deferredSourceId());
                    }
                }
                case EAGER_ONLY -> {
                    if (!promptContributor || !preparationOwners.contains(owned.extensionId())) {
                        throw new IllegalStateException("on-demand " + kind + " from "
                                + owned.extensionId() + " lacks a turn preparation replacement");
                    }
                }
            }
        }
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
