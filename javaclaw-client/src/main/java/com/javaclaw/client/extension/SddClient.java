package com.javaclaw.client.extension;

import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;

/** SDD Definition、摘要审批与可恢复阶段 Execution 的强类型 SDK facade。 */
public final class SddClient {
    private final AutomationClientSupport<SddContracts.Definition, SddContracts.ManagementSaveRequest> support;

    SddClient(ExtensionClient extensions) {
        support = new AutomationClientSupport<>(
                extensions,
                BuiltinExtensionIds.SDD,
                SddContracts.Definition.class,
                "definition/create",
                "definition/update",
                SddContracts.ManagementSaveRequest::id);
    }

    /** @return 指定 SDD Definition */
    public SddContracts.Definition read(WorkspaceId workspaceId, String id) {
        return support.read(workspaceId, id);
    }

    /** @return 稳定键分页的 SDD Definition */
    public TypedDocumentPage<SddContracts.Definition> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return support.list(workspaceId, afterKey, limit);
    }

    /** @return 由服务端生成 revision、摘要和时间的 SDD Definition */
    public SddContracts.Definition create(
            WorkspaceId workspaceId, SddContracts.ManagementSaveRequest request, CommandOptions options) {
        return support.create(workspaceId, request, options);
    }

    /** @return 按 expected revision 更新后的 SDD Definition */
    public SddContracts.Definition update(
            WorkspaceId workspaceId, SddContracts.ManagementSaveRequest request, CommandOptions options) {
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

    /** @return 摘要审批绑定后的 Execution 摘要 */
    public ExtensionExecutionReceipt approve(
            WorkspaceId workspaceId, SddContracts.Approval approval, CommandOptions options) {
        return support.mutateExecution(workspaceId, "execution/approve", approval, approval.jobId(), options);
    }
}
