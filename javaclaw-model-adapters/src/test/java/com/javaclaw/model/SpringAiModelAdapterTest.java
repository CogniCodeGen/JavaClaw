package com.javaclaw.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelStreamEvent;

import static com.javaclaw.model.ModelAdapterTestFixtures.ENDPOINT_ID;
import static com.javaclaw.model.ModelAdapterTestFixtures.TOOL;
import static com.javaclaw.model.ModelAdapterTestFixtures.invocation;
import static com.javaclaw.model.ModelAdapterTestFixtures.response;
import static com.javaclaw.model.ModelAdapterTestFixtures.simpleInvocation;
import static com.javaclaw.model.ModelAdapterTestFixtures.toolCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiModelAdapterTest {
    private static final ModelCapabilities NON_STREAMING =
            new ModelCapabilities(false, true, true, false, true, false, false);
    private static final ModelCapabilities STREAMING =
            new ModelCapabilities(true, true, false, false, true, false, false);

    @AfterEach
    void clearInterruptStatus() {
        Thread.interrupted();
    }

    @Test
    void mapsCompletePromptAndPublishesNonStreamingTerminalEvents() throws Exception {
        AssistantMessage output = AssistantMessage.builder()
                .content("含思考的原始输出")
                .properties(Map.of("outputWithoutThoughts", "答案", "thoughts", "摘要"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-out", "function", TOOL.identity().name(), "{\"path\":\"README.md\"}")))
                .build();
        var generation = ChatGenerationMetadata.builder().finishReason("stop").build();
        FakeChatModel model =
                new FakeChatModel(response(output, generation, new DefaultUsage(12, 4, 16, Map.of(), 5L, 0L)));
        List<ModelStreamEvent> events = new ArrayList<>();
        var adapter = adapter(model, NON_STREAMING, () -> {});

        var result = adapter.invoke(
                TurnId.random(),
                completeInvocation(),
                (turnId, event, cancellation) -> events.add(event),
                new CancellationSource());

        assertEquals("答案", result.text());
        assertEquals(Optional.of("摘要"), result.reasoningSummary());
        assertEquals(5, result.usage().cachedInputTokens());
        assertEquals(1, result.toolCalls().size());
        assertEquals(5, model.lastPrompt().getInstructions().size());
        assertInstanceOf(
                SystemMessage.class, model.lastPrompt().getInstructions().get(0));
        assertInstanceOf(
                SystemMessage.class, model.lastPrompt().getInstructions().get(1));
        assertInstanceOf(UserMessage.class, model.lastPrompt().getInstructions().get(2));
        assertInstanceOf(
                AssistantMessage.class, model.lastPrompt().getInstructions().get(3));
        assertInstanceOf(
                ToolResponseMessage.class, model.lastPrompt().getInstructions().get(4));
        assertEquals(3, events.size());
        assertInstanceOf(ModelStreamEvent.TextDelta.class, events.get(0));
        assertInstanceOf(ModelStreamEvent.ToolCallReady.class, events.get(1));
        assertInstanceOf(ModelStreamEvent.Usage.class, events.get(2));
    }

    @Test
    void streamsReasoningAndTextWithoutRepublishingFinalText() throws Exception {
        ChatResponse thought = response(
                AssistantMessage.builder()
                        .content("检查")
                        .properties(Map.of("isThought", true))
                        .build(),
                ChatGenerationMetadata.builder().finishReason("").build(),
                null);
        ChatResponse text = response(
                new AssistantMessage("完成"),
                ChatGenerationMetadata.builder().finishReason("stop").build(),
                new DefaultUsage(3, 1));
        FakeChatModel model = new FakeChatModel(text, List.of(thought, text));
        List<ModelStreamEvent> events = new ArrayList<>();

        var result = adapter(model, STREAMING, () -> {})
                .invoke(
                        TurnId.random(),
                        simpleInvocation(),
                        (turnId, event, cancellation) -> events.add(event),
                        new CancellationSource());

        assertFalse(result.text().isEmpty());
        assertTrue(events.stream().anyMatch(ModelStreamEvent.ReasoningSummaryDelta.class::isInstance));
        assertTrue(events.stream().anyMatch(ModelStreamEvent.TextDelta.class::isInstance));
        assertEquals(
                1,
                events.stream().filter(ModelStreamEvent.Usage.class::isInstance).count());
    }

    @Test
    void acceptsEmptyStreamingResponseAndPublishesOnlyUsage() {
        ChatResponse emptyText = response(
                new AssistantMessage(""), ChatGenerationMetadata.builder().build(), null);
        FakeChatModel emptyModel = new FakeChatModel(emptyText, List.of(emptyText));
        List<ModelStreamEvent> events = new ArrayList<>();

        try {
            var result = adapter(emptyModel, STREAMING, () -> {})
                    .invoke(
                            TurnId.random(),
                            simpleInvocation(),
                            (turnId, event, cancellation) -> events.add(event),
                            new CancellationSource());
            assertTrue(result.text().isEmpty());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertEquals(1, events.size());
        assertInstanceOf(ModelStreamEvent.Usage.class, events.getFirst());
    }

    @Test
    void convertsStreamingBackpressureInterruptionToCheckedFailure() {
        ChatResponse text = response(new AssistantMessage("片段"), null, null);
        FakeChatModel model = new FakeChatModel(text, List.of(text));

        assertThrows(
                InterruptedException.class,
                () -> adapter(model, STREAMING, () -> {})
                        .invoke(
                                TurnId.random(),
                                simpleInvocation(),
                                (turnId, event, cancellation) -> {
                                    throw new InterruptedException("no demand");
                                },
                                new CancellationSource()));
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void validatesEndpointSelectionCancellationAndBuilderState() {
        FakeChatModel model = new FakeChatModel(response(new AssistantMessage("完成"), null, null));
        SpringAiModelAdapter adapter = adapter(model, NON_STREAMING, () -> {});
        var cancelled = new CancellationSource();
        cancelled.cancel("用户取消");

        assertEquals(NON_STREAMING, adapter.capabilities(ENDPOINT_ID));
        assertThrows(IllegalArgumentException.class, () -> adapter.capabilities("missing"));
        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> adapter.invoke(
                        TurnId.random(), simpleInvocation(), (turnId, event, cancellation) -> {}, cancelled));
        assertThrows(
                IllegalStateException.class,
                () -> SpringAiModelAdapter.builder().build());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SpringAiModelAdapter(
                        List.of(endpoint(model, NON_STREAMING, () -> {}), endpoint(model, NON_STREAMING, () -> {}))));
    }

    @Test
    void closesResourcesInReverseOrderAndAggregatesFailures() {
        List<String> closed = new ArrayList<>();
        FakeChatModel first = new FakeChatModel(response(new AssistantMessage("一"), null, null));
        FakeChatModel second = new FakeChatModel(response(new AssistantMessage("二"), null, null));
        SpringAiEndpoint firstEndpoint = new SpringAiEndpoint(
                "first", first, NON_STREAMING, ignored -> ChatOptions.builder().build(), () -> closed.add("first"));
        SpringAiEndpoint secondEndpoint = new SpringAiEndpoint(
                "second",
                second,
                NON_STREAMING,
                ignored -> ChatOptions.builder().build(),
                () -> closed.add("second"));
        SpringAiModelAdapter adapter = new SpringAiModelAdapter(List.of(firstEndpoint, secondEndpoint));

        try {
            adapter.close();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertEquals(List.of("second", "first"), closed);

        Exception failure = assertThrows(
                Exception.class,
                () -> new SpringAiModelAdapter(List.of(
                                new SpringAiEndpoint(
                                        "fail-1",
                                        first,
                                        NON_STREAMING,
                                        ignored -> ChatOptions.builder().build(),
                                        () -> {
                                            throw new Exception("first");
                                        }),
                                new SpringAiEndpoint(
                                        "fail-2",
                                        second,
                                        NON_STREAMING,
                                        ignored -> ChatOptions.builder().build(),
                                        () -> {
                                            throw new Exception("second");
                                        })))
                        .close());
        assertEquals("second", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
    }

    private static com.javaclaw.runtime.ModelInvocation completeInvocation() {
        var assistantCall = toolCall("call-in");
        return invocation(
                List.of(
                        new ModelMessage(MessageRole.SYSTEM, "上下文", List.of(), Optional.empty(), Optional.empty()),
                        new ModelMessage(MessageRole.USER, "读取", List.of(), Optional.empty(), Optional.empty()),
                        ModelMessage.assistant("", List.of(assistantCall)),
                        ModelMessage.tool("call-in", TOOL.identity().name(), "内容")),
                List.of(TOOL));
    }

    private static SpringAiModelAdapter adapter(
            FakeChatModel model, ModelCapabilities capabilities, AutoCloseable resources) {
        return SpringAiModelAdapter.builder()
                .register(endpoint(model, capabilities, resources))
                .build();
    }

    private static SpringAiEndpoint endpoint(
            FakeChatModel model, ModelCapabilities capabilities, AutoCloseable resources) {
        return new SpringAiEndpoint(
                ENDPOINT_ID,
                model,
                capabilities,
                invocation -> ChatOptions.builder()
                        .model(ENDPOINT_ID)
                        .maxTokens(Math.toIntExact(invocation.maximumOutputTokens()))
                        .build(),
                resources);
    }

    private static final class FakeChatModel implements ChatModel {
        private final ChatResponse response;
        private final List<ChatResponse> stream;
        private Prompt lastPrompt;

        private FakeChatModel(ChatResponse response) {
            this(response, List.of(response));
        }

        private FakeChatModel(ChatResponse response, List<ChatResponse> stream) {
            this.response = response;
            this.stream = List.copyOf(stream);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            return response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            lastPrompt = prompt;
            return Flux.fromIterable(stream);
        }

        private Prompt lastPrompt() {
            return lastPrompt;
        }
    }
}
