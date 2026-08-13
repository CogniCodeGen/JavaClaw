package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentDefinitionDraft;
import com.javaclaw.framework.api.BudgetExceededException;
import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunProfileDraft;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.RunState;
import com.javaclaw.framework.extension.ExtensionArtifact;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.AgentFrameworkExtension;
import com.javaclaw.framework.spi.ExtensionContext;
import com.javaclaw.framework.spi.ExtensionDescriptor;
import com.javaclaw.framework.spi.EventCodec;
import com.javaclaw.framework.spi.EventTypeDescriptor;
import com.javaclaw.framework.spi.ExtensionRegistrar;
import com.javaclaw.framework.spi.ExtensionScope;
import com.javaclaw.framework.spi.HotUpdateCompatibility;
import com.javaclaw.framework.spi.SemanticVersion;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;
import com.javaclaw.framework.store.JdbcExecutionPlanStore;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEngineIntegrationTest {

    @Test
    void runEventsOutboxIdempotencyAndTerminalArbitrationShareOneStateMachine()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request -> {
                 request.events().emit("core.reasoning.observed", 1, "test",
                         JsonNodeFactory.instance.objectNode().put("ok", true));
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode().put("text", "done")));
             })) {
            RunRequest request = fixture.request("same-command");
            var first = engine.start(request);
            var outcome = first.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(RunState.COMPLETED, outcome.state());
            assertEquals("done", outcome.output().path("text").asText());

            var duplicate = engine.start(request);
            assertEquals(first.id(), duplicate.id());
            assertEquals(RunState.COMPLETED,
                    duplicate.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());

            List<String> eventTypes = fixture.runs.eventsAfter(first.id(), 0).stream()
                    .map(event -> event.type()).toList();
            assertEquals(List.of("core.run.created", "core.run.started",
                    "core.reasoning.observed", "core.run.completed"), eventTypes);
            assertEquals(1, eventTypes.stream().filter("core.run.completed"::equals).count());
            assertEquals(eventTypes.size(), fixture.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_run_outbox WHERE run_id = ?",
                    Integer.class, first.id().value()));
            assertFalse(engine.cancel(first.id(), new CancelReason("LATE", "already terminal")));
        }
    }

    @Test
    void pausedRunRestoresItsPersistedPlanAndResumesAfterKernelRestart() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        try (ExtensionManager extensions = fixture.extensionManager()) {
            ReasoningGateway resumable = request -> CompletableFuture.completedFuture(
                    request.resumeCommand() == null
                            ? ReasoningResult.waitingForInput(
                            JsonNodeFactory.instance.objectNode().put("question", "continue?"), "input")
                            : ReasoningResult.completed(
                            JsonNodeFactory.instance.objectNode().put("text", "resumed")));
            var firstEngine = fixture.engine(extensions, resumable);
            var handle = firstEngine.start(fixture.request(null));
            assertEquals(RunState.WAITING_INPUT, fixture.runs.find(handle.id())
                    .orElseThrow().snapshot().state());
            firstEngine.close();
            assertEquals(RunState.PAUSED, fixture.runs.find(handle.id())
                    .orElseThrow().snapshot().state());

            try (AgentEngine restored = fixture.engine(extensions, resumable)) {
                assertEquals(1, restored.activeRunCount());
                var resumed = restored.resume(handle.id(),
                        new ResumeCommand("user.input",
                                JsonNodeFactory.instance.objectNode().put("text", "yes")));
                assertEquals(RunState.COMPLETED,
                        resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                assertEquals("resumed", restored.get(handle.id()).output().path("text").asText());
            }
        }
    }

    @Test
    void approvalEventsUseCanonicalContractAndFingerprintControlsResume() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "sys_file_delete",
                JsonNodeFactory.instance.objectNode().put("path", "/tmp/test"),
                "fingerprint", "DOUBLE_CONFIRM", "delete file");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request ->
                     CompletableFuture.completedFuture(request.resumeCommand() == null
                             ? ReasoningResult.waitingForApproval(
                             challenge.toJson(), "approval required")
                             : ReasoningResult.completed(
                             JsonNodeFactory.instance.objectNode().put("text", "approved"))))) {
            var handle = engine.start(fixture.request(null));
            assertEquals(RunState.WAITING_APPROVAL, engine.get(handle.id()).state());
            var waiting = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.waiting_approval"))
                    .findFirst().orElseThrow();
            assertEquals("approval required", waiting.payload().path("reason").asText());
            assertEquals("sys_file_delete",
                    waiting.payload().path("approval").path("tool").asText());
            assertEquals("fingerprint",
                    waiting.payload().path("approval").path("fingerprint").asText());

            ObjectNode command = JsonNodeFactory.instance.objectNode();
            command.put("approved", true);
            command.put("fingerprint", "fingerprint");
            var resumed = engine.resume(handle.id(),
                    new ResumeCommand("tool.approval", command));
            assertEquals(RunState.COMPLETED,
                    resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void deniedApprovalCancelsInsteadOfRechallenging() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode(),
                "fingerprint", "CONFIRM", "send email");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request ->
                     CompletableFuture.completedFuture(
                             ReasoningResult.waitingForApproval(
                                     challenge.toJson(), "approval required")))) {
            var handle = engine.start(fixture.request(null));
            ObjectNode command = JsonNodeFactory.instance.objectNode();
            command.put("approved", false);
            command.put("fingerprint", "fingerprint");

            var denied = engine.resume(handle.id(),
                    new ResumeCommand("tool.approval", command));

            assertEquals(RunState.CANCELLED,
                    denied.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            assertEquals(1, fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.waiting_approval")).count());
        }
    }

    @Test
    void replayDoesNotRestoreAnApprovalThatWasAlreadyUsed() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode().put("to", "a@example.com"),
                "used-fingerprint", "CONFIRM", "send email");
        AtomicInteger resumedTurns = new AtomicInteger();
        ReasoningGateway reasoning = request -> {
            if (request.resumeCommand() == null) {
                return CompletableFuture.completedFuture(
                        ReasoningResult.waitingForApproval(challenge.toJson(), "approval"));
            }
            if (resumedTurns.getAndIncrement() == 0) {
                assertNotNull(request.approvedToolInvocation());
                ObjectNode started = JsonNodeFactory.instance.objectNode();
                started.put("tool", challenge.tool());
                started.put("fingerprint", challenge.fingerprint());
                started.put("invocationId", "used-call");
                started.set("arguments", challenge.arguments());
                request.events().emit("core.tool.started", 1, "test", started);
                return CompletableFuture.completedFuture(ReasoningResult.waitingForInput(
                        JsonNodeFactory.instance.objectNode().put("question", "next?"), "input"));
            }
            return CompletableFuture.completedFuture(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "continued")));
        };

        try (ExtensionManager extensions = fixture.extensionManager()) {
            AgentEngine first = fixture.engine(extensions, reasoning);
            var handle = first.start(fixture.request(null));
            ObjectNode approval = JsonNodeFactory.instance.objectNode();
            approval.put("approved", true);
            approval.put("fingerprint", challenge.fingerprint());
            first.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            assertEquals(RunState.WAITING_INPUT, first.get(handle.id()).state());
            first.close();

            try (AgentEngine restored = fixture.engine(extensions, reasoning)) {
                assertThrows(IllegalArgumentException.class, () -> restored.resume(
                        handle.id(), new ResumeCommand("tool.approval", approval)));
                var resumed = restored.resume(handle.id(), new ResumeCommand(
                        "user.input", JsonNodeFactory.instance.objectNode().put("text", "yes")));
                assertEquals(RunState.COMPLETED,
                        resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void durableApprovalWithoutToolStartSurvivesKernelRestartExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "sys_file_delete", JsonNodeFactory.instance.objectNode().put("path", "/tmp/test"),
                "pending-fingerprint", "DOUBLE_CONFIRM", "delete file");
        CompletableFuture<ReasoningResult> interruptedTurn = new CompletableFuture<>();
        ReasoningGateway firstReasoning = request -> request.resumeCommand() == null
                ? CompletableFuture.completedFuture(
                        ReasoningResult.waitingForApproval(challenge.toJson(), "approval"))
                : interruptedTurn;

        try (ExtensionManager extensions = fixture.extensionManager()) {
            AgentEngine first = fixture.engine(extensions, firstReasoning);
            var handle = first.start(fixture.request(null));
            ObjectNode approval = JsonNodeFactory.instance.objectNode();
            approval.put("approved", true);
            approval.put("fingerprint", challenge.fingerprint());
            first.resume(handle.id(), new ResumeCommand("tool.approval", approval));
            assertEquals(RunState.RUNNING, first.get(handle.id()).state());
            first.close();
            interruptedTurn.complete(ReasoningResult.completed(
                    JsonNodeFactory.instance.objectNode().put("text", "detached")));

            ReasoningGateway restoredReasoning = request -> {
                assertNotNull(request.approvedToolInvocation());
                assertEquals(challenge.fingerprint(), request.approvedToolInvocation()
                        .challenge().fingerprint());
                return CompletableFuture.completedFuture(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "resumed")));
            };
            try (AgentEngine restored = fixture.engine(extensions, restoredReasoning)) {
                var resumed = restored.resume(handle.id(), new ResumeCommand(
                        "user.input", JsonNodeFactory.instance.objectNode()));
                assertEquals(RunState.COMPLETED,
                        resumed.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
            }
        }
    }

    @Test
    void waitingApprovalRejectsAnUnrelatedResumeCommand() {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode(),
                "fingerprint", "CONFIRM", "send email");
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request ->
                     CompletableFuture.completedFuture(ReasoningResult.waitingForApproval(
                             challenge.toJson(), "approval")))) {
            var handle = engine.start(fixture.request(null));
            assertThrows(IllegalArgumentException.class, () -> engine.resume(
                    handle.id(), new ResumeCommand("user.input",
                            JsonNodeFactory.instance.objectNode().put("text", "bypass"))));
            assertEquals(RunState.WAITING_APPROVAL, engine.get(handle.id()).state());
        }
    }

    @Test
    void immediateApprovalFromTheWaitingEventQueuesAfterTheCurrentTurn() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        var challenge = new com.javaclaw.framework.api.ToolApprovalChallenge(
                "email_send", JsonNodeFactory.instance.objectNode(),
                "fast-fingerprint", "CONFIRM", "send email");
        CompletableFuture<ReasoningResult> firstTurn = new CompletableFuture<>();
        ReasoningGateway reasoning = request -> request.resumeCommand() == null
                ? firstTurn
                : CompletableFuture.completedFuture(ReasoningResult.completed(
                JsonNodeFactory.instance.objectNode().put("text", "approved")));
        AtomicReference<Throwable> resumeFailure = new AtomicReference<>();
        try (ExtensionManager extensions = fixture.extensionManager();
             ExecutorService executor = Executors.newSingleThreadExecutor();
             AgentEngine engine = fixture.engine(extensions, reasoning, executor)) {
            var handle = engine.start(fixture.request(null));
            var events = handle.events(0)
                    .filter(event -> event.type().equals("core.run.waiting_approval"))
                    .take(1)
                    .subscribe(event -> {
                        try {
                            ObjectNode approval = JsonNodeFactory.instance.objectNode();
                            approval.put("approved", true);
                            approval.put("fingerprint", challenge.fingerprint());
                            engine.resume(handle.id(), new ResumeCommand(
                                    "tool.approval", approval));
                        } catch (Throwable failure) {
                            resumeFailure.set(failure);
                        }
                    });
            try {
                firstTurn.complete(ReasoningResult.waitingForApproval(
                        challenge.toJson(), "approval"));

                assertEquals(RunState.COMPLETED,
                        handle.completion().toCompletableFuture()
                                .get(2, TimeUnit.SECONDS).state());
                assertNull(resumeFailure.get());
            } finally {
                events.dispose();
            }
        }
    }

    @Test
    void cancellationRetainsExactExtensionPlanUntilInFlightReasoningExits() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of("test.lifecycle", "=1.0.0"));
        AtomicInteger v1Stops = new AtomicInteger();
        LifecycleExtension v1 = new LifecycleExtension("1.0.0", v1Stops);
        LifecycleExtension v2 = new LifecycleExtension("2.0.0", new AtomicInteger());
        CompletableFuture<ReasoningResult> pending = new CompletableFuture<>();
        try (ExtensionManager extensions = fixture.extensionManager()) {
            extensions.publish(List.of(ExtensionArtifact.builtin(v1)));
            try (AgentEngine engine = fixture.engine(extensions, request -> pending)) {
                var handle = engine.start(fixture.request(null));
                extensions.publish(List.of(ExtensionArtifact.builtin(v2)));

                assertTrue(engine.cancel(handle.id(),
                        new CancelReason("USER_CANCELLED", "stop")));
                assertEquals(RunState.CANCELLED,
                        handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                assertEquals(0, v1Stops.get(),
                        "cancel must not unload code that is still on the execution stack");

                pending.complete(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "late")));
                assertEquals(1, v1Stops.get(),
                        "the retired generation is released after reasoning unwinds");
            }
        }
    }

    @Test
    void recoveryBlocksExplicitlyWhenLockedExtensionArtifactIsMissing() {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of("test.recovery", "1.0.0"));
        var installed = fixture.extensionManager();
        installed.publish(List.of(ExtensionArtifact.builtin(new RecoveryExtension())));
        ReasoningGateway waiting = request -> CompletableFuture.completedFuture(
                ReasoningResult.waitingForInput(JsonNodeFactory.instance.objectNode(), "input"));
        var firstEngine = fixture.engine(installed, waiting);
        var handle = firstEngine.start(fixture.request(null));
        firstEngine.close();
        installed.close();

        try (ExtensionManager empty = fixture.extensionManager();
             AgentEngine restored = fixture.engine(empty, waiting)) {
            assertEquals(RunState.RECOVERY_BLOCKED_MISSING_EXTENSION,
                    restored.get(handle.id()).state());
            assertTrue(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("core.run.recovery_blocked")
                            && event.payload().path("code").asText()
                            .equals("MISSING_LOCKED_EXTENSION")));
            assertTrue(restored.cancel(handle.id(),
                    new CancelReason("ABANDON_RECOVERY", "artifact intentionally removed")));
        }
    }

    @Test
    void exactExecutionPlanCodecAndSchemaOwnEveryExtensionEvent() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of("test.events", "=1.0.0"));
        try (ExtensionManager extensions = fixture.extensionManager()) {
            extensions.publish(List.of(ExtensionArtifact.builtin(new EventExtension())));
            try (AgentEngine engine = fixture.engine(extensions, request -> {
                request.events().emit("test.events.observed", 1, "test.events",
                        JsonNodeFactory.instance.objectNode().put("value", "normalized"));
                return CompletableFuture.completedFuture(ReasoningResult.completed(
                        JsonNodeFactory.instance.objectNode().put("text", "done")));
            })) {
                var handle = engine.start(fixture.request(null));
                assertEquals(RunState.COMPLETED,
                        handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).state());
                var observed = fixture.runs.eventsAfter(handle.id(), 0).stream()
                        .filter(event -> event.type().equals("test.events.observed"))
                        .findFirst().orElseThrow();
                assertEquals("normalized", observed.payload().path("value").asText());
                assertEquals(1, observed.schemaVersion());
            }
        }
    }

    @Test
    void unregisteredExtensionEventFailsRunInsteadOfPoisoningSharedEventStream()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions, request -> {
                 request.events().emit("test.unregistered.event", 1, "test",
                         JsonNodeFactory.instance.objectNode());
                 return CompletableFuture.completedFuture(ReasoningResult.completed(
                         JsonNodeFactory.instance.objectNode()));
             })) {
            var handle = engine.start(fixture.request(null));
            var outcome = handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(RunState.FAILED, outcome.state());
            assertTrue(outcome.error().contains("event type is not registered"));
            assertFalse(fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .anyMatch(event -> event.type().equals("test.unregistered.event")));
        }
    }

    @Test
    void budgetFailureEventIncludesTheExceededDimensionAndUsage() throws Exception {
        Fixture fixture = new Fixture();
        fixture.publishDefinition(Map.of());
        BudgetExceededException failure = BudgetExceededException.modelInputTokens(
                255_392, 250_000);
        try (ExtensionManager extensions = fixture.extensionManager();
             AgentEngine engine = fixture.engine(extensions,
                     request -> CompletableFuture.failedFuture(failure))) {
            var handle = engine.start(fixture.request(null));
            var outcome = handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(RunState.FAILED, outcome.state());
            var failed = fixture.runs.eventsAfter(handle.id(), 0).stream()
                    .filter(event -> event.type().equals("core.run.failed"))
                    .findFirst().orElseThrow();
            assertEquals(BudgetExceededException.class.getName(),
                    failed.payload().path("errorType").asText());
            assertEquals("MODEL_INPUT_TOKENS",
                    failed.payload().path("budgetKind").asText());
            assertEquals("255392", failed.payload().path("budgetActual").asText());
            assertEquals("250000", failed.payload().path("budgetLimit").asText());
        }
    }

    private static final class Fixture {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final Clock clock = Clock.systemUTC();
        private final JdbcTemplate jdbc;
        private final DataSourceTransactionManager transactions;
        private final JdbcAgentDefinitionStore definitions;
        private final JdbcRunStore runs;
        private final JdbcExecutionPlanStore plans;

        private Fixture() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:engine-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(dataSource).initialize();
            jdbc = new JdbcTemplate(dataSource);
            transactions = new DataSourceTransactionManager(dataSource);
            definitions = new JdbcAgentDefinitionStore(jdbc, transactions, json, clock);
            runs = new JdbcRunStore(jdbc, transactions, json, clock);
            plans = new JdbcExecutionPlanStore(jdbc, json, clock);
        }

        private ExtensionManager extensionManager() {
            return new ExtensionManager(new ExtensionContext(
                    clock, Runnable::run,
                    request -> CompletableFuture.failedFuture(
                            new AssertionError("model task not expected"))));
        }

        private void publishDefinition(Map<String, String> extensionRanges) {
            AgentDefinitionDraft agent = new AgentDefinitionDraft(
                    "test.agent", "Test Agent", "test:model", Map.of("system", "test"),
                    Map.of(), JsonNodeFactory.instance.objectNode(),
                    JsonNodeFactory.instance.objectNode(), RunBudget.UNBOUNDED,
                    JsonNodeFactory.instance.objectNode(), extensionRanges);
            definitions.saveAgentDraft("workspace", agent, false);
            definitions.publishAgent("workspace", agent.id());
            RunProfileDraft profile = new RunProfileDraft(
                    "test.profile", "Test Profile", PermissionSet.UNRESTRICTED,
                    RunBudget.UNBOUNDED, Map.of(), JsonNodeFactory.instance.objectNode());
            definitions.saveProfileDraft("workspace", profile, false);
            definitions.publishProfile("workspace", profile.id());
        }

        private RunRequest request(String idempotencyKey) {
            return RunRequest.builder()
                    .agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile"))
                    .source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session"))
                    .input(InputBlock.text("hello"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED)
                    .budget(RunBudget.UNBOUNDED)
                    .idempotencyKey(idempotencyKey)
                    .build();
        }

        private AgentEngine engine(ExtensionManager extensions, ReasoningGateway reasoning) {
            return engine(extensions, reasoning, Runnable::run);
        }

        private AgentEngine engine(
                ExtensionManager extensions, ReasoningGateway reasoning, Executor executor) {
            return new AgentEngine(new AgentCompiler(definitions, extensions, json), runs, plans,
                    reasoning, executor, json, clock, new RunUsageLedger());
        }
    }

    private static final class RecoveryExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.recovery", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        @Override public ExtensionDescriptor descriptor() { return descriptor; }
        @Override public void register(ExtensionRegistrar registrar) { }
    }

    private static final class EventExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor = new ExtensionDescriptor(
                "test.events", SemanticVersion.parse("1.0.0"), ">=2.0.0 <3.0.0",
                ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());

        @Override public ExtensionDescriptor descriptor() { return descriptor; }

        @Override
        public void register(ExtensionRegistrar registrar) {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            schema.put("type", "object");
            schema.putObject("properties").putObject("value").put("type", "string");
            schema.putArray("required").add("value");
            schema.put("additionalProperties", false);
            registrar.eventType(new EventTypeDescriptor(
                    "test.events.observed", 1, schema), new EventCodec<EventPayload>() {
                @Override
                public JsonNode encode(EventPayload payload) {
                    return JsonNodeFactory.instance.objectNode().put("value", payload.value());
                }

                @Override
                public EventPayload decode(JsonNode payload) {
                    return new EventPayload(payload.path("value").asText());
                }
            });
        }
    }

    private static final class LifecycleExtension implements AgentFrameworkExtension {
        private final ExtensionDescriptor descriptor;
        private final AtomicInteger stops;

        private LifecycleExtension(String version, AtomicInteger stops) {
            this.descriptor = new ExtensionDescriptor(
                    "test.lifecycle", SemanticVersion.parse(version), ">=2.0.0 <3.0.0",
                    ">=2.0.0 <3.0.0", List.of(), Set.of(), ExtensionScope.PLAN_SCOPED,
                    HotUpdateCompatibility.PLAN_ISOLATED, 1, Map.of());
            this.stops = stops;
        }

        @Override public ExtensionDescriptor descriptor() { return descriptor; }
        @Override public void register(ExtensionRegistrar registrar) { }
        @Override public void stop() { stops.incrementAndGet(); }
    }

    private record EventPayload(String value) { }
}
