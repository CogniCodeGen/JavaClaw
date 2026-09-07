package com.javaclaw.protocol;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ResolvedTurnConfigSummary;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;

/** Protocol v3 Core 方法的请求 payload；领域响应直接复用 {@code javaclaw-api}。 */
public final class CoreRpcContracts {
    private CoreRpcContracts() {}

    /**
     * Workspace 创建参数。
     *
     * @param name 用户可见名称
     * @param root 规范绝对根目录
     * @param execution 初始独立执行选择；空值继承安装默认
     */
    public record WorkspaceCreatePayload(String name, Path root, Optional<ExecutionOverrides> execution) {
        /** 校验参数。 */
        public WorkspaceCreatePayload {
            name = text(name, "name");
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            execution = Objects.requireNonNull(execution, "execution");
        }

        /**
         * 创建继承安装默认配置的 Workspace 参数。
         *
         * @param name 用户可见名称
         * @param root 规范绝对根目录
         */
        public WorkspaceCreatePayload(String name, Path root) {
            this(name, root, Optional.empty());
        }
    }

    /**
     * Workspace 重命名参数；根目录不可变。
     *
     * @param workspaceId Workspace
     * @param name 新的用户可见名称
     */
    public record WorkspaceRenamePayload(WorkspaceId workspaceId, String name) {
        /** 校验 Workspace 和名称。 */
        public WorkspaceRenamePayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            name = text(name, "name");
        }
    }

    /**
     * Workspace 归档参数。
     *
     * @param workspaceId Workspace
     */
    public record WorkspaceArchivePayload(WorkspaceId workspaceId) {
        /** 校验 Workspace。 */
        public WorkspaceArchivePayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * Thread 创建参数。
     *
     * @param workspaceId 所属 Workspace
     * @param parentThreadId 父 Thread；根 Thread 为空
     * @param executionIntent 服务端验证的执行隔离意图
     * @param title 标题
     */
    public record ThreadCreatePayload(
            WorkspaceId workspaceId,
            Optional<ThreadId> parentThreadId,
            ThreadExecutionIntent executionIntent,
            String title) {
        /** 校验参数。 */
        public ThreadCreatePayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            parentThreadId = Objects.requireNonNull(parentThreadId, "parentThreadId");
            Objects.requireNonNull(executionIntent, "executionIntent");
            boolean rootIntent = parentThreadId.isEmpty() && executionIntent == ThreadExecutionIntent.WORKSPACE;
            boolean childIntent = parentThreadId.isPresent() && executionIntent != ThreadExecutionIntent.WORKSPACE;
            if (!rootIntent && !childIntent) {
                throw new IllegalArgumentException("root and child Thread execution intent do not match");
            }
            title = text(title, "title");
        }
    }

    /**
     * Turn 启动参数；选择在服务端统一解析，附件必须已上传到所属 Workspace。
     *
     * @param threadId 所属 Thread
     * @param execution 独立 Role、模型、权限与可收窄执行选项
     * @param message 用户消息，可为空字符串
     * @param attachments 已上传附件引用，不可空
     */
    public record TurnStartPayload(
            ThreadId threadId, ExecutionOverrides execution, String message, List<AttachmentRef> attachments) {
        /** 校验执行选择与输入并冻结附件列表。 */
        public TurnStartPayload {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(execution, "execution");
            message = Objects.requireNonNull(message, "message");
            attachments = List.copyOf(attachments);
        }

        /**
         * 创建不含附件的 Turn 启动参数。
         *
         * @param threadId 所属 Thread
         * @param execution 独立执行选择
         * @param message 用户消息
         */
        public TurnStartPayload(ThreadId threadId, ExecutionOverrides execution, String message) {
            this(threadId, execution, message, List.of());
        }
    }

    /**
     * 已接受的 Turn 与安全执行摘要；不包含 Prompt 正文、凭据或内部策略。
     *
     * @param turn 已持久化 Turn
     * @param configuration 本 Turn 冻结的安全执行摘要
     */
    public record TurnStartResult(AgentTurn turn, ResolvedTurnConfigSummary configuration) {
        /** 校验 Turn 与摘要一致，避免展示尚未冻结的选择。 */
        public TurnStartResult {
            Objects.requireNonNull(turn, "turn");
            Objects.requireNonNull(configuration, "configuration");
            if (!configuration.equals(turn.resolvedConfig())) {
                throw new IllegalArgumentException("configuration must match the frozen Turn");
            }
        }
    }

    /**
     * Thread 标识查询参数。
     *
     * @param threadId Thread
     */
    public record ThreadQuery(ThreadId threadId) {
        /** 校验 Thread。 */
        public ThreadQuery {
            Objects.requireNonNull(threadId, "threadId");
        }
    }

    /**
     * Turn 标识查询参数。
     *
     * @param turnId Turn
     */
    public record TurnQuery(TurnId turnId) {
        /** 校验 Turn。 */
        public TurnQuery {
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * Turn 取消参数。
     *
     * @param turnId Turn
     * @param reason 用户可见且已脱敏的原因
     */
    public record TurnCancelPayload(TurnId turnId, String reason) {
        /** 校验取消参数。 */
        public TurnCancelPayload {
            Objects.requireNonNull(turnId, "turnId");
            reason = text(reason, "reason");
            if (reason.length() > 500) {
                throw new IllegalArgumentException("reason must not exceed 500 characters");
            }
        }
    }

    /**
     * Workspace 标识查询参数。
     *
     * @param workspaceId Workspace
     */
    public record WorkspaceQuery(WorkspaceId workspaceId) {
        /** 校验 Workspace。 */
        public WorkspaceQuery {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * Item 分页查询参数。
     *
     * @param threadId Thread
     * @param afterSequence 只返回此 sequence 之后的 Item；从头读取为 0
     * @param limit 最大返回数，1 到 1000
     */
    public record ItemList(ThreadId threadId, long afterSequence, int limit) {
        /** 校验游标和页大小。 */
        public ItemList {
            Objects.requireNonNull(threadId, "threadId");
            if (afterSequence < 0 || limit < 1 || limit > 1_000) {
                throw new IllegalArgumentException("invalid item page");
            }
        }
    }

    /**
     * Rollout 导出参数。
     *
     * @param threadId Thread
     * @param outputFile 目标 JSONL 文件
     */
    public record RolloutExportPayload(ThreadId threadId, Path outputFile) {
        /** 校验参数。 */
        public RolloutExportPayload {
            Objects.requireNonNull(threadId, "threadId");
            outputFile = Objects.requireNonNull(outputFile, "outputFile")
                    .toAbsolutePath()
                    .normalize();
        }
    }

    /**
     * 审批列表条件。
     *
     * @param turnId 可选 Turn 过滤
     * @param includeResolved 是否包含终态审批
     */
    public record ApprovalListPayload(Optional<TurnId> turnId, boolean includeResolved) {
        /** 复制可选过滤条件。 */
        public ApprovalListPayload {
            turnId = Objects.requireNonNull(turnId, "turnId");
        }
    }

    /**
     * 客户端审批决议。
     *
     * @param approvalId 审批 ID
     * @param decision 只允许批准或拒绝
     * @param reason 简短用户原因
     */
    public record ApprovalResolvePayload(String approvalId, ApprovalDecision decision, String reason) {
        /** 校验审批决议。 */
        public ApprovalResolvePayload {
            approvalId = text(approvalId, "approvalId");
            Objects.requireNonNull(decision, "decision");
            reason = text(reason, "reason");
            if (reason.length() > 500) {
                throw new IllegalArgumentException("reason must not exceed 500 characters");
            }
        }
    }

    /**
     * Workspace 列表结果。
     *
     * @param workspaces Workspace 快照
     */
    public record WorkspaceListResult(List<Workspace> workspaces) {
        /** 复制结果。 */
        public WorkspaceListResult {
            workspaces = List.copyOf(workspaces);
        }
    }

    /**
     * Thread 列表结果。
     *
     * @param threads Thread 快照
     */
    public record ThreadListResult(List<ConversationThread> threads) {
        /** 复制结果。 */
        public ThreadListResult {
            threads = List.copyOf(threads);
        }
    }

    /**
     * Item 分页结果。
     *
     * @param items Item
     * @param nextSequence 下一页游标；空页沿用请求游标
     */
    public record ItemListResult(List<ItemEnvelope> items, long nextSequence) {
        /** 复制结果并校验游标。 */
        public ItemListResult {
            items = List.copyOf(items);
            if (nextSequence < 0) {
                throw new IllegalArgumentException("nextSequence must not be negative");
            }
        }
    }

    /**
     * 审批列表结果。
     *
     * @param approvals 按创建时间排序的审批快照
     */
    public record ApprovalListResult(List<ApprovalRecord> approvals) {
        /** 复制审批列表。 */
        public ApprovalListResult {
            approvals = List.copyOf(approvals);
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static long positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
