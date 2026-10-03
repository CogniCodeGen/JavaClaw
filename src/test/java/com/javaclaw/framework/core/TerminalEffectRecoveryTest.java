package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.EffectReconciliationV1;
import com.javaclaw.framework.spi.RunEventDraft;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import com.javaclaw.framework.store.JdbcRunStore;
import com.javaclaw.platform.data.SchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TerminalEffectRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();
    private static final long BASE = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final String BEFORE = "123e4567-e89b-42d3-a456-426614174000";
    private static final String AFTER = "123e4567-e89b-42d3-a456-426614174001";

    @Test
    void freshRunRepairsMissedStrictProofAndRestoresOnlyThatSourceBarrier() {
        Fixture f = new Fixture();
        RunId source = f.source(true);
        RunId current = f.start("session");
        RunControl control = new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC());
        PersistedRunStateRestorer.restoreUnresolvedEffects(f.runs.eventsAfter(source, 0), control);
        assertThrows(ToolPermissionDeniedException.class, () -> nextInput(control));
        var original = f.runs.find(source).orElseThrow().snapshot();
        List<RunEventEnvelope> originalEvents = f.runs.eventsAfter(source, 0);

        f.coordinator().inheritEffects(current, f.runs.find(current).orElseThrow().request(), control);

        assertDoesNotThrow(() -> nextInput(control));
        var reconciled = f.runs.eventsAfter(source, 0).stream()
                .filter(event -> event.type().equals("core.effect.reconciled")).toList();
        assertEquals(1, reconciled.size());
        assertEquals(source.value(), reconciled.getFirst().runId());
        assertEquals(current.value(), reconciled.getFirst().payload().path("recoveryRunId").asText());
        var after = f.runs.find(source).orElseThrow().snapshot();
        assertEquals(original.state(), after.state());
        assertEquals(original.output(), after.output());
        assertEquals(originalEvents, f.runs.eventsAfter(source, 0).subList(0, originalEvents.size()));

        // Restart/repeated inheritance uses the durable source event instead of generating another one.
        RunControl restarted = new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC());
        f.coordinator().inheritEffects(current, f.runs.find(current).orElseThrow().request(), restarted);
        assertDoesNotThrow(() -> nextInput(restarted));
        assertEquals(1, f.runs.eventsAfter(source, 0).stream()
                .filter(event -> event.type().equals("core.effect.reconciled")).count());
    }

    @Test
    void genericContentProofAndVerifiedTaskCannotRecoverAnUnclassifiedUnknownClick() {
        Fixture f = new Fixture();
        RunId source = f.source(false);
        RunId current = f.start("session");
        List<RunEventEnvelope> events = f.runs.eventsAfter(source, 0);
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV3(
                TaskResultEvaluator.latestContractV3(events, JSON).orElseThrow(), events, "", CAPABILITIES)
                .outcome(), "content acceptance is independent of uncertain input delivery");
        assertTrue(f.runs.reconcileTerminalEffect(current, source, proof()).isEmpty());
        RunControl control = new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC());
        f.coordinator().inheritEffects(current, f.runs.find(current).orElseThrow().request(), control);

        assertThrows(ToolPermissionDeniedException.class, () -> nextInput(control));
        assertTrue(f.runs.eventsAfter(source, 0).stream()
                .noneMatch(event -> event.type().equals("core.effect.reconciled")));
    }

    @Test
    void anotherScopeCannotUseOrRepairTheSourcesProof() {
        Fixture f = new Fixture();
        RunId source = f.source(true);
        for (RunScope scope : List.of(new RunScope("workspace", "user", "different-session"),
                new RunScope("workspace", "different-user", "session"),
                new RunScope("different-workspace", "user", "session"))) {
            RunId foreign = f.start(scope);
            assertTrue(f.runs.reconcileTerminalEffect(foreign, source, proof()).isEmpty());
            assertTrue(VerifiedEffectReconciler.recoverTerminalEffects(f.runs, foreign, source, JSON).isEmpty());
        }
        assertEquals(0, f.runs.eventsAfter(source, 0).stream()
                .filter(event -> event.type().equals("core.effect.reconciled")).count());
    }

    @Test
    void sourceActionAndFrameTokensCannotBeSubstitutedAcrossRuns() {
        Fixture f = new Fixture();
        RunId validSource = f.source(true);
        RunId unclassifiedSource = f.source(false);
        RunId current = f.start("session");

        assertTrue(f.runs.reconcileTerminalEffect(current, unclassifiedSource, proof()).isEmpty(),
                "a qualified source must establish its own proof even when invocation tokens match");
        assertTrue(f.runs.reconcileTerminalEffect(current, validSource,
                new EffectReconciliationV1("different-action", "session-1", "window-1", BEFORE, AFTER)).isEmpty());
        assertTrue(f.runs.reconcileTerminalEffect(current, validSource,
                new EffectReconciliationV1("call-click", "session-1", "other-window", BEFORE, AFTER)).isEmpty());
        assertTrue(f.runs.reconcileTerminalEffect(current, validSource,
                new EffectReconciliationV1("call-click", "session-1", "window-1", BEFORE,
                        UUID.randomUUID().toString())).isEmpty());
        assertTrue(f.runs.reconcileTerminalEffect(validSource, validSource, proof()).isEmpty());
        assertTrue(f.runs.reconcileTerminalEffect(current, validSource, proof()).isPresent());
    }

    @Test
    void anOlderRunOrAnAlreadyTerminalRecoveryRunCannotAuthorizeRecovery() {
        Fixture f = new Fixture();
        RunId older = f.start("session");
        RunId source = f.source(true);
        assertTrue(f.runs.reconcileTerminalEffect(older, source, proof()).isEmpty());
        RunId finished = f.start("session");
        f.finish(finished);
        assertTrue(f.runs.reconcileTerminalEffect(finished, source, proof()).isEmpty());
        assertTrue(f.runs.reconcileEffect(source, proof()).isEmpty(),
                "terminal writes require the scope-qualified recovery API");
    }

    @Test
    void naturalLanguageAckClaimInLegacyUnknownMetadataNeverBecomesDeliveryProof() {
        Fixture f = new Fixture();
        RunId source = f.source(false);
        RunId current = f.start("session");
        var sourceEvents = f.runs.eventsAfter(source, 0);
        var unknown = sourceEvents.stream().filter(event -> event.type().equals("core.tool.receipt")
                && event.payload().path("operation").asText().equals("click")).findFirst().orElseThrow();
        assertEquals("Accessibility press was accepted; target effect is not confirmed",
                unknown.payload().path("reason").asText());
        assertEquals("BACKGROUND_SEMANTIC", unknown.payload().path("metadata").path("deliveryMode").asText());

        assertTrue(VerifiedEffectReconciler.recoverTerminalEffects(f.runs, current, source, JSON).isEmpty());
        assertTrue(f.runs.reconcileTerminalEffect(current, source, proof()).isEmpty());
    }

    private static void nextInput(RunControl control) {
        control.assertRepairRetryAllowed("fresh-fingerprint", "fresh-effect", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:window-1");
    }

    private static EffectReconciliationV1 proof() {
        return new EffectReconciliationV1("call-click", "session-1", "window-1", BEFORE, AFTER);
    }

    private static TaskContractV3 contract(boolean actionSpecific) {
        var open = new TaskCriterionV3("open", "Open Calendar", "desktop.open",
                CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "Calendar",
                EffectReceiptV1.Status.ACCEPTED, "");
        var click = new TaskCriterionV3("click", "Click the requested view", "desktop.click",
                CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "Calendar",
                EffectReceiptV1.Status.ACCEPTED, "");
        var view = new TaskCriterionV3("view", "Observe the requested events", "desktop.observe",
                CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, "Calendar",
                EffectReceiptV1.Status.OBSERVED, "Events");
        return new TaskContractV3(3, "Open Calendar and inspect events",
                actionSpecific ? List.of(open, click, view) : List.of(open, view), true, true,
                "model", List.of(), List.of(), TaskContractV3.DesktopObservationPolicy.REQUIRED_SUBJECT);
    }

    private static ObjectNode receipt(int step, String operation, String status, String subject, String frame) {
        var payload = JSON.createObjectNode().put("invocationId", operation.equals("click") ? "call-click" : "call-" + step)
                .put("tool", "desktop_session_" + operation).put("operation", operation)
                .put("target", "Calendar").put("status", status).put("subject", subject)
                .put("evidenceRef", "frame:" + step)
                .put("observedAt", Instant.ofEpochMilli(BASE + step * 1000).toString());
        var metadata = payload.putObject("metadata").put("applicationId", "org.example.calendar")
                .put("sessionId", "session-1").put("targetId", "window-1");
        if (!operation.equals("open")) metadata.put("observationId", frame).put("windowGeneration", "1");
        if (operation.equals("observe")) metadata.put("contentRevision", Integer.toString(step))
                .put("capturedAtMillis", Long.toString(BASE + step * 1000));
        if (!subject.isBlank()) metadata.put("viewEvidence", "heading:1,1,20,10|content:1,20,40,40");
        if (operation.equals("click")) {
            payload.put("reason", "Accessibility press was accepted; target effect is not confirmed");
            metadata.put("delivery", "MAYBE_SENT").put("dispatchAttempted", "true")
                    .put("deliveryMode", "BACKGROUND_SEMANTIC");
        }
        return payload;
    }

    private static String contentProof(JsonNode metadata) {
        var value = JSON.createObjectNode().put("schemaVersion", 1)
                .put("sessionId", "session-1").put("targetId", "window-1")
                .put("observationId", AFTER).put("windowGeneration", 1)
                .put("contentRevision", metadata.path("contentRevision").asLong())
                .put("capturedAtMillis", metadata.path("capturedAtMillis").asLong())
                .put("frameWidth", 100).put("frameHeight", 100);
        value.putArray("conditions").addObject().put("criterionId", "view").put("subject", "Events")
                .put("region", "main-content").put("confidence", 0.95).putObject("content")
                .put("label", "Tomorrow: meeting").put("role", "list")
                .put("x", 10).put("y", 20).put("width", 80).put("height", 40).put("confidence", 0.95);
        return value.toString();
    }

    private static final class Fixture {
        private final AtomicLong millis = new AtomicLong(BASE + 10_000);
        private final Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        };
        private final JdbcRunStore runs;
        private long sourceCaptureBase;

        private Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:terminal-effect-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(source).initialize();
            runs = new JdbcRunStore(new JdbcTemplate(source),
                    new DataSourceTransactionManager(source), JSON, clock);
        }

        private RunRecoveryCoordinator coordinator() {
            return new RunRecoveryCoordinator(null, runs, null, JSON, clock, null);
        }

        private RunId start(String session) {
            return start(new RunScope("workspace", "user", session));
        }

        private RunId start(RunScope scope) {
            millis.addAndGet(1000);
            RunId id = RunId.random();
            var request = RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.chat())
                    .scope(scope).input(InputBlock.text("Inspect Calendar"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED).build();
            runs.create(id, request, "test-plan", new RunEventDraft("core.run.created", 1,
                    "framework.core", null, null, JSON.createObjectNode()));
            runs.append(id, Set.of(RunState.CREATED), RunState.RUNNING, new RunEventDraft("core.run.started", 1,
                    "framework.core", null, null, JSON.createObjectNode()), null, null).orElseThrow();
            return id;
        }

        private RunId source(boolean actionSpecific) {
            RunId id = start("session");
            sourceCaptureBase = millis.get();
            append(id, "core.task.contract", 3, JSON.valueToTree(contract(actionSpecific)));
            append(id, "core.tool.receipt", 1, sourceReceipt(1, "open", "ACCEPTED", "", ""));
            append(id, "core.tool.receipt", 1,
                    sourceReceipt(2, "observe", "OBSERVED", actionSpecific ? "Month" : "", BEFORE));
            var start = JSON.createObjectNode().put("tool", "desktop_session_click")
                    .put("invocationId", "call-click").put("fingerprint", "click-fingerprint")
                    .put("effectKey", "click-effect").put("idempotent", false)
                    .put("effectPolicy", "OBSERVATION_GATED").put("resourceKey", "desktop:window-1");
            runs.appendBatch(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                    List.of(new RunEventDraft("core.step.started", 1, "framework.core", null, null,
                                    JSON.createObjectNode().put("stepId", "click-fingerprint")),
                            new RunEventDraft("core.tool.started", 1, "framework.core", null, null, start)))
                    .orElseThrow();
            append(id, "core.tool.receipt", 1, sourceReceipt(3, "click", "UNKNOWN", "", BEFORE));
            var observed = sourceReceipt(4, "observe", "OBSERVED", actionSpecific ? "Events" : "Event manager", AFTER);
            if (!actionSpecific) ((ObjectNode) observed.path("metadata"))
                    .put("conditionEvidence", contentProof(observed.path("metadata")));
            append(id, "core.tool.receipt", 1, observed);
            var result = TaskResultEvaluator.evaluateV3(contract(actionSpecific), runs.eventsAfter(id, 0), "", CAPABILITIES);
            assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
            append(id, "core.task.outcome", 3, JSON.valueToTree(result));
            finish(id);
            return id;
        }

        private ObjectNode sourceReceipt(int step, String operation, String status, String subject, String frame) {
            ObjectNode value = receipt(step, operation, status, subject, frame);
            long capturedAt = sourceCaptureBase + step * 1000;
            millis.updateAndGet(previous -> Math.max(previous, capturedAt));
            value.put("observedAt", Instant.ofEpochMilli(capturedAt).toString());
            if (operation.equals("observe")) ((ObjectNode) value.path("metadata"))
                    .put("capturedAtMillis", Long.toString(capturedAt));
            return value;
        }

        private void finish(RunId id) {
            millis.addAndGet(1000);
            runs.append(id, Set.of(RunState.RUNNING), RunState.COMPLETED,
                    new RunEventDraft("core.run.completed", 1, "framework.core", null, null, JSON.createObjectNode()),
                    JSON.createObjectNode().put("message", "original completed output"), null).orElseThrow();
        }

        private void append(RunId id, String type, int version, JsonNode value) {
            runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                    new RunEventDraft(type, version, "framework.core", null, null, value), null, null).orElseThrow();
        }
    }
}
