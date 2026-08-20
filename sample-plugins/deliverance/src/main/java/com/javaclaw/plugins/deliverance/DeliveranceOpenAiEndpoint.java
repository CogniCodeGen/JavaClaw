package com.javaclaw.plugins.deliverance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.service.api.ExternalInvocation;
import com.javaclaw.service.api.ExternalRequestHandler;
import com.javaclaw.service.api.DesktopServiceClient;
import com.javaclaw.service.api.PluginConfig;
import com.javaclaw.service.api.PluginLogger;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** OpenAI-compatible endpoint served directly by the Deliverance plugin process. */
final class DeliveranceOpenAiEndpoint implements ExternalRequestHandler {
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "model", "messages", "stream", "stream_options", "tools",
            "tool_choice", "parallel_tool_calls");
    private static final Set<String> KNOWN_UNSUPPORTED_FIELDS = Set.of(
            "n", "presence_penalty", "frequency_penalty", "logit_bias", "user",
            "response_format", "service_tier", "store", "metadata");
    private static final Map<String, String> OPENAI_PARAMETER_NAMES = Map.of(
            "max_tokens", "maxTokens",
            "max_completion_tokens", "maxTokens",
            "top_p", "topP",
            "top_logprobs", "topLogprobs");
    private final DeliveranceServicePlugin plugin;
    private final ObjectMapper json;
    private final PluginConfig config;
    private final ExternalApiKeyPolicies policies;
    private final Map<String, JsonNode> generationSchema;

    DeliveranceOpenAiEndpoint(DeliveranceServicePlugin plugin, ObjectMapper json, PluginConfig config,
                              DesktopServiceClient desktop, PluginLogger logger) {
        this.plugin = plugin;
        this.json = json;
        this.config = config;
        this.policies = new ExternalApiKeyPolicies(config, desktop, json, logger);
        this.generationSchema = loadGenerationSchema();
    }

    @Override
    public void handle(ExternalInvocation invocation) {
        try {
            String path = invocation.path();
            if ("GET".equals(invocation.method()) && "/v1/models".equals(path)) {
                try (var quota = policies.authorize(invocation, "MODELS_READ", null)) {
                    quota.success(new Protocol.Usage(0, 0));
                    quota.close();
                    send(invocation, 200, models(invocation));
                }
            } else if ("GET".equals(invocation.method()) && path.startsWith("/v1/models/")) {
                String alias = path.substring("/v1/models/".length());
                try (var quota = policies.authorize(invocation, "MODELS_READ", alias)) {
                    JsonNode response = model(alias);
                    quota.success(new Protocol.Usage(0, 0));
                    quota.close();
                    send(invocation, 200, response);
                }
            } else if ("GET".equals(invocation.method()) && "/openapi.json".equals(path)) {
                try (var quota = policies.authorize(invocation, "MODELS_READ", null)) {
                    quota.success(new Protocol.Usage(0, 0));
                    quota.close();
                    send(invocation, 200, openApi());
                }
            } else if ("POST".equals(invocation.method()) && "/v1/chat/completions".equals(path)) {
                chat(invocation);
            } else if ("POST".equals(invocation.method()) && "/v1/embeddings".equals(path)) {
                embeddings(invocation);
            } else {
                throw new ApiFailure(404, "not_found", "endpoint not found", null);
            }
        } catch (ExternalApiKeyPolicies.PolicyFailure failure) {
            error(invocation, failure.status, failure.code, failure.getMessage(), failure.param);
        } catch (ApiFailure failure) {
            error(invocation, failure.status, failure.code, failure.getMessage(), failure.param);
        } catch (Throwable failure) {
            if (resourceExhausted(failure)) {
                error(invocation, 503, "resource_exhausted", safeMessage(failure), null);
            } else {
                error(invocation, 500, "inference_error", safeMessage(failure), null);
            }
        }
    }

    private void chat(ExternalInvocation invocation) throws Exception {
        long started = System.nanoTime();
        InvocationOutcome outcome = new InvocationOutcome();
        String alias = "";
        try {
            JsonNode body = object(invocation.body());
            alias = requiredText(body, "model");
            try (var quota = policies.authorize(invocation, "CHAT_INVOKE", alias)) {
                chatAuthorized(invocation, body, alias, quota, outcome);
            }
        } catch (DeliveranceEngine.CountedInferenceFailure failure) {
            outcome.failure(failure.cancelled() ? "cancelled" : "error", failure.usage());
            throw failure;
        } catch (CancellationException failure) {
            outcome.failure("cancelled", outcome.usage);
            throw failure;
        } finally {
            plugin.logInvocation(source(invocation), "/v1/chat/completions", "chat", alias,
                    invocation.requestId(), outcome.status, started, outcome.usage);
        }
    }

    private void chatAuthorized(ExternalInvocation invocation, JsonNode body, String alias,
                                ExternalApiKeyPolicies.Lease quota,
                                InvocationOutcome outcome) throws Exception {
        validateEnvelope(body);
        String profileId = requireAlias(alias, "GENERATION");
        boolean stream = body.path("stream").asBoolean(false);
        Map<String, Object> parameters = parameters(body);
        Protocol.ToolChoice choice = toolChoice(body.get("tool_choice"));
        if (body.has("parallel_tool_calls") && !body.path("parallel_tool_calls").asBoolean(true)) {
            throw new ApiFailure(400, "unsupported_parameter",
                    "parallel_tool_calls=false is not supported by this runtime", "parallel_tool_calls");
        }
        List<Protocol.Message> messages = messages(body.path("messages"));
        List<Protocol.Tool> tools = tools(body.path("tools"));
        Protocol.ChatRequest request = new Protocol.ChatRequest(invocation.requestId(), messages, tools,
                parameters, choice, true, stream);
        DeliveranceServicePlugin.EngineSlot slot = plugin.externalAcquire(
                profileId, "GENERATION", invocation.cancellation());
        AtomicBoolean terminal = new AtomicBoolean();
        try {
            if (!stream) {
                Protocol.ChatResponse response = slot.engine().chat(
                        request, invocation.cancellation(), null, 0);
                if (invocation.cancellation().isCancellationRequested()) {
                    throw new CancellationException("request cancelled");
                }
                quota.success(response.usage());
                send(invocation, 200, chatResponse(alias, response));
                outcome.success(response.usage());
                return;
            }
            invocation.response().startStream(200, "text/event-stream; charset=utf-8",
                    Map.of("Cache-Control", "no-cache"));
            sse(invocation, streamRoleChunk(alias, request.requestId()));
            Protocol.ChatResponse response = slot.engine().chat(
                    request, invocation.cancellation(), event -> {
                if (invocation.cancellation().isCancellationRequested() || terminal.get()) return;
                JsonNode chunk = switch (event.type()) {
                    case "CONTENT_DELTA" -> streamChunk(alias, request.requestId(), event.text(), "",
                            List.of(), null, null);
                    case "REASONING_DELTA" -> streamChunk(alias, request.requestId(), "", event.text(),
                            List.of(), null, null);
                    case "TOOL_CALL_DELTA" -> streamChunk(alias, request.requestId(), "", "",
                            event.toolCalls(), null, null);
                    default -> null;
                };
                if (chunk != null) sse(invocation, chunk);
            }, 0);
            if (invocation.cancellation().isCancellationRequested()) {
                throw new CancellationException("request cancelled");
            }
            if (!terminal.compareAndSet(false, true)) return;
            quota.success(response.usage());
            sse(invocation, streamChunk(alias, request.requestId(), "", "", List.of(),
                    finish(response.finishReason()), null));
            if (body.path("stream_options").path("include_usage").asBoolean(false)) {
                sse(invocation, streamUsageChunk(alias, request.requestId(), response.usage()));
            }
            invocation.response().stream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            invocation.response().closeStream();
            outcome.success(response.usage());
        } catch (DeliveranceEngine.CountedInferenceFailure failure) {
            quota.failure(failure.usage());
            outcome.failure(failure.cancelled() ? "cancelled" : "error", failure.usage());
            if (stream && invocation.response().committed()) {
                streamFailure(invocation, terminal,
                        failure.cancelled() ? cancellationCode(invocation) : "inference_error",
                        safeMessage(failure), failure.usage());
                return;
            }
            throw failure;
        } catch (CancellationException failure) {
            Protocol.Usage usage = new Protocol.Usage(0, 0);
            quota.failure(usage);
            outcome.failure("cancelled", usage);
            if (stream && invocation.response().committed()) {
                streamFailure(invocation, terminal, cancellationCode(invocation),
                        safeMessage(failure), usage);
                return;
            }
            throw failure;
        } catch (Throwable failure) {
            if (stream && terminal.get()) {
                try { invocation.response().closeStream(); } catch (RuntimeException ignored) { }
                return;
            }
            Protocol.Usage usage = new Protocol.Usage(0, 0);
            quota.failure(usage);
            outcome.failure("error", usage);
            if (stream && invocation.response().committed()) {
                streamFailure(invocation, terminal, "inference_error", safeMessage(failure), usage);
                return;
            }
            throw failure;
        } finally {
            plugin.externalRelease(slot);
        }
    }

    private void embeddings(ExternalInvocation invocation) throws Exception {
        long started = System.nanoTime();
        InvocationOutcome outcome = new InvocationOutcome();
        String alias = "";
        try {
            JsonNode body = object(invocation.body());
            alias = requiredText(body, "model");
            try (var quota = policies.authorize(invocation, "EMBEDDINGS_INVOKE", alias)) {
                embeddingsAuthorized(invocation, body, alias, quota, outcome);
            }
        } catch (DeliveranceEngine.CountedInferenceFailure failure) {
            outcome.failure(failure.cancelled() ? "cancelled" : "error", failure.usage());
            throw failure;
        } catch (CancellationException failure) {
            outcome.failure("cancelled", outcome.usage);
            throw failure;
        } finally {
            plugin.logInvocation(source(invocation), "/v1/embeddings", "embedding", alias,
                    invocation.requestId(), outcome.status, started, outcome.usage);
        }
    }

    private void embeddingsAuthorized(ExternalInvocation invocation, JsonNode body, String alias,
                                      ExternalApiKeyPolicies.Lease quota,
                                      InvocationOutcome outcome) throws Exception {
        String profileId = requireAlias(alias, "EMBEDDING");
        List<String> input = strings(body.get("input"));
        if (input.isEmpty()) throw new ApiFailure(400, "invalid_request", "input is required", "input");
        DeliveranceServicePlugin.EngineSlot slot = plugin.externalAcquire(
                profileId, "EMBEDDING", invocation.cancellation());
        try {
            Protocol.EmbeddingResponse response = slot.engine().embeddings(
                    new Protocol.EmbeddingRequest(invocation.requestId(), input),
                    invocation.cancellation());
            quota.success(response.usage());
            quota.close();
            ObjectNode root = json.createObjectNode().put("object", "list").put("model", alias);
            ArrayNode data = root.putArray("data");
            for (int index = 0; index < response.embeddings().size(); index++) {
                ObjectNode item = data.addObject().put("object", "embedding").put("index", index);
                ArrayNode vector = item.putArray("embedding");
                for (float component : response.embeddings().get(index)) vector.add(component);
            }
            usage(root.putObject("usage"), response.usage());
            send(invocation, 200, root);
            outcome.success(response.usage());
        } catch (DeliveranceEngine.CountedInferenceFailure failure) {
            quota.failure(failure.usage());
            outcome.failure(failure.cancelled() ? "cancelled" : "error", failure.usage());
            throw failure;
        } finally {
            plugin.externalRelease(slot);
        }
    }

    private static String source(ExternalInvocation invocation) {
        if (invocation.remoteAddress() == null) return "external";
        if (invocation.remoteAddress().getAddress() != null) {
            return invocation.remoteAddress().getAddress().getHostAddress();
        }
        return invocation.remoteAddress().getHostString();
    }

    private static final class InvocationOutcome {
        private String status = "error";
        private Protocol.Usage usage = new Protocol.Usage(0, 0);

        private void success(Protocol.Usage value) {
            status = "success";
            usage = value == null ? new Protocol.Usage(0, 0) : value;
        }

        private void failure(String value, Protocol.Usage counted) {
            status = value;
            usage = counted == null ? new Protocol.Usage(0, 0) : counted;
        }
    }

    private ObjectNode models(ExternalInvocation invocation) {
        ObjectNode root = json.createObjectNode().put("object", "list");
        ArrayNode data = root.putArray("data");
        for (var published : plugin.publishedSnapshot().entrySet()) {
            if (!policies.visible(invocation.credentialId(), published.getKey())) continue;
            var model = published.getValue();
            data.addObject().put("id", published.getKey()).put("object", "model")
                    .put("created", Instant.now().getEpochSecond()).put("owned_by", "javaclaw")
                    .put("capability", "EMBEDDING".equalsIgnoreCase(model.kind())
                            ? "embeddings" : "chat.completions");
        }
        return root;
    }

    private ObjectNode model(String alias) {
        String profile = plugin.resolveAlias(alias);
        if (profile == null) throw new ApiFailure(404, "model_not_found", "model not found", "model");
        var status = plugin.publishedSnapshot().get(alias);
        if (status == null) throw new ApiFailure(404, "model_not_found", "model not found", "model");
        return json.createObjectNode().put("id", alias).put("object", "model")
                .put("created", Instant.now().getEpochSecond()).put("owned_by", "javaclaw")
                .put("capability", "EMBEDDING".equalsIgnoreCase(status.kind())
                        ? "embeddings" : "chat.completions");
    }

    private String requireAlias(String alias, String kind) {
        String profile = plugin.resolveAlias(alias);
        if (profile == null) throw new ApiFailure(404, "model_not_found", "model not found", "model");
        var status = plugin.publishedSnapshot().get(alias);
        boolean correct = status != null && status.profileId().equals(profile)
                && status.kind().equalsIgnoreCase(kind);
        if (!correct) throw new ApiFailure(400, "model_capability_mismatch",
                "model does not support this operation", "model");
        return profile;
    }

    private List<Protocol.Message> messages(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new ApiFailure(400, "invalid_request", "messages is required", "messages");
        }
        List<Protocol.Message> result = new ArrayList<>();
        for (JsonNode value : node) {
            String role = requiredText(value, "role").toUpperCase();
            String content = value.path("content").isTextual() ? value.path("content").asText() : "";
            String reasoning = value.path("reasoning_content").asText("");
            String toolCallId = value.path("tool_call_id").asText("");
            result.add(new Protocol.Message(role, content, reasoning, toolCallId,
                    toolCalls(value.path("tool_calls"))));
        }
        return result;
    }

    private List<Protocol.Tool> tools(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Protocol.Tool> result = new ArrayList<>();
        for (JsonNode value : node) {
            JsonNode function = value.path("function");
            if (!"function".equals(value.path("type").asText("function"))) {
                throw new ApiFailure(400, "unsupported_tool", "only function tools are supported", "tools");
            }
            @SuppressWarnings("unchecked") Map<String, Object> schema = function.path("parameters").isObject()
                    ? json.convertValue(function.path("parameters"), Map.class) : Map.of();
            result.add(new Protocol.Tool(requiredText(function, "name"),
                    function.path("description").asText(""), schema));
        }
        return result;
    }

    private List<Protocol.ToolCall> toolCalls(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Protocol.ToolCall> result = new ArrayList<>();
        for (JsonNode value : node) {
            JsonNode function = value.path("function");
            result.add(new Protocol.ToolCall(value.path("id").asText(""),
                    requiredText(function, "name"), function.path("arguments").asText("{}")));
        }
        return result;
    }

    private Protocol.ToolChoice toolChoice(JsonNode node) {
        if (node == null || node.isNull() || "auto".equals(node.asText())) {
            return new Protocol.ToolChoice("AUTO", "");
        }
        if ("none".equals(node.asText())) return new Protocol.ToolChoice("NONE", "");
        throw new ApiFailure(400, "unsupported_parameter",
                "tool_choice supports only auto or none", "tool_choice");
    }

    Map<String, Object> parameters(JsonNode body) {
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<String> fields = body.fieldNames();
        while (fields.hasNext()) {
            String source = fields.next();
            if (ENVELOPE_FIELDS.contains(source)) continue;
            if (KNOWN_UNSUPPORTED_FIELDS.contains(source)) {
                throw new ApiFailure(400, "unsupported_parameter",
                        source + " is not supported by this runtime", source);
            }
            String target = OPENAI_PARAMETER_NAMES.getOrDefault(source, source);
            JsonNode schema = generationSchema.get(target);
            if (schema == null) {
                throw new ApiFailure(400, "unknown_parameter",
                        "unknown request parameter: " + source, source);
            }
            if (values.containsKey(target)) {
                throw new ApiFailure(400, "invalid_request",
                        "duplicate parameter mapping for " + target, source);
            }
            JsonNode value = body.get(source);
            validateParameter(source, value, schema);
            values.put(target, json.convertValue(value, Object.class));
        }
        return values;
    }

    void validateEnvelope(JsonNode body) {
        if (body.has("stream") && !body.get("stream").isBoolean()) {
            throw new ApiFailure(400, "invalid_request", "stream must be a boolean", "stream");
        }
        if (body.has("parallel_tool_calls") && !body.get("parallel_tool_calls").isBoolean()) {
            throw new ApiFailure(400, "invalid_request",
                    "parallel_tool_calls must be a boolean", "parallel_tool_calls");
        }
        if (body.has("stream_options")) {
            JsonNode options = body.get("stream_options");
            if (!options.isObject()) {
                throw new ApiFailure(400, "invalid_request",
                        "stream_options must be an object", "stream_options");
            }
            Iterator<String> names = options.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!"include_usage".equals(name)) {
                    throw new ApiFailure(400, "unknown_parameter",
                            "unknown stream_options parameter: " + name,
                            "stream_options." + name);
                }
            }
            if (options.has("include_usage") && !options.get("include_usage").isBoolean()) {
                throw new ApiFailure(400, "invalid_request",
                        "include_usage must be a boolean", "stream_options.include_usage");
            }
        }
    }

    private void validateParameter(String source, JsonNode value, JsonNode schema) {
        String type = schema.path("type").asText();
        boolean valid = switch (type) {
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "string" -> value.isTextual();
            case "array" -> value.isArray();
            default -> false;
        };
        if (!valid) throw new ApiFailure(400, "invalid_request",
                source + " has an invalid type", source);
        if (value.isNumber()) {
            java.math.BigDecimal number = value.decimalValue();
            if (schema.has("minimum")
                    && number.compareTo(schema.path("minimum").decimalValue()) < 0
                    || schema.has("maximum")
                    && number.compareTo(schema.path("maximum").decimalValue()) > 0) {
                throw new ApiFailure(400, "invalid_request",
                        source + " is outside the supported range", source);
            }
        }
        if (value.isTextual() && schema.has("maxLength")
                && value.textValue().length() > schema.path("maxLength").asInt()) {
            throw new ApiFailure(400, "invalid_request", source + " is too long", source);
        }
        if (value.isArray()) {
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
                throw new ApiFailure(400, "invalid_request", source + " has too many items", source);
            }
            if ("string".equals(schema.path("items").path("type").asText())) {
                value.forEach(item -> {
                    if (!item.isTextual()) throw new ApiFailure(400, "invalid_request",
                            source + " must contain only strings", source);
                });
            }
        }
        if (schema.path("enum").isArray()) {
            boolean allowed = false;
            for (JsonNode option : schema.path("enum")) {
                if (option.equals(value)) { allowed = true; break; }
            }
            if (!allowed) throw new ApiFailure(400, "invalid_request",
                    source + " is not an allowed value", source);
        }
    }

    private Map<String, JsonNode> loadGenerationSchema() {
        try (InputStream input = DeliveranceOpenAiEndpoint.class.getClassLoader()
                .getResourceAsStream("plugin.json")) {
            if (input == null) throw new IllegalStateException("signed plugin.json is missing");
            JsonNode properties = json.readTree(input).path("inference").path("parameterSchema")
                    .path("properties").path("generation").path("properties");
            if (!properties.isObject() || properties.isEmpty()) {
                throw new IllegalStateException("generation parameter schema is missing");
            }
            Map<String, JsonNode> result = new LinkedHashMap<>();
            properties.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue()));
            return Map.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot load signed generation parameter schema", failure);
        }
    }

    private ObjectNode chatResponse(String alias, Protocol.ChatResponse value) {
        ObjectNode root = json.createObjectNode().put("id", "chatcmpl-" + value.requestId())
                .put("object", "chat.completion").put("created", Instant.now().getEpochSecond())
                .put("model", alias);
        ObjectNode choice = root.putArray("choices").addObject().put("index", 0);
        ObjectNode message = choice.putObject("message").put("role", "assistant")
                .put("content", value.content());
        if (!value.reasoningContent().isBlank()) message.put("reasoning_content", value.reasoningContent());
        writeToolCalls(message.putArray("tool_calls"), value.toolCalls());
        choice.put("finish_reason", finish(value.finishReason()));
        usage(root.putObject("usage"), value.usage());
        return root;
    }

    private ObjectNode streamChunk(String alias, String requestId, String content, String reasoning,
                                   List<Protocol.ToolCall> calls, String finish, Protocol.Usage usage) {
        ObjectNode root = json.createObjectNode().put("id", "chatcmpl-" + requestId)
                .put("object", "chat.completion.chunk").put("created", Instant.now().getEpochSecond())
                .put("model", alias);
        ObjectNode choice = root.putArray("choices").addObject().put("index", 0);
        ObjectNode delta = choice.putObject("delta");
        if (!content.isEmpty()) delta.put("content", content);
        if (!reasoning.isEmpty()) delta.put("reasoning_content", reasoning);
        writeToolCalls(delta.putArray("tool_calls"), calls);
        if (finish == null) choice.putNull("finish_reason"); else choice.put("finish_reason", finish);
        if (usage != null) usage(root.putObject("usage"), usage);
        return root;
    }

    private ObjectNode streamRoleChunk(String alias, String requestId) {
        ObjectNode root = streamChunk(alias, requestId, "", "", List.of(), null, null);
        ((ObjectNode) root.path("choices").path(0).path("delta")).put("role", "assistant");
        return root;
    }

    private ObjectNode streamUsageChunk(String alias, String requestId, Protocol.Usage usage) {
        ObjectNode root = json.createObjectNode().put("id", "chatcmpl-" + requestId)
                .put("object", "chat.completion.chunk").put("created", Instant.now().getEpochSecond())
                .put("model", alias);
        root.putArray("choices");
        usage(root.putObject("usage"), usage);
        return root;
    }

    private void writeToolCalls(ArrayNode target, List<Protocol.ToolCall> calls) {
        if (calls == null) return;
        for (int index = 0; index < calls.size(); index++) {
            Protocol.ToolCall call = calls.get(index);
            target.addObject().put("index", index).put("id", call.id()).put("type", "function")
                    .putObject("function").put("name", call.name()).put("arguments", call.argumentsJson());
        }
    }

    private static void usage(ObjectNode target, Protocol.Usage usage) {
        Protocol.Usage value = usage == null ? new Protocol.Usage(0, 0) : usage;
        target.put("prompt_tokens", value.promptTokens())
                .put("completion_tokens", value.completionTokens())
                .put("total_tokens", value.promptTokens() + value.completionTokens());
    }

    private ObjectNode openApi() {
        ObjectNode root = json.createObjectNode().put("openapi", "3.1.0");
        root.putObject("info").put("title", "JavaClaw Deliverance API").put("version", "1.0.0");
        ObjectNode paths = root.putObject("paths");
        paths.putObject("/v1/models").putObject("get").put("operationId", "listModels");
        paths.putObject("/v1/models/{id}").putObject("get").put("operationId", "retrieveModel");
        paths.putObject("/v1/chat/completions").putObject("post").put("operationId", "chatCompletions");
        paths.putObject("/v1/embeddings").putObject("post").put("operationId", "embeddings");
        return root;
    }

    private void sse(ExternalInvocation invocation, JsonNode value) {
        try {
            invocation.response().stream(("data: " + json.writeValueAsString(value) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private void streamFailure(ExternalInvocation invocation, AtomicBoolean terminal,
                               String code, String message, Protocol.Usage usage) {
        if (!terminal.compareAndSet(false, true)) return;
        try {
            ObjectNode event = json.createObjectNode();
            ObjectNode error = event.putObject("error").put("message", message)
                    .put("type", "inference_error").put("code", code);
            error.putNull("param");
            usage(event.putObject("usage"), usage);
            invocation.response().stream(("data: " + json.writeValueAsString(event) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            invocation.response().stream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // A disconnected client is already represented by the Runner cancellation token.
        } finally {
            try { invocation.response().closeStream(); } catch (RuntimeException ignored) { }
        }
    }

    private void send(ExternalInvocation invocation, int status, JsonNode value) throws Exception {
        invocation.response().send(status, "application/json; charset=utf-8", Map.of(),
                json.writeValueAsBytes(value));
    }

    private void error(ExternalInvocation invocation, int status, String code, String message, String param) {
        if (invocation.response().committed()) {
            try { invocation.response().closeStream(); } catch (RuntimeException ignored) { }
            return;
        }
        try {
            ObjectNode body = json.createObjectNode();
            ObjectNode error = body.putObject("error").put("message", message)
                    .put("type", status == 401 ? "authentication_error" : "invalid_request_error")
                    .put("code", code);
            if (param == null) error.putNull("param"); else error.put("param", param);
            send(invocation, status, body);
        } catch (Exception ignored) { }
    }

    private JsonNode object(byte[] body) throws Exception {
        JsonNode value = json.readTree(body);
        if (value == null || !value.isObject()) {
            throw new ApiFailure(400, "invalid_json", "request body must be a JSON object", null);
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        if (value.isEmpty()) throw new ApiFailure(400, "invalid_request", field + " is required", field);
        return value;
    }

    private static List<String> strings(JsonNode value) {
        if (value == null) return List.of();
        if (value.isTextual()) return List.of(value.asText());
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> { if (item.isTextual()) result.add(item.asText()); });
        return result;
    }

    private static String finish(String value) {
        if (value == null) return "stop";
        return switch (value) {
            case "TOOL_CALLS" -> "tool_calls";
            case "LENGTH" -> "length";
            default -> "stop";
        };
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static boolean resourceExhausted(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null
                    && current.getMessage().startsWith("resource_exhausted:")) return true;
        }
        return false;
    }

    private static String cancellationCode(ExternalInvocation invocation) {
        return invocation.deadline() != null && !Instant.now().isBefore(invocation.deadline())
                ? "request_timeout" : "cancelled";
    }

    static final class ApiFailure extends RuntimeException {
        final int status;
        final String code;
        final String param;
        private ApiFailure(int status, String code, String message, String param) {
            super(message); this.status = status; this.code = code; this.param = param;
        }
    }
}
