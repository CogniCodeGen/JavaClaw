package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStepJournalRecoveryTest {

    @Test
    void validatesEveryPersistedCallBeforeExecutingTheFirstOne() {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(List.of("visible"), List.of(
                    new AssistantMessage.ToolCall("first", "function", "visible", "{}"),
                    new AssistantMessage.ToolCall("second", "function", "hidden", "{}")));
            AtomicInteger invocations = new AtomicInteger();

            ToolRecoveryRequiredException failure = assertThrows(ToolRecoveryRequiredException.class,
                    () -> fixture.journal().recover(List.of(tool("visible"), tool("hidden")),
                            gateway(invocations), false));

            assertTrue(failure.getMessage().contains("hidden"));
            assertEquals(0, invocations.get());
            assertEquals(0, fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .filter(event -> event.type().equals("core.step.started")
                            && event.payload().path("kind").asText().equals("TOOL"))
                    .count());
        }
    }

    @Test
    void missingPersistedToolDirectoryIsRejectedBeforeExecution() {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(null, List.of(
                    new AssistantMessage.ToolCall("first", "function", "stale_tool", "{}")));
            AtomicInteger invocations = new AtomicInteger();

            ToolRecoveryRequiredException failure = assertThrows(ToolRecoveryRequiredException.class,
                    () -> fixture.journal().recover(
                            List.of(tool("stale_tool")), gateway(invocations), false));

            assertTrue(failure.getMessage().contains("tool directory"));
            assertEquals(0, invocations.get());
        }
    }

    private static ToolInvocationGateway gateway(AtomicInteger invocations) {
        return request -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(new ToolInvocationResult(
                    JsonNodeFactory.instance.objectNode().put("ok", true), Duration.ZERO));
        };
    }

    private static FrameworkTool tool(String name) {
        return new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "test tool",
                        JsonNodeFactory.instance.objectNode().put("type", "object"),
                        "test", PermissionSet.NONE, true);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                return JsonNodeFactory.instance.objectNode().put("ok", true);
            }
        };
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final ExtensionManager extensions = new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                request -> CompletableFuture.failedFuture(new AssertionError("unexpected model task"))));
        private final RunId runId = new RunId("recovery-test-run");
        private final RunRequest runRequest = RunRequest.builder()
                .agent(new AgentDefinitionRef("test.agent", 1L))
                .profile(new RunProfileRef("test.profile", 1L))
                .source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("task"))
                .permissionCeiling(PermissionSet.UNRESTRICTED)
                .budget(RunBudget.UNBOUNDED)
                .build();
        private final ExecutionPlan plan = new AgentCompiler(new AgentDefinitionResolver() {
            @Override public AgentDefinition resolveAgent(String workspaceId, AgentDefinitionRef reference) {
                return new AgentDefinition("test.agent", 1L, "Test", "test:model",
                        Map.of("system", "test"), Map.of(),
                        JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(),
                        RunBudget.UNBOUNDED, JsonNodeFactory.instance.objectNode(), Map.of(), "agent-checksum");
            }
            @Override public RunProfile resolveProfile(String workspaceId, RunProfileRef reference) {
                return new RunProfile("test.profile", 1L, "Test", PermissionSet.UNRESTRICTED,
                        RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode(),
                        "profile-checksum");
            }
        }, extensions, json).compile(runRequest);
        private final MemoryRunStore store = new MemoryRunStore(runId, runRequest, plan.descriptor().id());
        private final ReasoningEventSink events = store::record;
        private final ReasoningRequest reasoning = new ReasoningRequest(
                runId, plan, runRequest, null, newControl(), events);

        private ModelStepJournal journal() {
            return new ModelStepJournal(reasoning, store, json);
        }

        private void completedModelStep(
                List<String> visibleTools, List<AssistantMessage.ToolCall> calls) {
            StepId step = StepId.random();
            var input = JsonNodeFactory.instance.objectNode();
            input.set("messages", StepMessageCodec.messages(List.of(
                    new SystemMessage("test"), new UserMessage("task"))));
            if (visibleTools != null) {
                var names = input.putArray("toolNames");
                visibleTools.forEach(names::add);
            }
            StepEvents.started(events, step, AgentStep.Kind.MODEL, input, null);
            ChatResponse response = new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(calls).build())),
                    ChatResponseMetadata.builder().model("test:model").build());
            StepEvents.completed(events, step, StepMessageCodec.response(response),
                    StepMessageCodec.usage(response));
        }

        private static RunControl newControl() {
            try {
                Constructor<RunControl> constructor = RunControl.class.getDeclaredConstructor(
                        RunBudget.class, Clock.class);
                constructor.setAccessible(true);
                return constructor.newInstance(RunBudget.UNBOUNDED, Clock.systemUTC());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("cannot create test RunControl", failure);
            }
        }

        @Override public void close() {
            plan.close();
            extensions.close();
        }
    }

    private static final class MemoryRunStore implements RunStore {
        private final StoredRun run;
        private final List<RunEventEnvelope> events = new ArrayList<>();

        private MemoryRunStore(RunId id, RunRequest request, String planId) {
            Instant now = Instant.now();
            run = new StoredRun(new RunSnapshot(id, RunState.RUNNING, planId,
                    0, now, now, null, null, 1), request);
        }

        private void record(String type, int version, String producer,
                            com.fasterxml.jackson.databind.JsonNode payload) {
            events.add(new RunEventEnvelope(run.snapshot().id().value(), events.size() + 1L,
                    Instant.now(), type, version, producer, null, null, payload));
        }

        @Override public CreateRunResult create(RunId id, RunRequest request,
                                                String executionPlanId, RunEventDraft createdEvent) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<StoredRun> find(RunId id) {
            return id.equals(run.snapshot().id()) ? Optional.of(run) : Optional.empty();
        }
        @Override public Optional<StoredRun> findByIdempotencyKey(String workspaceId, String key) {
            return Optional.empty();
        }
        @Override public List<StoredRun> nonTerminalRuns() { return List.of(run); }
        @Override public List<RunEventEnvelope> eventsAfter(RunId id, long afterSequence) {
            return events.stream().filter(event -> event.sequence() > afterSequence).toList();
        }
        @Override public Optional<RunEventEnvelope> append(RunId id, Set<RunState> expectedStates,
                RunState nextState, RunEventDraft event,
                com.fasterxml.jackson.databind.JsonNode output, String error) {
            throw new UnsupportedOperationException();
        }
    }
}
