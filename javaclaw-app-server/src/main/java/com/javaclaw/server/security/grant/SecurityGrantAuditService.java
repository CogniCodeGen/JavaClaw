package com.javaclaw.server.security.grant;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

/** 读取脱敏权限决策记录的应用服务。 */
public final class SecurityGrantAuditService {
    private final H2Transactions transactions;
    private final SecurityGrantRepository grants = new SecurityGrantRepository();
    private final CanonicalJson json;

    /**
     * 创建审计读取服务。
     *
     * @param database data-v6 数据库
     * @param json 规范 JSON codec
     */
    public SecurityGrantAuditService(H2Database database, CanonicalJson json) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 按 Workspace 与可选授权范围读取最近决策。
     *
     * @param workspaceId 所属 Workspace
     * @param kind 可选授权类型
     * @param grantId 可选授权标识
     * @param limit 返回上限，范围 1 至 500
     * @return 新到旧排序的决策记录
     */
    public List<PermissionDecisionTrace> list(
            WorkspaceId workspaceId, Optional<SecurityGrantKind> kind, Optional<String> grantId, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(kind, "kind");
        Optional<String> checkedGrantId =
                Objects.requireNonNull(grantId, "grantId").map(this::identifier);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return execute(connection -> grants.listTraces(connection, workspaceId, kind, checkedGrantId, limit).stream()
                .map(payload -> json.decode(payload, PermissionDecisionTrace.class))
                .toList());
    }

    private String identifier(String value) {
        String checked = Objects.requireNonNull(value, "grantId").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("grantId contains unsupported characters");
        }
        return checked;
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("安全授权审计事务失败", failure);
        }
    }
}
