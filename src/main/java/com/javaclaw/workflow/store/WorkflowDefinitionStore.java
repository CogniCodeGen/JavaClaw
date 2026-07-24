package com.javaclaw.workflow.store;

import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.runtime.NodeExecutorRegistry;
import com.javaclaw.workflow.runtime.ValidationIssue;

import java.util.List;

public interface WorkflowDefinitionStore {
    List<WorkflowDefinitionRecord> list(boolean includeArchived);
    WorkflowDefinitionRecord get(String id);
    WorkflowDefinitionRecord saveDraft(GraphDefinition definition);
    WorkflowDefinitionRecord publish(String id, NodeExecutorRegistry registry);
    WorkflowDefinitionRecord cloneFrom(GraphDefinition source, String newName);
    boolean archive(String id, boolean archived);
    boolean delete(String id);
    List<ValidationIssue> validate(GraphDefinition definition, NodeExecutorRegistry registry);
}
