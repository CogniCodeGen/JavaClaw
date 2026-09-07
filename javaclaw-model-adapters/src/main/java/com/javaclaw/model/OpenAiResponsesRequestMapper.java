package com.javaclaw.model;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.JsonValue;
import com.openai.models.Reasoning;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.runtime.ModelInstructions;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelToolCall;

/** 将 Harness 调用映射为官方 Responses SDK 请求。 */
final class OpenAiResponsesRequestMapper {
    private final JsonMapper mapper = ModelJsonMapper.create();
    private final ProviderStateCodec states;

    OpenAiResponsesRequestMapper(ProviderStateCodec states) {
        this.states = states;
    }

    ResponseCreateParams initial(OpenAiResponsesEndpointConfig config, ModelInvocation invocation) {
        return create(config, invocation, messages(invocation.messages()));
    }

    ResponseCreateParams continuing(
            OpenAiResponsesEndpointConfig config, ModelInvocation invocation, ProviderStateCodec.DecodedState state) {
        requireSameInstructions(invocation, state);
        List<ResponseInputItem> input = new ArrayList<>(states.inputItems(state));
        input.addAll(messages(invocation.messages()));
        return create(config, invocation, input);
    }

    ResponseCompactParams compact(OpenAiResponsesEndpointConfig config, ProviderStateCodec.DecodedState state) {
        return compact(config, state, List.of());
    }

    ResponseCompactParams compact(
            OpenAiResponsesEndpointConfig config, ProviderStateCodec.DecodedState state, List<ModelMessage> pending) {
        List<ResponseInputItem> input = new ArrayList<>(states.inputItems(state));
        input.addAll(messages(pending));
        return ResponseCompactParams.builder()
                .model(config.model())
                .instructions(state.instructions().systemInstruction())
                .input(ResponseCompactParams.Input.ofResponseInputItems(withInstructions(state.instructions(), input)))
                .build();
    }

    private ResponseCreateParams create(
            OpenAiResponsesEndpointConfig config, ModelInvocation invocation, List<ResponseInputItem> input) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(config.model())
                .instructions(invocation.instructions().systemInstruction())
                .input(ResponseCreateParams.Input.ofResponse(withInstructions(invocation.instructions(), input)))
                .maxOutputTokens(invocation.maximumOutputTokens())
                .parallelToolCalls(true)
                .store(false)
                .include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT))
                .reasoning(reasoning(config, invocation));
        invocation.tools().stream().map(this::tool).forEach(builder::addTool);
        return builder.build();
    }

    private Reasoning reasoning(OpenAiResponsesEndpointConfig config, ModelInvocation invocation) {
        Reasoning.Builder builder = Reasoning.builder().summary(summary(config.summaryStyle()));
        invocation.reasoning().ifPresent(preference -> builder.effort(AdapterReasoningMapping.openAi(preference)));
        return builder.build();
    }

    private List<ResponseInputItem> withInstructions(ModelInstructions instructions, List<ResponseInputItem> messages) {
        List<ResponseInputItem> input = new ArrayList<>();
        if (!instructions.developerInstructions().isBlank()) {
            input.add(easy(instructions.developerInstructions(), EasyInputMessage.Role.DEVELOPER));
        }
        input.addAll(messages);
        if (!instructions.responseContract().isBlank()) {
            input.add(easy(instructions.responseContract(), EasyInputMessage.Role.DEVELOPER));
        }
        return input;
    }

    List<ResponseInputItem> messages(List<ModelMessage> messages) {
        List<ResponseInputItem> result = new ArrayList<>();
        messages.forEach(message -> append(message, result));
        return result;
    }

    private void append(ModelMessage message, List<ResponseInputItem> target) {
        switch (message.role()) {
            case SYSTEM -> target.add(easy(message.text(), EasyInputMessage.Role.SYSTEM));
            case USER -> target.add(easy(message.text(), EasyInputMessage.Role.USER));
            case TOOL -> target.add(toolOutput(message));
            case ASSISTANT -> appendAssistant(message, target);
        }
    }

    private void appendAssistant(ModelMessage message, List<ResponseInputItem> target) {
        if (!message.text().isEmpty()) {
            target.add(easy(message.text(), EasyInputMessage.Role.ASSISTANT));
        }
        message.toolCalls().stream().map(this::functionCall).forEach(target::add);
    }

    private ResponseInputItem easy(String text, EasyInputMessage.Role role) {
        EasyInputMessage message =
                EasyInputMessage.builder().content(text).role(role).build();
        return ResponseInputItem.ofEasyInputMessage(message);
    }

    private ResponseInputItem functionCall(ModelToolCall call) {
        ResponseFunctionToolCall item = ResponseFunctionToolCall.builder()
                .arguments(call.arguments().json())
                .callId(call.callId())
                .name(call.tool().name())
                .build();
        return ResponseInputItem.ofFunctionCall(item);
    }

    private ResponseInputItem toolOutput(ModelMessage message) {
        var output = ResponseInputItem.FunctionCallOutput.builder()
                .callId(message.toolCallId().orElseThrow())
                .output(message.text())
                .build();
        return ResponseInputItem.ofFunctionCallOutput(output);
    }

    private FunctionTool tool(ToolDescriptor descriptor) {
        return FunctionTool.builder()
                .name(descriptor.identity().name())
                .description(descriptor.description())
                .parameters(parameters(descriptor.inputSchema().json()))
                .strict(false)
                .build();
    }

    private FunctionTool.Parameters parameters(String schema) {
        try {
            JsonNode node = mapper.readTree(schema);
            if (!node.isObject()) {
                throw new IllegalArgumentException("tool input schema must be a JSON object");
            }
            Map<String, JsonValue> values = new LinkedHashMap<>();
            node.fields()
                    .forEachRemaining(entry -> values.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
            return FunctionTool.Parameters.builder()
                    .additionalProperties(values)
                    .build();
        } catch (IOException failure) {
            throw new IllegalArgumentException("tool input schema is invalid", failure);
        }
    }

    private void requireSameInstructions(ModelInvocation invocation, ProviderStateCodec.DecodedState state) {
        if (!state.instructions().equals(invocation.instructions())) {
            throw new IllegalArgumentException("Provider state cannot cross an instruction-layer revision");
        }
    }

    private Reasoning.Summary summary(ReasoningSummaryStyle style) {
        return switch (style) {
            case AUTO -> Reasoning.Summary.AUTO;
            case CONCISE -> Reasoning.Summary.CONCISE;
            case DETAILED -> Reasoning.Summary.DETAILED;
        };
    }
}
