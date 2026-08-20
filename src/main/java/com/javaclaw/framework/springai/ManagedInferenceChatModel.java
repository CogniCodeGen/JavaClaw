package com.javaclaw.framework.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.inference.api.InferenceChatRequest;
import com.javaclaw.inference.api.InferenceChatResponse;
import com.javaclaw.inference.api.InferenceMessage;
import com.javaclaw.inference.api.InferenceStreamEvent;
import com.javaclaw.inference.api.InferenceTool;
import com.javaclaw.inference.api.InferenceToolCall;
import com.javaclaw.inference.api.InferenceUsage;
import com.javaclaw.inference.api.LocalInferenceGateway;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Spring AI 适配器；所有 Deliverance 类型都停留在隔离的服务插件进程内。 */
public final class ManagedInferenceChatModel implements ChatModel {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final LocalInferenceGateway gateway;
    private final UUID profileId;
    private final boolean thinking;
    private final Duration timeout;
    private final ObjectMapper json;

    public ManagedInferenceChatModel(
            LocalInferenceGateway gateway, UUID profileId, boolean thinking,
            Duration timeout, ObjectMapper json) {
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.profileId = java.util.Objects.requireNonNull(profileId, "profileId");
        this.thinking = thinking;
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
        this.json = java.util.Objects.requireNonNull(json, "json");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return response(gateway.chat(request(prompt)));
        } catch (LocalInferenceGateway.InferenceException failure) {
            throw new ManagedInferenceModelException(failure.code(), failure.getMessage(), failure.retryable(),
                    failure.usage(), modelName(), failure);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.create(sink -> {
            AtomicBoolean terminal = new AtomicBoolean();
            try {
                LocalInferenceGateway.StreamSession session = gateway.streamChat(request(prompt), event -> {
                    switch (event.type()) {
                        case CONTENT_DELTA -> sink.next(delta(event.text(), false));
                        case REASONING_DELTA -> sink.next(delta(event.text(), true));
                        case COMPLETE -> {
                            // 正文与 reasoning 已经作为 delta 发出；终态只补 tool calls、finish reason 和 usage，
                            // 否则 Spring AI 会把完整正文再次拼接到流式结果末尾。
                            if (terminal.compareAndSet(false, true)) {
                                if (event.response() != null) sink.next(terminalResponse(event.response()));
                                sink.complete();
                            }
                        }
                        case CANCELLED, ERROR -> {
                            if (terminal.compareAndSet(false, true)) {
                                sink.error(new ManagedInferenceModelException(
                                        event.errorCode(), event.errorMessage(), false,
                                        event.usage(), modelName(), null));
                            }
                        }
                        default -> { }
                    }
                });
                sink.onCancel(session::cancel);
                session.completion().exceptionally(failure -> {
                    if (!sink.isCancelled() && terminal.compareAndSet(false, true)) sink.error(failure);
                    return null;
                });
            } catch (LocalInferenceGateway.InferenceException failure) {
                if (terminal.compareAndSet(false, true)) {
                    sink.error(new ManagedInferenceModelException(
                            failure.code(), failure.getMessage(), failure.retryable(),
                            failure.usage(), modelName(), failure));
                }
            }
        });
    }

