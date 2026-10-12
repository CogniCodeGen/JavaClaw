package com.javaclaw.infrastructure.inference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.inference.InferenceCatalogPort;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.inference.api.InferenceMessage;
import com.javaclaw.inference.api.InferenceModelProfile;
import com.javaclaw.inference.api.InferenceTool;
import com.javaclaw.inference.api.InferenceToolCall;
import com.javaclaw.inference.api.InferenceToolChoice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** OpenAI 请求到稳定推理契约的纯解析与 manifest 参数校验。 */
final class OpenAiInferenceRequestParser {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Set<String> INTERNAL_PARAMETERS = Set.of(
            "temperature", "maxTokens", "seed", "stop", "includeStopString", "guidedChoice",
            "guidedRegex", "guidedJson", "logprobs", "topLogprobs", "xtcThreshold",
            "xtcProbability", "topK", "topP", "uniformTopP", "cacheSalt", "enableThinking");
    private static final Set<String> SAME_NAME_OPENAI_PARAMETERS =
            Set.of("temperature", "seed", "stop", "logprobs");
    private static final JsonSchemaValidator SCHEMAS = new JsonSchemaValidator();

    private final InferenceCatalogPort catalog;
    private final ObjectMapper json;

    OpenAiInferenceRequestParser(InferenceCatalogPort catalog, ObjectMapper json) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    ParsedChat parseChat(JsonNode request, ParameterContract contract) {
        JsonNode messagesNode = request.get("messages");
        if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
            bad("messages", "messages 必须是非空数组");
        }
        List<InferenceMessage> messages = new ArrayList<>();
        for (JsonNode message : messagesNode) messages.add(message(message));
        List<InferenceTool> tools = tools(request.path("tools"));
        InferenceToolChoice toolChoice = toolChoice(tools, request.get("tool_choice"), contract);
        if (request.has("parallel_tool_calls") && !request.path("parallel_tool_calls").isBoolean()) {
            bad("parallel_tool_calls", "parallel_tool_calls 必须是布尔值");
        }
        boolean parallelToolCalls = !request.has("parallel_tool_calls")
                || request.path("parallel_tool_calls").booleanValue();
        if (!parallelToolCalls && !tools.isEmpty()
                && !contract.capabilities().contains("parallel_tool_calls_control")) {
            bad("parallel_tool_calls", "当前模型运行时不支持 parallel_tool_calls=false");
        }
        Map<String, Object> parameters = generationParameters(request, contract);
        return new ParsedChat(messages, tools, parameters, toolChoice, parallelToolCalls,
                Math.max(1, effectiveMaxTokens(parameters, contract)));
    }

    ParameterContract parameterContract(InferenceCatalogPort.PublishedModel published) {
        InferenceModelProfile profile = catalog.profile(published.profileId()).orElseThrow(() ->
                new OpenAiApiFailure(404, "invalid_request_error", "model_not_found",
                        "模型档案不存在", "model"));
        var runtime = catalog.runtime(profile.runtimeId()).orElseThrow(() ->
                new OpenAiApiFailure(503, "server_error", "runtime_not_ready",
                        "模型运行时当前不可用", "model"));
        JsonNode generation = json.valueToTree(runtime.manifest().parameterSchema())
                .path("properties").path("generation");
        Set<String> fields = new HashSet<>();
        generation.path("properties").fieldNames().forEachRemaining(fields::add);
        if (fields.isEmpty()) fields.addAll(INTERNAL_PARAMETERS);
        return new ParameterContract(profile, Set.copyOf(fields),
                runtime.manifest().capabilities(),
                generation.isObject() && generation.has("properties") ? generation : null);
    }

    List<String> embeddingInput(JsonNode value) {
        if (value == null || value.isNull()) bad("input", "input 不能为空");
        if (value.isTextual()) return List.of(value.asText());
        if (!value.isArray() || value.isEmpty()) bad("input", "input 必须是字符串或非空字符串数组");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) bad("input", "首版不支持 token ID 数组");
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private InferenceMessage message(JsonNode value) {
        if (!value.isObject()) bad("messages", "消息必须是对象");
        String role = requiredText(value, "role").toUpperCase(Locale.ROOT);
        InferenceMessage.Role mapped;
        try {
            mapped = InferenceMessage.Role.valueOf(role);
        } catch (IllegalArgumentException failure) {
            bad("messages.role", "不支持的消息角色");
            return null;
        }
        return new InferenceMessage(mapped, content(value.get("content")),
                value.path("reasoning_content").asText(""), value.path("tool_call_id").asText(""),
                toolCalls(value.path("tool_calls")));
    }

    private String content(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (!value.isArray()) bad("messages.content", "消息内容必须是字符串或文本块数组");
        StringBuilder result = new StringBuilder();
        for (JsonNode part : value) {
            if (!"text".equals(part.path("type").asText()) || !part.path("text").isTextual()) {
                bad("messages.content", "本地推理首版仅支持 text 内容块");
            }
            result.append(part.path("text").asText());
        }
        return result.toString();
    }

    private List<InferenceTool> tools(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray()) bad("tools", "tools 必须是数组");
        List<InferenceTool> result = new ArrayList<>();
        for (JsonNode tool : values) {
            if (!"function".equals(tool.path("type").asText("function"))) {
                bad("tools.type", "只支持 function 工具定义");
            }
            JsonNode function = tool.path("function");
            String name = requiredText(function, "name");
            Map<String, Object> schema = function.path("parameters").isObject()
                    ? json.convertValue(function.path("parameters"), MAP) : Map.of();
            result.add(new InferenceTool(name, function.path("description").asText(""), schema));
        }
        return List.copyOf(result);
    }

    private InferenceToolChoice toolChoice(
            List<InferenceTool> tools, JsonNode choice, ParameterContract contract) {
        if (choice == null || choice.isNull() || "auto".equals(choice.asText())) {
            return InferenceToolChoice.auto();
        }
        if ("none".equals(choice.asText())) return InferenceToolChoice.none();
        if ("required".equals(choice.asText())) {
            if (tools.isEmpty()) bad("tool_choice", "tool_choice=required 需要至少声明一个工具");
            if (!contract.capabilities().contains("tool_choice_required")) {
                bad("tool_choice", "当前模型运行时不支持 tool_choice=required");
            }
            return InferenceToolChoice.required();
        }
        if (choice.isObject()) {
            if (!"function".equals(choice.path("type").asText("function"))) {
                bad("tool_choice", "tool_choice 只支持 function 类型");
            }
            String selected = requiredText(choice.path("function"), "name");
            if (tools.stream().noneMatch(tool -> tool.name().equals(selected))) {
                throw new OpenAiApiFailure(400, "invalid_request_error", "invalid_tool_choice",
                        "tool_choice 引用了未声明工具", "tool_choice");
            }
            if (!contract.capabilities().contains("tool_choice_named")) {
                bad("tool_choice", "当前模型运行时不支持指定函数 tool_choice");
            }
            return InferenceToolChoice.named(selected);
        }
        bad("tool_choice", "tool_choice 无效");
        return InferenceToolChoice.auto();
    }

    private Map<String, Object> generationParameters(JsonNode request, ParameterContract contract) {
        Map<String, Object> result = new LinkedHashMap<>();
        mapParameter(request, result, "temperature", "temperature");
        if (request.has("max_tokens") && request.has("max_completion_tokens")) {
            bad("max_tokens", "max_tokens 与 max_completion_tokens 不能同时提供");
        }
        mapParameter(request, result, request.has("max_completion_tokens")
                ? "max_completion_tokens" : "max_tokens", "maxTokens");
        mapParameter(request, result, "seed", "seed");
        mapParameter(request, result, "stop", "stop");
        mapParameter(request, result, "top_p", "topP");
        mapParameter(request, result, "top_k", "topK");
        mapParameter(request, result, "include_stop_string", "includeStopString");
        mapParameter(request, result, "guided_choice", "guidedChoice");
        mapParameter(request, result, "guided_regex", "guidedRegex");
        mapParameter(request, result, "guided_json", "guidedJson");
        mapParameter(request, result, "logprobs", "logprobs");
        mapParameter(request, result, "top_logprobs", "topLogprobs");
        mapParameter(request, result, "xtc_threshold", "xtcThreshold");
        mapParameter(request, result, "xtc_probability", "xtcProbability");
        mapParameter(request, result, "uniform_top_p", "uniformTopP");
        mapParameter(request, result, "cache_salt", "cacheSalt");
        mapParameter(request, result, "enable_thinking", "enableThinking");
        JsonNode format = request.path("response_format");
        if (format.isObject() && "json_schema".equals(format.path("type").asText())) {
            JsonNode schema = format.path("json_schema").path("schema");
            if (!schema.isObject()) bad("response_format", "json_schema.schema 必须是对象");
            result.put("guidedJson", schema.toString());
        } else if (!format.isMissingNode() && !format.isNull()
                && !"text".equals(format.path("type").asText("text"))) {
            bad("response_format", "只支持 text 或 json_schema 响应格式");
        }
        for (String field : contract.fields()) {
            JsonNode direct = request.get(field);
            if (direct == null || direct.isNull()) continue;
            if (result.containsKey(field)) {
                if (SAME_NAME_OPENAI_PARAMETERS.contains(field)) continue;
                bad(field, "同一运行时参数不能同时使用 OpenAI 名称和 manifest 名称");
            }
            result.put(field, json.convertValue(direct, Object.class));
        }
        if (!contract.fields().containsAll(result.keySet())) {
            String unsupported = result.keySet().stream()
                    .filter(key -> !contract.fields().contains(key)).findFirst().orElse("parameters");
            bad(unsupported, "当前模型运行时不支持参数: " + unsupported);
        }
        if (contract.schema() != null) {
            var issues = SCHEMAS.validate(contract.schema(), json.valueToTree(result), "/parameters");
            if (!issues.isEmpty()) {
                var issue = issues.getFirst();
                bad(issue.path(), "运行时参数不符合 manifest Schema: " + issue.message());
            }
        }
        return Map.copyOf(result);
    }

    private List<InferenceToolCall> toolCalls(JsonNode values) {
        if (values.isMissingNode() || values.isNull()) return List.of();
        if (!values.isArray()) bad("tool_calls", "tool_calls 必须是数组");
        List<InferenceToolCall> result = new ArrayList<>();
        for (JsonNode call : values) {
            JsonNode function = call.path("function");
            result.add(new InferenceToolCall(call.path("id").asText("call_" + UUID.randomUUID()),
                    requiredText(function, "name"), function.path("arguments").asText("{}")));
        }
        return List.copyOf(result);
    }

    private static int effectiveMaxTokens(Map<String, Object> request, ParameterContract contract) {
        Object value = request.get("maxTokens");
        if (value == null) value = contract.profile().defaultParameters().get("maxTokens");
        if (value == null && contract.schema() != null) {
            JsonNode configuredDefault = contract.schema().path("properties").path("maxTokens").path("default");
            if (configuredDefault.isNumber()) value = configuredDefault.numberValue();
        }
        if (value instanceof Number number) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, number.longValue()));
        }
        return Math.max(1, contract.profile().contextLength());
    }

    private static void mapParameter(JsonNode source, Map<String, Object> target,
                                     String sourceName, String targetName) {
        JsonNode value = source.get(sourceName);
        if (value == null || value.isNull()) return;
        target.put(targetName, switch (value.getNodeType()) {
            case BOOLEAN -> value.booleanValue();
            case NUMBER -> value.numberValue();
            case STRING -> value.textValue();
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                value.forEach(item -> list.add(item.isNumber() ? item.numberValue() : item.asText()));
                yield List.copyOf(list);
            }
            case OBJECT -> value.toString();
            default -> throw new OpenAiApiFailure(400, "invalid_request_error", "invalid_parameter",
                    "参数类型无效: " + sourceName, sourceName);
        });
    }

    private static String requiredText(JsonNode root, String name) {
        JsonNode value = root == null ? null : root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            bad(name, name + " 必须是非空字符串");
        }
        return value.asText().strip();
    }

    private static void bad(String parameter, String message) {
        throw new OpenAiApiFailure(400, "invalid_request_error", "invalid_parameter", message, parameter);
    }

    record ParsedChat(List<InferenceMessage> messages, List<InferenceTool> tools,
                      Map<String, Object> parameters, InferenceToolChoice toolChoice,
                      boolean parallelToolCalls, int maxTokens) { }

    record ParameterContract(InferenceModelProfile profile, Set<String> fields,
                             Set<String> capabilities, JsonNode schema) { }
}
