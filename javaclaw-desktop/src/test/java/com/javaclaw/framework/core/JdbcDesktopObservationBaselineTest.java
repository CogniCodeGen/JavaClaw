package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunEventDraft;
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

import static org.junit.jupiter.api.Assertions.*;

class JdbcDesktopObservationBaselineTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final long BASE = Instant.parse("2026-10-03T04:00:00Z").toEpochMilli();

    @Test
    void settledUnknownInputCanReserveANewDecisionAfterAFreshHostObservation() {
        Fixture fixture = new Fixture();
        fixture.old("desktop:window", "TYPED");
        fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");

        assertDoesNotThrow(() -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000));
        var events = fixture.runs.eventsAfter(fixture.id, 0);
        assertEquals("UNKNOWN", events.stream().filter(event -> event.type().equals("core.tool.receipt")
                        && event.payload().path("invocationId").asText().equals("old"))
                .findFirst().orElseThrow().payload().path("status").asText());
        assertFalse(events.stream().anyMatch(event -> Set.of("core.effect.reconciled",
                "core.task.checkpoint_verified").contains(event.type())));
    }

    @Test
    void lostAndGenericTimeoutReceiptsCanRecoverOnlyAfterTheDurableHostBoundary() {
        for (String variant : List.of("MISSING", "GENERIC", "INVALID_TIME")) {
            Fixture fixture = new Fixture();
            fixture.old("desktop:window", variant);
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            assertDoesNotThrow(() -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000), variant);
        }
        Fixture fixture = new Fixture();
        fixture.old("desktop:window", "GENERIC");
        fixture.observe("old-capture", "session-new", "window", 2, 1900, "VALID");
        assertEquals(PendingEffectObservationRequiredException.Reason.DELIVERY_UNCERTAIN,
                assertThrows(PendingEffectObservationRequiredException.class,
                        () -> fixture.reserve(input("next", "session-new", "old-capture", 2), 4000)).reason());
    }

    @Test
    void captureMustFollowBothTheOriginalStartAndTheMatchedReceiptTime() {
        for (long captured : List.of(1000L, 1500L, 1999L, 2000L)) {
            Fixture fixture = new Fixture();
            fixture.old("desktop:window", "TYPED");
            fixture.observe("fresh", "session-new", "window", 2, captured, "VALID");
            var denied = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000));
            assertEquals(fixture.id.value(), denied.sourceRunId());
            assertEquals("old", denied.invocationId());
            assertEquals("desktop:window", denied.resourceKey());
        }
        Fixture fixture = new Fixture();
        fixture.old("desktop:window", "LATE_OBSERVED_AT");
        fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
        assertThrows(PendingEffectObservationRequiredException.class,
                () -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000));
    }

    @Test
    void theOriginalFingerprintAndEffectKeyRemainConsumedAfterFreshObservation() {
        for (String key : List.of("fingerprint", "effectKey")) {
            Fixture fixture = new Fixture();
            fixture.old("desktop:window", "TYPED");
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            var next = input("next", "session-new", "fresh", 2).put(key, "old");
            var denied = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(next, 4000));
            assertEquals(PendingEffectObservationRequiredException.Reason.OBSERVATION_ALREADY_CONSUMED,
                    denied.reason());
        }
    }

    @Test
    void candidateMustUseTheExactSessionObservationAndGenerationOfTheLatestFrame() {
        for (String variant : List.of("SESSION", "OBSERVATION", "GENERATION", "GENERATION_STRING", "FALSE_MARKER")) {
            Fixture fixture = new Fixture();
            fixture.old("desktop:window", "TYPED");
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            var next = input("next", "session-new", "fresh", 2);
            var args = (ObjectNode) next.path("arguments");
            if (variant.equals("SESSION")) args.put("sessionId", "guessed");
            if (variant.equals("OBSERVATION")) args.put("observationId", "guessed");
            if (variant.equals("GENERATION")) args.put("generation", 3);
            if (variant.equals("GENERATION_STRING")) args.put("generation", "2");
            if (variant.equals("FALSE_MARKER")) next.put("trustedDesktopTool", false);
            assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(next, 4000), variant);
        }
    }

    @Test
    void aNewerObservationOfTheSameTargetInvalidatesOldObservationTokens() {
        Fixture fixture = new Fixture();
        fixture.old("desktop:window", "TYPED");
        fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
        fixture.observe("newer", "session-new", "window", 2, 4600, "VALID");
        assertThrows(PendingEffectObservationRequiredException.class,
                () -> fixture.reserve(input("next", "session-new", "fresh", 2), 6000));
        assertDoesNotThrow(() -> fixture.reserve(input("next", "session-new", "newer", 2), 6000));
    }

    @Test
    void anotherWindowObservationDoesNotReplaceTheExactTargetsLatestFrame() {
        Fixture fixture = new Fixture();
        fixture.old("desktop:window", "TYPED");
        fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
        fixture.observe("other", "other-session", "other-window", 9, 4600, "VALID");
        assertDoesNotThrow(() -> fixture.reserve(input("next", "session-new", "fresh", 2), 6000));
    }

    @Test
    void oneNewReservationConsumesTheFrameEvenWhenItsInputWasAccepted() {
        Fixture fixture = new Fixture();
        fixture.old("desktop:window", "TYPED");
        fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
        fixture.reserve(input("next", "session-new", "fresh", 2), 4000);
        fixture.append(4100, "core.tool.receipt", 1, actionReceipt(fixture.id, "next", "session-new", "fresh",
                "window", "ACCEPTED", "SENT", 4100));
        var denied = assertThrows(PendingEffectObservationRequiredException.class,
                () -> fixture.reserve(input("another", "session-new", "fresh", 2), 4200));
        assertEquals("next", denied.invocationId());
        assertEquals(PendingEffectObservationRequiredException.Reason.OBSERVATION_ALREADY_CONSUMED, denied.reason());
        fixture.observe("newer", "session-new", "window", 2, 4600, "VALID");
        assertDoesNotThrow(() -> fixture.reserve(input("another", "session-new", "newer", 2), 6000));
    }

    @Test
    void incompleteUntrustedOrModelOnlyObservationCannotAuthorizeTheReservation() {
        for (String variant : List.of("NO_START", "NO_RAW", "NO_RECEIPT", "MODEL_OUTPUT", "PRODUCER",
                "SCHEMA", "FALSE_MARKER", "RAW_CONFLICT", "RECEIPT_CONFLICT")) {
            Fixture fixture = new Fixture();
            fixture.old("desktop:window", "TYPED");
            fixture.observe("fresh", "session-new", "window", 2, 2600, variant);
            assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000), variant);
        }
    }

    @Test
    void anUnpairedRefreshClaimDoesNotChangeTheDurableGuard() {
        Fixture fixture = new Fixture();
        fixture.old("desktop:window", "TYPED");
        fixture.append(3000, "core.desktop.observation_refreshed", 1, NODES.objectNode()
                .put("sessionId", "session-new").put("targetId", "window")
                .put("observationId", "fresh").put("windowGeneration", 2)
                .put("capturedAtMillis", BASE + 2600).put("inputBaseline", "OBSERVED_AFTER_ATTEMPT"));
        assertThrows(PendingEffectObservationRequiredException.class,
                () -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000));
    }

    @Test
    void anUnknownOriginalResourceOrExplicitlyUntrustedStartCannotUseAFreshFrame() {
        for (String variant : List.of("UNKNOWN_RESOURCE", "FALSE_MARKER", "MISSING_BINDING")) {
            Fixture fixture = new Fixture();
            var old = input("old", "session-old", "old-observation", 1);
            if (variant.equals("UNKNOWN_RESOURCE")) old.put("resourceKey", "desktop:unknown");
            if (variant.equals("FALSE_MARKER")) old.put("trustedDesktopTool", false);
            if (variant.equals("MISSING_BINDING")) old.remove("arguments");
            fixture.append(1000, "core.tool.started", 1, old);
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000), variant);
        }
    }

    @Test
    void aConflictingConcreteActionReceiptCannotBeDowngradedToALostReceipt() {
        Fixture fixture = new Fixture();
        fixture.append(1000, "core.tool.started", 1, input("old", "session-old", "old-observation", 1));
        fixture.append(2000, "core.tool.receipt", 1, actionReceipt(fixture.id, "old", "other-session",
                "old-observation", "window", "UNKNOWN", "MAYBE_SENT", 2000));
        fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
        assertThrows(PendingEffectObservationRequiredException.class,
                () -> fixture.reserve(input("next", "session-new", "fresh", 2), 4000));
    }

    @Test
    void failedNotSentRetainsTheFrameAndAllowsTheOriginalActionKeysToBeRetried() {
        Fixture fixture = new Fixture();
        fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
        fixture.reserve(input("first", "session-new", "fresh", 2), 4000);
        var rejected = actionReceipt(fixture.id, "first", "session-new", "fresh",
                "window", "FAILED", "NOT_SENT", 4100);
        ((ObjectNode) rejected.path("metadata")).put("dispatchAttempted", "false");
        fixture.append(4100, "core.tool.receipt", 1, rejected);
        var retry = input("retry", "session-new", "fresh", 2)
                .put("fingerprint", "first").put("effectKey", "first");

        assertDoesNotThrow(() -> fixture.reserve(retry, 4200));
        assertEquals(2, fixture.runs.eventsAfter(fixture.id, 0).stream()
                .filter(event -> event.type().equals("core.tool.started")
                        && event.payload().path("tool").asText().equals("desktop_session_click")).count());
        assertEquals("FAILED", fixture.runs.eventsAfter(fixture.id, 0).stream()
                .filter(event -> event.type().equals("core.tool.receipt")
                        && event.payload().path("invocationId").asText().equals("first"))
                .findFirst().orElseThrow().payload().path("status").asText());
    }

    @Test
    void sentMaybeSentMissingAndUnknownNotSentStillConsumeTheFrame() {
        for (String variant : List.of("ACCEPTED/SENT", "UNKNOWN/MAYBE_SENT", "FAILED/MAYBE_SENT",
                "FAILED/SENT", "UNKNOWN/NOT_SENT", "MISSING")) {
            Fixture fixture = new Fixture();
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            fixture.reserve(input("first", "session-new", "fresh", 2), 4000);
            if (!variant.equals("MISSING")) {
                String[] status = variant.split("/");
                fixture.append(4100, "core.tool.receipt", 1, actionReceipt(fixture.id, "first", "session-new",
                        "fresh", "window", status[0], status[1], 4100));
            }
            var denied = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(input("retry", "session-new", "fresh", 2), 4200), variant);
            assertEquals(PendingEffectObservationRequiredException.Reason.OBSERVATION_ALREADY_CONSUMED, denied.reason());
        }
    }

    @Test
    void notSentMustHaveAUniqueOrderedHostReceiptWithTheExactActionBinding() {
        for (String variant : List.of("PRODUCER", "SCHEMA", "TOOL", "INVOCATION", "OPERATION", "EVIDENCE",
                "SESSION", "OBSERVATION", "TARGET", "GENERATION", "DISPATCH_TRUE", "FALSE_MARKER", "INVALID_TIME",
                "PRE_START_TIME")) {
            Fixture fixture = new Fixture();
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            fixture.reserve(input("first", "session-new", "fresh", 2), 4000);
            var rejected = actionReceipt(fixture.id, "first", "session-new", "fresh",
                    "window", "FAILED", "NOT_SENT", 4100);
            var metadata = (ObjectNode) rejected.path("metadata");
            if (variant.equals("TOOL")) rejected.put("tool", "desktop_session_type");
            if (variant.equals("INVOCATION")) rejected.put("invocationId", "different-call");
            if (variant.equals("OPERATION")) rejected.put("operation", "type");
            if (variant.equals("EVIDENCE")) rejected.put("evidenceRef", "model-claimed-success");
            if (variant.equals("SESSION")) metadata.put("sessionId", "another-session");
            if (variant.equals("OBSERVATION")) metadata.put("observationId", "another-frame");
            if (variant.equals("TARGET")) metadata.put("targetId", "another-window");
            if (variant.equals("GENERATION")) metadata.put("windowGeneration", "3");
            if (variant.equals("DISPATCH_TRUE")) metadata.put("dispatchAttempted", "true");
            if (variant.equals("FALSE_MARKER")) rejected.put("trustedDesktopTool", false);
            if (variant.equals("INVALID_TIME")) rejected.put("observedAt", "invalid");
            fixture.clock.at(variant.equals("PRE_START_TIME") ? 3900 : 4100);
            fixture.runs.append(fixture.id, Set.of(RunState.RUNNING), RunState.RUNNING,
                    new RunEventDraft("core.tool.receipt", variant.equals("SCHEMA") ? 2 : 1,
                            variant.equals("PRODUCER") ? "model" : "framework.core", null, null, rejected),
                    null, null).orElseThrow();

            assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(input("retry", "session-new", "fresh", 2), 4200), variant);
        }
    }

    @Test
    void duplicateOrConflictingReceiptsCannotMakeTheLastNotSentClaimAuthoritative() {
        for (String variant : List.of("UNKNOWN_THEN_NOT_SENT", "NOT_SENT_THEN_UNKNOWN", "DUPLICATE_NOT_SENT")) {
            Fixture fixture = new Fixture();
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            fixture.reserve(input("first", "session-new", "fresh", 2), 4000);
            boolean firstUnknown = variant.equals("UNKNOWN_THEN_NOT_SENT");
            boolean lastUnknown = variant.equals("NOT_SENT_THEN_UNKNOWN");
            fixture.append(4100, "core.tool.receipt", 1, actionReceipt(fixture.id, "first", "session-new", "fresh",
                    "window", firstUnknown ? "UNKNOWN" : "FAILED", firstUnknown ? "MAYBE_SENT" : "NOT_SENT", 4100));
            fixture.append(4150, "core.tool.receipt", 1, actionReceipt(fixture.id, "first", "session-new", "fresh",
                    "window", lastUnknown ? "UNKNOWN" : "FAILED", lastUnknown ? "MAYBE_SENT" : "NOT_SENT", 4150));
            assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(input("retry", "session-new", "fresh", 2), 4200), variant);
        }
    }

    @Test
    void theSameOriginalActionGuardAlsoRejectsAnUnboundNotSentClaim() {
        Fixture fixture = new Fixture();
        fixture.append(1000, "core.tool.started", 1, input("old", "session-old", "old-frame", 1));
        fixture.append(2000, "core.tool.receipt", 1, actionReceipt(fixture.id, "old", "different-session",
                "old-frame", "window", "FAILED", "NOT_SENT", 2000));
        var retry = input("retry", "session-old", "old-frame", 1).put("fingerprint", "old").put("effectKey", "old");
        assertThrows(PendingEffectObservationRequiredException.class, () -> fixture.reserve(retry, 3000));
    }

    @Test
    void aHostExecutorSubmissionRejectionCanRetryWithoutSpendingTheFrame() {
        for (boolean pairedFailure : List.of(true, false)) {
            Fixture fixture = new Fixture();
            fixture.observe("fresh", "session-new", "window", 2, 2600, "VALID");
            fixture.reserve(input("first", "session-new", "fresh", 2), 4000);
            if (pairedFailure) fixture.append(4050, "core.tool.failed", 2, NODES.objectNode()
                    .put("tool", "desktop_session_click").put("invocationId", "first").put("status", "FAILED"));
            var rejected = actionReceipt(fixture.id, "first", "session-new", "fresh", "window", "FAILED", "NOT_SENT", 4100)
                    .put("operation", "execute").put("evidenceRef", "core.tool.failed:" + fixture.id.value() + ":first");
            rejected.set("metadata", NODES.objectNode().put("delivery", "NOT_SENT"));
            fixture.append(4100, "core.tool.receipt", 1, rejected);
            if (pairedFailure) assertDoesNotThrow(() -> fixture.reserve(input("retry", "session-new", "fresh", 2), 4200));
            else assertThrows(PendingEffectObservationRequiredException.class,
                    () -> fixture.reserve(input("retry", "session-new", "fresh", 2), 4200));
        }
    }

    private static ObjectNode input(String invocation, String session, String observation, long generation) {
        var start = NODES.objectNode().put("tool", "desktop_session_click").put("invocationId", invocation)
                .put("fingerprint", invocation).put("effectKey", invocation).put("idempotent", false)
                .put("effectPolicy", "OBSERVATION_GATED").put("resourceKey", "desktop:window");
        start.putObject("arguments").put("sessionId", session).put("observationId", observation)
                .put("generation", generation).put("elementId", observation + ":e1");
        return start;
    }

    private static ObjectNode actionReceipt(RunId id, String invocation, String session, String observation,
            String target, String status, String delivery, long observedAt) {
        var receipt = NODES.objectNode().put("tool", "desktop_session_click").put("operation", "click")
                .put("invocationId", invocation).put("status", status).put("target", "App")
                .put("evidenceRef", "core.tool.completed:" + id.value() + ":" + invocation)
                .put("observedAt", Instant.ofEpochMilli(BASE + observedAt).toString());
        receipt.putObject("metadata").put("sessionId", session).put("observationId", observation)
                .put("targetId", target).put("delivery", delivery);
        return receipt;
    }

    private static final class Fixture {
        private final MutableClock clock = new MutableClock();
        private final JdbcRunStore runs;
        private final RunId id = RunId.random();

        private Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:desktop-baseline-" + UUID.randomUUID()
                    + ";DB_CLOSE_DELAY=-1", "sa", "");
            new SchemaInitializer(source).initialize();
            runs = new JdbcRunStore(new JdbcTemplate(source), new DataSourceTransactionManager(source),
                    new ObjectMapper().findAndRegisterModules(), clock);
            var request = RunRequest.builder().agent(AgentDefinitionRef.latest("test.agent"))
                    .profile(RunProfileRef.latest("test.profile")).source(InvocationSource.chat())
                    .scope(new RunScope("workspace", "user", "session")).input(InputBlock.text("test"))
                    .permissionCeiling(PermissionSet.UNRESTRICTED).budget(RunBudget.UNBOUNDED).build();
            runs.create(id, request, "test-plan", draft("core.run.created", 1, NODES.objectNode()));
            runs.append(id, Set.of(RunState.CREATED), RunState.RUNNING,
                    draft("core.run.started", 1, NODES.objectNode()), null, null).orElseThrow();
        }

        private void old(String resource, String variant) {
            append(1000, "core.tool.started", 1,
                    input("old", "session-old", "old-observation", 1).put("resourceKey", resource));
            if (variant.equals("MISSING")) return;
            var receipt = actionReceipt(id, "old", "session-old", "old-observation", "window",
                    "UNKNOWN", "MAYBE_SENT", variant.equals("LATE_OBSERVED_AT") ? 2800 : 1900);
            if (variant.equals("GENERIC") || variant.equals("INVALID_TIME")) {
                receipt.put("operation", "execute");
                receipt.set("metadata", NODES.objectNode().put("delivery", "MAYBE_SENT"));
            }
            if (variant.equals("INVALID_TIME")) receipt.put("observedAt", "invalid");
            append(2000, "core.tool.receipt", 1, receipt);
        }

        private void observe(String observation, String session, String target, long generation,
                long capturedAt, String variant) {
            long offset = capturedAt >= 4000 ? 2000 : 0;
            String invocation = "observe-" + observation;
            var started = NODES.objectNode().put("tool", "desktop_session_observe")
                    .put("invocationId", invocation).put("idempotent", true);
            started.putObject("arguments").put("sessionId", session);
            if (variant.equals("FALSE_MARKER")) started.put("trustedDesktopTool", false);
            if (!variant.equals("NO_START")) append(2500 + offset, "core.tool.started", 1, started);
            var raw = NODES.objectNode().put("protocol", "computer-use").put("schemaVersion", 1)
                    .put("kind", "desktop.observation").put("application", "App").put("sessionId", session)
                    .put("targetId", target).put("observationId", observation).put("windowGeneration", generation)
                    .put("contentRevision", 3).put("capturedAtMillis", BASE + capturedAt);
            raw.putObject("frame").put("targetId", target).put("windowGeneration", generation)
                    .put("contentRevision", 3).put("capturedAtMillis", BASE + capturedAt)
                    .put("width", 800).put("height", 600);
            if (variant.equals("RAW_CONFLICT")) raw.put("sessionId", "other");
            var completed = NODES.objectNode().put("tool", "desktop_session_observe")
                    .put("invocationId", invocation).put("status", "SUCCEEDED");
            if (!variant.equals("NO_RAW")) completed.set(variant.equals("MODEL_OUTPUT") ? "modelOutput" : "output", raw);
            clock.at(3000 + offset);
            runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING,
                    new RunEventDraft("core.tool.completed", variant.equals("SCHEMA") ? 1 : 2,
                            variant.equals("PRODUCER") ? "model" : "framework.core", null, null, completed),
                    null, null).orElseThrow();
            var receipt = NODES.objectNode().put("tool", "desktop_session_observe").put("operation", "observe")
                    .put("invocationId", invocation).put("status", "OBSERVED").put("target", "App")
                    .put("evidenceRef", "core.tool.completed:" + id.value() + ":" + invocation)
                    .put("observedAt", Instant.ofEpochMilli(BASE + 2900 + offset).toString());
            receipt.putObject("metadata").put("sessionId", session).put("targetId", target)
                    .put("observationId", observation).put("windowGeneration", Long.toString(generation))
                    .put("contentRevision", "3").put("capturedAtMillis", Long.toString(BASE + capturedAt));
            if (variant.equals("RECEIPT_CONFLICT")) ((ObjectNode) receipt.path("metadata")).put("targetId", "other");
            if (!variant.equals("NO_RECEIPT")) append(3100 + offset, "core.tool.receipt", 1, receipt);
        }

        private void reserve(ObjectNode input, long at) {
            clock.at(at);
            runs.appendBatch(id, Set.of(RunState.RUNNING), RunState.RUNNING, List.of(
                    draft("core.step.started", 1, NODES.objectNode().put("stepId", input.path("invocationId").asText())),
                    draft("core.tool.started", 1, input))).orElseThrow();
        }

        private void append(long at, String type, int schema, JsonNode payload) {
            clock.at(at);
            runs.append(id, Set.of(RunState.RUNNING), RunState.RUNNING, draft(type, schema, payload),
                    null, null).orElseThrow();
        }

        private static RunEventDraft draft(String type, int schema, JsonNode payload) {
            return new RunEventDraft(type, schema, "framework.core", null, null, payload);
        }
    }

    private static final class MutableClock extends Clock {
        private long now = BASE;
        private void at(long offset) { now = BASE + offset; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
