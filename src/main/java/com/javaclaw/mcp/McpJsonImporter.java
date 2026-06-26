package com.javaclaw.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON 配置导入器 — 把 Claude Desktop / Cursor / 通用风格的 JSON 文本
 * 解析为 {@link McpServerConfig} 列表。
 *
 * <p>三种受支持的输入形态（按优先级匹配）：</p>
 * <ol>
 *   <li><b>包装格式</b>：{@code {"mcpServers": {"foo": {...}, "bar": {...}}}} — Claude Desktop 风格</li>
 *   <li><b>纯 servers 映射</b>：{@code {"foo": {...}, "bar": {...}}} — 复制 mcpServers 字段值的场景</li>
 *   <li><b>单条服务器</b>：{@code {"command": "...", "args": [...]}} — 复制单条配置时使用，
 *       此时调用方需指定 fallback name；或 {@code {"name": {...}}} 视为只含一项的 servers 映射</li>
 * </ol>
 *
 * <p>解析失败抛出 {@link IllegalArgumentException}，调用方应展示给用户。</p>
 *
 * @author JavaClaw
 */
public final class McpJsonImporter {

    private static final Logger log = LoggerFactory.getLogger(McpJsonImporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJsonImporter() {}

    /**
     * 解析 JSON 文本为 MCP 服务器配置列表。
     *
     * @param json         待解析文本（去除首尾空白）
     * @param fallbackName 当输入是单条不带 name 的服务器配置时使用的名称；可为 null（此时该形态不支持）
     * @return 解析得到的配置列表（永不返回 null；空 JSON 返回空列表）
     * @throws IllegalArgumentException 输入既不是有效 JSON 也不能匹配任何受支持的形态
     */
    public static List<McpServerConfig> parse(String json, String fallbackName) {
        if (json == null || json.isBlank()) return List.of();
        JsonNode root;
        try {
            root = MAPPER.readTree(json.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析为 JSON：" + e.getMessage());
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("MCP 配置必须是 JSON 对象，当前是 " + root.getNodeType());
        }

        // 形态 1：包装格式 — 顶层有 mcpServers 字段且为对象
        JsonNode wrapped = root.path("mcpServers");
        if (wrapped.isObject()) {
            return readServersMap(wrapped);
        }

        // 形态 3 优先于 形态 2：顶层有 command 或 url 字段，视为单条服务器
        if (root.hasNonNull("command") || root.hasNonNull("url")) {
            String name = root.path("name").asText("");
            if (name.isBlank()) name = fallbackName;
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("单条服务器配置缺少 name，请在 JSON 中加入 \"name\" 字段或先在表单填写名称");
            }
            return List.of(toServerConfig(name, root));
        }

        // 形态 2：纯 servers 映射 — 顶层对象的所有值都是对象（且至少含 command）
        boolean looksLikeServersMap = root.size() > 0 && allValuesLookLikeServer(root);
        if (looksLikeServersMap) {
            return readServersMap(root);
        }

        throw new IllegalArgumentException("无法识别 MCP 配置形态。请粘贴：" +
                "1) 完整 {\"mcpServers\": {...}}；2) 仅 mcpServers 内部映射 {\"名称\": {...}}；" +
                "3) 单条 {\"command\": \"...\"} 配置（需另填名称）");
    }

    /** 三参数的兼容入口，无 fallbackName */
    public static List<McpServerConfig> parse(String json) {
        return parse(json, null);
    }

    private static boolean allValuesLookLikeServer(JsonNode obj) {
        // JsonNode 实现 Iterable<JsonNode>，直接 for-each 遍历对象值
        for (JsonNode v : obj) {
            if (!v.isObject()) return false;
            // 允许 stdio (command) 或 http (url) 形态
            if (!v.hasNonNull("command") && !v.hasNonNull("url")) return false;
        }
        return true;
    }

    private static List<McpServerConfig> readServersMap(JsonNode servers) {
        List<McpServerConfig> result = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : servers.properties()) {
            result.add(toServerConfig(e.getKey(), e.getValue()));
        }
        log.info("从 JSON 解析出 {} 个 MCP Server", result.size());
        return result;
    }

    private static McpServerConfig toServerConfig(String name, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("服务器 [" + name + "] 配置不是 JSON 对象");
        }
        boolean enabled = !node.has("enabled") || node.path("enabled").asBoolean(true);

        // HTTP 传输：有 url 字段就走 http 路径
        String url = node.path("url").asText("");
        if (!url.isBlank()) {
            Map<String, String> headers = new HashMap<>();
            JsonNode headersNode = node.path("headers");
            if (headersNode.isObject()) {
                for (Map.Entry<String, JsonNode> h : headersNode.properties()) {
                    headers.put(h.getKey(), h.getValue().asText(""));
                }
            }
            return new McpServerConfig(name, url, headers, enabled);
        }

        // stdio 传输：必须有 command
        String command = node.path("command").asText("");
        if (command.isBlank()) {
            throw new IllegalArgumentException(
                    "服务器 [" + name + "] 缺少 command 或 url 字段（stdio 传输需 command，HTTP 传输需 url）");
        }
        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.path("args");
        if (argsNode.isArray()) {
            argsNode.forEach(a -> args.add(a.asText()));
        }
        Map<String, String> env = new HashMap<>();
        JsonNode envNode = node.path("env");
        if (envNode.isObject()) {
            for (Map.Entry<String, JsonNode> ev : envNode.properties()) {
                env.put(ev.getKey(), ev.getValue().asText(""));
            }
        }
        return new McpServerConfig(name, command, args, env, enabled);
    }
}
