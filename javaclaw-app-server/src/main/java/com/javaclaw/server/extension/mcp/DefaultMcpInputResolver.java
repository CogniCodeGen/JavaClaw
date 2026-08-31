package com.javaclaw.server.extension.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.model.ModelInvocationService;
import com.javaclaw.agent.prompt.ContextBlock;
import com.javaclaw.agent.prompt.PromptPurpose;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.UserInputGateway;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ThreadItem;

/** Elicitation, sampling and roots resolver constrained to the invoking Turn. */
public final class DefaultMcpInputResolver implements McpInputResolver {
    private final UserInputGateway userInput;
    private final ModelInvocationService models;
    private final ObjectMapper json;

    /** 绑定用户交互和共享模型预算网关，将 MRTR 映射到本 Turn；Sampling 不开放递归工具。 */
    public DefaultMcpInputResolver(UserInputGateway userInput, ModelGateway models, ObjectMapper json) {
        this.userInput = Objects.requireNonNull(userInput, "userInput");
        this.models = new ModelInvocationService(Objects.requireNonNull(models, "models"));
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public JsonNode resolve(String requestId, JsonNode request, McpInvocation invocation) throws Exception {
        String method = request.path("method").asText();
        return switch (method) {
            case "roots/list" -> roots(invocation);
            case "elicitation/create" -> elicit(requestId, request.path("params"), invocation);
            case "sampling/createMessage" -> sample(request.path("params"), invocation);
            default -> throw new IllegalStateException("unsupported MCP MRTR input method: " + method);
        };
    }

    private ObjectNode roots(McpInvocation invocation) {
        ObjectNode result = json.createObjectNode();
        ArrayNode roots = result.putArray("roots");
        ObjectNode root = roots.addObject();
        root.put("uri", invocation.context().thread().workingDirectory().toUri().toString());
        root.put("name", invocation.context().thread().workspaceId());
        return result;
    }

    private ObjectNode elicit(String mcpRequestId, JsonNode params, McpInvocation invocation) throws Exception {
        String mode = params.path("mode").asText("form");
        String prompt = params.path("message").asText("MCP server requests input");
        if (mode.equals("url")) {
            String url = params.path("url").asText("");
            if (!url.isBlank()) {
                prompt += "\n" + url;
            }
            UserInputGateway.Response response = ask(mcpRequestId, prompt, List.of("approve", "decline"), invocation);
            ObjectNode result = json.createObjectNode();
            result.put("action", !response.cancelled() && "approve".equals(response.value()) ? "accept" : "decline");
            return result;
        }
        if (!mode.equals("form")) {
            throw new IllegalArgumentException("unsupported MCP elicitation mode: " + mode);
        }
        JsonNode requestedSchema = params.path("requestedSchema");
        if (!requestedSchema.isObject()
                || !"object".equals(requestedSchema.path("type").asText())) {
            throw new IllegalArgumentException("MCP form elicitation requires a top-level object schema");
        }
        JsonNode properties = requestedSchema.path("properties");
        if (!properties.isObject() || properties.isEmpty() || properties.size() > 32) {
            throw new IllegalArgumentException("MCP form elicitation requires 1-32 top-level fields");
        }
        validateRequired(requestedSchema.path("required"), properties);
        ObjectNode result = json.createObjectNode();
        ObjectNode content = result.putObject("content");
        var fields = properties.fields();
        int fieldIndex = 0;
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode schema = field.getValue();
            List<String> choices = validatePrimitiveSchema(field.getKey(), schema);
            String label = schema.path("title").asText(field.getKey());
            String description = schema.path("description").asText("");
            String fieldPrompt = prompt + "\n" + label + (description.isBlank() ? "" : ": " + description);
            UserInputGateway.Response response =
                    ask(mcpRequestId + "_" + fieldIndex++, fieldPrompt, choices, invocation);
            if (response.cancelled()) {
                result.remove("content");
                result.put("action", "cancel");
                return result;
            }
            putFormValue(content, field.getKey(), schema, response.value());
        }
        result.put("action", "accept");
        return result;
    }

