package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/**
 * 编排器提交给平台的单 Turn 命令。
 *
 * @param workspaceId Workspace
 * @param parentThreadId 父 Thread；顶层为空
 * @param executionIntent 服务端可验证的执行隔离意图
 * @param title 子 Thread 标题
 * @param executionSnapshot Execution 启动时由平台冻结的完整执行快照
 * @param instruction 已冻结的用户或编排指令
 * @param context 扩展上下文
 * @param idempotencyKey 跨崩溃重试保持不变的业务步骤键
 */
public record OrchestratedTurnCommand(
        WorkspaceId workspaceId,
        Optional<ThreadId> parentThreadId,
        ThreadExecutionIntent executionIntent,
        String title,
        AutomationExecutionSnapshot executionSnapshot,
        String instruction,
        CanonicalPayload context,
        String idempotencyKey) {
    /** 校验输入并复制 Optional。 */
    public OrchestratedTurnCommand {
        Objects.requireNonNull(workspaceId, "workspaceId");
        parentThreadId = Objects.requireNonNull(parentThreadId, "parentThreadId");
        Objects.requireNonNull(executionIntent, "executionIntent");
        boolean rootIntent = parentThreadId.isEmpty() && executionIntent == ThreadExecutionIntent.WORKSPACE;
        boolean childIntent = parentThreadId.isPresent() && executionIntent != ThreadExecutionIntent.WORKSPACE;
        if (!rootIntent && !childIntent) {
            throw new IllegalArgumentException("root and child Thread execution intent do not match");
        }
        title = text(title, "title", 500);
        Objects.requireNonNull(executionSnapshot, "executionSnapshot");
        instruction = text(instruction, "instruction", 100_000);
        Objects.requireNonNull(context, "context");
        idempotencyKey = text(idempotencyKey, "idempotencyKey", 160);
    }

    private static String text(String value, String name, int maximumLength) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return normalized;
    }
}
