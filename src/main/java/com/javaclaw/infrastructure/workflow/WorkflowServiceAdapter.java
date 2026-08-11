package com.javaclaw.infrastructure.workflow;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunObserver;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunTerminal;
import com.javaclaw.application.workflow.WorkflowApplicationService.RunTerminalStatus;
import com.javaclaw.application.workflow.WorkflowPort;
import com.javaclaw.workflow.editor.WorkflowEditorModel;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.runtime.GraphRun;
import com.javaclaw.workflow.runtime.ValidationIssue;
import com.javaclaw.workflow.service.WorkflowService;
import com.javaclaw.workflow.store.WorkflowDefinitionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将现有工作流领域服务适配到可测试的应用层端口。 */
public final class WorkflowServiceAdapter implements WorkflowPort {

    private final WorkflowService service;

    public WorkflowServiceAdapter(WorkflowService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public List<Definition> definitions() {
        List<Definition> result = new ArrayList<>();
        service.systemGraphs().list().forEach(
                graph -> result.add(new Definition(graph, true, true)));
        service.definitions().list(false).forEach(record -> result.add(
                new Definition(record.draft(), false, record.isPublished())));
        return List.copyOf(result);
    }

    @Override
    public GraphDefinition createDraft() {
        return service.definitions().saveDraft(WorkflowEditorModel.blank("新工作流")).draft();
    }

    @Override
    public GraphDefinition cloneDraft(String workflowId) {
        GraphDefinition source = source(workflowId);
        return service.definitions().cloneFrom(source, source.name() + " 副本").draft();
    }

    @Override
    public void saveDraft(GraphDefinition graph) {
        service.definitions().saveDraft(graph);
    }

    @Override
    public String publish(String workflowId) {
        return service.definitions().publish(workflowId, service.nodeRegistry()).id();
    }

    @Override
    public List<ValidationIssue> validate(GraphDefinition graph) {
        return service.definitions().validate(graph, service.nodeRegistry());
    }

    @Override
    public List<GraphRun> runs(String workflowId, int limit) {
        return service.listRuns(workflowId, limit);
    }

    @Override
    public void testRun(GraphDefinition graph, String input, RunObserver observer) {
        service.testRun(graph, input, callbacks(observer));
    }

    @Override
    public void resumeRun(String runId, String input, boolean unsafeRetryConfirmed,
                          RunObserver observer) {
        service.resumeRun(runId, input.isBlank() ? null : input,
                unsafeRetryConfirmed, callbacks(observer));
    }

    @Override
    public boolean cancelRun(String runId) {
        return service.cancelRun(runId);
    }

    private GraphDefinition source(String workflowId) {
        GraphDefinition system = service.systemGraphs().get(workflowId);
        if (system != null) {
            return system;
        }
        WorkflowDefinitionRecord record = service.definitions().get(workflowId);
        if (record == null) {
            throw new NotFoundException("工作流不存在: " + workflowId);
        }
        return record.draft();
    }

    private static ConversationCallbacks callbacks(RunObserver observer) {
        return new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                observer.onTrace(event.toString());
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
                if (outcome instanceof ConversationOutcome.Completed) {
                    observer.onTerminal(new RunTerminal(RunTerminalStatus.COMPLETED, ""));
                } else if (outcome instanceof ConversationOutcome.Cancelled cancelled) {
                    observer.onTerminal(new RunTerminal(
                            RunTerminalStatus.CANCELLED, String.valueOf(cancelled.reason())));
                } else if (outcome instanceof ConversationOutcome.Failed failed) {
                    observer.onTerminal(new RunTerminal(
                            RunTerminalStatus.FAILED, message(failed.error())));
                }
            }
        };
    }

    private static String message(Throwable failure) {
        if (failure == null) {
            return "未知错误";
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