    private void putFormValue(ObjectNode content, String field, JsonNode schema, String value) {
        String type = schema.path("type").asText("string");
        try {
            switch (type) {
                case "boolean" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("expected true or false");
                    }
                    content.put(field, Boolean.parseBoolean(value));
                }
                case "integer" -> {
                    long parsed = Long.parseLong(value);
                    validateNumberBounds(schema, parsed);
                    content.put(field, parsed);
                }
                case "number" -> {
                    double parsed = Double.parseDouble(value);
                    if (!Double.isFinite(parsed)) {
                        throw new IllegalArgumentException("expected a finite number");
                    }
                    validateNumberBounds(schema, parsed);
                    content.put(field, parsed);
                }
                case "array" -> {
                    JsonNode parsed = json.readTree(value);
                    if (parsed == null || !parsed.isArray()) {
                        throw new IllegalArgumentException("expected a JSON string array");
                    }
                    List<String> allowed = selectionValues(schema);
                    Set<String> unique = new HashSet<>();
                    parsed.forEach(selected -> {
                        if (!selected.isTextual() || !allowed.contains(selected.asText())) {
                            throw new IllegalArgumentException("array contains an unoffered selection");
                        }
                        if (!unique.add(selected.asText())) {
                            throw new IllegalArgumentException("array contains a duplicate selection");
                        }
                    });
                    validateItemCount(schema, parsed.size());
                    content.set(field, parsed);
                }
                case "string" -> {
                    List<String> allowed = selectionValues(schema);
                    if (!allowed.isEmpty() && !allowed.contains(value)) {
                        throw new IllegalArgumentException("value is not an offered selection");
                    }
                    validateStringLength(schema, value);
                    content.put(field, value);
                }
                default -> throw new IllegalArgumentException("unsupported primitive type: " + type);
            }
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "invalid MCP elicitation value for " + field + ": " + failure.getMessage(), failure);
        }
    }

    private List<String> validatePrimitiveSchema(String field, JsonNode schema) {
        if (!schema.isObject()) {
            throw new IllegalArgumentException("MCP elicitation field schema is not an object: " + field);
        }
        String type = schema.path("type").asText("");
        if (!Set.of("string", "integer", "number", "boolean", "array").contains(type)) {
            throw new IllegalArgumentException("MCP elicitation field is not primitive: " + field);
        }
        if (schema.has("properties") || schema.has("additionalProperties")) {
            throw new IllegalArgumentException("nested MCP elicitation schemas are forbidden: " + field);
        }
        List<String> choices = selectionValues(schema);
        if (type.equals("array") && choices.isEmpty()) {
            throw new IllegalArgumentException("MCP array elicitation must be a string multi-select: " + field);
        }
        if (!type.equals("array") && schema.has("items")) {
            throw new IllegalArgumentException("MCP primitive elicitation has nested items: " + field);
        }
        if (!type.equals("string") && !type.equals("array") && !choices.isEmpty()) {
            throw new IllegalArgumentException("MCP selections must be string-valued: " + field);
        }
        return choices;
    }

    private static void validateRequired(JsonNode required, JsonNode properties) {
        if (required.isMissingNode()) {
            return;
        }
        if (!required.isArray()) {
            throw new IllegalArgumentException("MCP elicitation required must be an array");
        }
        Set<String> names = new HashSet<>();
        required.forEach(value -> {
            if (!value.isTextual() || !properties.has(value.asText()) || !names.add(value.asText())) {
                throw new IllegalArgumentException("MCP elicitation required contains an invalid field");
            }
        });
    }

    private static List<String> selectionValues(JsonNode schema) {
        JsonNode values = schema.get("enum");
        if (values == null) {
            values = schema.get("oneOf");
        }
        if (schema.path("type").asText().equals("array")) {
            values = schema.path("items").get("enum");
            if (values == null) {
                values = schema.path("items").get("anyOf");
            }
        }
        if (values == null) {
            return List.of();
        }
        if (!values.isArray() || values.isEmpty() || values.size() > 100) {
            throw new IllegalArgumentException("MCP elicitation choices are invalid");
        }
        ArrayList<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        values.forEach(value -> {
            JsonNode actual = value.isObject() ? value.get("const") : value;
            if (actual == null || !actual.isTextual() || actual.asText().isEmpty() || !unique.add(actual.asText())) {
                throw new IllegalArgumentException("MCP elicitation choice is invalid");
            }
            if (value.isObject() && !value.path("title").isTextual()) {
                throw new IllegalArgumentException("MCP titled elicitation choice has no title");
            }
            result.add(actual.asText());
        });
        return List.copyOf(result);
    }

    private static void validateNumberBounds(JsonNode schema, double value) {
        if (schema.has("minimum") && value < schema.path("minimum").asDouble()
                || schema.has("maximum") && value > schema.path("maximum").asDouble()) {
            throw new IllegalArgumentException("number is outside the requested range");
        }
    }

    private static void validateStringLength(JsonNode schema, String value) {
        int points = value.codePointCount(0, value.length());
        if (schema.has("minLength") && points < schema.path("minLength").asInt()
                || schema.has("maxLength") && points > schema.path("maxLength").asInt()) {
            throw new IllegalArgumentException("string length is outside the requested range");
        }
    }

    private static void validateItemCount(JsonNode schema, int count) {
        if (schema.has("minItems") && count < schema.path("minItems").asInt()
                || schema.has("maxItems") && count > schema.path("maxItems").asInt()) {
            throw new IllegalArgumentException("selection count is outside the requested range");
        }
    }

    private UserInputGateway.Response ask(
            String mcpRequestId, String prompt, List<String> choices, McpInvocation invocation) throws Exception {
        String id = "mcp_input_" + mcpRequestId.replaceAll("[^A-Za-z0-9_-]", "_");
        UserInputGateway.Request request = new UserInputGateway.Request(id, invocation.context(), prompt, choices);
        UserInputGateway.Response response = userInput.ask(
                request, () -> invocation.events().append(new ThreadItem.UserInputRequest(id, prompt, choices)));
        invocation.events().append(new ThreadItem.UserInputResponse(id, response.value(), response.cancelled()));
        return response;
    }

    private ObjectNode sample(JsonNode params, McpInvocation invocation) throws Exception {
        if (params.has("tools") || params.has("toolChoice")) {
            throw new IllegalArgumentException("MCP nested sampling cannot recursively expose tools");
        }
        invocation.consumeNestedModelCall();
        ArrayList<ModelMessage> messages = new ArrayList<>();
        ArrayList<ContextBlock> references = new ArrayList<>();
        String system = params.path("systemPrompt").asText("");
        if (!system.isBlank()) {
            references.add(
                    new ContextBlock(ContextBlock.Kind.EXTERNAL_REQUEST, "mcp", "sampling-system-prompt", 0, system));
        }
        for (JsonNode message : params.path("messages")) {
            ModelMessage.Role role = "assistant".equals(message.path("role").asText())
                    ? ModelMessage.Role.ASSISTANT
                    : ModelMessage.Role.USER;
            messages.add(new ModelMessage(role, contentText(message.path("content")), null));
        }
        var prepared = models.prepare(
                PromptPurpose.MCP_SAMPLING,
                invocation.context().config(),
                List.of(),
                references,
                com.javaclaw.agent.prompt.AgentsInstructionResolution.empty(
                        invocation.context().thread().workingDirectory()),
                messages);
        TurnExecutionContext context = new TurnExecutionContext(
                invocation.context().thread(),
                invocation.context().turn(),
                List.of(),
                new java.util.concurrent.atomic.AtomicBoolean(),
                com.javaclaw.core.api.TurnSteering.NONE,
                invocation.context().scope());
        ModelResponse response = models.complete(context, prepared, invocation.events());
        ObjectNode result = json.createObjectNode();
        result.put("role", "assistant");
        ObjectNode content = result.putObject("content");
        content.put("type", "text");
        content.put("text", response.text());
        result.put("model", invocation.context().config().model());
        result.put("stopReason", "endTurn");
        return result;
    }

    private String contentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder value = new StringBuilder();
            content.forEach(block -> {
                if (block.path("type").asText().equals("text")) {
                    if (!value.isEmpty()) {
                        value.append('\n');
                    }
                    value.append(block.path("text").asText());
                }
            });
            return value.toString();
        }
        if (content.path("type").asText().equals("text")) {
            return content.path("text").asText();
        }
        return content.toString();
    }
}
