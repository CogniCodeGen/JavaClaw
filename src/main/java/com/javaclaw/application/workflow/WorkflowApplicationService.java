package com.javaclaw.application.workflow;

import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;

import java.util.List;

/**
 * 工作流中心的应用层入口。
 *
 * <p>定义与运行查询会访问数据库，调用方必须在工作区托管 I/O 任务中执行。测试运行和恢复
 * 只负责启动异步图执行，后续事件通过 {@link RunObserver} 交付。观察者可能从任意托管线程
 * 回调，展示层必须使用 FX 调度器。返回快照不可跨工作区 Context 生命周期缓存。</p>
 */
public interface WorkflowApplicationService {

    Snapshot snapshot();

    OperationResult createDraft();

    OperationResult cloneDraft(String workflowId);

    void saveDraft(GraphDefinition graph);

    PublishResult publish(GraphDefinition graph);

    List<ValidationIssue> validate(GraphDefinition graph);

    List<GraphRun> runs(String workflowId);

    void testRun(GraphDefinition graph, String input, RunObserver observer);

    void resumeRun(String runId, String input, boolean unsafeRetryConfirmed,
                   RunObserver observer);

    boolean cancelRun(String runId);

    record Snapshot(List<WorkflowItem> workflows) {
        public Snapshot {
            workflows = List.copyOf(workflows == null ? List.of() : workflows);
        }

        public WorkflowItem find(String id) {
            return workflows.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        }
    }

    record WorkflowItem(
            String id,
            String name,
            GraphDefinition graph,
            boolean system,
            boolean published) {
        public WorkflowItem {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            graph = java.util.Objects.requireNonNull(graph, "graph");
        }
    }

    record OperationResult(Snapshot snapshot, String selectedId) {
        public OperationResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            selectedId = selectedId == null ? "" : selectedId;
        }
    }

    record PublishResult(Snapshot snapshot, WorkflowItem published) {
        public PublishResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            published = java.util.Objects.requireNonNull(published, "published");
        }
    }

    interface RunObserver {
        void onTrace(String trace);

        void onTerminal(RunTerminal terminal);
    }

    record RunTerminal(RunTerminalStatus status, String detail) {
        public RunTerminal {
            status = java.util.Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
        }
    }

    enum RunTerminalStatus {
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
