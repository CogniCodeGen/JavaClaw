package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.AgentDefinition;
import com.javaclaw.framework.api.DefinitionValidationIssue;
import com.javaclaw.framework.api.RunProfile;

import java.util.List;

/** Cross-field validation executed after schemas and version resolution. */
@FunctionalInterface
public interface DefinitionValidator {
    List<DefinitionValidationIssue> validate(
            AgentDefinition definition, RunProfile profile, CompilationContext context);
}
