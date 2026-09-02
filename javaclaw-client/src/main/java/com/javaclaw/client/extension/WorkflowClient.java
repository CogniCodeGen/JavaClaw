package com.javaclaw.client.extension;

import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.builtin.contracts.WorkflowManagementContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;

/** Workflow Graph Definition、输入续跑与可恢复 Execution 的强类型 SDK facade。 */
public final class WorkflowClient {
    private final AutomationClientSupport<WorkflowContracts.Definition, WorkflowManagementContracts.SaveRequest>
            support;

    WorkflowClient(ExtensionClient extensions) {
        support = new AutomationClientSupport<>(
                extensions,
                BuiltinExtensionIds.WORKFLOW,
                WorkflowContracts.Definition.class,
                "definition/save",
                "definition/save",
                WorkflowManagementContracts.SaveRequest::id);
    }

    /** @return 指定 Workflow Definition */
    public WorkflowContracts.Definition read(WorkspaceId workspaceId, String id) {
        return support.read(workspaceId, id);
    }

    /** @return 稳定键分页的 Workflow Definition */
    public TypedDocumentPage<WorkflowContracts.Definition> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return support.list(workspaceId, afterKey, limit);
    }

    /** @return 由平台编译并生成 revision 的安全 Graph Definition */
    public WorkflowContracts.Definition create(
            WorkspaceId workspaceId, WorkflowManagementContracts.SaveRequest request, CommandOptions options) {
        return support.create(workspaceId, request, options);
    }

    /** @return 按 expected revision 更新后的安全 Graph Definition */
    public WorkflowContracts.Definition update(
            WorkspaceId workspaceId, WorkflowManagementContracts.SaveRequest request, CommandOptions options) {
        return support.update(workspaceId, request, options);
    }

    /** @return 删除确认 */
    public DocumentContracts.Deleted delete(WorkspaceId workspaceId, String id, CommandOptions options) {
        return support.delete(workspaceId, id, options);
    }

    /** @return 已事务提交且不含内部恢复 payload 的 Execution 摘要 */
    public ExtensionExecutionReceipt start(
            WorkspaceId workspaceId,
            Optional<ThreadId> parentThreadId,
            OrchestrationContracts.StartRequest request,
            CommandOptions options) {
        return support.start(workspaceId, parentThreadId, request, options);
    }

    /** @return 已消费同一 InputRequest 身份后的 Execution 摘要 */
    public ExtensionExecutionReceipt continueInput(
            WorkspaceId workspaceId, WorkflowContracts.ContinueInput request, CommandOptions options) {
        return support.mutateExecution(workspaceId, "execution/input/continue", request, request.jobId(), options);
    }
}
