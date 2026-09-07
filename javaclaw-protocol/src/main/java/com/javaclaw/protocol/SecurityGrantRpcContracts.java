package com.javaclaw.protocol;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.api.PrivateNetworkGrantPreview;
import com.javaclaw.api.PrivateNetworkPurpose;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.WorkspaceId;

/** 私网授权、无人值守授权与决策审计的 Protocol v3 DTO。 */
public final class SecurityGrantRpcContracts {
    private SecurityGrantRpcContracts() {}

    /**
     * 私网授权预览请求。
     *
     * @param workspaceId 所属 Workspace
     * @param purpose MCP 或 Site
     * @param origin 精确 HTTPS Origin
     * @param dnsAddresses 当前解析得到的完整数字地址集合
     * @param validity 可选有效期；为空时一小时
     */
    public record PrivateNetworkPreviewPayload(
            WorkspaceId workspaceId,
            PrivateNetworkPurpose purpose,
            URI origin,
            Set<String> dnsAddresses,
            Optional<Duration> validity) {
        /** 复制集合并校验必填字段。 */
        public PrivateNetworkPreviewPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(origin, "origin");
            dnsAddresses = Set.copyOf(dnsAddresses);
            validity = Objects.requireNonNull(validity, "validity");
        }
    }

    /**
     * 提交已确认私网授权预览。
     *
     * @param preview 规范化预览及确认摘要
     */
    public record PrivateNetworkCreatePayload(PrivateNetworkGrantPreview preview) {
        /** 校验预览。 */
        public PrivateNetworkCreatePayload {
            Objects.requireNonNull(preview, "preview");
        }
    }

    /**
     * 按 Workspace 查询授权。
     *
     * @param workspaceId Workspace
     */
    public record WorkspaceGrantQuery(WorkspaceId workspaceId) {
        /** 校验 Workspace。 */
        public WorkspaceGrantQuery {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * 查询单个授权历史。
     *
     * @param grantId 授权标识
     */
    public record GrantHistoryQuery(String grantId) {
        /** 校验授权标识。 */
        public GrantHistoryQuery {
            grantId = identifier(grantId);
        }
    }

    /**
     * 撤销一个授权。
     *
     * @param grantId 授权标识
     */
    public record GrantRevokePayload(String grantId) {
        /** 校验授权标识。 */
        public GrantRevokePayload {
            grantId = identifier(grantId);
        }
    }

    /**
     * 私网授权最新版本列表。
     *
     * @param grants 按标识排序的授权
     */
    public record PrivateNetworkListResult(List<PrivateNetworkGrant> grants) {
        /** 复制结果。 */
        public PrivateNetworkListResult {
            grants = List.copyOf(grants);
        }
    }

    /**
     * 私网授权不可变历史。
     *
     * @param grants revision 升序历史
     */
    public record PrivateNetworkHistoryResult(List<PrivateNetworkGrant> grants) {
        /** 复制结果。 */
        public PrivateNetworkHistoryResult {
            grants = List.copyOf(grants);
        }
    }

    /**
     * 创建无人值守授权。
     *
     * @param draft 用户确认的完整授权配置
     */
    public record UnattendedCreatePayload(UnattendedToolGrantDraft draft) {
        /** 校验配置。 */
        public UnattendedCreatePayload {
            Objects.requireNonNull(draft, "draft");
        }
    }

    /**
     * 无人值守授权最新状态列表。
     *
     * @param grants 授权与使用余额
     */
    public record UnattendedListResult(List<UnattendedToolGrantStatus> grants) {
        /** 复制结果。 */
        public UnattendedListResult {
            grants = List.copyOf(grants);
        }
    }

    /**
     * 无人值守授权不可变历史。
     *
     * @param grants revision 升序历史
     */
    public record UnattendedHistoryResult(List<UnattendedToolGrant> grants) {
        /** 复制结果。 */
        public UnattendedHistoryResult {
            grants = List.copyOf(grants);
        }
    }

    /**
     * 权限决策审计查询。
     *
     * @param workspaceId 所属 Workspace
     * @param grantKind 可选授权类型
     * @param grantId 可选授权标识
     * @param limit 返回上限，范围 1 至 500
     */
    public record PermissionDecisionListPayload(
            WorkspaceId workspaceId, Optional<SecurityGrantKind> grantKind, Optional<String> grantId, int limit) {
        /** 校验查询范围。 */
        public PermissionDecisionListPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            grantKind = Objects.requireNonNull(grantKind, "grantKind");
            grantId = Objects.requireNonNull(grantId, "grantId").map(SecurityGrantRpcContracts::identifier);
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("limit must be between 1 and 500");
            }
        }
    }

    /**
     * 权限决策审计结果。
     *
     * @param traces 新到旧排序的脱敏记录
     */
    public record PermissionDecisionListResult(List<PermissionDecisionTrace> traces) {
        /** 复制结果。 */
        public PermissionDecisionListResult {
            traces = List.copyOf(traces);
        }
    }

    private static String identifier(String value) {
        String checked = Objects.requireNonNull(value, "grantId").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("grantId contains unsupported characters");
        }
        return checked;
    }
}
