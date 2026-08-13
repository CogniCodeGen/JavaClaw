package com.javaclaw.framework.extension;

import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.spi.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Collects and validates contributions before the snapshot is visible to any run. */
final class StagingExtensionRegistrar implements ExtensionRegistrar {
    private static final Pattern EVENT_TYPE = Pattern.compile(
            "^[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+$");
    private final String extensionId;
    private final Map<CapabilityId, ExtensionContributions.CapabilityRegistration> capabilities;
    private final Map<String, EventTypeRegistration> eventTypes;
    private final List<OwnedContribution<DefinitionValidator>> definitionValidators;
    private final List<OwnedContribution<PromptContributor>> prompts;
    private final List<OwnedContribution<ContextProvider>> contexts;
    private final List<OwnedContribution<RetrieverContribution>> retrievers;
    private final List<OwnedContribution<AdvisorSpecFactory>> advisors;
    private final List<OwnedContribution<OutputGuard>> guards;
    private final List<OwnedContribution<ToolFactory>> tools;
    private final List<OwnedContribution<ToolProviderFactory>> toolProviders;
    private final List<OwnedContribution<ToolPolicy>> toolPolicies;
    private final List<OwnedContribution<ToolResultPostProcessor>> toolResultPostProcessors;
    private final List<OwnedContribution<ModelPolicy>> modelPolicies;
    private final List<OwnedContribution<PermissionPolicy>> permissionPolicies;
    private final List<OwnedContribution<BudgetPolicy>> budgetPolicies;
    private final List<OwnedContribution<RetryPolicy>> retryPolicies;
    private final List<OwnedContribution<EvaluationPolicy>> evaluations;
    private final List<OwnedContribution<RunProfileContribution>> runProfiles;
    private final List<OwnedContribution<WorkflowNodeContribution>> workflowNodes;
    private final List<OwnedContribution<WorkflowTemplateContribution>> workflowTemplates;
    private final List<OwnedContribution<SubAgentPolicy>> subAgentPolicies;
    private final List<OwnedContribution<StateCodec>> codecs;
    private final List<OwnedContribution<StateMigrator>> migrators;
    private final List<OwnedContribution<BackgroundJob>> jobs;
    private final List<OwnedContribution<InfrastructureProvider<?>>> infrastructureProviders;

    StagingExtensionRegistrar(String extensionId, Builder builder) {
        this.extensionId = extensionId;
        capabilities = builder.capabilities;
        eventTypes = builder.eventTypes;
        definitionValidators = builder.definitionValidators;
        prompts = builder.prompts;
        contexts = builder.contexts;
        retrievers = builder.retrievers;
        advisors = builder.advisors;
        guards = builder.guards;
        tools = builder.tools;
        toolProviders = builder.toolProviders;
        toolPolicies = builder.toolPolicies;
        toolResultPostProcessors = builder.toolResultPostProcessors;
        modelPolicies = builder.modelPolicies;
        permissionPolicies = builder.permissionPolicies;
        budgetPolicies = builder.budgetPolicies;
        retryPolicies = builder.retryPolicies;
        evaluations = builder.evaluations;
        runProfiles = builder.runProfiles;
        workflowNodes = builder.workflowNodes;
        workflowTemplates = builder.workflowTemplates;
        subAgentPolicies = builder.subAgentPolicies;
        codecs = builder.codecs;
        migrators = builder.migrators;
        jobs = builder.jobs;
        infrastructureProviders = builder.infrastructureProviders;
    }

    @Override public void definitionValidator(DefinitionValidator value) {
        definitionValidators.add(owned(value));
    }

    @Override
    public void capability(CapabilityDescriptor descriptor, CapabilityCompiler compiler) {
        ExtensionContributions.CapabilityRegistration registration =
                new ExtensionContributions.CapabilityRegistration(extensionId,
                        Objects.requireNonNull(descriptor), Objects.requireNonNull(compiler));
        if (capabilities.putIfAbsent(descriptor.id(), registration) != null) {
            throw new IllegalStateException("duplicate capability: " + descriptor.id());
        }
    }

