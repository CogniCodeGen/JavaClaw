package com.javaclaw.server.transport;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.extension.McpServerState;
import com.javaclaw.server.extension.PluginUseCases;
import com.javaclaw.server.extension.mcp.McpUseCases;

/** Plugin, trust and MCP management protocol adapter. */
final class ExtensionRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.MCP_LIST,
            RpcMethods.MCP_HEALTH,
            RpcMethods.MCP_CONFIGURE,
            RpcMethods.MCP_DISCOVER,
            RpcMethods.MCP_AUTHORIZE_START,
            RpcMethods.MCP_AUTHORIZE_CANCEL,
            RpcMethods.MCP_CREDENTIAL_SET,
            RpcMethods.MCP_CREDENTIAL_READ,
            RpcMethods.MCP_CREDENTIAL_CLEAR,
            RpcMethods.PLUGIN_LIST,
            RpcMethods.PLUGIN_READ,
            RpcMethods.PLUGIN_INSTALL,
            RpcMethods.PLUGIN_PREVIEW,
            RpcMethods.PLUGIN_ENABLE,
            RpcMethods.PLUGIN_DISABLE,
            RpcMethods.PLUGIN_UNINSTALL,
            RpcMethods.PLUGIN_HEALTH,
            RpcMethods.PLUGIN_TRUST_LIST,
            RpcMethods.PLUGIN_TRUST_ADD,
            RpcMethods.PLUGIN_TRUST_REMOVE,
            RpcMethods.SITE_LIST,
            RpcMethods.SITE_PUT,
            RpcMethods.SITE_DELETE,
            RpcMethods.SITE_CREDENTIAL_SET,
            RpcMethods.SITE_CREDENTIAL_READ,
            RpcMethods.SITE_CREDENTIAL_CLEAR,
            RpcMethods.SITE_LOGIN_START,
            RpcMethods.SITE_LOGIN_FINISH,
            RpcMethods.SITE_SESSION_READ,
            RpcMethods.SITE_SESSION_CLEAR,
            RpcMethods.NETWORK_GRANT_LIST,
            RpcMethods.NETWORK_GRANT_PUT,
            RpcMethods.NETWORK_GRANT_DELETE,
            RpcMethods.TOOL_AUTHORIZATION_OPTIONS,
            RpcMethods.TOOL_AUTHORIZATION_LIST,
            RpcMethods.TOOL_AUTHORIZATION_PUT,
            RpcMethods.TOOL_AUTHORIZATION_DELETE);

    private final PluginUseCases plugins;
    private final McpUseCases mcp;
    private final ServerDiscovery discovery;
    private final ObjectMapper json;
    private final ProtocolMapper wire;
    private final Consumer<JsonRpcNotification> notifications;
    private final AutoCloseable authorizationStatuses;
    private final SiteRpcOperations sites;
    private final ToolAuthorizationRpcOperations toolAuthorizations;

    ExtensionRpcHandler(
            PluginUseCases plugins,
            McpUseCases mcp,
            ServerDiscovery discovery,
            ObjectMapper json,
            ProtocolMapper wire,
            Consumer<JsonRpcNotification> notifications,
            SiteRpcOperations sites,
            ToolAuthorizationRpcOperations toolAuthorizations) {
        this.plugins = plugins;
        this.mcp = mcp;
        this.sites = sites;
        this.toolAuthorizations = toolAuthorizations;
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.json = Objects.requireNonNull(json, "json");
        this.wire = Objects.requireNonNull(wire, "wire");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        authorizationStatuses = mcp == null ? () -> {} : mcp.onAuthorizationStatus(this::notifyAuthorizationStatus);
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public JsonNode handle(String method, JsonNode params) {
        if (method.startsWith("tool/authorization/")) {
            return toolAuthorizations.handle(method, params);
        }
        if (method.startsWith("site/") || method.startsWith("network/grant/")) {
            return sites.handle(method, params);
        }
        try {
            return switch (method) {
                case RpcMethods.MCP_LIST ->
                    plugins == null
                            ? wire.discoveredProcesses(discovery.mcpServers())
                            : json.valueToTree(plugins.listMcp().stream()
                                    .map(wire::mcpServer)
                                    .toList());
                case RpcMethods.MCP_HEALTH -> mcpHealth(params);
                case RpcMethods.MCP_CONFIGURE -> configureMcp(params);
                case RpcMethods.MCP_DISCOVER ->
                    wire.mcpDiscovery(requireMcp().discover(RequestParameters.requiredText(params, "mcpId")));
                case RpcMethods.MCP_AUTHORIZE_START -> startAuthorization(params);
                case RpcMethods.MCP_AUTHORIZE_CANCEL ->
                    RpcResults.flag(
                            "cancelled",
                            requireMcp()
                                    .cancelAuthorization(RequestParameters.requiredText(params, "authorizationId")));
                case RpcMethods.MCP_CREDENTIAL_SET -> setMcpCredential(params);
                case RpcMethods.PLUGIN_PREVIEW -> previewPlugin(params);
                case RpcMethods.MCP_CREDENTIAL_READ ->
                    requireMcp()
                            .credentialMetadata(RequestParameters.requiredText(params, "mcpId"))
                            .map(value -> json.<JsonNode>valueToTree(wire.secretMetadata(value)))
                            .orElseGet(json::nullNode);
                case RpcMethods.MCP_CREDENTIAL_CLEAR ->
                    RpcResults.flag(
                            "cleared",
                            requireMcp()
                                    .clearCredential(
                                            RequestParameters.requiredText(params, "mcpId"),
                                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                                            RequestParameters.optionalText(params, "idempotencyKey", null)));
                case RpcMethods.PLUGIN_LIST ->
                    plugins == null
                            ? wire.discoveredPlugins(discovery.plugins())
                            : json.valueToTree(
                                    plugins.list().stream().map(wire::plugin).toList());
                case RpcMethods.PLUGIN_READ ->
                    json.valueToTree(
                            wire.plugin(requirePlugins().read(RequestParameters.requiredText(params, "pluginId"))));
                case RpcMethods.PLUGIN_INSTALL ->
                    json.valueToTree(wire.plugin(requirePlugins()
                            .install(
                                    RequestParameters.requiredText(params, "sha256"),
                                    RequestParameters.optionalBoolean(params, "sourceConfirmed", false),
                                    RequestParameters.optionalBoolean(params, "permissionsApproved", false),
                                    RequestParameters.optionalBoolean(params, "enabled", true),
                                    RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.PLUGIN_ENABLE -> setEnabled(params, true);
                case RpcMethods.PLUGIN_DISABLE -> setEnabled(params, false);
                case RpcMethods.PLUGIN_UNINSTALL ->
                    RpcResults.flag(
                            "removed",
                            requirePlugins()
                                    .uninstall(
                                            RequestParameters.requiredText(params, "pluginId"),
                                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                                            RequestParameters.optionalText(params, "idempotencyKey", null)));
                case RpcMethods.PLUGIN_HEALTH ->
                    json.valueToTree(
                            wire.plugin(requirePlugins().health(RequestParameters.requiredText(params, "pluginId"))));
                case RpcMethods.PLUGIN_TRUST_LIST ->
                    json.valueToTree(requirePlugins().trustList().stream()
                            .map(wire::pluginTrustKey)
                            .toList());
                case RpcMethods.PLUGIN_TRUST_ADD ->
                    json.valueToTree(wire.pluginTrustKey(requirePlugins()
                            .trustAdd(
                                    RequestParameters.requiredText(params, "keyId"),
                                    RequestParameters.requiredText(params, "publicKey"),
                                    RequestParameters.requiredText(params, "label"),
                                    RequestParameters.optionalLong(params, "expectedRevision", 0),
                                    RequestParameters.optionalText(params, "idempotencyKey", null))));
                case RpcMethods.PLUGIN_TRUST_REMOVE ->
                    RpcResults.flag(
                            "removed",
                            requirePlugins()
                                    .trustRemove(
                                            RequestParameters.requiredText(params, "keyId"),
                                            RequestParameters.optionalLong(params, "expectedRevision", -1),
                                            RequestParameters.optionalText(params, "idempotencyKey", null)));
                default -> throw new RpcRouter.MethodNotFound(method);
            };
        } catch (IOException failure) {
            throw new IllegalStateException("extension operation failed", failure);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("extension operation failed", failure);
        }
    }

    private JsonNode startAuthorization(JsonNode params) throws Exception {
        String mcpId = RequestParameters.requiredText(params, "mcpId");
        var authorization = requireMcp().startAuthorization(mcpId);
        var result = json.createObjectNode();
        result.put("authorizationId", authorization.authorizationId());
        result.put("url", authorization.authorizationUrl().toString());
        result.put("expiresAt", authorization.expiresAt().toString());
        notifications.accept(new JsonRpcNotification(
                "2.0", RpcMethods.MCP_AUTHORIZATION_REQUESTED, result.deepCopy().put("mcpId", mcpId)));
        return result;
    }

    private JsonNode previewPlugin(JsonNode params) throws IOException {
        var value = requirePlugins().preview(RequestParameters.requiredText(params, "attachmentSha256"));
        return json.valueToTree(new com.javaclaw.protocol.WirePluginPreview(
                value.sha256(),
                value.id(),
                value.name(),
                value.version(),
                value.signatureVerified(),
                value.signerKeyId(),
                value.requiresPermissions(),
                value.permissions()));
    }

    private JsonNode mcpHealth(JsonNode params) {
        McpServerState state = requirePlugins().mcpHealth(RequestParameters.requiredText(params, "mcpId"));
        notifyMcpStatus(state);
        return json.valueToTree(wire.mcpServer(state));
    }

    private JsonNode setMcpCredential(JsonNode params) {
        char[] credential = RequestParameters.requiredText(params, "credential").toCharArray();
        try {
            return json.valueToTree(wire.secretMetadata(requireMcp()
                    .setCredential(
                            RequestParameters.requiredText(params, "mcpId"),
                            credential,
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private JsonNode configureMcp(JsonNode params) {
        JsonNode config = params == null ? null : params.get("config");
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("MCP config must be an object");
        }
        final String configurationJson;
        try {
            configurationJson = json.writeValueAsString(config);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP config cannot be encoded", failure);
        }
        McpServerState state = requirePlugins()
                .configureMcp(
                        RequestParameters.requiredText(params, "mcpId"),
                        RequestParameters.optionalText(params, "pluginId", null),
                        RequestParameters.requiredText(params, "name"),
                        configurationJson,
                        RequestParameters.optionalBoolean(params, "enabled", true),
                        RequestParameters.optionalLong(params, "expectedRevision", 0),
                        RequestParameters.optionalText(params, "idempotencyKey", null));
        notifyMcpStatus(state);
        return json.valueToTree(wire.mcpServer(state));
    }

    private void notifyMcpStatus(McpServerState state) {
        var params = json.createObjectNode();
        params.put("mcpId", state.id());
        params.put("state", state.state());
        params.put("revision", state.revision());
        notifications.accept(new JsonRpcNotification("2.0", RpcMethods.MCP_STATUS_CHANGED, params));
    }

    private void notifyAuthorizationStatus(
            com.javaclaw.server.extension.mcp.McpAuthorizationService.AuthorizationStatus status) {
        var params = json.createObjectNode();
        params.put("mcpId", status.mcpId());
        params.put("authorizationId", status.authorizationId());
        params.put("state", status.state());
        params.put("occurredAt", status.occurredAt().toString());
        try {
            params.put("revision", requireMcp().discover(status.mcpId()).revision());
        } catch (RuntimeException unavailable) {
            params.put("revision", 0);
        }
        notifications.accept(new JsonRpcNotification("2.0", RpcMethods.MCP_STATUS_CHANGED, params));
    }

    private JsonNode setEnabled(JsonNode params, boolean enabled) throws IOException {
        return json.valueToTree(wire.plugin(requirePlugins()
                .setEnabled(
                        RequestParameters.requiredText(params, "pluginId"),
                        enabled,
                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
    }

    private PluginUseCases requirePlugins() {
        if (plugins == null) {
            throw new IllegalStateException("plugin management capability is unavailable");
        }
        return plugins;
    }

    private McpUseCases requireMcp() {
        if (mcp == null) {
            throw new IllegalStateException("MCP capability is unavailable");
        }
        return mcp;
    }

    @Override
    public void close() {
        try {
            authorizationStatuses.close();
        } catch (Exception failure) {
            throw new IllegalStateException("MCP authorization status subscription could not close", failure);
        }
    }
}
