package com.javaclaw.server.transport;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.conversation.ProfileUseCases;
import com.javaclaw.protocol.RpcMethods;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.model.ProviderUseCases;

/** Provider, profile, model and tool discovery RPC adapter. */
final class ModelRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.MODEL_LIST, RpcMethods.TOOL_LIST,
            RpcMethods.PROVIDER_LIST, RpcMethods.PROVIDER_CONFIGURE,
            RpcMethods.PROVIDER_CREDENTIAL_SET, RpcMethods.PROVIDER_CREDENTIAL_CLEAR,
            RpcMethods.PROFILE_LIST, RpcMethods.PROFILE_READ,
            RpcMethods.PROFILE_PUT, RpcMethods.PROFILE_DELETE,
            RpcMethods.PROFILE_PROMPT_PREVIEW, RpcMethods.PROFILE_PROMPT_OPTIMIZE);

    private final ServerDiscovery discovery;
    private final ProfileUseCases profiles;
    private final ProviderUseCases providers;
    private final com.javaclaw.agent.conversation.ProfilePromptUseCases prompts;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    ModelRpcHandler(
            ServerDiscovery discovery,
            ProfileUseCases profiles,
            ProviderUseCases providers,
            com.javaclaw.agent.conversation.ProfilePromptUseCases prompts,
            ObjectMapper json,
            ProtocolMapper wire) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.profiles = profiles;
        this.providers = providers;
        this.prompts = prompts;
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
            case RpcMethods.PROFILE_PROMPT_PREVIEW ->
                json.valueToTree(wire.promptPreview(requirePrompts()
                        .preview(
                                RequestParameters.requiredText(params, "profileId"),
                                new com.javaclaw.core.api.WorkspaceId(
                                        RequestParameters.requiredText(params, "workspaceId")))));
            case RpcMethods.PROFILE_PROMPT_OPTIMIZE ->
                json.valueToTree(wire.turn(requirePrompts()
                        .optimize(
                                new com.javaclaw.core.api.ThreadId(RequestParameters.requiredText(params, "threadId")),
                                RequestParameters.requiredText(params, "profileId"),
                                RequestParameters.optionalText(params, "draft", ""),
                                RequestParameters.optionalLong(params, "expectedRevision", -1),
                                RequestParameters.requiredText(params, "idempotencyKey"))));
            case RpcMethods.MODEL_LIST -> wire.models(discovery.models());
            case RpcMethods.TOOL_LIST -> wire.tools(discovery.tools());
            case RpcMethods.PROVIDER_LIST ->
                json.valueToTree(
                        requireProviders().list().stream().map(wire::provider).toList());
            case RpcMethods.PROVIDER_CONFIGURE -> configureProvider(params);
            case RpcMethods.PROVIDER_CREDENTIAL_SET -> setCredential(params);
            case RpcMethods.PROVIDER_CREDENTIAL_CLEAR -> clearCredential(params);
            case RpcMethods.PROFILE_LIST ->
                json.valueToTree(
                        requireProfiles().list().stream().map(wire::profile).toList());
            case RpcMethods.PROFILE_READ ->
                json.valueToTree(
                        wire.profile(requireProfiles().read(RequestParameters.requiredText(params, "profileId"))));
            case RpcMethods.PROFILE_PUT ->
                json.valueToTree(wire.profile(requireProfiles()
                        .put(
                                RequestParameters.profileDraft(params),
                                RequestParameters.optionalLong(params, "expectedRevision", 0),
                                RequestParameters.optionalText(params, "idempotencyKey", null))));
            case RpcMethods.PROFILE_DELETE ->
                RpcResults.flag(
                        "deleted",
                        requireProfiles()
                                .delete(
                                        RequestParameters.requiredText(params, "profileId"),
                                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                                        RequestParameters.optionalText(params, "idempotencyKey", null)));
            default -> throw new RpcRouter.MethodNotFound(method);
        };
    }

    private JsonNode configureProvider(JsonNode params) {
        JsonNode config = params == null ? null : params.get("config");
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("config must be an object");
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        config.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("provider config values must be strings");
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return json.valueToTree(wire.provider(requireProviders()
                .configure(
                        RequestParameters.requiredText(params, "provider"),
                        values,
                        RequestParameters.optionalLong(params, "expectedRevision", 0),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
    }

    private JsonNode setCredential(JsonNode params) {
        char[] credential = RequestParameters.requiredText(params, "credential").toCharArray();
        try {
            return json.valueToTree(wire.secretMetadata(requireProviders()
                    .setCredential(
                            RequestParameters.requiredText(params, "provider"),
                            credential,
                            RequestParameters.optionalText(params, "idempotencyKey", null))));
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private JsonNode clearCredential(JsonNode params) {
        return RpcResults.flag(
                "cleared",
                requireProviders()
                        .clearCredential(
                                RequestParameters.requiredText(params, "provider"),
                                RequestParameters.optionalLong(params, "expectedRevision", -1),
                                RequestParameters.optionalText(params, "idempotencyKey", null)));
    }

    private com.javaclaw.agent.conversation.ProfilePromptUseCases requirePrompts() {
        if (prompts == null) {
            throw new IllegalStateException("prompt capability is unavailable");
        }
        return prompts;
    }

    private ProfileUseCases requireProfiles() {
        if (profiles == null) {
            throw new IllegalStateException("profile capability is unavailable");
        }
        return profiles;
    }

    private ProviderUseCases requireProviders() {
        if (providers == null) {
            throw new IllegalStateException("provider capability is unavailable");
        }
        return providers;
    }
}
