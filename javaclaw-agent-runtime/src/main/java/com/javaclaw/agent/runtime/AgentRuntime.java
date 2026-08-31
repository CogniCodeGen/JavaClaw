package com.javaclaw.agent.runtime;

/** Aggregate exposed only to App Server bootstrap; consumers depend on narrow use cases. */
public interface AgentRuntime
        extends WorkspaceUseCases,
                ThreadUseCases,
                TurnUseCases,
                InteractionUseCases,
                RuntimeStreams,
                ThreadMaintenanceUseCases,
                AutoCloseable {
    @Override
    void close();
}
