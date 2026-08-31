package com.javaclaw.server.extension.mcp;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Forward-compatible remote tool definition; unknown fields remain in {@code raw}.
 *
 * @param name 远端调用使用的稳定工具名，非空白；展示名称另见 title
 * @param title 展示标题；null 归一为空字符串
 * @param description 模型可见说明；null 归一为空字符串，不是安全授权
 * @param inputSchema 非空输入 Schema，构造时深拷贝
 * @param outputSchema 可选输出 Schema；null 表示未声明
 * @param annotations 可选工具注解；null 表示未声明，不能据此提升权限
 * @param raw 非空完整定义 JSON，保留未知字段；消费者不得修改返回节点
 */
public record McpRemoteTool(
        String name,
        String title,
        String description,
        JsonNode inputSchema,
        JsonNode outputSchema,
        JsonNode annotations,
        JsonNode raw) {
    /** 校验工具名并复制 Schema/原始 JSON；未知扩展保留，执行仍必须通过治理链。 */
    public McpRemoteTool {
        name = require(name, "name");
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
        outputSchema = outputSchema == null ? null : outputSchema.deepCopy();
        annotations = annotations == null ? null : annotations.deepCopy();
        raw = Objects.requireNonNull(raw, "raw").deepCopy();
    }

    private static String require(String value, String name) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return result;
    }
}
