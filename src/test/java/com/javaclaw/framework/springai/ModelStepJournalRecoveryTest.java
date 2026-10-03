package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void identicalHostObservationsReuseOneExecutionDespiteArgumentFieldOrder(boolean succeeded) throws Exception {
        try (Fixture fixture = new Fixture()) {
            FrameworkTool observe = hostDesktopTool(fixture, "desktop_session_observe");
            ModelStepJournal journal = fixture.journal();
            String first = "{\"sessionId\":\"owned-session\",\"question\":\"models\",\"extractAllText\":true}";
            String reordered = "{\"extractAllText\":true,\"question\":\"models\",\"sessionId\":\"owned-session\"}";
            StepId model = completedBatch(journal, observe.descriptor().name(), List.of(
                    new AssistantMessage.ToolCall("observe-first", "function", observe.descriptor().name(), first),
                    new AssistantMessage.ToolCall("observe-duplicate", "function", observe.descriptor().name(), reordered),
                    new AssistantMessage.ToolCall("observe-third", "function", observe.descriptor().name(), first)));
            AtomicInteger invocations = new AtomicInteger();
            ToolExecutionStatus status = succeeded ? ToolExecutionStatus.SUCCEEDED : ToolExecutionStatus.TIMED_OUT;
            ToolInvocationGateway gateway = persistingGateway(fixture, invocations, status);

            var original = journal.invokeForModel(observe, fixture.json.readTree(first), gateway);
            var duplicate = journal.invokeForModel(observe, fixture.json.readTree(reordered), gateway);
            var third = journal.invokeForModel(observe, fixture.json.readTree(first), gateway);

            assertEquals(original, duplicate);
            assertEquals(original, third);
            assertEquals(status.name(), duplicate.path("status").asText());
            assertEquals(succeeded ? 1 : 0, third.path("evidenceRefs").size());
            assertEquals(1, invocations.get());
            List<AgentStep> steps = new RunStepQuery(fixture.store).steps(fixture.runId);
            assertEquals(1, steps.stream().filter(step -> step.kind() == AgentStep.Kind.TOOL).count());
            assertEquals(2, steps.stream().filter(BatchObservationReuse::isAlias).count());
            steps.stream().filter(BatchObservationReuse::isAlias).forEach(alias ->
                    assertEquals("model/" + model.value() + "/observe-first",
                            alias.input().path("sourceInvocationId").asText()));
            var recovered = fixture.journal().recover(List.of(observe), gateway, false);
            ToolResponseMessage responses = (ToolResponseMessage) recovered.messages().getLast();
            assertEquals(List.of("observe-first", "observe-duplicate", "observe-third"), responses.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::id).toList());
            assertEquals(responses.getResponses().getFirst().responseData(),
                    responses.getResponses().getLast().responseData());
            assertEquals(1, invocations.get(), "recovery must reproduce every provider ID without another capture");
        }
    }

    @Test
    void recoveryReusesPendingDuplicateFromCompletedOriginalObservation() {
        try (Fixture fixture = new Fixture()) {
            FrameworkTool observe = hostDesktopTool(fixture, "desktop_session_observe");
            ModelStepJournal journal = fixture.journal();
            completedBatch(journal, observe.descriptor().name(), List.of(
                    new AssistantMessage.ToolCall("first", "function", observe.descriptor().name(), "{\"sessionId\":\"s\"}"),
                    new AssistantMessage.ToolCall("duplicate", "function", observe.descriptor().name(), "{\"sessionId\":\"s\"}")));
            AtomicInteger invocations = new AtomicInteger();
            ToolInvocationGateway gateway = persistingGateway(fixture, invocations, ToolExecutionStatus.FAILED);
            journal.invokeForModel(observe, fixture.json.createObjectNode().put("sessionId", "s"), gateway);

            var recovered = fixture.journal().recover(List.of(observe), gateway, false);

            assertEquals(1, invocations.get());
            assertEquals(2, ((ToolResponseMessage) recovered.messages().getLast()).getResponses().size());
        }
    }

    @Test
    void observationAfterInterveningInputOrChangedArgumentsIsExecutedAgain() {
        try (Fixture fixture = new Fixture()) {
            FrameworkTool observe = hostDesktopTool(fixture, "desktop_session_observe");
            FrameworkTool input = tool("input");
            ModelStepJournal journal = fixture.journal();
            completedBatch(journal, observe.descriptor().name(), List.of(
                    new AssistantMessage.ToolCall("first", "function", observe.descriptor().name(), "{\"sessionId\":\"s\"}"),
                    new AssistantMessage.ToolCall("input", "function", "input", "{}"),
                    new AssistantMessage.ToolCall("fresh", "function", observe.descriptor().name(), "{\"sessionId\":\"s\"}"),
                    new AssistantMessage.ToolCall("changed", "function", observe.descriptor().name(), "{\"sessionId\":\"other\"}")));
            AtomicInteger invocations = new AtomicInteger();
            ToolInvocationGateway gateway = persistingGateway(fixture, invocations, ToolExecutionStatus.SUCCEEDED);
            journal.invokeForModel(observe, fixture.json.createObjectNode().put("sessionId", "s"), gateway);
            journal.invokeForModel(input, fixture.json.createObjectNode(), gateway);
            journal.invokeForModel(observe, fixture.json.createObjectNode().put("sessionId", "s"), gateway);
            journal.invokeForModel(observe, fixture.json.createObjectNode().put("sessionId", "other"), gateway);
            assertEquals(4, invocations.get());
        }
    }

    @Test
    void nextProviderMessageAndUntrustedObservationNamesCannotReuseEarlierFrames() {
        try (Fixture fixture = new Fixture()) {
            FrameworkTool observe = hostDesktopTool(fixture, "desktop_session_observe");
            ModelStepJournal journal = fixture.journal();
            AtomicInteger invocations = new AtomicInteger();
            ToolInvocationGateway gateway = persistingGateway(fixture, invocations, ToolExecutionStatus.SUCCEEDED);
            for (String id : List.of("previous", "next")) {
                completedBatch(journal, observe.descriptor().name(), List.of(
                        new AssistantMessage.ToolCall(id, "function", observe.descriptor().name(), "{}")));
                journal.invokeForModel(observe, fixture.json.createObjectNode(), gateway);
            }
            FrameworkTool untrusted = tool(observe.descriptor().name());
            completedBatch(journal, untrusted.descriptor().name(), List.of(
                    new AssistantMessage.ToolCall("plugin-first", "function", untrusted.descriptor().name(), "{}"),
                    new AssistantMessage.ToolCall("plugin-next", "function", untrusted.descriptor().name(), "{}")));
            journal.invokeForModel(untrusted, fixture.json.createObjectNode(), gateway);
            journal.invokeForModel(untrusted, fixture.json.createObjectNode(), gateway);
            assertEquals(4, invocations.get());
        }
    }

    @Test
    void tamperedObservationAliasRequiresReconciliationWithoutExecutingAgain() {
        try (Fixture fixture = new Fixture()) {
            FrameworkTool observe = hostDesktopTool(fixture, "desktop_session_observe");
            ModelStepJournal journal = fixture.journal();
            completedBatch(journal, observe.descriptor().name(), List.of(
                    new AssistantMessage.ToolCall("first", "function", observe.descriptor().name(), "{}"),
                    new AssistantMessage.ToolCall("duplicate", "function", observe.descriptor().name(), "{}")));
            AtomicInteger invocations = new AtomicInteger();
            ToolInvocationGateway gateway = persistingGateway(fixture, invocations, ToolExecutionStatus.SUCCEEDED);
            journal.invokeForModel(observe, fixture.json.createObjectNode(), gateway);
            journal.invokeForModel(observe, fixture.json.createObjectNode(), gateway);
            fixture.store.events.replaceAll(event -> {
                var payload = event.payload();
                if (!event.type().equals("core.step.started")
                        || !payload.path("input").path("phase").asText().equals(BatchObservationReuse.PHASE)) {
                    return event;
                }
                payload.withObject("input").put("sourceInvocationId", "model/another-message/first");
                // Envelopes defensively copy payloads; replace the actual durable event.
                return new RunEventEnvelope(event.runId(), event.sequence(), event.timestamp(),
                        event.type(), event.schemaVersion(), event.producer(), event.correlationId(),
                        event.causationId(), payload);
            });
            var damaged = new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(BatchObservationReuse::isAlias).findFirst().orElseThrow();
            assertEquals("model/another-message/first", damaged.input().path("sourceInvocationId").asText());

            assertThrows(ToolRecoveryRequiredException.class,
                    () -> fixture.journal().recover(List.of(observe), gateway, false));
            assertEquals(1, invocations.get());
        }
    }

    @Test
    void observationAliasAuditFailureCannotClaimCompletionOrTriggerAnotherCapture() {
        try (Fixture fixture = new Fixture()) {
            FrameworkTool observe = hostDesktopTool(fixture, "desktop_session_observe");
            ReasoningEventSink failingAudit = (type, version, producer, payload) -> {
                if (type.equals("core.step.completed")
                        && payload.path("output").path("reusedObservation").asBoolean(false)) {
                    throw new IllegalStateException("audit unavailable");
                }
                fixture.store.record(type, version, producer, payload);
            };
            ModelStepJournal journal = new ModelStepJournal(new ReasoningRequest(fixture.runId,
                    fixture.plan, fixture.runRequest, null, fixture.reasoning.control(), failingAudit),
                    fixture.store, fixture.json);
            completedBatch(journal, observe.descriptor().name(), List.of(
                    new AssistantMessage.ToolCall("first", "function", observe.descriptor().name(), "{}"),
                    new AssistantMessage.ToolCall("duplicate", "function", observe.descriptor().name(), "{}")));
            AtomicInteger invocations = new AtomicInteger();
            ToolInvocationGateway gateway = persistingGateway(fixture, invocations, ToolExecutionStatus.SUCCEEDED);
            journal.invokeForModel(observe, fixture.json.createObjectNode(), gateway);

            assertThrows(IllegalStateException.class,
                    () -> journal.invokeForModel(observe, fixture.json.createObjectNode(), gateway));
            assertTrue(new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(BatchObservationReuse::isAlias).allMatch(step -> step.state() == AgentStep.State.RUNNING));
            assertThrows(ToolRecoveryRequiredException.class,
                    () -> fixture.journal().recover(List.of(observe), gateway, false));
            assertEquals(1, invocations.get());
        }
    }

    private static StepId completedBatch(ModelStepJournal journal, String name,
            List<AssistantMessage.ToolCall> calls) {
        StepId model = journal.started(new Prompt(List.of(new UserMessage("task")),
                org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                        .toolCallbacks(List.of(new org.springframework.ai.tool.ToolCallback() {
                            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                                return org.springframework.ai.tool.definition.ToolDefinition.builder()
                                        .name(name).description("test").inputSchema("{\"type\":\"object\"}").build();
                            }
                            @Override public String call(String input) { throw new AssertionError("unexpected callback"); }
                        })).build()), 1, null);
        journal.completed(model, new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(calls).build()))));
        return model;
    }

    private static ToolInvocationGateway persistingGateway(Fixture fixture,
            AtomicInteger invocations, ToolExecutionStatus status) {
        return request -> {
            invocations.incrementAndGet();
            String invocation = request.context().invocationId();
            String name = request.tool().descriptor().name();
            StepId id = StepId.tool(fixture.runId, invocation);
            var input = fixture.json.createObjectNode().put("tool", name).put("invocationId", invocation)
                    .put("trustedDesktopTool", SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool()))
                    .put("fingerprint", ToolInvocationFingerprint.create(name, request.arguments()));
            input.set("arguments", request.arguments());
            StepEvents.started(fixture.events, id, AgentStep.Kind.TOOL, input, null);
            var result = fixture.json.createObjectNode().put("capture", invocations.get());
            var output = fixture.json.createObjectNode().put("durationMillis", 5)
                    .put("status", status.name()).put("errorCode", "").put("displayMessage", "");
            output.set("rawOutput", result);
            output.set("modelOutput", result);
            StepEvents.completed(fixture.events, id, output, null);
            if (status == ToolExecutionStatus.SUCCEEDED) {
                fixture.store.record("core.tool.receipt", 1, "framework.core",
                        fixture.json.createObjectNode().put("invocationId", invocation)
                                .put("status", "OBSERVED").put("evidenceRef", "observed:" + invocation));
            }
            return CompletableFuture.completedFuture(new ToolInvocationResult(result, Duration.ofMillis(5), status));
        };
    }

    private static FrameworkTool hostDesktopTool(Fixture fixture, String name) {
        var sessions = (com.javaclaw.desktop.api.DesktopSessionService) Proxy.newProxyInstance(
                com.javaclaw.desktop.api.DesktopSessionService.class.getClassLoader(),
                new Class<?>[] { com.javaclaw.desktop.api.DesktopSessionService.class },
                (proxy, method, arguments) -> { throw new AssertionError("native desktop must not run in journal tests"); });
        var source = new com.javaclaw.desktop.agent.DesktopSessionTools(sessions,
                new com.javaclaw.desktop.api.DesktopSessionOwner("workspace", "session", "chat", "journal-test"),
                Path.of("/tmp"));
        var registry = new SpringAiAnnotatedToolRegistry(fixture.json);
        registry.register("workspace", ignored -> ToolObjectBundle.of(List.of(source)));
        return registry.create(new ToolContext(fixture.runId, fixture.runRequest.scope(),
                        PermissionSet.UNRESTRICTED, () -> false, Instant.MAX, fixture.runRequest)).stream()
                .filter(candidate -> candidate.descriptor().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void auditedHumanContractRevisionStartsFreshWithoutDispatchingOldPendingTools() {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(List.of("visible"), List.of(
                    new AssistantMessage.ToolCall("old-pending", "function", "visible", "{}")));
            fixture.store.record("core.run.resumed", 1, "framework.core",
                    fixture.json.createObjectNode().put("commandType", "user.input")
                            .set("command", fixture.json.createObjectNode().put("text", "cancel the old goal")));
            long resume = fixture.store.events.size();
            fixture.store.events.add(new RunEventEnvelope(fixture.runId.value(), resume + 1, Instant.now(),
                    "core.task.contract_revised", 3, "framework.core", null,
                    "core.run.resumed:" + resume, fixture.json.valueToTree(new TaskContractV3(3,
                            "explain the concept", List.of(), false, true, "model"))));
            AtomicInteger invocations = new AtomicInteger();
            assertEquals(null, fixture.journal().recover(List.of(tool("visible")), gateway(invocations), false));
            assertEquals(0, invocations.get());
        }
    }

    @Test
    void unauditedContractRevisionCannotDiscardPersistedProviderRecovery() {
        try (Fixture fixture = new Fixture()) {
            ModelStepJournal journal = fixture.journal();
            StepId model = journal.started(new Prompt(List.of(new SystemMessage("rules"), new UserMessage("task"))),
                    1, null);
            journal.completed(model, new ChatResponse(List.of(new Generation(new AssistantMessage("finished")))));
            fixture.store.events.add(new RunEventEnvelope(fixture.runId.value(), fixture.store.events.size() + 1,
                    Instant.now(), "core.task.contract_revised", 3, "framework.core", null,
                    "core.run.resumed:999", fixture.json.valueToTree(new TaskContractV3(3,
                            "fake new goal", List.of(), false, true, "model"))));
            var recovered = fixture.journal().recover(List.of(), gateway(new AtomicInteger()), false);
            assertEquals("finished", recovered.finalResponse().getResult().getOutput().getText());
        }
    }

    @Test
    void inFlightRecoveryRetainsExactProviderRoleBoundariesAndTypedSystemMetadata() {
        try (Fixture fixture = new Fixture()) {
            Message control = HostContextBlock.mark(new SystemMessage("current host control"),
                    new HostContextBlock.Metadata("run/control", HostContextBlock.Kind.CONTROL,
                            "control-v2", "run", true, List.of("event:control")));
            Message identity = HostContextBlock.mark(new UserMessage("selected application identity"),
                    new HostContextBlock.Metadata("run/application", HostContextBlock.Kind.APPLICATION_IDENTITY,
                            "identity-v1", "run", true, List.of("event:launch")));
            Message runtime = HostContextBlock.mark(new SystemMessage("current runtime"),
                    new HostContextBlock.Metadata("run/runtime", HostContextBlock.Kind.RUNTIME,
                            "runtime-v3", "run", true, List.of("event:runtime")));
            List<Message> frozen = List.of(new SystemMessage("stable instructions"), control,
                    new UserMessage("task"), identity, runtime);
            fixture.journal().started(new Prompt(frozen), 1, null);
            AtomicInteger invocations = new AtomicInteger();

            var recovered = fixture.journal().recover(List.of(), gateway(invocations), false);

            assertTrue(recovered.replayPrompt());
            assertEquals(StepMessageCodec.messages(frozen),
                    StepMessageCodec.messages(recovered.providerMessages()));
            assertEquals(3, recovered.systemMessages().size());
            assertEquals(HostContextBlock.metadata(control),
                    HostContextBlock.metadata(recovered.providerMessages().get(1)));
            assertEquals(HostContextBlock.metadata(runtime),
                    HostContextBlock.metadata(recovered.systemMessages().getLast()));
            assertEquals(2, recovered.messages().size());
            assertEquals(0, invocations.get());
            assertThrows(UnsupportedOperationException.class,
                    () -> recovered.providerMessages().add(new UserMessage("modified")));
        }
    }

    @Test
    void completedRecoveryPreservesTypedSystemsForNextStepLifecycleReplacement() {
        try (Fixture fixture = new Fixture()) {
            Message runtime = HostContextBlock.mark(new SystemMessage("previous runtime"),
                    new HostContextBlock.Metadata("run/runtime", HostContextBlock.Kind.RUNTIME,
                            "runtime-v1", "run", true, List.of("event:runtime")));
            List<Message> frozen = List.of(new SystemMessage("stable instructions"), runtime,
                    new UserMessage("task"));
            ModelStepJournal journal = fixture.journal();
            StepId model = journal.started(new Prompt(frozen), 1, null);
            journal.completed(model, new ChatResponse(List.of(new Generation(
                    new AssistantMessage("finished"))),
                    ChatResponseMetadata.builder().model("test:model").build()));

            var recovered = fixture.journal().recover(
                    List.of(), gateway(new AtomicInteger()), false);

            assertEquals(false, recovered.replayPrompt());
            assertEquals(2, recovered.systemMessages().size());
            assertEquals(HostContextBlock.metadata(runtime),
                    HostContextBlock.metadata(recovered.systemMessages().getLast()));
            assertEquals("finished", recovered.providerMessages().getLast().getText());
            assertEquals("finished", recovered.finalResponse().getResult().getOutput().getText());
        }
    }

    @Test
    void legacyRecoveryConstructorSynthesizesAProviderPromptWithoutChangingExistingAccessors() {
        List<Message> messages = List.of(new UserMessage("task"));
        var statistics = new StepContextProjector.Statistics(2, 2, 10, 10, 0, false);

        var recovered = new ModelStepJournal.Recovery("system", messages, statistics,
                null, true, List.of("visible"), "candidate");

        assertEquals("system", recovered.systemPrompt());
        assertEquals(messages, recovered.messages());
        assertEquals(List.of("visible"), recovered.toolNames());
        assertEquals("candidate", recovered.toolCandidateStepId());
        assertEquals(2, recovered.providerMessages().size());
        assertTrue(recovered.providerMessages().getFirst() instanceof SystemMessage);
        assertEquals("system", recovered.systemMessages().getFirst().getText());
    }

    @Test
    void rejectedBatchProgressUsesTrustedToolMarkersRatherThanNamePrefixes() {
        try (Fixture fixture = new Fixture()) {
            ModelStepJournal journal = fixture.journal();
            completedRejection(fixture, "first");
            completedRejection(fixture, "second");
            completedTool(fixture, "framework_custom_business", "business", false, false);
            assertEquals(0, journal.consecutiveRejectedBatches());

            completedRejection(fixture, "third");
            completedTool(fixture, "plain_context", "context", true, false);
            completedTool(fixture, "plain_catalog", "catalog", false, true);
            assertEquals(1, journal.consecutiveRejectedBatches());
        }
    }

    private static void completedRejection(Fixture fixture, String modelStep) {
        StepId id = StepId.random();
        StepEvents.started(fixture.events, id, AgentStep.Kind.ORCHESTRATION,
                JsonNodeFactory.instance.objectNode()
                        .put("phase", "reject_unavailable_tool_batch")
                        .put("modelStepId", modelStep), null);
        StepEvents.completed(fixture.events, id, JsonNodeFactory.instance.objectNode(), null);
    }

    private static void completedTool(Fixture fixture, String name, String invocation,
            boolean trustedContextRead, boolean trustedToolCatalog) {
        StepId id = StepId.random();
        StepEvents.started(fixture.events, id, AgentStep.Kind.TOOL,
                JsonNodeFactory.instance.objectNode().put("tool", name)
                        .put("invocationId", invocation)
                        .put("trustedContextRead", trustedContextRead)
                        .put("trustedToolCatalog", trustedToolCatalog), null);
        var output = JsonNodeFactory.instance.objectNode().put("status", "SUCCEEDED");
        output.set("rawOutput", JsonNodeFactory.instance.objectNode());
        StepEvents.completed(fixture.events, id, output, null);
        fixture.events.emit("core.tool.receipt", 1, "framework.core",
                JsonNodeFactory.instance.objectNode().put("invocationId", invocation)
                        .put("status", "OBSERVED"));
    }

    @Test
    void pendingDecisionIsPersistedOnceAndReplayedWithoutReinvocation() {
        try (Fixture fixture = new Fixture()) {
            String arguments = "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                    + "\"evidenceRefs\":[],\"unmetCriterionIds\":[]}";
            fixture.completedModelStep(List.of(HarnessDecisionToolCallback.NAME), List.of(
                    new AssistantMessage.ToolCall("decision", "function",
                            HarnessDecisionToolCallback.NAME, arguments)));
            AtomicInteger invocations = new AtomicInteger();

            assertEquals(HarnessDecisionToolCallback.NAME, fixture.journal()
                    .recover(List.of(), gateway(invocations), false)
                    .finalResponse().getResult().getOutput().getToolCalls().getFirst().name());
            assertEquals(HarnessDecisionToolCallback.NAME, fixture.journal()
                    .recover(List.of(), gateway(invocations), false)
                    .finalResponse().getResult().getOutput().getToolCalls().getFirst().name());

            assertEquals(0, invocations.get());
            assertEquals(1, fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .filter(event -> event.type().equals("core.harness.decision_submitted"))
                    .count());
            assertEquals(1, new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && step.input().path("phase").asText("").equals("harness.decision"))
                    .count());
        }
    }

    @Test
    void invalidDecisionArgumentsReplayOneDurableRejectionAndNeverSubmit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(List.of(HarnessDecisionToolCallback.NAME), List.of(
                    new AssistantMessage.ToolCall("decision", "function",
                            HarnessDecisionToolCallback.NAME,
                            "{\"decision\":\"FINISHED\",\"userMessage\":\"done\","
                                    + "\"unmetCriterionIds\":[]}")));
            AtomicInteger invocations = new AtomicInteger();
            com.fasterxml.jackson.databind.JsonNode originalFeedback = null;

            for (int recovery = 0; recovery < 2; recovery++) {
                ModelStepJournal.Recovery result = fixture.journal().recover(
                        List.of(), gateway(invocations), false);
                assertTrue(result.finalResponse() != null);
                ToolResponseMessage feedback = (ToolResponseMessage) result.messages().getLast();
                var payload = fixture.json.readTree(
                        feedback.getResponses().getFirst().responseData());
                assertEquals("INVALID_DECISION_ARGUMENTS", payload.path("errorCode").asText());
                assertEquals("DECISION_SCHEMA_INVALID", payload.path("reasonCode").asText());
                assertTrue(payload.path("message").asText().contains("CLAIM_DONE"));
                assertTrue(payload.path("availableEvidenceRefs").isArray());
                assertTrue(payload.path("availableCriterionIds").isArray());
                if (originalFeedback == null) originalFeedback = payload;
                else assertEquals(originalFeedback, payload);
            }
            assertEquals(0, invocations.get());
            assertEquals(1, new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && "harness.decision_invalid".equals(
                                    step.input().path("phase").asText("")))
                    .count());
            assertEquals(0, fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .filter(event -> event.type().equals("core.harness.decision_submitted"))
                    .count());
        }
    }

    @Test
    void malformedDecisionJsonRetainsItsSpecificRejectionAfterRecovery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(List.of(HarnessDecisionToolCallback.NAME), List.of(
                    new AssistantMessage.ToolCall("decision", "function",
                            HarnessDecisionToolCallback.NAME, "{\"decision\":")));
            AtomicInteger invocations = new AtomicInteger();
            var first = fixture.journal().recover(List.of(), gateway(invocations), false);
            var response = (ToolResponseMessage) first.messages().getLast();
            var original = fixture.json.readTree(response.getResponses().getFirst().responseData());
            assertEquals("INVALID_DECISION_ARGUMENTS", original.path("errorCode").asText());
            assertEquals("DECISION_JSON_INVALID", original.path("reasonCode").asText());
            assertTrue(original.path("message").asText().contains("JSON object"));

            var second = fixture.journal().recover(List.of(), gateway(invocations), false);
            var replayed = (ToolResponseMessage) second.messages().getLast();
            assertEquals(original, fixture.json.readTree(
                    replayed.getResponses().getFirst().responseData()));
            assertEquals(0, invocations.get());
            assertEquals(1, new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && "harness.decision_invalid".equals(step.input().path("phase").asText()))
                    .count());
            assertTrue(fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .noneMatch(event -> event.type().equals("core.harness.decision_submitted")));
        }
    }

    @Test
    void recoveryRejectsMixedDecisionBatchBeforeAnyBusinessCall() {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(List.of(HarnessDecisionToolCallback.NAME, "visible"), List.of(
                    new AssistantMessage.ToolCall("first", "function", "visible", "{}"),
                    new AssistantMessage.ToolCall("decision", "function",
                            HarnessDecisionToolCallback.NAME,
                            "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                                    + "\"unmetCriterionIds\":[]}")));
            AtomicInteger invocations = new AtomicInteger();

            assertThrows(ToolRecoveryRequiredException.class, () -> fixture.journal().recover(
                    List.of(tool("visible")), gateway(invocations), false));
            assertEquals(0, invocations.get());
            assertEquals(0, fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .filter(event -> event.type().equals("core.harness.decision_submitted"))
                    .count());
        }
    }

    @Test
    void trustedProtocolRepairRecoversMixedBatchWithoutExecutingBusinessCall() {
        try (Fixture fixture = new Fixture()) {
            StepId model = fixture.completedModelStep(
                    List.of(HarnessDecisionToolCallback.NAME, "visible"), List.of(
                            new AssistantMessage.ToolCall("first", "function", "visible", "{}"),
                            new AssistantMessage.ToolCall("decision", "function",
                                    HarnessDecisionToolCallback.NAME,
                                    "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                                            + "\"unmetCriterionIds\":[]}")));
            protocolRepair(fixture, model);
            AtomicInteger invocations = new AtomicInteger();

            for (int recovery = 0; recovery < 2; recovery++) {
                var restored = fixture.journal().recover(
                        List.of(tool("visible")), gateway(invocations), false);
                assertEquals(null, restored.finalResponse());
                assertTrue(restored.messages().getLast() instanceof UserMessage);
                assertEquals("Submit one valid control decision", restored.messages().getLast().getText());
            }
            assertEquals(0, invocations.get());
            assertEquals(0, new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.TOOL).count());
        }
    }

    @Test
    void trustedProtocolRepairRecoversDuplicateControlCallsWithoutToolResponses() {
        try (Fixture fixture = new Fixture()) {
            StepId model = fixture.completedModelStep(List.of(HarnessDecisionToolCallback.NAME),
                    List.of(new AssistantMessage.ToolCall("same", "function",
                                    HarnessDecisionToolCallback.NAME, "{}"),
                            new AssistantMessage.ToolCall("same", "function",
                                    HarnessDecisionToolCallback.NAME, "{}")));
            protocolRepair(fixture, model);

            var restored = fixture.journal().recover(
                    List.of(), gateway(new AtomicInteger()), false);
            assertTrue(restored.messages().getLast() instanceof UserMessage);
            assertEquals(0, restored.messages().stream()
                    .filter(ToolResponseMessage.class::isInstance).count());
        }
    }

    @Test
    void protocolRepairCannotHideAnAlreadyStartedToolStep() {
        try (Fixture fixture = new Fixture()) {
            StepId model = fixture.completedModelStep(
                    List.of(HarnessDecisionToolCallback.NAME, "visible"), List.of(
                            new AssistantMessage.ToolCall("first", "function", "visible", "{}"),
                            new AssistantMessage.ToolCall("decision", "function",
                                    HarnessDecisionToolCallback.NAME, "{}")));
            completedTool(fixture, "visible", "already-started", false, false);
            protocolRepair(fixture, model);

            assertThrows(ToolRecoveryRequiredException.class, () -> fixture.journal().recover(
                    List.of(tool("visible")), gateway(new AtomicInteger()), false));
        }
    }

    private static void protocolRepair(Fixture fixture, StepId model) {
        fixture.events.emit("core.harness.protocol_repair_requested", 1,
                "framework.springai", JsonNodeFactory.instance.objectNode()
                        .put("modelStepId", model.value())
                        .put("code", "MODEL_DECISION_MIXED_BATCH")
                        .put("attempt", 1)
                        .put("feedback", "Submit one valid control decision"));
    }

    @Test
    void decisionCannotCiteAnUnrelatedOrInventedReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(List.of(HarnessDecisionToolCallback.NAME), List.of(
                    new AssistantMessage.ToolCall("decision", "function",
                            HarnessDecisionToolCallback.NAME,
                            "{\"decision\":\"CLAIM_DONE\",\"userMessage\":\"done\","
                                    + "\"evidenceRefs\":[\"other-run:receipt\"],"
                                    + "\"unmetCriterionIds\":[]}")));
            AtomicInteger invocations = new AtomicInteger();
            var recovered = fixture.journal().recover(List.of(), gateway(invocations), false);
            var response = (ToolResponseMessage) recovered.messages().getLast();
            var originalFeedback = fixture.json.readTree(
                    response.getResponses().getFirst().responseData());
            assertEquals("INVALID_DECISION_ARGUMENTS", originalFeedback.path("errorCode").asText());
            assertEquals("UNKNOWN_EVIDENCE_REFERENCE", originalFeedback.path("reasonCode").asText());
            assertTrue(originalFeedback.path("message").asText().contains("evidenceRefs"));
            assertEquals(fixture.json.createArrayNode(), originalFeedback.path("availableEvidenceRefs"));
            assertEquals(fixture.json.createArrayNode(), originalFeedback.path("availableCriterionIds"));

            // Later receipts do not change a rejected decision or its original recovery guidance.
            fixture.events.emit("core.tool.receipt", 1, "framework.core",
                    fixture.json.createObjectNode().put("invocationId", "later-observation")
                            .put("status", "OBSERVED").put("evidenceRef", "later-trusted-receipt"));
            var replayed = fixture.journal().recover(List.of(), gateway(invocations), false);
            var replayedResponse = (ToolResponseMessage) replayed.messages().getLast();
            assertEquals(originalFeedback, fixture.json.readTree(
                    replayedResponse.getResponses().getFirst().responseData()));
            assertEquals(0, invocations.get());
            assertEquals(1, new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(step -> step.kind() == AgentStep.Kind.ORCHESTRATION
                            && "harness.decision_invalid".equals(
                                    step.input().path("phase").asText("")))
                    .count());
            assertEquals(0, fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .filter(event -> event.type().equals("core.harness.decision_submitted"))
                    .count());
        }
    }

    @Test
    void unknownUnmetCriterionIdGetsDurableProtocolFeedback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var contract = new TaskContractV3(3, "inspect", List.of(
                    new TaskCriterionV3("known", "Known condition", "file.read",
                            CapabilityMetadata.TargetKind.FILE, "result.txt",
                            EffectReceiptV1.Status.OBSERVED, "")),
                    true, true, "definition");
            fixture.events.emit("core.task.contract", 3, "framework.core",
                    fixture.json.valueToTree(contract));
            fixture.completedModelStep(List.of(HarnessDecisionToolCallback.NAME), List.of(
                    new AssistantMessage.ToolCall("decision", "function",
                            HarnessDecisionToolCallback.NAME,
                            "{\"decision\":\"CONTINUE\",\"userMessage\":\"working\","
                                    + "\"unmetCriterionIds\":[\"invented\"]}")));

            var recovered = fixture.journal().recover(
                    List.of(), gateway(new AtomicInteger()), false);
            var response = (ToolResponseMessage) recovered.messages().getLast();
            var feedback = fixture.json.readTree(response.getResponses().getFirst().responseData());
            assertEquals("INVALID_DECISION_ARGUMENTS", feedback.path("errorCode").asText());
            assertEquals("UNKNOWN_CRITERION_ID", feedback.path("reasonCode").asText());
            assertEquals(List.of("known"), fixture.json.convertValue(
                    feedback.path("availableCriterionIds"), List.class));
            assertTrue(feedback.path("message").asText().contains("unmetCriterionIds"));
            assertEquals(0, fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .filter(event -> event.type().equals("core.harness.decision_submitted"))
                    .count());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void durableCompletionRepairReconstructsMarkedFeedbackForLegacyAndCurrentEvents(int schema) {
        try (Fixture fixture = new Fixture()) {
            StepId model = fixture.completedModelStep(List.of(), List.of());
            fixture.events.emit("core.task.repair_requested", schema, "framework.springai",
                    fixture.json.createObjectNode().put("modelStepId", model.value())
                            .put("feedback", "缺少证据的条件：显示预览页面"));
            AtomicInteger invocations = new AtomicInteger();

            ModelStepJournal.Recovery recovered = fixture.journal().recover(
                    List.of(), gateway(invocations), false);

            assertEquals(null, recovered.finalResponse());
            assertTrue(TaskRepairContext.isRepair(recovered.messages().getLast()));
            assertEquals("缺少证据的条件：显示预览页面",
                    TaskRepairContext.latest(recovered.messages()).getText());
            assertEquals(0, invocations.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void earlierUnavailableFeedbackVersionsRemainRecoverableWithoutExecutingCalls(boolean withOfferedTools) {
        try (Fixture fixture = new Fixture()) {
            var call = new AssistantMessage.ToolCall("missing", "function", "hidden", "{}");
            StepId model = fixture.completedModelStep(List.of("visible"), List.of(call));
            var fingerprintInput = fixture.json.createObjectNode()
                    .put("id", call.id()).put("name", call.name()).put("arguments", call.arguments());
            var input = fixture.json.createObjectNode()
                    .put("phase", "reject_unavailable_tool_batch")
                    .put("modelStepId", model.value()).put("callId", call.id())
                    .put("toolName", call.name()).put("callFingerprint", ToolInvocationFingerprint.create(
                            "reject_unavailable_tool_batch", fingerprintInput));
            var feedback = fixture.json.createObjectNode()
                    .put("error", "tool_not_offered").put("tool", call.name());
            if (withOfferedTools) {
                feedback.put("executed", false)
                        .put("message", "At least one tool in this batch was not offered for this step. "
                                + "No calls in the batch were executed. Only the tools in offeredTools "
                                + "were offered in the failed step. In the next step, use only tools "
                                + "offered in that step's provider prompt. Call framework_tool_catalog "
                                + "to activate another tool only if framework_tool_catalog is offered "
                                + "in that step. This does not mean the tool or desktop capability is "
                                + "unavailable. Do not claim a permission or platform failure without "
                                + "a corresponding tool result.");
                feedback.putArray("offeredTools").add("visible");
            } else {
                feedback.put("message", "At least one tool in this batch was not offered for this step. "
                        + "No calls in the batch were executed. This does not mean the tool or "
                        + "desktop capability is unavailable: select or activate the authorized "
                        + "tool for the next step. Do not claim a permission or platform failure "
                        + "without a corresponding tool result.");
            }
            StepId rejected = StepId.tool(fixture.runId, "model/" + model.value() + "/" + call.id());
            StepEvents.started(fixture.events, rejected, AgentStep.Kind.ORCHESTRATION, input, model.value());
            StepEvents.completed(fixture.events, rejected, fixture.json.createObjectNode()
                    .put("rejectedUnavailableToolBatch", true).set("modelOutput", feedback), null);
            AtomicInteger invocations = new AtomicInteger();

            ModelStepJournal.Recovery recovery = fixture.journal().recover(
                    List.of(tool("visible"), tool("hidden")), gateway(invocations), false);

            ToolResponseMessage response = (ToolResponseMessage) recovery.messages().getLast();
            assertEquals(feedback.toString(), response.getResponses().getFirst().responseData());
            assertEquals(0, invocations.get());
        }
    }

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

    @Test
    void legacyDesktopBatchIsDurablyRejectedAndReplannedWithoutReplayingInput() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.completedModelStep(List.of("desktop_session_click", "visible"), List.of(
                    new AssistantMessage.ToolCall("click", "function", "desktop_session_click",
                            "{\"sessionId\":\"editor-session\",\"generation\":1,\"x\":12,\"y\":20}"),
                    new AssistantMessage.ToolCall("other", "function", "visible", "{}")));
            AtomicInteger invocations = new AtomicInteger();

            for (int resume = 0; resume < 2; resume++) {
                ModelStepJournal.Recovery recovery = fixture.journal().recover(
                        List.of(tool("desktop_session_click"), tool("visible")),
                        gateway(invocations), false);
                ToolResponseMessage response = (ToolResponseMessage) recovery.messages().getLast();
                assertEquals(2, response.getResponses().size());
                for (var item : response.getResponses()) {
                    var feedback = fixture.json.readTree(item.responseData());
                    assertEquals("legacy_desktop_observation_required",
                            feedback.path("error").asText());
                    assertEquals(false, feedback.path("executed").asBoolean(true));
                }
            }
            assertEquals(0, invocations.get());
            assertEquals(2, fixture.store.eventsAfter(fixture.runId, 0).stream()
                    .filter(event -> event.type().equals("core.step.started")
                            && event.payload().path("kind").asText()
                                    .equals("ORCHESTRATION"))
                    .count());
            assertTrue(new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .anyMatch(step -> step.input() != null
                            && step.input().path("phase").asText()
                                    .equals("legacy_desktop_reobserve")
                            && step.input().path("sessionId").asText()
                                    .equals("editor-session")));
        }
    }

    @Test
    void startedLegacyDesktopActionRequiresReconciliationAndIsNeverRepeated() {
        try (Fixture fixture = new Fixture()) {
            StepId model = fixture.completedModelStep(List.of("desktop_session_click"), List.of(
                    new AssistantMessage.ToolCall("click", "function", "desktop_session_click",
                            "{\"sessionId\":\"editor-session\",\"generation\":1}")));
            String invocation = "model/" + model.value() + "/click";
            StepEvents.started(fixture.events, StepId.tool(fixture.runId, invocation),
                    AgentStep.Kind.TOOL, JsonNodeFactory.instance.objectNode()
                            .put("tool", "desktop_session_click")
                            .put("invocationId", invocation), model.value());
            AtomicInteger invocations = new AtomicInteger();

            assertThrows(ToolRecoveryRequiredException.class, () -> fixture.journal().recover(
                    List.of(tool("desktop_session_click")), gateway(invocations), false));
            assertEquals(0, invocations.get());
        }
    }

    @Test
    void completedSiblingToolIsReplayedWhilePendingLegacyClickIsRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            StepId model = fixture.completedModelStep(List.of("visible", "desktop_session_click"),
                    List.of(new AssistantMessage.ToolCall("done", "function", "visible", "{}"),
                            new AssistantMessage.ToolCall("click", "function",
                                    "desktop_session_click",
                                    "{\"sessionId\":\"editor-session\",\"generation\":1}")));
            String invocation = "model/" + model.value() + "/done";
            StepId toolId = StepId.tool(fixture.runId, invocation);
            StepEvents.started(fixture.events, toolId, AgentStep.Kind.TOOL,
                    JsonNodeFactory.instance.objectNode().put("tool", "visible")
                            .put("invocationId", invocation), model.value());
            StepEvents.completed(fixture.events, toolId,
                    JsonNodeFactory.instance.objectNode().set("modelOutput",
                            JsonNodeFactory.instance.objectNode().put("alreadyDone", true)), null);
            AtomicInteger invocations = new AtomicInteger();

            ModelStepJournal.Recovery recovered = fixture.journal().recover(
                    List.of(tool("visible"), tool("desktop_session_click")),
                    gateway(invocations), false);

            ToolResponseMessage response = (ToolResponseMessage) recovered.messages().getLast();
            assertEquals(true, fixture.json.readTree(response.getResponses().get(0)
                    .responseData()).path("alreadyDone").asBoolean());
            assertEquals("legacy_desktop_observation_required", fixture.json.readTree(
                    response.getResponses().get(1).responseData()).path("error").asText());
            assertEquals(0, invocations.get());
            assertEquals(1, new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(step -> step.input() != null
                            && step.input().path("phase").asText()
                                    .equals("legacy_desktop_reobserve"))
                    .count());
        }
    }

    @Test
    void completedLegacyClickIsOnlyReplayedAsHistory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            StepId model = fixture.completedModelStep(List.of("desktop_session_click"), List.of(
                    new AssistantMessage.ToolCall("click", "function", "desktop_session_click",
                            "{\"sessionId\":\"editor-session\",\"generation\":1}")));
            String invocation = "model/" + model.value() + "/click";
            StepId toolId = StepId.tool(fixture.runId, invocation);
            StepEvents.started(fixture.events, toolId, AgentStep.Kind.TOOL,
                    JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_click")
                            .put("invocationId", invocation), model.value());
            StepEvents.completed(fixture.events, toolId,
                    JsonNodeFactory.instance.objectNode().set("modelOutput",
                            JsonNodeFactory.instance.textNode(
                                    "[desktop_session_click][成功] previous action")), null);
            AtomicInteger invocations = new AtomicInteger();

            ModelStepJournal.Recovery recovered = fixture.journal().recover(
                    List.of(tool("desktop_session_click")), gateway(invocations), false);

            ToolResponseMessage response = (ToolResponseMessage) recovered.messages().getLast();
            assertTrue(response.getResponses().getFirst().responseData()
                    .contains("previous action"));
            assertEquals(0, invocations.get());
            assertEquals(0, new RunStepQuery(fixture.store).steps(fixture.runId).stream()
                    .filter(step -> step.input() != null
                            && step.input().path("phase").asText()
                                    .equals("legacy_desktop_reobserve"))
                    .count());
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

        private StepId completedModelStep(
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
            return step;
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
