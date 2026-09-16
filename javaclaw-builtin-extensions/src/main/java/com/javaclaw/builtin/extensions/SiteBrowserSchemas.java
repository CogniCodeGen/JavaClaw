package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;

/** 浏览器工具的有界输入定义；禁止任意 JavaScript、选择器和宿主路径。 */
final class SiteBrowserSchemas {
    private SiteBrowserSchemas() {}

    static Map<String, Object> empty() {
        return object(Map.of(), List.of());
    }

    static Map<String, Object> open() {
        return object(
                Map.of(
                        "uri",
                        text(8192),
                        "account",
                        object(Map.of("siteId", text(100), "accountId", text(100)), List.of("siteId", "accountId"))),
                List.of("uri"));
    }

    static Map<String, Object> credentials() {
        return object(
                Map.of("pageId", text(100), "usernameRef", text(100), "passwordRef", text(100)),
                List.of("pageId", "usernameRef", "passwordRef"));
    }

    static Map<String, Object> act() {
        Map<String, Object> target =
                object(Map.of("pageId", text(100), "reference", text(100), "frameId", text(100)), List.of());
        Map<String, Object> point = object(
                Map.of("x", Map.of("type", "number", "minimum", 0), "y", Map.of("type", "number", "minimum", 0)),
                List.of("x", "y"));
        Map<String, Object> input = object(
                Map.of(
                        "value",
                        text(16384),
                        "point",
                        point,
                        "drag",
                        object(Map.of("from", point, "to", point), List.of("from", "to"))),
                List.of());
        List<String> operations = java.util.Arrays.stream(
                        com.javaclaw.builtin.contracts.BrowserContracts.Operation.values())
                .filter(operation -> operation != com.javaclaw.builtin.contracts.BrowserContracts.Operation.FILL_SECRET)
                .map(Enum::name)
                .toList();
        return object(
                Map.of(
                        "operation",
                        Map.of("type", "string", "enum", operations),
                        "target",
                        target,
                        "input",
                        input,
                        "attachmentDigest",
                        text(64)),
                List.of("operation"));
    }

    private static Map<String, Object> text(int maximum) {
        return Map.of("type", "string", "maxLength", maximum);
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }
}
