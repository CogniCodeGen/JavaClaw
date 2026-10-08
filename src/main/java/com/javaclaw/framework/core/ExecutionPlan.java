package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.extension.ExtensionRegistrySnapshot;
import com.javaclaw.framework.extension.ExtensionContributions;
import com.javaclaw.framework.extension.EventTypeRegistration;
import com.javaclaw.framework.spi.*;

import java.util.List;
import java.util.Objects;

/** Runtime plan plus a generation lease. The lease survives pauses and is closed only at terminal state. */
public final class ExecutionPlan implements AutoCloseable {
    private final ExecutionPlanDescriptor descriptor;
    private final ExtensionRegistrySnapshot.SnapshotLease lease;
    private final ExtensionContributions contributions;
    private final JsonSchemaValidator eventSchemas = new JsonSchemaValidator();

    ExecutionPlan(ExecutionPlanDescriptor descriptor,
                  ExtensionRegistrySnapshot.SnapshotLease lease) {
        this.descriptor = descriptor;
        this.lease = lease;
        this.contributions = lease.snapshot().contributionsFor(descriptor.extensionLocks());
    }

    public ExecutionPlanDescriptor descriptor() { return descriptor; }

    public ExtensionRegistrySnapshot snapshot() { return lease.snapshot(); }

    public List<ToolFactory> toolFactories() { return values(contributions.tools()); }
    public List<ToolProviderFactory> toolProviderFactories() {
        return values(contributions.toolProviders());
    }
    public List<ToolPolicy> toolPolicies() { return values(contributions.toolPolicies()); }
    public List<ToolResultPostProcessor> toolResultPostProcessors() {
        return values(contributions.toolResultPostProcessors());
    }
    public List<RetryPolicy> retryPolicies() {
        return values(contributions.retryPolicies()).stream()
                .sorted(java.util.Comparator.comparingInt(RetryPolicy::order)
                        .thenComparing(RetryPolicy::id))
                .toList();
    }
    public List<PromptContributor> promptContributors() { return values(contributions.promptContributors()); }
    public List<ContextProvider> contextProviders() { return values(contributions.contextProviders()); }
    public List<RetrieverContribution> retrievers() { return values(contributions.retrievers()); }
    public List<DeferredContextSource> deferredContextSources() {
        return values(contributions.deferredContextSources());
    }
    /** 固定来源仅按此计划冻结的 ID 暴露，旧计划不接入新来源。 */
    public List<FixedContextSource> fixedContextSources() {
        java.util.Map<String, FixedContextSource> available = contributions.fixedContextSources()
                .stream().map(com.javaclaw.framework.extension.OwnedContribution::value)
                .collect(java.util.stream.Collectors.toMap(FixedContextSource::id,
                        java.util.function.Function.identity()));
        return descriptor.fixedContextSourceIds().stream().map(id -> {
            FixedContextSource source = available.get(id);
            if (source == null) {
                throw new IllegalStateException("locked fixed context source is unavailable: " + id);
            }
            return source;
        }).toList();
    }
    public List<TurnPreparation> turnPreparations() {
        return values(contributions.turnPreparations());
    }
    public List<PromptContributor> fixedPromptContributors() {
        return promptContributors().stream()
                .filter(value -> value instanceof OnDemandClassified classified
                        && classified.classification() == OnDemandClassified.Classification.FIXED)
                .toList();
    }
    public List<AdvisorSpecFactory> advisorFactories() { return values(contributions.advisors()); }
    public List<OutputGuard> outputGuards() { return values(contributions.outputGuards()); }
    public List<EvaluationPolicy> evaluationPolicies() { return values(contributions.evaluationPolicies()); }

    /** Adds only compiled, non-secret plan metadata needed by run-scoped extension adapters. */
    public com.javaclaw.framework.api.RunRequest annotate(
            com.javaclaw.framework.api.RunRequest request) {
        return annotate(request, null);
    }

