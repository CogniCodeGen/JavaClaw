package com.javaclaw.server.transport;

import java.time.Instant;
import java.util.LinkedHashSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.server.extension.ToolAuthorization;
import com.javaclaw.server.extension.ToolAuthorizationUseCases;

/** 预授权协议映射，所有领域校验和原子核销仍由同一个服务端用例实现。 */
final class ToolAuthorizationRpcOperations {
    private final ToolAuthorizationUseCases service;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    ToolAuthorizationRpcOperations(ToolAuthorizationUseCases service, ObjectMapper json, ProtocolMapper wire) {
        this.service = service;
        this.json = json;
        this.wire = wire;
    }

    JsonNode handle(String method, JsonNode params) {
        if (service == null) {
            throw new IllegalStateException("bounded tool authorizations are unavailable");
        }
        String key = RequestParameters.optionalText(params, "idempotencyKey", null);
        long revision = RequestParameters.optionalLong(params, "expectedRevision", -1);
        return switch (method) {
            case RpcMethods.TOOL_AUTHORIZATION_OPTIONS ->
                json.valueToTree(service.options(text(params, "workspaceId")).stream()
                        .map(wire::toolAuthorityOption)
                        .toList());
            case RpcMethods.TOOL_AUTHORIZATION_LIST ->
                json.valueToTree(service.list(text(params, "workspaceId")).stream()
                        .map(wire::toolAuthorization)
                        .toList());
            case RpcMethods.TOOL_AUTHORIZATION_PUT ->
                json.valueToTree(wire.toolAuthorization(service.put(
                        new ToolAuthorization(
                                RequestParameters.optionalText(params, "authorizationId", null),
                                text(params, "workspaceId"),
                                text(params, "sourceId"),
                                text(params, "toolName"),
                                RequestParameters.optionalLong(params, "sourceRevision", -1),
                                text(params, "schemaSha256"),
                                text(params, "argumentTemplate"),
                                text(params, "recipientField"),
                                variables(params),
                                Math.toIntExact(RequestParameters.optionalLong(params, "maximumUses", -1)),
                                0,
                                Instant.parse(text(params, "expiresAt")),
                                RequestParameters.optionalBoolean(params, "enabled", true),
                                revision,
                                Instant.now()),
                        RequestParameters.optionalBoolean(params, "confirmed", false),
                        key)));
            case RpcMethods.TOOL_AUTHORIZATION_DELETE ->
                RpcResults.flag("removed", service.disable(text(params, "authorizationId"), revision, key));
            default -> throw new RpcRouter.MethodNotFound(method);
        };
    }

    private static String text(JsonNode params, String field) {
        return RequestParameters.requiredText(params, field);
    }

    private static java.util.Set<String> variables(JsonNode params) {
        JsonNode values = params.path("variableFields");
        if (!values.isArray() || values.size() > 6) {
            throw new IllegalArgumentException("bounded variableFields array is required");
        }
        var result = new LinkedHashSet<String>();
        for (JsonNode field : values) {
            if (!field.isTextual() || !result.add(field.asText())) {
                throw new IllegalArgumentException("distinct text variable fields are required");
            }
        }
        return java.util.Set.copyOf(result);
    }
}
