package com.javaclaw.model;

import java.util.Objects;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelStreamEvent;
import com.javaclaw.runtime.NativeCompactionRequest;
import com.javaclaw.runtime.NativeCompactionResult;
import com.javaclaw.runtime.NativeCompactionSupport;
import com.javaclaw.runtime.NativeConversationSupport;
import com.javaclaw.runtime.ProviderState;

/**
 * OpenAI Responses 原生适配器。
 *
 * <p>该路径补齐 Spring AI ChatModel 当前没有覆盖的 reasoning-summary stream、opaque output item 和原生 compaction。它只向模型注册
 * FunctionTool，工具仍由 Harness 治理。
 *
 * <p>实现不变量：Provider state 与 system instruction revision 绑定；隐藏 reasoning text 事件永不发布，只发布 Provider 明确标记的 summary。
 */
public final class OpenAiResponsesModelAdapter
        implements ModelGateway, NativeConversationSupport, NativeCompactionSupport, AutoCloseable {
    private static final ModelCapabilities CAPABILITIES =
            new ModelCapabilities(true, true, false, false, true, true, true);

    private final OpenAiResponsesEndpointConfig config;
    private final OpenAiResponsesTransport transport;
    private final ProviderStateCodec states;
    private final OpenAiResponsesRequestMapper requests;
    private final OpenAiResponsesResultMapper results;

    OpenAiResponsesModelAdapter(OpenAiResponsesEndpointConfig config, OpenAiResponsesTransport transport) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        states = new ProviderStateCodec();
        requests = new OpenAiResponsesRequestMapper(states);
        results = new OpenAiResponsesResultMapper(new CanonicalJsonCodec(), states);
    }

    /**
     * 使用官方 OpenAI SDK 创建端点。
     *
     * <p>客户端可能在内部保留 API key 的不可变副本；调用者仍负责及时清空传入数组，且不得记录该值。
     *
     * @param config 端点配置
     * @param apiKey API key 字符数组
     * @return Responses 适配器
     */
    public static OpenAiResponsesModelAdapter create(OpenAiResponsesEndpointConfig config, char[] apiKey) {
        Objects.requireNonNull(config, "config");
        if (apiKey == null || apiKey.length == 0) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(new String(apiKey))
                .timeout(config.timeout())
                .maxRetries(config.maximumRetries());
        config.baseUri().ifPresent(uri -> builder.baseUrl(uri.toString()));
        OpenAIClient client = builder.build();
        return new OpenAiResponsesModelAdapter(config, new OfficialOpenAiResponsesTransport(client));
    }

    @Override
    public ModelCapabilities capabilities(String modelId) {
        requireEndpoint(modelId);
        return CAPABILITIES;
    }

    @Override
    public ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
        requireInvocation(turnId, invocation, events, cancellation);
        return execute(turnId, invocation, requests.initial(config, invocation), events, cancellation);
    }

    @Override
    public ModelInvocationResult invokeContinuing(
            TurnId turnId,
            ModelInvocation invocation,
            ProviderState state,
            ModelEventSink events,
            CancellationToken cancellation) {
        requireInvocation(turnId, invocation, events, cancellation);
        ProviderStateCodec.DecodedState decoded = states.decode(state);
        ResponseCreateParams request = requests.continuing(config, invocation, decoded);
        return execute(turnId, invocation, request, events, cancellation);
    }

    private ModelInvocationResult execute(
            TurnId turnId,
            ModelInvocation invocation,
            ResponseCreateParams request,
            ModelEventSink events,
            CancellationToken cancellation) {
        StreamAccumulator accumulator = new StreamAccumulator(turnId, events, cancellation);
        try {
            transport.stream(request, accumulator::accept);
        } catch (StreamPublishFailure failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型流发布被中断", failure.interrupted());
        }
        cancellation.throwIfCancelled();
        ModelInvocationResult result =
                results.map(invocation, accumulator.response(), accumulator.text(), accumulator.summary());
        publishTerminal(turnId, result, accumulator, events, cancellation);
        return result;
    }

    private void publishTerminal(
            TurnId turnId,
            ModelInvocationResult result,
            StreamAccumulator accumulator,
            ModelEventSink events,
            CancellationToken cancellation) {
        if (accumulator.text().isEmpty() && !result.text().isEmpty()) {
            publish(turnId, new ModelStreamEvent.TextDelta(result.text()), events, cancellation);
        }
        if (accumulator.summary().isEmpty()) {
            result.reasoningSummary()
                    .filter(summary -> !summary.isEmpty())
                    .ifPresent(summary ->
                            publish(turnId, new ModelStreamEvent.ReasoningSummaryDelta(summary), events, cancellation));
        }
        result.toolCalls()
                .forEach(call -> publish(turnId, new ModelStreamEvent.ToolCallReady(call), events, cancellation));
        publish(turnId, new ModelStreamEvent.Usage(result.usage()), events, cancellation);
    }

    @Override
    public NativeCompactionResult compact(NativeCompactionRequest request, CancellationToken cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        requireEndpoint(request.modelId());
        cancellation.throwIfCancelled();
        ProviderStateCodec.DecodedState decoded = states.decode(request.state());
        CompactedResponse response = transport.compact(requests.compact(config, decoded));
        cancellation.throwIfCancelled();
        ProviderState compacted = states.compacted(response, decoded.instructions());
        return new NativeCompactionResult(compacted, response.usage().inputTokens());
    }

    private void requireInvocation(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(cancellation, "cancellation");
        requireEndpoint(invocation.modelId());
        cancellation.throwIfCancelled();
    }

    private void requireEndpoint(String modelId) {
        if (!config.endpointId().equals(modelId)) {
            throw new IllegalArgumentException("unknown model endpoint: " + modelId);
        }
    }

    private static void publish(
            TurnId turnId, ModelStreamEvent event, ModelEventSink events, CancellationToken cancellation) {
        try {
            events.publish(turnId, event, cancellation);
        } catch (InterruptedException failure) {
            throw new StreamPublishFailure(failure);
        }
    }

    @Override
    public void close() {
        transport.close();
    }

    private static final class StreamAccumulator {
        private final TurnId turnId;
        private final ModelEventSink events;
        private final CancellationToken cancellation;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder summary = new StringBuilder();
        private Response response;

        private StreamAccumulator(TurnId turnId, ModelEventSink events, CancellationToken cancellation) {
            this.turnId = turnId;
            this.events = events;
            this.cancellation = cancellation;
        }

        private void accept(ResponseStreamEvent event) {
            cancellation.throwIfCancelled();
            if (event.isOutputTextDelta()) {
                appendText(event.asOutputTextDelta().delta());
            } else if (event.isReasoningSummaryTextDelta()) {
                appendSummary(event.asReasoningSummaryTextDelta().delta());
            } else if (event.isCompleted()) {
                response = event.asCompleted().response();
            } else if (event.isError() || event.isFailed() || event.isIncomplete()) {
                throw new IllegalStateException("OpenAI Responses 流未正常完成");
            }
        }

        private void appendText(String delta) {
            if (!delta.isEmpty()) {
                text.append(delta);
                publish(turnId, new ModelStreamEvent.TextDelta(delta), events, cancellation);
            }
        }

        private void appendSummary(String delta) {
            if (!delta.isEmpty()) {
                summary.append(delta);
                publish(turnId, new ModelStreamEvent.ReasoningSummaryDelta(delta), events, cancellation);
            }
        }

        private Response response() {
            if (response == null) {
                throw new IllegalStateException("OpenAI Responses 流缺少 completed 事件");
            }
            return response;
        }

        private String text() {
            return text.toString();
        }

        private String summary() {
            return summary.toString();
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