    /** The optional grant must come from a scope-checked stored owner, never caller attributes. */
    com.javaclaw.framework.api.RunRequest annotate(
            com.javaclaw.framework.api.RunRequest request, JsonNode trustedDelegatableGroups) {
        var capabilities = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        descriptor.compiledCapabilities().keySet().stream().sorted()
                .forEach(id -> capabilities.add(id.value()));
        var configurations = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        descriptor.compiledCapabilities().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> configurations.set(entry.getKey().value(), entry.getValue()));
        var effective = new com.javaclaw.framework.api.RunRequest(request.agent(), request.profile(), request.source(),
                request.scope(), request.inputs(), request.linkage(), descriptor.permissions(), descriptor.budget(),
                request.idempotencyKey(), request.attributes());
        effective = effective.withAttribute("framework.capabilities", capabilities)
                .withAttribute("framework.modelPolicyRef", com.fasterxml.jackson.databind.node.TextNode.valueOf(descriptor.modelPolicyRef()))
                .withAttribute("framework.compiledCapabilities", configurations);
        if (!InteractionExecutionPolicy.isMain(effective)) return effective;

        JsonNode configured = request.attributes().get(ToolGroupAccess.ATTRIBUTE);
        JsonNode delegatable = trustedDelegatableGroups != null ? trustedDelegatableGroups : configured;
        if (delegatable == null) delegatable = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().add("*");
        // Ordinary annotation replaces any caller-supplied delegation attribute with this host grant.
        effective = effective.withAttribute(ToolGroupAccess.DELEGATABLE_ATTRIBUTE, delegatable);
        boolean hasBackend = ToolGroupAccess.allowsDelegation(effective, "web")
                || ToolGroupAccess.allowsDelegation(effective, "desktop-session");
        if (configured != null && !configured.isArray())
            throw new SecurityException(ToolGroupAccess.ATTRIBUTE + " must be an array");
        var direct = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        boolean hasInteraction = false;
        if (configured == null) direct.add("*");
        else for (JsonNode group : configured) {
            if (group.isTextual() && (group.asText().equals("web") || group.asText().equals("desktop-session"))) continue;
            direct.add(group);
            if (group.isTextual() && group.asText().equals(InteractionExecutionPolicy.TOOL_GROUP)) hasInteraction = true;
        }
        if (hasBackend && !hasInteraction) direct.add(InteractionExecutionPolicy.TOOL_GROUP);
        return effective.withAttribute(ToolGroupAccess.ATTRIBUTE, direct);
    }

    /**
     * Converts an event to its registered wire representation and validates that representation.
     * Core events are owned and versioned by the framework; every extension event must be locked
     * into this exact plan with an explicit schema and codec.
     */
    public JsonNode encodeEvent(String type, int schemaVersion, JsonNode payload) {
        String eventType = Objects.requireNonNull(type, "type");
        JsonNode value = Objects.requireNonNull(payload, "payload");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("event schema version must be positive: " + eventType);
        }
        if (eventType.startsWith("core.")) return value.deepCopy();

        String key = eventType + "@" + schemaVersion;
        EventTypeRegistration registration = contributions.eventTypes().get(key);
        if (registration == null) {
            throw new IllegalArgumentException(
                    "event type is not registered in execution plan " + descriptor.id() + ": " + key);
        }
        JsonNode encoded = registration.normalize(value);
        var issues = eventSchemas.validate(
                registration.descriptor().jsonSchema(), encoded, "/event/payload");
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("extension event payload violates " + key
                    + " schema: " + issues);
        }
        return encoded;
    }

    /** Applies the published Agent output contract after guards and evaluation enrichment. */
    public JsonNode validateOutput(JsonNode output) {
        JsonNode value = Objects.requireNonNull(output, "output").deepCopy();
        var issues = eventSchemas.validate(descriptor.outputContract(), value, "/output");
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("agent output violates published contract: " + issues);
        }
        return value;
    }

    private static <T> List<T> values(
            List<com.javaclaw.framework.extension.OwnedContribution<T>> values) {
        return values.stream().map(com.javaclaw.framework.extension.OwnedContribution::value).toList();
    }

    @Override
    public void close() {
        lease.close();
    }
}
