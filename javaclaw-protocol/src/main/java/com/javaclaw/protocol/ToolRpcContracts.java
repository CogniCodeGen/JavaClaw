package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.WorkspaceId;

/** Tool 目录查询与 Turn 内渐进搜索共用的 Protocol v3 契约。 */
public final class ToolRpcContracts {
    private ToolRpcContracts() {}

    /**
     * Turn 内搜索参数。
     *
     * @param query 名称、说明或标签关键词；空字符串表示列出有界候选
     * @param limit 最大结果数，1 到 100
     */
    public record SearchArguments(String query, int limit) {
        /** 校验关键词和页大小。 */
        public SearchArguments {
            query = normalizeQuery(query);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
        }
    }

    /**
     * SDK 的实时目录查询参数。
     *
     * @param workspaceId Workspace
     * @param permissionProfileId 权限配置标识
     * @param permissionProfileVersion 冻结版本
     * @param agentRole 精确 Agent Role；存在时按其工具可见范围返回可执行目录
     * @param query 名称、说明或标签关键词；空字符串表示列出有界候选
     * @param limit 最大结果数
     */
    public record CatalogQuery(
            WorkspaceId workspaceId,
            String permissionProfileId,
            long permissionProfileVersion,
            Optional<AgentRoleRef> agentRole,
            String query,
            int limit) {
        /** 校验目录查询。 */
        public CatalogQuery {
            Objects.requireNonNull(workspaceId, "workspaceId");
            permissionProfileId = text(permissionProfileId, "permissionProfileId");
            if (permissionProfileVersion < 1) {
                throw new IllegalArgumentException("permissionProfileVersion must be positive");
            }
            agentRole = Objects.requireNonNull(agentRole, "agentRole");
            query = normalizeQuery(query);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
        }

        /**
         * 创建 PermissionProfile 编辑器使用的候选查询。
         *
         * @param workspaceId Workspace
         * @param permissionProfileId 权限配置标识
         * @param permissionProfileVersion 精确版本
         * @param query 查询词
         * @param limit 最大结果数
         */
        public CatalogQuery(
                WorkspaceId workspaceId,
                String permissionProfileId,
                long permissionProfileVersion,
                String query,
                int limit) {
            this(workspaceId, permissionProfileId, permissionProfileVersion, Optional.empty(), query, limit);
        }
    }

    /**
     * 搜索结果。
     *
     * @param catalogRevision 当前未分页可执行目录的权威版本
     * @param tools 当前权限可见的工具描述
     */
    public record SearchResult(long catalogRevision, List<ToolDescriptor> tools) {
        /** 校验目录版本并复制结果列表。 */
        public SearchResult {
            if (catalogRevision < 1) {
                throw new IllegalArgumentException("catalogRevision must be positive");
            }
            tools = List.copyOf(tools);
            if (tools.size() > 100) {
                throw new IllegalArgumentException("tools must not exceed 100 items");
            }
        }

        /** @return 不暴露 Protocol 类型的 API 查询结果 */
        public ToolCatalogQueryResult toApi() {
            return new ToolCatalogQueryResult(catalogRevision, tools);
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeQuery(String value) {
        String normalized = Objects.requireNonNull(value, "query").strip();
        if (normalized.length() > 1_000) {
            throw new IllegalArgumentException("query must not exceed 1000 characters");
        }
        return normalized;
    }
}
