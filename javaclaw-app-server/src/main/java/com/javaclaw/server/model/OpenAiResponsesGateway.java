package com.javaclaw.server.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.errors.OpenAIServiceException;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseUsage;

import com.javaclaw.agent.model.CompactionStrategy;
import com.javaclaw.agent.model.CompactionThresholds;
import com.javaclaw.agent.model.ContextWindowExceededException;
import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.model.NativeCompactionResult;
import com.javaclaw.core.api.ModelImage;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelRequest;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.ModelToolCall;
import com.javaclaw.core.api.ModelUsage;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.core.api.TurnConfig;

/** OpenAI Responses stateless gateway; opaque output items remain SDK-independent outside this adapter. */
final class OpenAiResponsesGateway implements ModelGateway {
    static final int STATE_SCHEMA_VERSION = 1;

    private final OpenAIClient client;
    private final boolean nativeCompaction;
    private final JsonMapper mapper;

    /** 绑定现有官方 SDK Client；自定义端点只有显式声明能力时才允许原生压缩。 */
    OpenAiResponsesGateway(OpenAIClient client, boolean nativeCompaction) {
        this.client = Objects.requireNonNull(client, "client");
        this.nativeCompaction = nativeCompaction;
        mapper = ObjectMappers.jsonMapper();
    }

    @Override
    public CompactionStrategy compactionStrategy(TurnConfig config) {
        return nativeCompaction ? CompactionStrategy.NATIVE : CompactionStrategy.SUMMARY;
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws Exception {
        PreparedInput input = prepareInput(request);
        try {
            return response(request, input, client.responses().create(parameters(request, input)));
        } catch (OpenAIServiceException failure) {
            throw translate(failure);
        }
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelStreamSink sink) throws Exception {
        Objects.requireNonNull(sink, "sink");
        PreparedInput input = prepareInput(request);
        ResponseAccumulator accumulator = ResponseAccumulator.create();
        try (var events = client.responses().createStreaming(parameters(request, input));
                var stream = events.stream()) {
            stream.forEach(event -> {
                accumulator.accumulate(event);
                event.outputTextDelta().ifPresent(delta -> sink.text(delta.delta()));
                event.refusalDelta().ifPresent(delta -> sink.text(delta.delta()));
                event.reasoningSummaryTextDelta().ifPresent(delta -> sink.reasoningSummary(delta.delta()));
            });
            ModelResponse response = response(request, input, accumulator.response());
            sink.usage(response.usage());
            return response;
        } catch (OpenAIServiceException failure) {
            throw translate(failure);
        }
    }

    @Override
    public NativeCompactionResult compact(ModelRequest request) throws Exception {
        if (!nativeCompaction) {
            return ModelGateway.super.compact(request);
        }
        PreparedInput input = prepareInput(request);
        ResponseCompactParams parameters = ResponseCompactParams.builder()
                .model(request.config().model())
                .instructions(input.instructions())
                .inputOfResponseInputItems(input.requestItems())
                .build();
        try {
            CompactedResponse response = client.responses().compact(parameters);
            String payload = mapper.writeValueAsString(response.output());
            return new NativeCompactionResult(
                    new ProviderConversationState("openai", STATE_SCHEMA_VERSION, payload, 0, true),
                    usage(response.usage()));
        } catch (OpenAIServiceException failure) {
            throw translate(failure);
        }
    }

    private ModelResponse response(ModelRequest request, PreparedInput input, Response response) throws Exception {
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ArrayList<ModelToolCall> calls = new ArrayList<>();
        boolean compacted = false;
        for (ResponseOutputItem item : response.output()) {
            if (item.isMessage()) {
                item.asMessage().content().forEach(content -> {
                    if (content.isOutputText()) {
                        text.append(content.asOutputText().text());
                    } else if (content.isRefusal()) {
                        text.append(content.asRefusal().refusal());
                    }
                });
            } else if (item.isReasoning()) {
                item.asReasoning().summary().forEach(summary -> reasoning.append(summary.text()));
            } else if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                calls.add(new ModelToolCall(call.callId(), call.name(), call.arguments()));
            } else if (item.isCompaction()) {
                compacted = true;
            }
        }
        List<ResponseInputItem> stateItems;
        if (compacted) {
            stateItems = asInputItems(response.output());
        } else {
            stateItems = new ArrayList<>(input.priorItems());
            stateItems.addAll(input.dynamicItems());
            stateItems.addAll(asInputItems(response.output()));
        }
        int consumed = compacted
                ? request.messages().size() - request.conversationStartIndex() + 1
                : input.priorConsumedMessages() + input.dynamicMessageCount() + 1;
        ProviderConversationState state = new ProviderConversationState(
                "openai", STATE_SCHEMA_VERSION, mapper.writeValueAsString(stateItems), consumed, compacted);
        return new ModelResponse(
                text.toString(),
                reasoning.toString(),
                calls,
                response.usage().map(OpenAiResponsesGateway::usage).orElse(ModelUsage.ZERO),
                state);
    }

