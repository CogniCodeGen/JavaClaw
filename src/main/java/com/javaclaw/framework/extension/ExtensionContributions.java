package com.javaclaw.framework.extension;

import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.framework.spi.*;

import java.util.List;
import java.util.Map;

/** Immutable contributions compiled from one complete registry snapshot. */
public record ExtensionContributions(
        Map<CapabilityId, CapabilityRegistration> capabilities,
        Map<String, EventTypeRegistration> eventTypes,
        List<OwnedContribution<DefinitionValidator>> definitionValidators,
        List<OwnedContribution<PromptContributor>> promptContributors,
        List<OwnedContribution<ContextProvider>> contextProviders,
        List<OwnedContribution<RetrieverContribution>> retrievers,
        List<OwnedContribution<AdvisorSpecFactory>> advisors,
        List<OwnedContribution<OutputGuard>> outputGuards,
        List<OwnedContribution<ToolFactory>> tools,
        List<OwnedContribution<ToolProviderFactory>> toolProviders,
        List<OwnedContribution<ToolPolicy>> toolPolicies,
        List<OwnedContribution<ToolResultPostProcessor>> toolResultPostProcessors,
        List<OwnedContribution<ModelPolicy>> modelPolicies,
        List<OwnedContribution<PermissionPolicy>> permissionPolicies,
        List<OwnedContribution<BudgetPolicy>> budgetPolicies,
        List<OwnedContribution<RetryPolicy>> retryPolicies,
        List<OwnedContribution<EvaluationPolicy>> evaluationPolicies,
        List<OwnedContribution<RunProfileContribution>> runProfiles,
        List<OwnedContribution<WorkflowNodeContribution>> workflowNodes,
        List<OwnedContribution<WorkflowTemplateContribution>> workflowTemplates,
        List<OwnedContribution<SubAgentPolicy>> subAgentPolicies,
        List<OwnedContribution<StateCodec>> stateCodecs,
        List<OwnedContribution<StateMigrator>> stateMigrators,
        List<OwnedContribution<BackgroundJob>> backgroundJobs,
        List<OwnedContribution<InfrastructureProvider<?>>> infrastructureProviders) {

    public ExtensionContributions {
        capabilities = Map.copyOf(capabilities);
        eventTypes = Map.copyOf(eventTypes);
        definitionValidators = List.copyOf(definitionValidators);
        promptContributors = List.copyOf(promptContributors);
        contextProviders = List.copyOf(contextProviders);
        retrievers = List.copyOf(retrievers);
        advisors = List.copyOf(advisors);
        outputGuards = List.copyOf(outputGuards);
        tools = List.copyOf(tools);
        toolProviders = List.copyOf(toolProviders);
        toolPolicies = List.copyOf(toolPolicies);
        toolResultPostProcessors = List.copyOf(toolResultPostProcessors);
        modelPolicies = List.copyOf(modelPolicies);
        permissionPolicies = List.copyOf(permissionPolicies);
        budgetPolicies = List.copyOf(budgetPolicies);
        retryPolicies = List.copyOf(retryPolicies);
        evaluationPolicies = List.copyOf(evaluationPolicies);
        runProfiles = List.copyOf(runProfiles);
        workflowNodes = List.copyOf(workflowNodes);
        workflowTemplates = List.copyOf(workflowTemplates);
        subAgentPolicies = List.copyOf(subAgentPolicies);
        stateCodecs = List.copyOf(stateCodecs);
        stateMigrators = List.copyOf(stateMigrators);
        backgroundJobs = List.copyOf(backgroundJobs);
        infrastructureProviders = List.copyOf(infrastructureProviders);
    }

    public record CapabilityRegistration(
            String extensionId,
            CapabilityDescriptor descriptor,
            CapabilityCompiler compiler) {}
}
