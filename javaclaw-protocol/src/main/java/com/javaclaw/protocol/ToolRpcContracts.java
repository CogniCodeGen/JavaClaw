package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.WorkspaceId;

/** Tool 目录查询与 Turn 内渐进搜索共用的 Protocol v2 契约。 */
public final class ToolRpcContracts {
    private ToolRpcContracts() {}

    /**
     * Turn 内搜索参数。
     *
     * @param query 名称、说明或标签关键词
     * @param limit 最大结果数，1 到 100
     */
    public record SearchArguments(String query, int limit) {
        /** 校验关键词和页大小。 */
        public SearchArguments {
            query = text(query, "query");
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
     * @param query 名称、说明或标签关键词
     * @param limit 最大结果数
     */
    public record CatalogQuery(
            WorkspaceId workspaceId,
            String permissionProfileId,
            long permissionProfileVersion,
            String query,
            int limit) {
        /** 校验目录查询。 */
        public CatalogQuery {
            Objects.requireNonNull(workspaceId, "workspaceId");
            permissionProfileId = text(permissionProfileId, "permissionProfileId");
            if (permissionProfileVersion < 1) {
                throw new IllegalArgumentException("permissionProfileVersion must be positive");
            }
            query = text(query, "query");
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
        }
    }

    /**
     * 搜索结果。
     *
     * @param tools 当前权限可见的工具描述
     */
    public record SearchResult(List<ToolDescriptor> tools) {
        /** 复制结果列表。 */
        public SearchResult {
            tools = List.copyOf(tools);
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
