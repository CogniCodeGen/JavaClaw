package com.javaclaw.application.workflow;

import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;

import java.util.List;
import java.util.Objects;

/** 工作流中心查询、草稿、发布和运行流程；不保存 JavaFX 页面状态。 */
public final class WorkflowUseCase implements WorkflowApplicationService {

    private static final int RUN_HISTORY_LIMIT = 100;

    private final WorkflowPort workflows;

    public WorkflowUseCase(WorkflowPort workflows) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
    }

    @Override
    public Snapshot snapshot() {
        List<WorkflowItem> items = workflows.definitions().stream()
                .map(definition -> new WorkflowItem(
                        definition.graph().id(), definition.graph().name(), definition.graph(),
                        definition.system(), definition.published()))
                .toList();
        return new Snapshot(items);
    }

    @Override
    public OperationResult createDraft() {
        GraphDefinition created = workflows.createDraft();
        return new OperationResult(snapshot(), created.id());
    }

    @Override
    public OperationResult cloneDraft(String workflowId) {
        GraphDefinition cloned = workflows.cloneDraft(required(workflowId, "工作流 ID"));
        return new OperationResult(snapshot(), cloned.id());
    }

    @Override
    public void saveDraft(GraphDefinition graph) {
        workflows.saveDraft(Objects.requireNonNull(graph, "graph"));
    }

    @Override
    public PublishResult publish(GraphDefinition graph) {
        GraphDefinition checked = Objects.requireNonNull(graph, "graph");
        workflows.saveDraft(checked);
        String publishedId = workflows.publish(checked.id());
        Snapshot updated = snapshot();
        WorkflowItem published = updated.find(publishedId);
        if (published == null) {
            throw new NotFoundException("发布后未找到工作流: " + publishedId);
        }
        return new PublishResult(updated, published);
    }

    @Override
    public List<ValidationIssue> validate(GraphDefinition graph) {
        return List.copyOf(workflows.validate(Objects.requireNonNull(graph, "graph")));
    }

    @Override
    public List<GraphRun> runs(String workflowId) {
        return List.copyOf(workflows.runs(required(workflowId, "工作流 ID"), RUN_HISTORY_LIMIT));
    }

    @Override
    public void testRun(GraphDefinition graph, String input, RunObserver observer) {
        workflows.testRun(Objects.requireNonNull(graph, "graph"), normalized(input),
                Objects.requireNonNull(observer, "observer"));
    }

    @Override
    public void resumeRun(String runId, String input, boolean unsafeRetryConfirmed,
                          RunObserver observer) {
        workflows.resumeRun(required(runId, "运行 ID"), normalized(input),
                unsafeRetryConfirmed, Objects.requireNonNull(observer, "observer"));
    }

    @Override
    public boolean cancelRun(String runId) {
        return workflows.cancelRun(required(runId, "运行 ID"));
    }

    private static String required(String value, String label) {
        String checked = normalized(value);
        if (checked.isBlank()) {
            throw new ValidationException(label + "不能为空");
        }
        return checked;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }
}
