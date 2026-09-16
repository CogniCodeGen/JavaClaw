package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.javaclaw.api.SecurityGrantState;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

/** 浏览器专用精确来源授权；不构成普通 PermissionProfile、MCP 或账号授权。 */
public final class BrowserGrantContracts {
    private BrowserGrantContracts() {}

    /**
     * 当前对话授权的对象包装，保持扩展通道的 JSON object 契约。
     *
     * @param grants 权威最新授权版本的不可变列表，包含已撤销记录
     */
    public record GrantList(List<Grant> grants) {
        /** 固定列表快照，拒绝缺失记录。 */
        public GrantList {
            grants = List.copyOf(grants);
        }
    }

    /**
     * 用户确认前的完整预览；摘要包含作用域、来源和到期时刻，不能修改后沿用。
     *
     * @param workspaceId 所属 Workspace，不可空
     * @param threadId 唯一 Thread，不可空
     * @param origin 精确 HTTPS 来源，不含路径、用户信息、查询和片段
     * @param expiresAt 预览到期时刻，不是授权到期时刻
     * @param digest 预览内容的 SHA-256 摘要
     */
    public record Preview(WorkspaceId workspaceId, ThreadId threadId, URI origin, Instant expiresAt, String digest) {
        /** 校验预览边界。 */
        public Preview {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            origin = normalizeOrigin(origin);
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Browser preview digest must be SHA-256");
            }
        }
    }

    /**
     * 不可变授权版本；授权保持到主动撤销，网络操作还必须持有当前 Turn 和短期租约。
     *
     * @param id 授权 UUID
     * @param revision 安全版本，从一开始，撤销递增
     * @param state ACTIVE 或不可逆 REVOKED
     * @param workspaceId 唯一 Workspace
     * @param threadId 唯一 Thread
     * @param origin 精确 HTTPS 来源
     * @param createdAt 此版本创建时刻
     */
    public record Grant(
            String id,
            long revision,
            SecurityGrantState state,
            WorkspaceId workspaceId,
            ThreadId threadId,
            URI origin,
            Instant createdAt) {
        /** 校验不可变授权。 */
        public Grant {
            id = uuid(id);
            positive(revision);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            origin = normalizeOrigin(origin);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 精确的授权版本引用。
     *
     * @param id 授权 UUID
     * @param revision 冻结的安全版本，从一开始
     */
    public record GrantRef(String id, long revision) {
        /** 校验精确安全版本。 */
        public GrantRef {
            id = uuid(id);
            positive(revision);
        }
    }

    /**
     * 服务端持久冻结的 Turn 快照；构造 DTO 不会授予权限，服务端每次核对已存快照及实时授权。
     *
     * @param snapshotId 快照 UUID
     * @param workspaceId 唯一 Workspace
     * @param threadId 唯一 Thread
     * @param turnId 唯一 Turn
     * @param grants 精确来源到授权安全版本的不可变映射，最多 128 项
     * @param frozenAt 冻结时刻
     */
    public record Snapshot(
            String snapshotId,
            WorkspaceId workspaceId,
            ThreadId threadId,
            TurnId turnId,
            Map<URI, GrantRef> grants,
            Instant frozenAt) {
        /** 校验并冻结来源映射。 */
        public Snapshot {
            snapshotId = uuid(snapshotId);
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            grants = Map.copyOf(grants);
            if (grants.size() > 128) {
                throw new IllegalArgumentException("Browser snapshot may contain at most 128 origins");
            }
            grants.keySet().forEach(BrowserGrantContracts::normalizeOrigin);
            Objects.requireNonNull(frozenAt, "frozenAt");
        }

        /** @return 冻结来源集合；不包含此 Turn 冻结之后新增的授权 */
        public Set<URI> origins() {
            return grants.keySet();
        }
    }

    /**
     * 规范化精确 HTTPS 来源；省略默认 443 端口并统一主机大小写。
     *
     * @param value 来源 URI；仅可带空路径或单独的斜线
     * @return 规范化且无路径的来源
     */
    public static URI normalizeOrigin(URI value) {
        URI origin = com.javaclaw.api.PrivateNetworkGrant.normalizeOrigin(value);
        if (!"https".equals(origin.getScheme())) {
            throw new IllegalArgumentException("Browser origin grants require HTTPS");
        }
        return origin;
    }

    private static String uuid(String value) {
        return UUID.fromString(Objects.requireNonNull(value, "id")).toString();
    }

    private static void positive(long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("Browser grant revision must be positive");
        }
    }
}
