package com.javaclaw.client.extension;

import java.util.Optional;

import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;

/** Loop Definition、真实证据确认与可恢复 Execution 的强类型 SDK facade。 */
public final class LoopClient {
    private final AutomationClientSupport<LoopContracts.Definition, LoopContracts.ManagementSaveRequest> support;

    LoopClient(ExtensionClient extensions) {
        support = new AutomationClientSupport<>(
                extensions,
                BuiltinExtensionIds.LOOP,
                LoopContracts.Definition.class,
                "definition/save",
                "definition/save",
                LoopContracts.ManagementSaveRequest::id);
    }

    /** @return 指定 Loop Definition */
    public LoopContracts.Definition read(WorkspaceId workspaceId, String id) {
        return support.read(workspaceId, id);
    }

    /** @return 稳定键分页的 Loop Definition */
    public TypedDocumentPage<LoopContracts.Definition> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return support.list(workspaceId, afterKey, limit);
    }

    /** @return 由服务端生成 revision 和时间的 Loop Definition */
    public LoopContracts.Definition create(
            WorkspaceId workspaceId, LoopContracts.ManagementSaveRequest request, CommandOptions options) {
        return support.create(workspaceId, request, options);
    }

    /** @return 按 expected revision 更新后的 Loop Definition */
    public LoopContracts.Definition update(
            WorkspaceId workspaceId, LoopContracts.ManagementSaveRequest request, CommandOptions options) {
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

    /** @return 人工证据决议后的 Execution 摘要 */
    public ExtensionExecutionReceipt confirm(
            WorkspaceId workspaceId, LoopContracts.Confirmation confirmation, CommandOptions options) {
        return support.mutateExecution(workspaceId, "execution/confirm", confirmation, confirmation.jobId(), options);
    }
}
