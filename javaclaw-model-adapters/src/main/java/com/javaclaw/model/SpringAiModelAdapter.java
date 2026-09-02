package com.javaclaw.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelStreamEvent;

/**
 * Spring AI 通用 ChatModel 适配器。
 *
 * <p>该适配器只做 Prompt、流、tool-call 和 usage 映射。工具回调只暴露定义，任何执行尝试都会失败，审批、Sandbox、预算与 EffectReceipt 始终由 Turn Harness 掌握。
 *
 * <p>实现不变量：构造完成后端点表不可变，可供多个 Turn 并发读取；关闭时统一释放三家官方客户端持有的连接池与线程。
 */
public final class SpringAiModelAdapter implements ModelGateway, AutoCloseable {
    private final Map<String, SpringAiEndpoint> endpoints;
    private final SpringAiPromptMapper prompts = new SpringAiPromptMapper();
    private final SpringAiResultMapper results = new SpringAiResultMapper(new CanonicalJsonCodec());

    SpringAiModelAdapter(List<SpringAiEndpoint> endpoints) {
        Map<String, SpringAiEndpoint> index = new LinkedHashMap<>();
        for (SpringAiEndpoint endpoint : endpoints) {
            if (index.put(endpoint.id(), endpoint) != null) {
                throw new IllegalArgumentException("duplicate model endpoint: " + endpoint.id());
            }
        }
        this.endpoints = Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    /**
     * 创建只在组合阶段使用的 Builder。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ModelCapabilities capabilities(String modelId) {
        return endpoint(modelId).capabilities();
    }

    @Override
    public ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancelled();
        SpringAiEndpoint endpoint = endpoint(invocation.modelId());
        ChatOptions options = endpoint.options().create(invocation);
        ChatResponse response = endpoint.capabilities().streaming()
                ? stream(turnId, endpoint, prompts.map(invocation, options), events, cancellation)
                : endpoint.model().call(prompts.map(invocation, options));
        ModelInvocationResult result = results.map(invocation, response);
        publishTerminal(
                turnId, result, events, cancellation, endpoint.capabilities().streaming());
        return result;
    }

    private ChatResponse stream(
            TurnId turnId,
            SpringAiEndpoint endpoint,
            org.springframework.ai.chat.prompt.Prompt prompt,
            ModelEventSink events,
            CancellationToken cancellation)
            throws InterruptedException {
        AtomicReference<ChatResponse> complete = new AtomicReference<>();
        try {
            new MessageAggregator()
                    .aggregate(endpoint.model().stream(prompt), complete::set)
                    .doOnNext(chunk -> publishChunk(turnId, chunk, events, cancellation))
                    .blockLast();
        } catch (StreamPublishFailure failure) {
            Thread.currentThread().interrupt();
            throw failure.interrupted();
        }
        cancellation.throwIfCancelled();
        ChatResponse response = complete.get();
        if (response == null) {
            throw new IllegalStateException("Spring AI 流在完成前未生成聚合响应");
        }
        return response;
    }

    private void publishChunk(
            TurnId turnId, ChatResponse chunk, ModelEventSink events, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = chunk.getResult().getOutput();
        String text = output.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean thought =
                Boolean.parseBoolean(String.valueOf(output.getMetadata().get("isThought")));
        ModelStreamEvent event =
                thought ? new ModelStreamEvent.ReasoningSummaryDelta(text) : new ModelStreamEvent.TextDelta(text);
        publish(turnId, event, events, cancellation);
    }

    private void publishTerminal(
            TurnId turnId,
            ModelInvocationResult result,
            ModelEventSink events,
            CancellationToken cancellation,
            boolean textAlreadyStreamed)
            throws InterruptedException {
        if (!textAlreadyStreamed && !result.text().isEmpty()) {
            events.publish(turnId, new ModelStreamEvent.TextDelta(result.text()), cancellation);
        }
        for (var call : result.toolCalls()) {
            events.publish(turnId, new ModelStreamEvent.ToolCallReady(call), cancellation);
        }
        events.publish(turnId, new ModelStreamEvent.Usage(result.usage()), cancellation);
    }

    private void publish(TurnId turnId, ModelStreamEvent event, ModelEventSink events, CancellationToken cancellation) {
        try {
            events.publish(turnId, event, cancellation);
        } catch (InterruptedException failure) {
            throw new StreamPublishFailure(failure);
        }
    }

    private SpringAiEndpoint endpoint(String modelId) {
        SpringAiEndpoint endpoint = endpoints.get(modelId);
        if (endpoint == null) {
            throw new IllegalArgumentException("unknown model endpoint: " + modelId);
        }
        return endpoint;
    }

    @Override
    public void close() throws Exception {
        Exception first = null;
        List<SpringAiEndpoint> values = new ArrayList<>(endpoints.values());
        for (int index = values.size() - 1; index >= 0; index--) {
            try {
                values.get(index).resources().close();
            } catch (Exception failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** Spring AI 端点组合器；只在 App Server bootstrap 阶段使用。 */
    public static final class Builder {
        private final List<SpringAiEndpoint> endpoints = new ArrayList<>();
        private final SpringAiModelFactory factory = new SpringAiModelFactory();

        private Builder() {}

        /**
         * 注册一个 Provider 端点。
         *
         * <p>客户端可能在内部保留 API key 的不可变副本；调用者仍负责及时清空传入数组，且不得记录该值。
         *
         * @param config 端点配置
         * @param apiKey API key 字符数组
         * @return 当前 Builder
         */
        public Builder register(SpringAiEndpointConfig config, char[] apiKey) {
            endpoints.add(factory.create(Objects.requireNonNull(config, "config"), apiKey));
            return this;
        }

        Builder register(SpringAiEndpoint endpoint) {
            endpoints.add(Objects.requireNonNull(endpoint, "endpoint"));
            return this;
        }

        /**
         * 冻结端点表并创建适配器。
         *
         * @return 适配器
         */
        public SpringAiModelAdapter build() {
            if (endpoints.isEmpty()) {
                throw new IllegalStateException("at least one Spring AI endpoint is required");
            }
            return new SpringAiModelAdapter(endpoints);
        }
    }

    private static final class StreamPublishFailure extends RuntimeException {
        private final InterruptedException interrupted;

        private StreamPublishFailure(InterruptedException interrupted) {
            super(interrupted);
            this.interrupted = interrupted;
        }

        private InterruptedException interrupted() {
            return interrupted;
        }
    }
}
