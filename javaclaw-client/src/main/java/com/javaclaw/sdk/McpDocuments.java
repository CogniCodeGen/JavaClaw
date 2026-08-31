package com.javaclaw.sdk;

import java.util.LinkedHashSet;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.McpSettingsInfo;

/** MCP 设置的 SDK 内部协议映射；只允许已声明的元数据，不接受任意认证 Header。 */
final class McpDocuments {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private McpDocuments() {}

    static McpSettingsInfo read(JsonDocument document) {
        try {
            var value = JSON.readTree(document.canonicalJson());
            var auth = value.path("auth");
            var hosts = new LinkedHashSet<String>();
            value.path("networkAllowlist").forEach(item -> hosts.add(item.asText()));
            var scopes = new LinkedHashSet<String>();
            auth.path("scopes").forEach(item -> scopes.add(item.asText()));
            return new McpSettingsInfo(
                    value.path("transport").asText(),
                    value.path("url").asText(),
                    hosts,
                    auth.path("type").asText("none"),
                    auth.path("credentialName").asText(),
                    auth.path("headerName").asText(),
                    auth.path("clientId").asText(),
                    scopes,
                    value.path("timeoutMillis").asLong(30_000),
                    value.path("outputLimitBytes").asLong(4_194_304),
                    value.path("workspaceId").asText(""));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("MCP 元数据不是有效 JSON", failure);
        }
    }

    static JsonDocument write(McpSettingsInfo value) {
        if (!"http".equals(value.transport())) {
            throw new IllegalArgumentException("stdio 元数据只能由服务器生成");
        }
        var root = JSON.createObjectNode()
                .put("transport", "http")
                .put("url", value.url())
                .put("timeoutMillis", value.timeoutMillis())
                .put("outputLimitBytes", value.outputLimitBytes());
        var hosts = root.putArray("networkAllowlist");
        if (!value.workspaceId().isBlank()) {
            root.put("workspaceId", value.workspaceId());
        }
        value.networkAllowlist().stream().sorted().forEach(hosts::add);
        var auth = root.putObject("auth").put("type", value.authentication());
        if (!value.credentialName().isBlank()) {
            auth.put("credentialName", value.credentialName());
        }
        if (!value.headerName().isBlank()) {
            auth.put("headerName", value.headerName());
        }
        if (!value.clientId().isBlank()) {
            auth.put("clientId", value.clientId());
        }
        var scopes = auth.putArray("scopes");
        value.scopes().stream().sorted().forEach(scopes::add);
        return new JsonDocument(root.toString());
    }
}
