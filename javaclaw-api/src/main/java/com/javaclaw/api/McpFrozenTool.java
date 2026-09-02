package com.javaclaw.api;

import java.util.Objects;

/**
 * Turn 冻结的 MCP Tool 权威身份。
 *
 * @param endpointId 端点标识
 * @param endpointRevision 端点精确版本
 * @param catalogRevision 目录精确版本
 * @param name Tool 名
 * @param schemaHash 输入输出 Schema 摘要
 */
public record McpFrozenTool(
        String endpointId, long endpointRevision, long catalogRevision, String name, String schemaHash) {
    /** 校验冻结身份。 */
    public McpFrozenTool {
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        endpointRevision = Preconditions.positive(endpointRevision, "endpointRevision");
        catalogRevision = Preconditions.positive(catalogRevision, "catalogRevision");
        name = Preconditions.text(name, "name");
        Objects.requireNonNull(schemaHash, "schemaHash");
        if (!schemaHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("schemaHash must be SHA-256 hex");
        }
    }

    /**
     * 生成放入 Core ToolIdentity 的稳定正数版本。
     *
     * @return 由端点、目录与 Schema 共同决定的版本
     */
    public long toolRevision() {
        String digest = McpHashes.sha256(
                endpointId + ":" + endpointRevision + ":" + catalogRevision + ":" + name + ":" + schemaHash);
        return Long.parseLong(digest.substring(0, 15), 16) + 1;
    }
}
