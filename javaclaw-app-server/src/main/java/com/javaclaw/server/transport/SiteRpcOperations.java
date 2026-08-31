package com.javaclaw.server.transport;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.server.browser.BrowserSite;
import com.javaclaw.server.browser.BrowserSiteUseCases;
import com.javaclaw.server.network.NetworkGrant;
import com.javaclaw.server.network.NetworkGrantUseCases;

/** Extension Handler 的站点/网络协议映射；服务端用例不接收 Wire 或 JsonNode。 */
final class SiteRpcOperations {
    private final BrowserSiteUseCases sites;
    private final NetworkGrantUseCases network;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    SiteRpcOperations(BrowserSiteUseCases sites, NetworkGrantUseCases network, ObjectMapper json, ProtocolMapper wire) {
        this.sites = sites;
        this.network = network;
        this.json = json;
        this.wire = wire;
    }

    JsonNode handle(String method, JsonNode params) {
        try {
            if (method.startsWith("network/") && network == null || method.startsWith("site/") && sites == null) {
                throw new IllegalStateException("site or scoped network capability is unavailable");
            }
            String key = RequestParameters.optionalText(params, "idempotencyKey", null);
            long revision = RequestParameters.optionalLong(params, "expectedRevision", -1);
            return switch (method) {
                case RpcMethods.SITE_LIST ->
                    json.valueToTree(sites.list(text(params, "workspaceId")).stream()
                            .map(wire::site)
                            .toList());
                case RpcMethods.SITE_PUT ->
                    json.valueToTree(wire.site(sites.put(
                            new BrowserSite(
                                    RequestParameters.optionalText(params, "siteId", null),
                                    text(params, "workspaceId"),
                                    text(params, "name"),
                                    URI.create(text(params, "origin")),
                                    strings(params, "allowedOrigins").stream()
                                            .map(URI::create)
                                            .collect(Collectors.toSet()),
                                    RequestParameters.optionalBoolean(params, "enabled", true),
                                    0,
                                    Instant.now()),
                            revision,
                            RequestParameters.optionalBoolean(params, "confirmed", false),
                            key)));
                case RpcMethods.SITE_DELETE ->
                    RpcResults.flag("removed", sites.disable(text(params, "siteId"), revision, key));
                case RpcMethods.SITE_CREDENTIAL_SET -> putSecret(params, key);
                case RpcMethods.SITE_CREDENTIAL_READ ->
                    sites.secret(text(params, "siteId"), text(params, "name"))
                            .map(value -> json.<JsonNode>valueToTree(wire.secretMetadata(value)))
                            .orElseGet(json::nullNode);
                case RpcMethods.SITE_CREDENTIAL_CLEAR ->
                    RpcResults.flag(
                            "cleared", sites.clearSecret(text(params, "siteId"), text(params, "name"), revision, key));
                case RpcMethods.SITE_LOGIN_START -> {
                    var value = sites.startLogin(
                            text(params, "siteId"),
                            revision,
                            RequestParameters.optionalBoolean(params, "confirmed", false),
                            key);
                    yield json.valueToTree(new com.javaclaw.protocol.WireBrowserLogin(
                            value.sessionId(), value.siteId(), value.expiresAt().toString()));
                }
                case RpcMethods.SITE_LOGIN_FINISH ->
                    RpcResults.flag(
                            "finished",
                            sites.finishLogin(
                                    text(params, "sessionId"),
                                    RequestParameters.optionalBoolean(params, "save", false),
                                    key));
                case RpcMethods.SITE_SESSION_READ ->
                    sites.savedSession(text(params, "siteId"))
                            .map(value -> json.<JsonNode>valueToTree(wire.secretMetadata(value)))
                            .orElseGet(json::nullNode);
                case RpcMethods.SITE_SESSION_CLEAR ->
                    RpcResults.flag("cleared", sites.clearSession(text(params, "siteId"), revision, key));
                case RpcMethods.NETWORK_GRANT_LIST ->
                    json.valueToTree(network.list(text(params, "workspaceId")).stream()
                            .map(wire::networkGrant)
                            .toList());
                case RpcMethods.NETWORK_GRANT_PUT ->
                    json.valueToTree(wire.networkGrant(network.put(
                            new NetworkGrant(
                                    RequestParameters.optionalText(params, "grantId", null),
                                    text(params, "workspaceId"),
                                    text(params, "purpose"),
                                    URI.create(text(params, "origin")),
                                    strings(params, "addresses"),
                                    Instant.parse(text(params, "expiresAt")),
                                    RequestParameters.optionalBoolean(params, "enabled", true),
                                    0,
                                    Instant.now()),
                            revision,
                            RequestParameters.optionalBoolean(params, "confirmed", false),
                            key)));
                case RpcMethods.NETWORK_GRANT_DELETE ->
                    RpcResults.flag("removed", network.disable(text(params, "grantId"), revision, key));
                default -> throw new RpcRouter.MethodNotFound(method);
            };
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("browser operation failed; no action was automatically retried");
        }
    }

    private JsonNode putSecret(JsonNode params, String key) {
        char[] value = text(params, "value").toCharArray();
        try {
            return json.valueToTree(
                    wire.secretMetadata(sites.putSecret(text(params, "siteId"), text(params, "name"), value, key)));
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private static String text(JsonNode params, String field) {
        return RequestParameters.requiredText(params, field);
    }

    private static Set<String> strings(JsonNode params, String field) {
        JsonNode value = params.path(field);
        if (!value.isArray() || value.size() > 32) {
            throw new IllegalArgumentException("a bounded " + field + " array is required");
        }
        return StreamSupport.stream(value.spliterator(), false)
                .map(item -> {
                    if (!item.isTextual()) {
                        throw new IllegalArgumentException("string entries are required");
                    }
                    return item.asText();
                })
                .collect(Collectors.toUnmodifiableSet());
    }
}
