package com.javaclaw.workflow.runtime;

import com.javaclaw.framework.spi.ExtensionLock;
import com.javaclaw.framework.spi.WorkflowTemplateContribution;
import com.javaclaw.workflow.model.GraphDefinition;

import java.util.List;
import java.util.Optional;

/** Bridge from the graph runtime to exact, version-pinned framework extensions. */
public interface WorkflowExtensionPlanProvider {
    WorkflowExtensionPlan compile(GraphDefinition definition);

    Optional<WorkflowExtensionPlan> restore(List<ExtensionLock> locks);

    Optional<NodeExecutor> currentExecutor(String type);

    default List<WorkflowTemplateContribution> currentTemplates() { return List.of(); }

    WorkflowExtensionPlanProvider NONE = new WorkflowExtensionPlanProvider() {
        @Override public WorkflowExtensionPlan compile(GraphDefinition definition) {
            return WorkflowExtensionPlan.empty();
        }
        @Override public Optional<WorkflowExtensionPlan> restore(List<ExtensionLock> locks) {
            return locks == null || locks.isEmpty()
                    ? Optional.of(WorkflowExtensionPlan.empty()) : Optional.empty();
        }
        @Override public Optional<NodeExecutor> currentExecutor(String type) {
            return Optional.empty();
        }
    };
}
