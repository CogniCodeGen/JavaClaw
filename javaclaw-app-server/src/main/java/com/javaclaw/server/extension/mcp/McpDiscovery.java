package com.javaclaw.server.extension.mcp;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Forward-compatible server discovery result.
 *
 * @param supportedVersions 服务端支持的协议版本列表，非空引用，构造时复制
 * @param capabilities 非空能力 JSON，构造时深拷贝；消费者不得修改 accessor 返回节点
 * @param instructions 服务说明；null 归一为空字符串，不自动作为系统指令
 * @param serverName Server 名称；null 归一为空字符串
 * @param serverVersion Server 版本；null 归一为空字符串
 * @param raw 完整原始发现 JSON，构造时深拷贝以保留未知扩展
 */
public record McpDiscovery(
        List<String> supportedVersions,
        JsonNode capabilities,
        String instructions,
        String serverName,
        String serverVersion,
        JsonNode raw) {
    /** 复制版本与 JSON 节点，保留未知字段；不因为服务说明而改变 Agent 权限或上下文。 */
    public McpDiscovery {
        supportedVersions = List.copyOf(Objects.requireNonNull(supportedVersions, "supportedVersions"));
        capabilities = Objects.requireNonNull(capabilities, "capabilities").deepCopy();
        instructions = instructions == null ? "" : instructions;
        serverName = serverName == null ? "" : serverName;
        serverVersion = serverVersion == null ? "" : serverVersion;
        raw = Objects.requireNonNull(raw, "raw").deepCopy();
    }
}
