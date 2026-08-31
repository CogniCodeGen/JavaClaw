package com.javaclaw.server.transport;

import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.server.configuration.ConfigurationUseCases;
import com.javaclaw.server.diagnostics.DiagnosticsUseCases;

/** Configuration, diagnostics and paused-interaction protocol adapter. */
final class AdministrationRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.CONFIG_READ, RpcMethods.CONFIG_UPDATE,
            RpcMethods.DIAGNOSTICS_READ, RpcMethods.DIAGNOSTICS_EXPORT,
            RpcMethods.APPROVAL_RESPOND, RpcMethods.USER_INPUT_RESPOND);

    private final ConfigurationUseCases configuration;
    private final DiagnosticsUseCases diagnostics;
    private final ApprovalResponseHandler approvals;
    private final UserInputResponseHandler userInputs;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    AdministrationRpcHandler(
            ConfigurationUseCases configuration,
            DiagnosticsUseCases diagnostics,
            ApprovalResponseHandler approvals,
            UserInputResponseHandler userInputs,
            ObjectMapper json,
            ProtocolMapper wire) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.diagnostics = diagnostics;
        this.approvals = approvals;
        this.userInputs = userInputs;
        this.json = Objects.requireNonNull(json, "json");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public JsonNode handle(String method, JsonNode params) {
        return switch (method) {
            case RpcMethods.CONFIG_READ -> wire.configuration(configuration.read());
            case RpcMethods.CONFIG_UPDATE -> updateConfiguration(params);
            case RpcMethods.DIAGNOSTICS_READ ->
                wire.diagnostics(requireDiagnostics()
                        .read(Math.toIntExact(RequestParameters.optionalLong(params, "limit", 1_000))));
            case RpcMethods.DIAGNOSTICS_EXPORT ->
                json.valueToTree(wire.attachment(requireDiagnostics().export()));
            case RpcMethods.APPROVAL_RESPOND ->
                approvals == null
                        ? pendingNotFound()
                        : RpcResults.flag(
                                "accepted",
                                approvals.respond(
                                        RequestParameters.requiredText(params, "approvalId"),
                                        RequestParameters.optionalBoolean(params, "approved", false)));
            case RpcMethods.USER_INPUT_RESPOND ->
                userInputs == null
                        ? pendingNotFound()
                        : RpcResults.flag(
                                "accepted",
                                userInputs.respond(
                                        RequestParameters.requiredText(params, "requestId"),
                                        RequestParameters.optionalText(params, "value", ""),
                                        RequestParameters.optionalBoolean(params, "cancelled", false)));
            default -> throw new RpcRouter.MethodNotFound(method);
        };
    }

    private JsonNode updateConfiguration(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("params must be an object");
        }
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> removals = new java.util.LinkedHashSet<>();
        params.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                removals.add(entry.getKey());
            } else {
                try {
                    values.put(entry.getKey(), json.writeValueAsString(entry.getValue()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    throw new IllegalArgumentException("configuration value cannot be encoded", failure);
                }
            }
        });
        return wire.configuration(configuration.update(values, removals));
    }

    private JsonNode pendingNotFound() {
        var result = json.createObjectNode();
        result.put("accepted", false);
        result.put("reason", "no matching pending request");
        return result;
    }

    private DiagnosticsUseCases requireDiagnostics() {
        if (diagnostics == null) {
            throw new IllegalStateException("diagnostics capability is unavailable");
        }
        return diagnostics;
    }
}
