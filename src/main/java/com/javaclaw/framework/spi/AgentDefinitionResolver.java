package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.AgentDefinition;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.RunProfile;
import com.javaclaw.framework.api.RunProfileRef;

/** Resolves only validated, published versions for AgentCompiler. */
public interface AgentDefinitionResolver {
    AgentDefinition resolveAgent(String workspaceId, AgentDefinitionRef reference);

    RunProfile resolveProfile(String workspaceId, RunProfileRef reference);
}
