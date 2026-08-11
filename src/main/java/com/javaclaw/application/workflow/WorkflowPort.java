package com.javaclaw.application.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService.RunObserver;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;

import java.util.List;

/** 工作流定义仓储与运行引擎的应用层端口。 */
public interface WorkflowPort {

    List<Definition> definitions();

    GraphDefinition createDraft();

    GraphDefinition cloneDraft(String workflowId);

    void saveDraft(GraphDefinition graph);

    String publish(String workflowId);

    List<ValidationIssue> validate(GraphDefinition graph);

    List<GraphRun> runs(String workflowId, int limit);

    void testRun(GraphDefinition graph, String input, RunObserver observer);

    void resumeRun(String runId, String input, boolean unsafeRetryConfirmed,
                   RunObserver observer);

    boolean cancelRun(String runId);

    record Definition(GraphDefinition graph, boolean system, boolean published) {
        public Definition {
            graph = java.util.Objects.requireNonNull(graph, "graph");
        }
    }
}
