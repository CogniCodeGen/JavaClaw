package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunOutcome;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunSnapshot;
import com.javaclaw.framework.api.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConversationNeedsInputTest {
    @ParameterizedTest
    @ValueSource(strings = {"chat", "plan"})
    void 模型提问先于等待终态显示且回答继续原Turn不重放旧提问(String profile) {
        WaitingClient agents = new WaitingClient();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);
        runner.start(request("请生成报告", profile), ToolCallOrigin.INTERACTIVE, callbacks);

        ObjectNode waiting = object().put("reason", "MODEL_NEEDS_INPUT");
        waiting.putObject("output").put("kind", "harness.needs_input")
                .put("text", "请选择 PDF 或 Markdown");
        agents.waitForInput(waiting);

        assertEquals(List.of("请选择 PDF 或 Markdown", "waiting"), callbacks.deliveryOrder);
        assertInstanceOf(ConversationOutcome.WaitingInput.class, callbacks.outcomes.getFirst());
        assertFalse(runner.isRunning());
        assertFalse(agents.completion.isDone());
        assertEquals(0, agents.cancellations);

        RecordingCallbacks resumed = new RecordingCallbacks();
        runner.start(request("Markdown", profile), ToolCallOrigin.INTERACTIVE, resumed);
        assertEquals(1, agents.starts);
        assertEquals("input", agents.resume.type());
        assertEquals("Markdown", agents.resume.payload().path("text").asText());
        assertTrue(resumed.events.isEmpty());
        ObjectNode completed = object();
        completed.putObject("output").put("text", "报告正文");
        agents.emit("core.run.completed", completed);
        agents.completion.complete(new RunOutcome(agents.id, RunState.COMPLETED, null, null));
        assertEquals(List.of("报告正文"), resumed.deliveryOrder);
        assertInstanceOf(ConversationOutcome.Completed.class, resumed.outcomes.getFirst());
        assertEquals(1, callbacks.outcomes.size());
    }

    @Test
    void 空提问继续使用等待提示而备用正文可显示() {
        for (String value : List.of("", "请选择日期")) {
            WaitingClient agents = new WaitingClient();
            RecordingCallbacks callbacks = new RecordingCallbacks();
            AgentConversationRunner runner = new AgentConversationRunner(agents, Runnable::run);
            runner.start(request("查看日历"), ToolCallOrigin.INTERACTIVE, callbacks);
            ObjectNode waiting = object().put("reason", "等待补充输入");
            waiting.putObject("output").put("text", "  ").put("value", value);
            agents.waitForInput(waiting);

            if (value.isEmpty()) {
                assertInstanceOf(ConversationEvent.Hint.class, callbacks.events.getFirst());
                assertEquals(List.of("waiting"), callbacks.deliveryOrder);
            } else {
                assertEquals(List.of(value, "waiting"), callbacks.deliveryOrder);
            }
            assertEquals(1, callbacks.outcomes.size());
        }
    }

    private static RunRequest request(String text) {
        return request(text, "chat");
    }

    private static RunRequest request(String text, String profile) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest(profile)).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "input-session"))
                .input(InputBlock.text(text)).build();
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static final class RecordingCallbacks implements ConversationCallbacks {
        private final List<ConversationEvent> events = new ArrayList<>();
        private final List<ConversationOutcome> outcomes = new ArrayList<>();
        private final List<String> deliveryOrder = new ArrayList<>();

        @Override public void onEvent(ConversationEvent event) {
            events.add(event);
            if (event instanceof ConversationEvent.Reply reply) deliveryOrder.add(reply.chunk());
        }

        @Override public void onTerminal(ConversationOutcome outcome) {
            outcomes.add(outcome);
            if (outcome instanceof ConversationOutcome.WaitingInput) deliveryOrder.add("waiting");
        }
    }

    private static final class WaitingClient implements AgentClient, RunHandle {
        private final RunId id = new RunId("needs-input");
        private final Sinks.Many<RunEventEnvelope> events = Sinks.many().replay().all();
        private final CompletableFuture<RunOutcome> completion = new CompletableFuture<>();
        private RunRequest original;
        private RunSnapshot waiting;
        private ResumeCommand resume;
        private long sequence;
        private int starts;
        private int cancellations;

        @Override public RunHandle start(RunRequest request) {
            original = request;
            starts++;
            return this;
        }

        @Override public RunHandle resume(RunId runId, ResumeCommand command) {
            assertEquals(id, runId);
            resume = command;
            return this;
        }

        @Override public Optional<RunSnapshot> activeTurn(RunScope scope) {
            return original != null && original.scope().equals(scope)
                    ? Optional.ofNullable(waiting) : Optional.empty();
        }

        @Override public Optional<RunRequest> request(RunId runId) { return Optional.of(original); }
        @Override public RunSnapshot get(RunId runId) { return waiting; }
        @Override public boolean cancel(RunId runId, CancelReason reason) { cancellations++; return true; }
        @Override public RunId id() { return id; }
        @Override public Flux<RunEventEnvelope> events(long afterSequence) {
            return events.asFlux().filter(event -> event.sequence() > afterSequence);
        }
        @Override public CompletionStage<RunOutcome> completion() { return completion; }

        private void waitForInput(ObjectNode payload) {
            waiting = new RunSnapshot(id, RunState.WAITING_INPUT, "plan", sequence + 1,
                    Instant.EPOCH, Instant.EPOCH, payload, null, 1);
            emit("core.run.waiting_input", payload);
        }

        private void emit(String type, JsonNode payload) {
            assertEquals(Sinks.EmitResult.OK, events.tryEmitNext(new RunEventEnvelope(
                    id.value(), ++sequence, Instant.EPOCH, type, 1, "test", null, null, payload)));
        }
    }
}