    @Override
    public void eventType(EventTypeDescriptor descriptor, EventCodec<?> codec) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(codec, "codec");
        if (!EVENT_TYPE.matcher(descriptor.type()).matches()) {
            throw new IllegalArgumentException(
                    "extension event type must be a lower-case namespace: " + descriptor.type());
        }
        if (descriptor.type().startsWith("core.")) {
            throw new IllegalArgumentException(
                    "the core event namespace is reserved: " + descriptor.type());
        }
        String key = descriptor.type() + "@" + descriptor.schemaVersion();
        EventTypeRegistration registration = new EventTypeRegistration(
                extensionId, descriptor, codec);
        if (eventTypes.putIfAbsent(key, registration) != null) {
            throw new IllegalStateException("duplicate event schema: " + key);
        }
    }

    @Override public void promptContributor(PromptContributor value) { prompts.add(owned(value)); }
    @Override public void contextProvider(ContextProvider value) { contexts.add(owned(value)); }
    @Override public void retriever(RetrieverContribution value) { retrievers.add(owned(value)); }
    @Override public void advisor(AdvisorSpecFactory value) { advisors.add(owned(value)); }
    @Override public void outputGuard(OutputGuard value) { guards.add(owned(value)); }
    @Override public void tool(ToolFactory value) { tools.add(owned(value)); }
    @Override public void toolProvider(ToolProviderFactory value) { toolProviders.add(owned(value)); }
    @Override public void toolPolicy(ToolPolicy value) { toolPolicies.add(owned(value)); }
    @Override public void toolResultPostProcessor(ToolResultPostProcessor value) {
        toolResultPostProcessors.add(owned(value));
    }
    @Override public void modelPolicy(ModelPolicy value) { modelPolicies.add(owned(value)); }
    @Override public void permissionPolicy(PermissionPolicy value) { permissionPolicies.add(owned(value)); }
    @Override public void budgetPolicy(BudgetPolicy value) { budgetPolicies.add(owned(value)); }
    @Override public void retryPolicy(RetryPolicy value) { retryPolicies.add(owned(value)); }
    @Override public void evaluationPolicy(EvaluationPolicy value) { evaluations.add(owned(value)); }
    @Override public void runProfile(RunProfileContribution value) { runProfiles.add(owned(value)); }
    @Override public void workflowNode(WorkflowNodeContribution value) { workflowNodes.add(owned(value)); }
    @Override public void workflowTemplate(WorkflowTemplateContribution value) {
        workflowTemplates.add(owned(value));
    }
    @Override public void subAgentPolicy(SubAgentPolicy value) { subAgentPolicies.add(owned(value)); }
    @Override public void stateCodec(StateCodec value) { codecs.add(owned(value)); }
    @Override public void stateMigrator(StateMigrator value) { migrators.add(owned(value)); }
    @Override public void backgroundJob(BackgroundJob value) { jobs.add(owned(value)); }
    @Override public void infrastructureProvider(InfrastructureProvider<?> value) {
        Objects.requireNonNull(value, "provider");
        Objects.requireNonNull(value.kind(), "provider kind");
        String id = Objects.requireNonNull(value.id(), "provider id").trim();
        Class<?> contract = Objects.requireNonNull(value.contract(), "provider contract");
        Object instance = Objects.requireNonNull(value.instance(), "provider instance");
        if (id.isEmpty() || !contract.isInstance(instance)) {
            throw new IllegalArgumentException("invalid infrastructure provider: " + id);
        }
        infrastructureProviders.add(owned(value));
    }

    private <T> OwnedContribution<T> owned(T value) {
        return new OwnedContribution<>(extensionId, Objects.requireNonNull(value));
    }

    static final class Builder {
        private final Map<CapabilityId, ExtensionContributions.CapabilityRegistration> capabilities = new LinkedHashMap<>();
        private final Map<String, EventTypeRegistration> eventTypes = new LinkedHashMap<>();
        private final List<OwnedContribution<DefinitionValidator>> definitionValidators =
                new ArrayList<>();
        private final List<OwnedContribution<PromptContributor>> prompts = new ArrayList<>();
        private final List<OwnedContribution<ContextProvider>> contexts = new ArrayList<>();
        private final List<OwnedContribution<RetrieverContribution>> retrievers = new ArrayList<>();
        private final List<OwnedContribution<AdvisorSpecFactory>> advisors = new ArrayList<>();
        private final List<OwnedContribution<OutputGuard>> guards = new ArrayList<>();
        private final List<OwnedContribution<ToolFactory>> tools = new ArrayList<>();
        private final List<OwnedContribution<ToolProviderFactory>> toolProviders = new ArrayList<>();
        private final List<OwnedContribution<ToolPolicy>> toolPolicies = new ArrayList<>();
        private final List<OwnedContribution<ToolResultPostProcessor>> toolResultPostProcessors =
                new ArrayList<>();
        private final List<OwnedContribution<ModelPolicy>> modelPolicies = new ArrayList<>();
        private final List<OwnedContribution<PermissionPolicy>> permissionPolicies = new ArrayList<>();
        private final List<OwnedContribution<BudgetPolicy>> budgetPolicies = new ArrayList<>();
        private final List<OwnedContribution<RetryPolicy>> retryPolicies = new ArrayList<>();
        private final List<OwnedContribution<EvaluationPolicy>> evaluations = new ArrayList<>();
        private final List<OwnedContribution<RunProfileContribution>> runProfiles = new ArrayList<>();
        private final List<OwnedContribution<WorkflowNodeContribution>> workflowNodes = new ArrayList<>();
        private final List<OwnedContribution<WorkflowTemplateContribution>> workflowTemplates =
                new ArrayList<>();
        private final List<OwnedContribution<SubAgentPolicy>> subAgentPolicies = new ArrayList<>();
        private final List<OwnedContribution<StateCodec>> codecs = new ArrayList<>();
        private final List<OwnedContribution<StateMigrator>> migrators = new ArrayList<>();
        private final List<OwnedContribution<BackgroundJob>> jobs = new ArrayList<>();
        private final List<OwnedContribution<InfrastructureProvider<?>>> infrastructureProviders =
                new ArrayList<>();

        ExtensionContributions build() {
            return new ExtensionContributions(capabilities, eventTypes, definitionValidators,
                    prompts, contexts,
                    retrievers, advisors, guards, tools, toolProviders, toolPolicies,
                    toolResultPostProcessors, modelPolicies, permissionPolicies,
                    budgetPolicies, retryPolicies, evaluations, runProfiles, workflowNodes,
                    workflowTemplates, subAgentPolicies, codecs, migrators, jobs,
                    infrastructureProviders);
        }
    }
}
