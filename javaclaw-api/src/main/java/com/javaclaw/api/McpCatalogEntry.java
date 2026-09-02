package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * MCP Catalog 的脱敏条目；Prompt/Resource 正文不进入目录，也不会自动进入 system context。
 *
 * @param kind 条目类型
 * @param name 远端稳定名称
 * @param title 可选展示标题
 * @param description 非指令性简介
 * @param inputSchema Tool 输入 Schema；其他类型为空
 * @param outputSchema Tool 输出 Schema；其他类型为空
 * @param schemaHash Tool Schema 组合摘要；其他类型为空
 */
public record McpCatalogEntry(
        McpCatalogKind kind,
        String name,
        Optional<String> title,
        String description,
        Optional<CanonicalPayload> inputSchema,
        Optional<CanonicalPayload> outputSchema,
        Optional<String> schemaHash) {
    /** 校验名称与 Tool Schema。 */
    public McpCatalogEntry {
        Objects.requireNonNull(kind, "kind");
        name = Preconditions.text(name, "name");
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("MCP catalog name must be provider-safe ASCII");
        }
        title = Objects.requireNonNull(title, "title").map(value -> Preconditions.text(value, "title"));
        description = Preconditions.text(description, "description");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        schemaHash = Objects.requireNonNull(schemaHash, "schemaHash");
        requireSchemaShape(kind, inputSchema, outputSchema, schemaHash);
    }

    /**
     * 创建 Tool 条目并由规范 Schema 计算组合摘要。
     *
     * @param name 工具名
     * @param title 可选标题
     * @param description 简介
     * @param input 输入 Schema
     * @param output 输出 Schema
     * @return 已校验条目
     */
    public static McpCatalogEntry tool(
            String name, Optional<String> title, String description, CanonicalPayload input, CanonicalPayload output) {
        String hash = McpHashes.sha256(input.sha256() + ":" + output.sha256());
        return new McpCatalogEntry(
                McpCatalogKind.TOOL,
                name,
                title,
                description,
                Optional.of(input),
                Optional.of(output),
                Optional.of(hash));
    }

    private static void requireSchemaShape(
            McpCatalogKind kind,
            Optional<CanonicalPayload> input,
            Optional<CanonicalPayload> output,
            Optional<String> hash) {
        boolean toolShape = input.isPresent() && output.isPresent() && hash.isPresent();
        if ((kind == McpCatalogKind.TOOL) != toolShape) {
            throw new IllegalArgumentException("only MCP Tool entries carry schemas");
        }
        hash.ifPresent(value -> {
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("schemaHash must be SHA-256 hex");
            }
        });
    }
}
