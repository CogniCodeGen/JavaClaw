package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.AgentDefinition;
import com.javaclaw.framework.api.RunProfile;

public record CompilationContext(AgentDefinition definition, RunProfile profile, long extensionGeneration) {}
