package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AutomationExecutionSnapshot;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

/** Workflow 执行固定 Tool 与创建用户输入 Turn 的平台治理端口。 */
public interface AutomationStepPort {
    /**
     * 创建始终安全拒绝的端口。
     *
     * @return 未装配治理执行器的实现
     */
    static AutomationStepPort unavailable() {
        return new UnavailableAutomationStepPort();
    }

    /**
     * 在冻结目录和实时权限约束下执行精确工具，不经过模型选择。
     *
     * @param command 已冻结执行身份与工具参数
     * @param cancellation Execution 取消信号
     * @return 持久 ToolResult 摘要
     * @throws Exception 工具、审批、权限或持久化失败
     */
    ToolResult executeTool(ToolCommand command, CancellationToken cancellation) throws Exception;

    /**
     * 创建只承载 InputRequest 的受治理 Turn，并立即进入等待状态。
     *
     * @param command 已冻结执行身份与非 Secret 输入 Schema
     * @param cancellation Execution 取消信号
     * @return 输入请求与所属 Turn
     * @throws Exception 创建或持久化失败
     */
    InputResult openInput(InputCommand command, CancellationToken cancellation) throws Exception;

    /**
     * Workflow 单元共享身份。
     *
     * @param workspaceId Workspace
     * @param parentThreadId 父 Thread；顶层为空
     * @param title 子 Thread 标题
     * @param snapshot Execution 启动时冻结的 Profile、权限、预算与工具目录
     * @param idempotencyKey 工作单元恢复键
     */
    record StepContext(
            WorkspaceId workspaceId,
            Optional<ThreadId> parentThreadId,
            String title,
            AutomationExecutionSnapshot snapshot,
            String idempotencyKey) {
        /** 校验共享身份。 */
        public StepContext {
            Objects.requireNonNull(workspaceId, "workspaceId");
            parentThreadId = Objects.requireNonNull(parentThreadId, "parentThreadId");
            title = text(title, "title");
            Objects.requireNonNull(snapshot, "snapshot");
            idempotencyKey = text(idempotencyKey, "idempotencyKey");
        }
    }

    /**
     * 精确工具命令。
     *
     * @param context 单元身份
     * @param toolName 冻结目录中的全局名称
     * @param arguments 规范参数
     */
    record ToolCommand(StepContext context, String toolName, CanonicalPayload arguments) {
        /** 校验工具命令。 */
        public ToolCommand {
            Objects.requireNonNull(context, "context");
            toolName = text(toolName, "toolName");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 已提交工具结果。
     *
     * @param threadId 工具专用 Thread
     * @param turnId 工具专用 Turn
     * @param successful 工具是否成功
     * @param output 规范输出
     * @param effectReceiptKey 已提交副作用的恢复键
     */
    record ToolResult(
            ThreadId threadId,
            TurnId turnId,
            boolean successful,
            CanonicalPayload output,
            Optional<String> effectReceiptKey) {
        /** 校验工具结果。 */
        public ToolResult {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(output, "output");
            effectReceiptKey = Objects.requireNonNull(effectReceiptKey, "effectReceiptKey")
                    .map(value -> text(value, "effectReceiptKey"));
        }
    }

    /**
     * 用户输入命令。
     *
     * @param context 单元身份
     * @param prompt 用户可见问题
     * @param responseSchema 非 Secret 对象 Schema
     * @param expiresAt 绝对到期时间
     */
    record InputCommand(StepContext context, String prompt, CanonicalPayload responseSchema, Instant expiresAt) {
        /** 校验用户输入命令。 */
        public InputCommand {
            Objects.requireNonNull(context, "context");
            prompt = text(prompt, "prompt");
            Objects.requireNonNull(responseSchema, "responseSchema");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 已持久化输入请求身份。
     *
     * @param threadId 输入专用 Thread
     * @param turnId 输入专用 Turn
     * @param requestId InputRequest ID
     */
    record InputResult(ThreadId threadId, TurnId turnId, String requestId) {
        /** 校验输入结果。 */
        public InputResult {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            requestId = text(requestId, "requestId");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
        return normalized;
    }

    /** 在平台执行器完成装配前安全拒绝所有步骤。 */
    final class UnavailableAutomationStepPort implements AutomationStepPort {
        @Override
        public ToolResult executeTool(ToolCommand command, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            throw new IllegalStateException("Automation step port is unavailable");
        }

        @Override
        public InputResult openInput(InputCommand command, CancellationToken cancellation) {
            cancellation.throwIfCancelled();
            throw new IllegalStateException("Automation step port is unavailable");
        }
    }
}
