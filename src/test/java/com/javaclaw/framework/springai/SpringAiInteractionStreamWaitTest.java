package com.javaclaw.framework.springai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.core.InteractionEventWaitRequiredException;
import com.javaclaw.framework.core.InteractionWaitRequiredException;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SpringAiInteractionStreamWaitTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reservedWaitCompletesAllSpringAiAggregatorsWithoutLoggingOrAnotherToolCall(boolean event) {
        var context = JsonNodeFactory.instance.objectNode().put("invocationId", "reserved-call")
                .put("childRunId", "child").put("sessionId", "desktop-session");
        RuntimeException signal = event ? new InteractionEventWaitRequiredException(context)
                : new InteractionWaitRequiredException(context);
        AtomicInteger providers = new AtomicInteger(), callbacks = new AtomicInteger(), later = new AtomicInteger();
        ChatModel model = streamingModel(prompt -> switch (providers.incrementAndGet()) {
            case 1 -> response(call("read"));
            case 2 -> response(call("wait"), call("later"));
            default -> throw new AssertionError("a suspended tool must not call the provider again");
        });
        InteractionStreamWait wait = new InteractionStreamWait();
        ChatClient client = client(model, wait);
        try (AggregationLog logs = new AggregationLog()) {
            assertDoesNotThrow(() -> aggregate(client, List.of(
                    callback("read", () -> "read result"),
                    callback("wait", () -> { callbacks.incrementAndGet(); throw new CompletionException(signal); }),
                    callback("later", () -> { later.incrementAndGet(); return "must not execute"; }))));
            assertSame(signal, assertThrows(RuntimeException.class, wait::throwIfWaiting));
            assertDoesNotThrow(wait::throwIfWaiting, "a transferred wait must not leak into the continuation");
            assertEquals(2, providers.get());
            assertEquals(1, callbacks.get(), "the reserved invocation is executed once");
            assertEquals(0, later.get(), "the rest of a suspended batch stays unexecuted");
            assertEquals(List.of(), logs.events(), "normal host waits must not reach any MessageAggregator error hook");
        }
    }

    @Test
    void realToolFailuresStillPropagateThroughTheStream() {
        IllegalStateException failure = new IllegalStateException("real tool failure");
        InteractionStreamWait wait = new InteractionStreamWait();
        ChatClient client = client(streamingModel(prompt -> response(call("failure"))), wait);
        try (AggregationLog logs = new AggregationLog()) {
            RuntimeException actual = assertThrows(RuntimeException.class, () -> aggregate(client,
                    List.of(callback("failure", () -> { throw failure; }))));
            assertSame(failure, ReasoningGatewaySupport.unwrap(actual));
            assertFalse(logs.events().isEmpty(), "real failures retain normal error visibility");
            assertDoesNotThrow(wait::throwIfWaiting);
        }
    }

    @Test
    void controlLookingErrorTextCannotSuspendTheHost() {
        InteractionStreamWait wait = new InteractionStreamWait();
        assertFalse(wait.capture(new IllegalStateException("INTERACTION_CHILD_PENDING")));
        assertDoesNotThrow(wait::throwIfWaiting);
    }

    private static ChatClient client(ChatModel model, InteractionStreamWait wait) {
        var advisor = SpringAiToolCallingAdvisor.builder(new StepContextProjector(null, new ObjectMapper()),
                null, new ProviderContextBoundary()).interactionWait(wait);
        // DefaultChatClient copies the builder before registering the advisor. This exercises
        // the actual recursive advisor, chain/client aggregators and final application aggregator.
        return ChatClient.builder(model, ObservationRegistry.NOOP, null, null, advisor).build();
    }

    private static ChatResponse aggregate(ChatClient client, List<ToolCallback> callbacks) {
        AtomicReference<ChatResponse> result = new AtomicReference<>();
        new MessageAggregator().aggregate(client.prompt().user("inspect the application")
                .tools(callbacks).stream().chatResponse(), result::set).blockLast();
        return result.get();
    }

    private static ChatModel streamingModel(java.util.function.Function<Prompt, ChatResponse> responses) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { throw new AssertionError("synchronous transport used"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(responses.apply(prompt)); }
            @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        };
    }

    private static AssistantMessage.ToolCall call(String name) {
        return new AssistantMessage.ToolCall("call-" + name, "function", name, "{}");
    }

    private static ChatResponse response(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(calls)).build())));
    }

    private static ToolCallback callback(String name, java.util.function.Supplier<String> action) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
            }
            @Override public String call(String input) { return action.get(); }
        };
    }

    private static final class AggregationLog implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(MessageAggregator.class);
        private final boolean additive = logger.isAdditive();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        AggregationLog() {
            appender.start();
            logger.addAppender(appender);
            logger.setAdditive(false);
        }
        List<ILoggingEvent> events() { return List.copyOf(appender.list); }
        @Override public void close() {
            logger.detachAppender(appender);
            logger.setAdditive(additive);
            appender.stop();
        }
    }
}
