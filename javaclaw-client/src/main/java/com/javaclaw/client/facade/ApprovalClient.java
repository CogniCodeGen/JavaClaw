package com.javaclaw.client.facade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.TurnId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.protocol.CoreRpcContracts;

/** 等待审批查询与乐观锁决议 facade。 */
public final class ApprovalClient {
    private final RpcClientConnection connection;

    /**
     * 创建审批客户端。
     *
     * @param connection 已初始化连接
     */
    public ApprovalClient(RpcClientConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * 列出审批。
     *
     * @param turnId 可选 Turn 过滤
     * @param includeResolved 是否包含终态
     * @return 按创建时间排序的审批
     */
    public List<ApprovalRecord> list(Optional<TurnId> turnId, boolean includeResolved) {
        return connection
                .query(
                        "approval/list",
                        new CoreRpcContracts.ApprovalListPayload(turnId, includeResolved),
                        CoreRpcContracts.ApprovalListResult.class)
                .approvals();
    }

    /**
     * 批准或拒绝一次工具调用。
     *
     * @param approvalId 审批 ID
     * @param decision 决议
     * @param reason 简短原因
     * @param options 幂等键与审批当前 revision
     * @return 终态审批
     */
    public ApprovalRecord resolve(String approvalId, ApprovalDecision decision, String reason, CommandOptions options) {
        return connection.command(
                "approval/resolve",
                new CoreRpcContracts.ApprovalResolvePayload(approvalId, decision, reason),
                options,
                ApprovalRecord.class);
    }
}
