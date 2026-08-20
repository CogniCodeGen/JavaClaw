package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.inference.api.InferenceChatRequest;
import com.javaclaw.inference.api.InferenceChatResponse;
import com.javaclaw.inference.api.InferenceEmbeddingRequest;
import com.javaclaw.inference.api.InferenceEmbeddingResponse;
import com.javaclaw.inference.api.InferenceStreamEvent;
import com.javaclaw.inference.api.InferenceUsage;
import com.javaclaw.inference.api.LocalInferenceGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedInferenceChatModelTest {

    @Test
    void successfulStreamCompletionDoesNotCancelTheFinishedPluginRequest() {
        AtomicInteger cancellations = new AtomicInteger();
        LocalInferenceGateway gateway = new LocalInferenceGateway() {
            @Override public InferenceChatResponse chat(InferenceChatRequest request) {
                return response(request.requestId());
            }

            @Override
            public StreamSession streamChat(
                    InferenceChatRequest request, Consumer<InferenceStreamEvent> events) {
                InferenceChatResponse response = response(request.requestId());
                events.accept(new InferenceStreamEvent(request.requestId(),
                        InferenceStreamEvent.Type.CONTENT_DELTA, "ok", List.of(), null,
                        null, "", "", Instant.now()));
                events.accept(new InferenceStreamEvent(request.requestId(),
                        InferenceStreamEvent.Type.COMPLETE, "", List.of(), response.usage(),
                        response, "", "", Instant.now()));
                return new StreamSession() {
                    @Override public String requestId() { return request.requestId(); }
                    @Override public CompletableFuture<InferenceChatResponse> completion() {
                        return CompletableFuture.completedFuture(response);
                    }
                    @Override public boolean cancel() {
                        cancellations.incrementAndGet();
                        return true;
                    }
                };
            }

            @Override public InferenceEmbeddingResponse embeddings(InferenceEmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean cancel(String requestId) { return false; }
        };
        var model = new ManagedInferenceChatModel(gateway, UUID.randomUUID(), true,
                Duration.ofSeconds(5), new ObjectMapper());

        var chunks = model.stream(new Prompt(new UserMessage("hello"))).collectList().block();

        assertEquals(2, chunks == null ? 0 : chunks.size());
        assertEquals(0, cancellations.get(), "正常完成不能被 onDispose 误判成客户端取消");
    }

    @Test
    void blockingFailurePreservesExactTerminalUsageForUpperLayerMetering() {
        LocalInferenceGateway gateway = new LocalInferenceGateway() {
            @Override public InferenceChatResponse chat(InferenceChatRequest request)
                    throws InferenceException {
                throw new InferenceException("inference_error", "failed", true,
                        new InferenceUsage(7, 2), null);
            }
            @Override public StreamSession streamChat(
                    InferenceChatRequest request, Consumer<InferenceStreamEvent> events) {
                throw new UnsupportedOperationException();
            }
            @Override public InferenceEmbeddingResponse embeddings(InferenceEmbeddingRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean cancel(String requestId) { return false; }
        };
        var model = new ManagedInferenceChatModel(gateway, UUID.randomUUID(), false,
                Duration.ofSeconds(5), new ObjectMapper());

        var failure = assertThrows(ManagedInferenceChatModel.ManagedInferenceModelException.class,
                () -> model.call(new Prompt(new UserMessage("hello"))));

        assertEquals(7, failure.usage().promptTokens());
        assertEquals(2, failure.usage().completionTokens());
    }

    private static InferenceChatResponse response(String requestId) {
        return new InferenceChatResponse(requestId, "fixture", "ok", "", List.of(),
                InferenceChatResponse.FinishReason.STOP, new InferenceUsage(2, 1),
                Duration.ZERO, Duration.ofMillis(1));
    }
}
