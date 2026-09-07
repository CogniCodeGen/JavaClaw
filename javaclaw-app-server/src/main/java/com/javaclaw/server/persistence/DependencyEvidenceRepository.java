package com.javaclaw.server.persistence;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.protocol.CanonicalJson;

/** 保存依赖准备的独立证据版本；不改变操作状态或触发任何项目副作用。 */
public final class DependencyEvidenceRepository {
    private final H2Transactions transactions;
    private final CanonicalJson json;

    /**
     * 绑定已经迁移的 data-v6。
     *
     * @param database 服务端数据库
     * @param json 严格规范 JSON
     */
    public DependencyEvidenceRepository(H2Database database, CanonicalJson json) {
        transactions = new H2Transactions(database);
        this.json = json;
    }

    /**
     * 写入执行前或执行后证据；已完成操作及跨 Workspace 更新拒绝。
     *
     * @param workspaceId 已验证 Workspace
     * @param evidence 当前操作的证据，不修改 start 次数
     */
    public void record(WorkspaceId workspaceId, DependencyEvidence evidence) {
        execute(connection -> {
            requireActiveTurn(connection, workspaceId, evidence.operationId());
            try (var statement = connection.prepareStatement("""
                    UPDATE CORE.CODING_OPERATION SET DEPENDENCY_EVIDENCE_JSON=?
                    WHERE ID=? AND WORKSPACE_ID=? AND OPERATION_NAME='dependencies_prepare'
                    AND STATE IN ('PREPARED','STARTED')
                    """)) {
                statement.setString(1, json.encode(evidence).json());
                statement.setString(2, evidence.operationId());
                statement.setString(3, workspaceId.toString());
                if (statement.executeUpdate() != 1) {
                    throw new SecurityException("依赖证据不属于此 Workspace、操作种类或执行阶段");
                }
            }
            return null;
        });
    }

    private static void requireActiveTurn(java.sql.Connection connection, WorkspaceId workspaceId, String operationId)
            throws java.sql.SQLException {
        // 与 Turn 终态提交互斥，不能在关闭超时或外部撤销之后补写新的执行证据。
        try (var statement = connection.prepareStatement("""
                SELECT STATUS FROM CORE.AGENT_TURN WHERE ID=(
                    SELECT TURN_ID FROM CORE.CODING_OPERATION WHERE ID=? AND WORKSPACE_ID=?) FOR UPDATE
                """)) {
            statement.setString(1, operationId);
            statement.setString(2, workspaceId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !java.util.Set.of("RUNNING", "WAITING").contains(rows.getString(1))) {
                    throw new SecurityException("Turn 已结束或证据身份无效，拒绝追加依赖证据");
                }
            }
        }
    }

    /**
     * 读取持久证据；调用方仍须验证当前 Thread/Turn 证据访问权限。
     *
     * @param workspaceId 已验证 Workspace
     * @param operationId 仅用于定位的资源标识
     * @return 已保存的前后快照和原文引用
     */
    public DependencyEvidence read(WorkspaceId workspaceId, String operationId) {
        return execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT DEPENDENCY_EVIDENCE_JSON FROM CORE.CODING_OPERATION
                    WHERE ID=? AND WORKSPACE_ID=? AND OPERATION_NAME='dependencies_prepare'
                    """)) {
                statement.setString(1, operationId);
                statement.setString(2, workspaceId.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        throw new SecurityException("依赖准备证据不存在或不属于当前 Workspace");
                    }
                    return json.decode(new CanonicalPayload(rows.getString(1)), DependencyEvidence.class);
                }
            }
        });
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("依赖准备证据事务失败", failure);
        }
    }
}