    private PreparedInput prepareInput(ModelRequest request) throws Exception {
        int boundary = request.conversationStartIndex();
        String instructions = request.messages().stream()
                .filter(message -> message.role() == ModelMessage.Role.SYSTEM)
                .map(ModelMessage::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        ArrayList<ResponseInputItem> staticItems = new ArrayList<>();
        for (int index = 0; index < boundary; index++) {
            ModelMessage message = request.messages().get(index);
            if (message.role() != ModelMessage.Role.SYSTEM) {
                staticItems.addAll(inputItems(message));
            }
        }
        ProviderConversationState state = request.conversationState();
        ArrayList<ResponseInputItem> prior = state == null ? new ArrayList<>() : new ArrayList<>(readState(state));
        int consumed = state == null ? 0 : state.consumedMessages();
        int dynamicStart = Math.min(request.messages().size(), boundary + consumed);
        ArrayList<ResponseInputItem> dynamic = new ArrayList<>();
        for (int index = dynamicStart; index < request.messages().size(); index++) {
            dynamic.addAll(inputItems(request.messages().get(index)));
        }
        ArrayList<ResponseInputItem> requestItems = new ArrayList<>(staticItems);
        requestItems.addAll(prior);
        requestItems.addAll(dynamic);
        return new PreparedInput(
                instructions, requestItems, prior, dynamic, request.messages().size() - dynamicStart, consumed);
    }

    private List<ResponseInputItem> readState(ProviderConversationState state) throws Exception {
        if (!"openai".equalsIgnoreCase(state.provider()) || state.schemaVersion() != STATE_SCHEMA_VERSION) {
            throw new IllegalArgumentException("OpenAI conversation state has an incompatible provider or schema");
        }
        return mapper.readValue(state.payloadJson(), new TypeReference<>() {});
    }

    private List<ResponseInputItem> inputItems(ModelMessage message) {
        if (message.role() == ModelMessage.Role.TOOL) {
            return List.of(ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder()
                    .callId(message.toolCallId())
                    .output(message.content())
                    .build()));
        }
        EasyInputMessage.Builder builder = EasyInputMessage.builder()
                .role(
                        switch (message.role()) {
                            case SYSTEM -> EasyInputMessage.Role.SYSTEM;
                            case USER -> EasyInputMessage.Role.USER;
                            case ASSISTANT -> EasyInputMessage.Role.ASSISTANT;
                            case TOOL -> throw new IllegalStateException("tool output is not a message");
                        });
        if (message.images().isEmpty()) {
            builder.content(message.content());
        } else {
            ArrayList<ResponseInputContent> content = new ArrayList<>();
            content.add(ResponseInputContent.ofInputText(
                    ResponseInputText.builder().text(message.content()).build()));
            message.images().stream().map(OpenAiResponsesGateway::imageContent).forEach(content::add);
            builder.contentOfResponseInputMessageContentList(content);
        }
        ArrayList<ResponseInputItem> items = new ArrayList<>();
        items.add(ResponseInputItem.ofEasyInputMessage(builder.build()));
        for (ModelToolCall call : message.toolCalls()) {
            items.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                    .callId(call.id())
                    .name(call.name())
                    .arguments(call.argumentsJson())
                    .build()));
        }
        return items;
    }

    private static ResponseInputContent imageContent(ModelImage image) {
        String dataUrl =
                "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
        return ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                .detail(ResponseInputImage.Detail.AUTO)
                .imageUrl(dataUrl)
                .build());
    }

    private ResponseCreateParams parameters(ModelRequest request, PreparedInput input) throws Exception {
        ResponseCreateParams.Builder parameters = ResponseCreateParams.builder()
                .model(request.config().model())
                .instructions(input.instructions())
                .inputOfResponse(input.requestItems())
                .store(false)
                .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT);
        applyOptions(parameters, request);
        return parameters.build();
    }

    private void applyOptions(ResponseCreateParams.Builder parameters, ModelRequest request) throws Exception {
        for (ToolDescriptor descriptor : request.tools()) {
            parameters.addTool(functionTool(descriptor));
        }
        integerAttribute(request, "maxOutputTokens").ifPresent(value -> parameters.maxOutputTokens(value.longValue()));
        doubleAttribute(request, "temperature").ifPresent(parameters::temperature);
        if (!request.config().reasoningEffort().isBlank()) {
            parameters.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.of(
                            request.config().reasoningEffort().toLowerCase(Locale.ROOT)))
                    .summary(Reasoning.Summary.AUTO)
                    .build());
        }
        if (nativeCompaction) {
            CompactionThresholds.autoThreshold(request.config())
                    .ifPresent(threshold ->
                            parameters.addContextManagement(ResponseCreateParams.ContextManagement.builder()
                                    .type("compaction")
                                    .compactThreshold(threshold)
                                    .build()));
        }
    }

    private FunctionTool functionTool(ToolDescriptor descriptor) throws Exception {
        JsonNode schema = mapper.readTree(descriptor.inputSchemaJson().getBytes(StandardCharsets.UTF_8));
        if (!schema.isObject()) {
            throw new IllegalArgumentException("tool input schema must be a JSON object: " + descriptor.name());
        }
        LinkedHashMap<String, JsonValue> properties = new LinkedHashMap<>();
        schema.fields()
                .forEachRemaining(entry -> properties.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
        return FunctionTool.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .parameters(FunctionTool.Parameters.builder()
                        .additionalProperties(properties)
                        .build())
                .strict(true)
                .build();
    }

    private List<ResponseInputItem> asInputItems(List<ResponseOutputItem> output) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(output), new TypeReference<>() {});
    }

    private static ModelUsage usage(ResponseUsage usage) {
        long reasoning = usage.outputTokensDetails().reasoningTokens();
        return new ModelUsage(usage.inputTokens(), usage.outputTokens(), reasoning);
    }

    private static Exception translate(OpenAIServiceException failure) {
        String marker = (failure.code().orElse("") + " " + failure.type().orElse("") + " " + failure.getMessage())
                .toLowerCase(Locale.ROOT);
        if (marker.contains("context_length")
                || marker.contains("context window")
                || marker.contains("context_window")
                || marker.contains("too many tokens")) {
            return new ContextWindowExceededException("OpenAI context window exceeded", failure);
        }
        return failure;
    }

    private static java.util.Optional<Integer> integerAttribute(ModelRequest request, String key) {
        try {
            String value = request.config().attributes().get(key);
            return value == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(Math.max(1, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<Double> doubleAttribute(ModelRequest request, String key) {
        try {
            String value = request.config().attributes().get(key);
            if (value == null) {
                return java.util.Optional.empty();
            }
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? java.util.Optional.of(parsed) : java.util.Optional.empty();
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private record PreparedInput(
            String instructions,
            List<ResponseInputItem> requestItems,
            List<ResponseInputItem> priorItems,
            List<ResponseInputItem> dynamicItems,
            int dynamicMessageCount,
            int priorConsumedMessages) {}
}