    private InferenceChatRequest request(Prompt prompt) {
        List<InferenceMessage> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            switch (message.getMessageType()) {
                case SYSTEM -> messages.add(simple(InferenceMessage.Role.SYSTEM, message.getText()));
                case USER -> messages.add(simple(InferenceMessage.Role.USER, message.getText()));
                case ASSISTANT -> messages.add(assistant((AssistantMessage) message));
                case TOOL -> {
                    ToolResponseMessage tool = (ToolResponseMessage) message;
                    for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                        messages.add(new InferenceMessage(InferenceMessage.Role.TOOL,
                                response.responseData(), "", response.id(), List.of()));
                    }
                }
            }
        }
        List<InferenceTool> tools = new ArrayList<>();
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            for (ToolCallback callback : toolOptions.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                tools.add(new InferenceTool(definition.name(), definition.description(),
                        parseSchema(definition.inputSchema())));
            }
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("enableThinking", thinking);
        if (options != null) {
            put(parameters, "temperature", options.getTemperature());
            put(parameters, "maxTokens", options.getMaxTokens());
            put(parameters, "topK", options.getTopK());
            put(parameters, "topP", options.getTopP());
            if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
                parameters.put("stop", options.getStopSequences());
            }
        }
        return new InferenceChatRequest(UUID.randomUUID().toString(), profileId,
                messages, tools, parameters, timeout);
    }

    private InferenceMessage assistant(AssistantMessage message) {
        List<InferenceToolCall> calls = message.getToolCalls().stream().map(call ->
                new InferenceToolCall(call.id(), call.name(), call.arguments())).toList();
        Object reasoning = message.getMetadata().get("reasoning_content");
        return new InferenceMessage(InferenceMessage.Role.ASSISTANT, message.getText(),
                reasoning == null ? "" : String.valueOf(reasoning), "", calls);
    }

    private static InferenceMessage simple(InferenceMessage.Role role, String text) {
        return new InferenceMessage(role, text, "", "", List.of());
    }

    private ChatResponse response(InferenceChatResponse value) {
        return response(value, value.content(), value.reasoningContent());
    }

    private ChatResponse terminalResponse(InferenceChatResponse value) {
        return response(value, "", "");
    }

    private ChatResponse response(InferenceChatResponse value, String content, String reasoningContent) {
        List<AssistantMessage.ToolCall> calls = value.toolCalls().stream().map(call ->
                new AssistantMessage.ToolCall(call.id(), "function", call.name(), call.argumentsJson())).toList();
        Map<String, Object> properties = reasoningContent.isBlank() ? Map.of()
                : Map.of("reasoning_content", reasoningContent);
        AssistantMessage assistant = AssistantMessage.builder().content(content)
                .properties(properties).toolCalls(calls).build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(finish(value.finishReason())).build();
        var usage = value.usage();
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().id(value.requestId())
                .model("deliverance:" + value.model()).usage(new DefaultUsage(safeInt(usage.promptTokens()),
                        safeInt(usage.completionTokens()), safeInt(usage.totalTokens()))).build();
        return new ChatResponse(List.of(new Generation(assistant, generationMetadata)), metadata);
    }

    private ChatResponse delta(String text, boolean reasoning) {
        AssistantMessage message = AssistantMessage.builder().content(reasoning ? "" : text)
                .properties(reasoning ? Map.of("reasoning_content", text) : Map.of()).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private Map<String, Object> parseSchema(String schema) {
        try {
            return json.readValue(schema, MAP);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Spring AI 工具 Schema 不是有效 JSON", failure);
        }
    }

    private static String finish(InferenceChatResponse.FinishReason reason) {
        return switch (reason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_CALLS -> "tool_calls";
            case CANCELLED -> "cancelled";
            case CONTENT_FILTER -> "content_filter";
            case ERROR -> "error";
        };
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static int safeInt(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }

    private String modelName() { return "deliverance:managed/" + profileId; }

    /** 保留服务插件错误代码和可重试属性，供上层重试策略判定。 */
    public static final class ManagedInferenceModelException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final InferenceUsage usage;
        private final String model;
        ManagedInferenceModelException(String code, String message, boolean retryable,
                                       InferenceUsage usage, String model, Throwable cause) {
            super(message, cause);
            this.code = code == null ? "inference_error" : code;
            this.retryable = retryable;
            this.usage = usage == null ? new InferenceUsage(0, 0) : usage;
            this.model = model == null ? "deliverance:managed" : model;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public InferenceUsage usage() { return usage; }
        public String model() { return model; }
    }
}
