package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PromptOptimizationAdoption;
import com.javaclaw.api.PromptOptimizationDraft;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.PromptOptimizationRpcContracts;

/** Agent Role Prompt 优化、取消与人工采纳的强类型 SDK facade。 */
public final class PromptOptimizationClient {
    private final RpcClientConnection connection;

    /**
     * 创建 facade。
     *
     * @param connection 已初始化连接
     */
    public PromptOptimizationClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 异步启动可能计费的普通 Harness Turn；RPC 只返回初始任务快照。
     *
     * @param workspaceId Workspace
     * @param role 精确源 Agent Role
     * @param execution 独立模型、权限和预算选择
     * @param billingConfirmed 必须由调用方显式传 true
     * @param confirmation 固定计费确认文本
     * @param options expected revision 必须为 0；网络重试复用同一幂等键
     * @return 初始或幂等恢复的任务投影
     */
    public PromptOptimizationDraft start(
            WorkspaceId workspaceId,
            AgentRoleRef role,
            ExecutionOverrides execution,
            boolean billingConfirmed,
            String confirmation,
            CommandOptions options) {
        CommandOptions checked = requireRevision(options, 0, "Prompt optimization start");
        requireConfirmation(
                billingConfirmed,
                confirmation,
                PromptOptimizationRpcContracts.BILLING_CONFIRMATION,
                "Prompt optimization billing");
        return connection.command(
                "agent/role/prompt/optimization/start",
                new PromptOptimizationRpcContracts.StartPayload(
                        workspaceId, role, execution, billingConfirmed, confirmation),
                checked,
                PromptOptimizationDraft.class);
    }

    /**
     * 读取任务最新投影。
     *
     * @param id 优化任务标识
     * @return 权威 Turn/Item 投影
     */
    public PromptOptimizationDraft read(PromptOptimizationId id) {
        return connection.query(
                "agent/role/prompt/optimization/read",
                new PromptOptimizationRpcContracts.ReadPayload(id),
                PromptOptimizationDraft.class);
    }

    /**
     * 列出 Workspace 的任务。
     *
     * @param workspaceId Workspace
     * @return 创建时间倒序目录
     */
    public List<PromptOptimizationDraft> list(WorkspaceId workspaceId) {
        return connection
                .query(
                        "agent/role/prompt/optimization/list",
                        new PromptOptimizationRpcContracts.ListPayload(workspaceId),
                        PromptOptimizationRpcContracts.ListResult.class)
                .drafts();
    }

    /**
     * 取消活动优化 Turn。
     *
     * @param id 优化任务标识
     * @param reason 脱敏原因
     * @param options expected revision 必须匹配当前 Turn revision
     * @return 取消后的任务投影
     */
    public PromptOptimizationDraft cancel(PromptOptimizationId id, String reason, CommandOptions options) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (checked.expectedRevision() < 1) {
            throw new IllegalArgumentException("Prompt optimization cancel requires positive Turn revision");
        }
        return connection.command(
                "agent/role/prompt/optimization/cancel",
                new PromptOptimizationRpcContracts.CancelPayload(id, reason),
                checked,
                PromptOptimizationDraft.class);
    }

    /**
     * 人工采纳 READY 草稿并创建新的 Agent Role revision。
     *
     * @param id 优化任务标识
     * @param adoptionConfirmed 必须由调用方显式传 true
     * @param confirmation 固定人工采纳确认文本
     * @param options expected revision 必须等于源 Role revision
     * @return 草稿和新 Role
     */
    public PromptOptimizationAdoption adopt(
            PromptOptimizationId id, boolean adoptionConfirmed, String confirmation, CommandOptions options) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (checked.expectedRevision() < 1) {
            throw new IllegalArgumentException("Prompt adoption requires positive source Role revision");
        }
        requireConfirmation(
                adoptionConfirmed,
                confirmation,
                PromptOptimizationRpcContracts.ADOPTION_CONFIRMATION,
                "Prompt adoption");
        return connection.command(
                "agent/role/prompt/optimization/adopt",
                new PromptOptimizationRpcContracts.AdoptPayload(id, adoptionConfirmed, confirmation),
                checked,
                PromptOptimizationAdoption.class);
    }

    private static CommandOptions requireRevision(CommandOptions options, long revision, String operation) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (checked.expectedRevision() != revision) {
            throw new IllegalArgumentException(operation + " expected revision does not match");
        }
        return checked;
    }

    private static void requireConfirmation(boolean confirmed, String actual, String expected, String operation) {
        if (!confirmed
                || !expected.equals(Objects.requireNonNullElse(actual, "").strip())) {
            throw new IllegalArgumentException(operation + " requires the exact confirmation");
        }
    }
}
