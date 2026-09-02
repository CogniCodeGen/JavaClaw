package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.models.responses.CompactedResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompactParams;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelStreamEvent;
import com.javaclaw.runtime.NativeCompactionRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiResponsesModelAdapterTest {
    private static final String INSTRUCTIONS = "只输出可验证结果";
    private static final JsonMapper JSON = ModelJsonMapper.create();

    @Test
    void streamsSummaryAndPersistsOpaqueOutputWithoutRemoteConversation() throws Exception {
        FakeTransport transport = new FakeTransport(response(), compacted());
        OpenAiResponsesModelAdapter adapter = new OpenAiResponsesModelAdapter(config(), transport);
        List<ModelStreamEvent> events = new ArrayList<>();
        ModelInvocation invocation = invocation(List.of(message(MessageRole.USER, "你好")));

        var result = adapter.invoke(
                TurnId.random(),
                invocation,
                (turnId, event, cancellation) -> events.add(event),
                new CancellationSource());

        assertEquals("完成", result.text());
        assertEquals(Optional.of("已检查"), result.reasoningSummary());
        assertEquals(new com.javaclaw.runtime.ModelUsage(10, 3, 2, 2), result.usage());
        assertEquals(ModelFinishReason.COMPLETE, result.finishReason());
        assertTrue(events.stream().anyMatch(ModelStreamEvent.ReasoningSummaryDelta.class::isInstance));
        String state = result.providerState().orElseThrow().payload().json();
        assertTrue(state.contains("encrypted_content"));
        assertTrue(state.contains("opaque-reasoning"));
        assertFalse(transport.lastCreate().store().orElseThrow());
    }

    @Test
    void resumesFromOpaqueItemsAndSendsOnlyMessagesAfterLastAssistant() throws Exception {
        FakeTransport transport = new FakeTransport(response(), compacted());
        OpenAiResponsesModelAdapter adapter = new OpenAiResponsesModelAdapter(config(), transport);
        CancellationSource cancellation = new CancellationSource();
        var first = adapter.invoke(
                TurnId.random(), invocation(List.of(message(MessageRole.USER, "第一问"))), noEvents(), cancellation);
        List<ModelMessage> continued = List.of(
                message(MessageRole.USER, "第一问"),
                ModelMessage.assistant("完成", List.of()),
                message(MessageRole.USER, "第二问"));

        adapter.invokeContinuing(
                TurnId.random(), invocation(continued), first.providerState().orElseThrow(), noEvents(), cancellation);

        var input = transport.lastCreate().input().orElseThrow().asResponse();
        assertEquals(3, input.size());
        assertTrue(input.get(0).isReasoning());
        assertTrue(input.get(1).isResponseOutputMessage());
        assertTrue(input.get(2).isEasyInputMessage());
        assertEquals("第二问", input.get(2).asEasyInputMessage().content().asTextInput());
    }

    @Test
    void compactsOpaqueItemsAndReturnsReusableCompactionState() throws Exception {
        FakeTransport transport = new FakeTransport(response(), compacted());
        OpenAiResponsesModelAdapter adapter = new OpenAiResponsesModelAdapter(config(), transport);
        CancellationSource cancellation = new CancellationSource();
        var first = adapter.invoke(
                TurnId.random(), invocation(List.of(message(MessageRole.USER, "压缩"))), noEvents(), cancellation);

        var compactedState = adapter.compact(
                new NativeCompactionRequest(
                        TurnId.random(), "responses-main", first.providerState().orElseThrow()),
                cancellation);

        assertEquals(10, compactedState.consumedTokens());
        assertEquals(
                2,
                transport
                        .lastCompact()
                        .input()
                        .orElseThrow()
                        .asResponseInputItems()
                        .size());
        assertTrue(compactedState.state().payload().json().contains("compact-secret"));
        assertEquals(
                "compacted",
                new ProviderStateCodec().decode(compactedState.state()).kind());
    }

    @Test
    void rejectsInvalidConfigurationAndMissingSecretsBeforeNetworkSetup() {
        assertThrows(IllegalArgumentException.class, () -> endpointConfig(" ", "gpt-5", Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> endpointConfig("endpoint", " ", Duration.ofSeconds(1), 0));
        assertThrows(IllegalArgumentException.class, () -> endpointConfig("endpoint", "gpt-5", Duration.ZERO, 0));
        assertThrows(
                IllegalArgumentException.class, () -> endpointConfig("endpoint", "gpt-5", Duration.ofSeconds(1), -1));
        assertThrows(
                IllegalArgumentException.class, () -> endpointConfig("endpoint", "gpt-5", Duration.ofSeconds(1), 11));
        assertThrows(NullPointerException.class, () -> OpenAiResponsesModelAdapter.create(null, new char[] {'x'}));
        assertThrows(IllegalArgumentException.class, () -> OpenAiResponsesModelAdapter.create(config(), null));
        assertThrows(IllegalArgumentException.class, () -> OpenAiResponsesModelAdapter.create(config(), new char[0]));
    }

    @Test
    void rejectsUnknownEndpointAndCancellationBeforeTransport() {
        FakeTransport transport = new FakeTransport(responseUnchecked(), compactedUnchecked());
        OpenAiResponsesModelAdapter adapter = new OpenAiResponsesModelAdapter(config(), transport);
        ModelInvocation unknown = new ModelInvocation("missing", INSTRUCTIONS, List.of(), List.of(), 1);
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("用户取消");

        assertThrows(IllegalArgumentException.class, () -> adapter.capabilities("missing"));
        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.invoke(TurnId.random(), unknown, noEvents(), new CancellationSource()));
        assertThrows(
                com.javaclaw.api.TurnCancelledException.class,
                () -> adapter.invoke(TurnId.random(), invocation(List.of()), noEvents(), cancelled));
        assertFalse(transport.streamCalled());
    }

    @Test
    void convertsInterruptedEventPublicationAndClosesOwnedTransport() throws Exception {
        FakeTransport transport = new FakeTransport(response(), compacted());
        OpenAiResponsesModelAdapter adapter = new OpenAiResponsesModelAdapter(config(), transport);

        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> adapter.invoke(
                            TurnId.random(),
                            invocation(List.of(message(MessageRole.USER, "中断"))),
                            (turnId, event, cancellation) -> {
                                throw new InterruptedException("no demand");
                            },
                            new CancellationSource()));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        adapter.close();
        assertTrue(transport.closed());
    }

    private static OpenAiResponsesEndpointConfig config() {
        return new OpenAiResponsesEndpointConfig(
                "responses-main",
                "gpt-5",
                Optional.<URI>empty(),
                Duration.ofSeconds(30),
                0,
                ReasoningSummaryStyle.AUTO);
    }

    private static OpenAiResponsesEndpointConfig endpointConfig(
            String endpoint, String model, Duration timeout, int retries) {
        return new OpenAiResponsesEndpointConfig(
                endpoint, model, Optional.empty(), timeout, retries, ReasoningSummaryStyle.CONCISE);
    }

    private static ModelInvocation invocation(List<ModelMessage> messages) {
        return new ModelInvocation("responses-main", INSTRUCTIONS, messages, List.of(), 512);
    }

    private static ModelMessage message(MessageRole role, String text) {
        return new ModelMessage(role, text, List.of(), Optional.empty(), Optional.empty());
    }

    private static com.javaclaw.runtime.ModelEventSink noEvents() {
        return (turnId, event, cancellation) -> {};
    }

    private static Response response() throws Exception {
        return JSON.readValue("""
                {
                  "id": "resp-1",
                  "created_at": 1,
                  "model": "gpt-5",
                  "object": "response",
                  "output": [
                    {
                      "id": "reasoning-1",
                      "type": "reasoning",
                      "summary": [{"type": "summary_text", "text": "已检查"}],
                      "encrypted_content": "opaque-reasoning",
                      "status": "completed"
                    },
                    {
                      "id": "message-1",
                      "type": "message",
                      "role": "assistant",
                      "status": "completed",
                      "content": [{"type": "output_text", "text": "完成", "annotations": []}]
                    }
                  ],
                  "parallel_tool_calls": true,
                  "status": "completed",
                  "tool_choice": "auto",
                  "tools": [],
                  "usage": {
                    "input_tokens": 10,
                    "input_tokens_details": {"cached_tokens": 2},
                    "output_tokens": 5,
                    "output_tokens_details": {"reasoning_tokens": 2},
                    "total_tokens": 15
                  }
                }
                """, Response.class);
    }

    private static CompactedResponse compacted() throws Exception {
        return JSON.readValue("""
                {
                  "id": "compact-1",
                  "created_at": 2,
                  "object": "response.compaction",
                  "output": [
                    {"id": "compaction-1", "type": "compaction", "encrypted_content": "compact-secret"}
                  ],
                  "usage": {
                    "input_tokens": 10,
                    "input_tokens_details": {"cached_tokens": 0},
                    "output_tokens": 1,
                    "output_tokens_details": {"reasoning_tokens": 0},
                    "total_tokens": 11
                  }
                }
                """, CompactedResponse.class);
    }

    private static Response responseUnchecked() {
        try {
            return response();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static CompactedResponse compactedUnchecked() {
        try {
            return compacted();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static List<ResponseStreamEvent> stream(Response response) {
        var reasoning = ResponseReasoningSummaryTextDeltaEvent.builder()
                .delta("已检查")
                .itemId("reasoning-1")
                .outputIndex(0)
                .sequenceNumber(1)
                .summaryIndex(0)
                .build();
        var text = ResponseTextDeltaEvent.builder()
                .contentIndex(0)
                .delta("完成")
                .itemId("message-1")
                .logprobs(List.of())
                .outputIndex(1)
                .sequenceNumber(2)
                .build();
        var completed = ResponseCompletedEvent.builder()
                .response(response)
                .sequenceNumber(3)
                .build();
        return List.of(
                ResponseStreamEvent.ofReasoningSummaryTextDelta(reasoning),
                ResponseStreamEvent.ofOutputTextDelta(text),
                ResponseStreamEvent.ofCompleted(completed));
    }

    private static final class FakeTransport implements OpenAiResponsesTransport {
        private final List<ResponseStreamEvent> events;
        private final CompactedResponse compacted;
        private ResponseCreateParams lastCreate;
        private ResponseCompactParams lastCompact;
        private boolean streamCalled;
        private boolean closed;

        private FakeTransport(Response response, CompactedResponse compacted) {
            events = OpenAiResponsesModelAdapterTest.stream(response);
            this.compacted = compacted;
        }

        @Override
        public void stream(ResponseCreateParams request, java.util.function.Consumer<ResponseStreamEvent> consumer) {
            lastCreate = request;
            streamCalled = true;
            events.forEach(consumer);
        }

        @Override
        public CompactedResponse compact(ResponseCompactParams request) {
            lastCompact = request;
            return compacted;
        }

        @Override
        public void close() {
            closed = true;
        }

        private ResponseCreateParams lastCreate() {
            return lastCreate;
        }

        private ResponseCompactParams lastCompact() {
            return lastCompact;
        }

        private boolean streamCalled() {
            return streamCalled;
        }

        private boolean closed() {
            return closed;
        }
    }
}
