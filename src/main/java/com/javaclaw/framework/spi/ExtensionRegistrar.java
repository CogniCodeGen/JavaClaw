package com.javaclaw.framework.spi;

/** Complete contribution surface; intentionally contains no runtime/state-machine replacement hook. */
public interface ExtensionRegistrar {
    void capability(CapabilityDescriptor descriptor, CapabilityCompiler compiler);

    void definitionValidator(DefinitionValidator validator);

    void eventType(EventTypeDescriptor descriptor, EventCodec<?> codec);

    void promptContributor(PromptContributor contributor);

    void contextProvider(ContextProvider provider);

    void retriever(RetrieverContribution retriever);

    void advisor(AdvisorSpecFactory advisorFactory);

    void outputGuard(OutputGuard outputGuard);

    void tool(ToolFactory toolFactory);

    void toolProvider(ToolProviderFactory toolProviderFactory);

    void toolPolicy(ToolPolicy policy);

    void toolResultPostProcessor(ToolResultPostProcessor processor);

    void modelPolicy(ModelPolicy policy);

    void permissionPolicy(PermissionPolicy policy);

    void budgetPolicy(BudgetPolicy policy);

    void retryPolicy(RetryPolicy policy);

    void evaluationPolicy(EvaluationPolicy policy);

    void runProfile(RunProfileContribution profile);

    void workflowNode(WorkflowNodeContribution node);

    void workflowTemplate(WorkflowTemplateContribution template);

    void subAgentPolicy(SubAgentPolicy policy);

    void stateCodec(StateCodec codec);

    void stateMigrator(StateMigrator migrator);

    void backgroundJob(BackgroundJob job);

    void infrastructureProvider(InfrastructureProvider<?> provider);
}
