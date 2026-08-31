package com.javaclaw.server.extension.mcp;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds the mandatory 2026-07-28 HTTP routing headers, including x-mcp-header fields. */
public final class McpHttpRoutingHeaders {
    private static final BigInteger MAX_SAFE_INTEGER = new BigInteger("9007199254740991");

    private McpHttpRoutingHeaders() {}

    /** 从方法、名称及已验证 Schema 路由注解生成 MCP 路由头；拒绝非法元数据，不能由静态凭据覆盖。 */
    public static Map<String, String> forRequest(ObjectNode request, JsonNode toolSchema, JsonNode toolArguments) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String method = request.path("method").asText("");
        if (method.isBlank()) {
            throw new IllegalArgumentException("MCP request has no method");
        }
        result.put("Mcp-Method", method);
        JsonNode params = request.path("params");
        if (method.equals(McpProtocol.TOOLS_CALL) || method.equals(McpProtocol.PROMPT_GET)) {
            result.put("Mcp-Name", encode(params.path("name").asText()));
        } else if (method.equals(McpProtocol.RESOURCES_READ)) {
            result.put("Mcp-Name", encode(params.path("uri").asText()));
        } else if (McpProtocol.TASK_METHODS.contains(method)) {
            result.put("Mcp-Name", encode(params.path("taskId").asText()));
        }
        if (method.equals(McpProtocol.TOOLS_CALL) && toolSchema != null) {
            LinkedHashMap<String, HeaderProperty> properties = new LinkedHashMap<>();
            collect(toolSchema, false, true, java.util.List.of(), properties, new LinkedHashSet<>());
            for (HeaderProperty property : properties.values()) {
                JsonNode value = at(toolArguments, property.path());
                if (value == null || value.isNull() || value.isMissingNode()) {
                    continue;
                }
                String encoded =
                        switch (property.type()) {
                            case "string" -> {
                                if (!value.isTextual()) {
                                    throw typeMismatch(property);
                                }
                                yield encode(value.asText());
                            }
                            case "boolean" -> {
                                if (!value.isBoolean()) {
                                    throw typeMismatch(property);
                                }
                                yield Boolean.toString(value.asBoolean());
                            }
                            case "integer" -> {
                                if (!value.isIntegralNumber()) {
                                    throw typeMismatch(property);
                                }
                                BigInteger integer = value.bigIntegerValue();
                                if (integer.abs().compareTo(MAX_SAFE_INTEGER) > 0) {
                                    throw new IllegalArgumentException(
                                            "x-mcp-header integer is outside the safe range");
                                }
                                yield integer.toString();
                            }
                            default -> throw new IllegalArgumentException("unsupported x-mcp-header property type");
                        };
                result.put("Mcp-Param-" + property.header(), encoded);
            }
        }
        return Map.copyOf(result);
    }

    private static void collect(
            JsonNode node,
            boolean propertyNode,
            boolean directPropertyChain,
            java.util.List<String> path,
            Map<String, HeaderProperty> found,
            Set<String> names) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode annotation = node.get("x-mcp-header");
        if (annotation != null) {
            if (!propertyNode
                    || !annotation.isTextual()
                    || !annotation.asText().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("invalid x-mcp-header annotation");
            }
            String normalized = annotation.asText().toLowerCase(Locale.ROOT);
            if (!names.add(normalized)) {
                throw new IllegalArgumentException("duplicate x-mcp-header annotation");
            }
            String type = node.path("type").asText();
            if (!Set.of("string", "integer", "boolean").contains(type)) {
                throw new IllegalArgumentException(
                        "x-mcp-header is only valid on string, integer or boolean properties");
            }
            found.put(normalized, new HeaderProperty(annotation.asText(), java.util.List.copyOf(path), type));
        }
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                java.util.ArrayList<String> childPath = new java.util.ArrayList<>(path);
                childPath.add(entry.getKey());
                collect(entry.getValue(), directPropertyChain, directPropertyChain, childPath, found, names);
            });
        }
        node.fields().forEachRemaining(entry -> {
            if (entry.getKey().equals("properties") || entry.getKey().equals("x-mcp-header")) {
                return;
            }
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                collect(value, false, false, path, found, names);
            } else if (value.isArray()) {
                value.forEach(child -> collect(child, false, false, path, found, names));
            }
        });
    }

    private static JsonNode at(JsonNode value, java.util.List<String> path) {
        JsonNode current = value;
        for (String part : path) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private static IllegalArgumentException typeMismatch(HeaderProperty property) {
        return new IllegalArgumentException(
                "MCP argument for header " + property.header() + " does not match schema type " + property.type());
    }

    static String encode(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("MCP routing header value is empty");
        }
        boolean plain = value.equals(value.strip()) && !(value.startsWith("=?base64?") && value.endsWith("?="));
        for (int index = 0; plain && index < value.length(); index++) {
            char character = value.charAt(index);
            plain = character == '\t' || (character >= 0x20 && character <= 0x7e);
        }
        return plain
                ? value
                : "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private record HeaderProperty(String header, java.util.List<String> path, String type) {}
}
