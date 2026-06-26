package com.javaclaw.agent.model;

import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * 工具 Schema 修复工具类 — 修复工具参数中的裸 object 类型
 *
 * <p>部分 OpenAI 兼容 API（如智谱 GLM）不支持参数 schema 中
 * 不带 {@code properties} 的 {@code "type":"object"}，
 * 会导致请求挂起无响应。此工具类提供共享的修复逻辑，
 * 供 {@link ToolSchemaFixFormatter} 和 {@link ToolSchemaFixMultiAgentFormatter} 复用。</p>
 *
 * @author JavaClaw
 */
public final class ToolSchemaFixer {

    private static final Logger log = LoggerFactory.getLogger(ToolSchemaFixer.class);

    private ToolSchemaFixer() {}

    /**
     * 修复请求中所有工具的裸 object 类型参数
     *
     * @param request OpenAI 格式的请求
     */
    public static void fixTools(OpenAIRequest request) {
        if (request.getTools() == null) {
            return;
        }

        for (OpenAITool tool : request.getTools()) {
            if (tool.getFunction() != null && tool.getFunction().getParameters() != null) {
                boolean fixed = fixBareObjectTypes(tool.getFunction().getParameters());
                if (fixed) {
                    log.debug("已修复工具 [{}] 中的裸 object 类型参数", tool.getFunction().getName());
                }
            }
        }
    }

    /**
     * 递归修复 schema 中不带 properties 的 object 类型
     *
     * @param schema 参数 schema（Map 结构）
     * @return 是否进行了修复
     */
    @SuppressWarnings("unchecked")
    static boolean fixBareObjectTypes(Map<String, Object> schema) {
        if (schema == null) {
            return false;
        }

        boolean fixed = false;

        // 当前节点是 object 类型但没有 properties -> 补充空 properties
        Object type = schema.get("type");
        if ("object".equals(type) && !schema.containsKey("properties")) {
            schema.put("properties", Collections.emptyMap());
            fixed = true;
        }

        // 递归处理 properties 中的每个子字段
        Object props = schema.get("properties");
        if (props instanceof Map) {
            for (Object value : ((Map<String, Object>) props).values()) {
                if (value instanceof Map) {
                    fixed |= fixBareObjectTypes((Map<String, Object>) value);
                }
            }
        }

        // 递归处理 array 的 items
        Object items = schema.get("items");
        if (items instanceof Map) {
            fixed |= fixBareObjectTypes((Map<String, Object>) items);
        }

        return fixed;
    }
}
