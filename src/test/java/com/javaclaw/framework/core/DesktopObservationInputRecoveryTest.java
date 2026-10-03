package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopObservationInputRecoveryTest {
    @Test
    void restartReplaysFreshHostObservationAndKeepsSourceQualifiedUnknownInput() {
        RunControl recovered = control();
        PersistedRunStateRestorer.restore(observationJournal(), recovered);
        PersistedRunStateRestorer.restoreUnresolvedEffects(oldJournal(true), recovered);
        assertDoesNotThrow(() -> input(recovered, fresh()));
        var previous = assertThrows(PendingEffectObservationRequiredException.class,
                () -> input(recovered, old()));
        assertEquals("old-run", previous.sourceRunId());
        assertEquals(EffectReceiptV1.Status.UNKNOWN, previous.status());
        assertEquals("MAYBE_SENT", previous.delivery());
        assertEquals(1, recovered.toolCallCount(), "only this Run's observe consumes its budget");
    }

    @Test
    void hostObservationAlsoRecoversAStartedInputWhoseReceiptWasLost() {
        RunControl recovered = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(oldJournal(false), recovered);
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(recovered, fresh()));
        PersistedRunStateRestorer.restore(observationJournal(), recovered);
        assertDoesNotThrow(() -> input(recovered, fresh()));
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(recovered, old()));
    }

    @Test
    void unknownTimeoutReceiptWithoutPlatformProofStillRequiresCaptureAfterItsBoundaryTime() {
        RunControl recovered = control();
        ObjectNode timeout = new EffectReceiptV1("click", "desktop_session_click", "execute", "",
                EffectReceiptV1.Status.UNKNOWN, Instant.ofEpochMilli(2000),
                "core.tool.failed:old-run:click", "timeout", "", Map.of("delivery", "MAYBE_SENT")).toJson();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(oldJournal(false).getFirst(),
                event("old-run", 2, 2000, "core.tool.receipt", 1, timeout)), recovered);
        recovered.restoreDesktopObservation(frame("window", "next-session", "fresh", 1500));
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(recovered, fresh()));
        recovered.restoreDesktopObservation(frame("window", "next-session", "fresh", 3000));
        assertDoesNotThrow(() -> input(recovered, fresh()));
    }

    @Test
    void aBaselineClaimWithoutTheMatchingHostInvocationCannotRefreshRecoveryState() {
        RunControl recovered = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(oldJournal(true), recovered);
        ObjectNode claim = JsonNodeFactory.instance.objectNode().put("targetId", "window")
                .put("sessionId", "next-session").put("observationId", "fresh")
                .put("inputBaseline", "OBSERVED_AFTER_ATTEMPT");
        PersistedRunStateRestorer.restore(List.of(event("current-run", 1, 3300,
                "core.desktop.observation_refreshed", 1, claim)), recovered);
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(recovered, fresh()));
    }

    @Test
    void newOwnedObservationAllowsLaterInputWithoutReconcilingTheOldUnknownEffect() {
        RunControl next = inheritedUnknown();
        assertTrue(next.hasPendingDesktopInput());
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, fresh()));
        next.restoreDesktopObservation(frame("window", "next-session", "fresh", 3000));
        assertFalse(next.hasPendingDesktopInput());
        assertFalse(next.requiresDesktopObservation("window", "next-session", "fresh"));
        assertDoesNotThrow(() -> input(next, fresh()));
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, old()));
        assertThrows(PendingEffectObservationRequiredException.class,
                () -> next.assertRepairRetryAllowed("fresh", "fresh", false,
                        ToolEffectPolicy.OBSERVATION_GATED, "desktop:window"),
                "a baseline does not remove the UNKNOWN receipt or grant unbound retries");
    }

    @Test
    void newInputConsumesBaselineAndRequiresAnotherPostActionObservation() {
        RunControl next = inheritedUnknown();
        next.restoreDesktopObservation(frame("window", "next-session", "fresh", 3000));
        next.reserveEffect("next-click", "fresh", "fresh", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:window", fresh(), () -> { });
        next.restoreDesktopInputStart("next-click", fresh(), Instant.ofEpochMilli(3200));
        next.restoreEffectReceipt("next-click", EffectReceiptV1.Status.ACCEPTED, "SENT");
        assertTrue(next.hasPendingDesktopInput());
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, fresh()));
        next.restoreDesktopObservation(frame("window", "next-session", "newer", 4000));
        assertDoesNotThrow(() -> input(next, args("next-session", "newer", 2)));
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, fresh()));
    }

    @Test
    void screenshotOfAnotherWindowAndOldOrGuessedTokensCannotRefreshInput() {
        RunControl next = inheritedUnknown();
        next.restoreDesktopObservation(frame("other", "next-session", "fresh", 3000));
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, fresh()));
        next.restoreDesktopObservation(frame("window", "next-session", "fresh", 1500));
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, fresh()),
                "frame must be captured after the prior action receipt, not just returned later");
        next.restoreDesktopObservation(frame("window", "next-session", "fresh", 3000));
        for (JsonNode invalid : new JsonNode[] { args("guessed", "fresh", 2),
                args("next-session", "guessed", 2), args("next-session", "fresh", 3) })
            assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, invalid));
    }

    @Test
    void inheritedSourceFramesNeverGrantInputInANewRun() {
        RunControl source = unknown();
        source.restoreDesktopObservation(frame("window", "old-session", "source-new", 3000));
        RunControl next = control();
        next.inheritUnresolvedEffects(source, "old-run");
        assertTrue(next.hasPendingDesktopInput());
        assertThrows(PendingEffectObservationRequiredException.class,
                () -> input(next, args("old-session", "source-new", 2)));
    }

    @Test
    void unknownResourceWithoutHostActionBindingAndOtherEffectsRemainFenced() {
        RunControl next = control();
        next.restoreEffectStart("lost", "old", "old", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:unknown");
        next.restoreEffectReceipt("lost", EffectReceiptV1.Status.UNKNOWN, "MAYBE_SENT");
        next.restoreDesktopObservation(frame("window", "next-session", "fresh", 3000));
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, fresh()));
        RunControl launch = control();
        launch.restoreEffectStart("launch", "app", "app", false,
                ToolEffectPolicy.DISCOVERY_GATED, "desktop.application:app");
        launch.restoreEffectReceipt("launch", EffectReceiptV1.Status.UNKNOWN, "MAYBE_SENT");
        launch.restoreDesktopObservation(frame("window", "next-session", "fresh", 3000));
        assertThrows(PendingEffectObservationRequiredException.class,
                () -> launch.assertRepairRetryAllowed("app", "app", false,
                        ToolEffectPolicy.DISCOVERY_GATED, "desktop.application:app", fresh()));
    }

    @Test
    void laterRecoverySnapshotKeepsTheLatestAttemptTime() {
        RunControl source = unknown();
        RunControl next = control();
        next.inheritUnresolvedEffects(source, "old-run");
        next.restoreDesktopObservation(frame("window", "next-session", "fresh", 3000));
        assertDoesNotThrow(() -> input(next, fresh()));
        source.restoreDesktopInputReceipt("click", old(), receipt(3500));
        next.inheritUnresolvedEffects(source, "old-run");
        assertThrows(PendingEffectObservationRequiredException.class, () -> input(next, fresh()));
    }

    private static RunControl inheritedUnknown() {
        RunControl next = control();
        next.inheritUnresolvedEffects(unknown(), "old-run");
        return next;
    }

    private static RunControl unknown() {
        RunControl source = control();
        source.restoreEffectStart("click", "old", "old", false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:window");
        source.restoreDesktopInputStart("click", old(), Instant.ofEpochMilli(1000));
        source.restoreEffectReceipt("click", EffectReceiptV1.Status.UNKNOWN, "MAYBE_SENT");
        source.restoreDesktopInputReceipt("click", old(), receipt(2000));
        return source;
    }

    private static EffectReceiptV1 receipt(long observedAt) {
        return new EffectReceiptV1("click", "desktop_session_click", "click", "App",
                EffectReceiptV1.Status.UNKNOWN, Instant.ofEpochMilli(observedAt),
                "core.tool.completed:old-run:click", "", "", Map.of(
                "targetId", "window", "sessionId", "old-session", "observationId", "old",
                "delivery", "MAYBE_SENT"));
    }

    private static List<RunEventEnvelope> oldJournal(boolean completed) {
        ObjectNode start = JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_click")
                .put("invocationId", "click").put("fingerprint", "old").put("effectKey", "old")
                .put("effectPolicy", "OBSERVATION_GATED").put("resourceKey", "desktop:window")
                .put("idempotent", false);
        start.set("arguments", old());
        var first = event("old-run", 1, 1000, "core.tool.started", 1, start);
        return completed ? List.of(first, event("old-run", 2, 2000,
                "core.tool.receipt", 1, receipt(2000).toJson())) : List.of(first);
    }

    private static List<RunEventEnvelope> observationJournal() {
        ObjectNode start = JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_observe")
                .put("invocationId", "observe").put("fingerprint", "observe-new")
                .put("idempotent", true);
        start.putObject("arguments").put("sessionId", "next-session");
        ObjectNode raw = JsonNodeFactory.instance.objectNode().put("protocol", "computer-use")
                .put("schemaVersion", 1).put("kind", "desktop.observation")
                .put("application", "App").put("sessionId", "next-session")
                .put("targetId", "window").put("observationId", "fresh")
                .put("windowGeneration", 2).put("contentRevision", 7).put("capturedAtMillis", 3000);
        raw.putObject("frame").put("targetId", "window").put("windowGeneration", 2)
                .put("contentRevision", 7).put("capturedAtMillis", 3000).put("width", 800).put("height", 600);
        ObjectNode completed = JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_observe")
                .put("invocationId", "observe").put("status", "SUCCEEDED");
        completed.set("output", raw);
        var observed = new EffectReceiptV1("observe", "desktop_session_observe", "observe", "App",
                EffectReceiptV1.Status.OBSERVED, Instant.ofEpochMilli(3150),
                "core.tool.completed:current-run:observe", "", "", Map.of(
                        "sessionId", "next-session", "targetId", "window", "observationId", "fresh",
                        "windowGeneration", "2", "contentRevision", "7", "capturedAtMillis", "3000"));
        return List.of(event("current-run", 1, 2900, "core.tool.started", 1, start),
                event("current-run", 2, 3200, "core.tool.completed", 2, completed),
                event("current-run", 3, 3300, "core.tool.receipt", 1, observed.toJson()));
    }

    private static RunEventEnvelope event(String run, long sequence, long timestamp,
            String type, int schema, ObjectNode payload) {
        return new RunEventEnvelope(run, sequence, Instant.ofEpochMilli(timestamp), type, schema,
                "framework.core", null, null, payload);
    }

    private static DesktopObservationBaseline.Frame frame(String target, String session,
            String observation, long capturedAt) {
        return new DesktopObservationBaseline.Frame("current-run", "observe", session, target,
                observation, 2, 7, capturedAt, capturedAt + 100, 0);
    }

    private static ObjectNode old() { return args("old-session", "old", 1); }
    private static ObjectNode fresh() { return args("next-session", "fresh", 2); }
    private static ObjectNode args(String session, String observation, long generation) {
        return JsonNodeFactory.instance.objectNode().put("sessionId", session)
                .put("observationId", observation).put("generation", generation);
    }
    private static void input(RunControl control, JsonNode args) {
        String key = args.path("observationId").asText();
        control.assertRepairRetryAllowed(key, key, false,
                ToolEffectPolicy.OBSERVATION_GATED, "desktop:window", args);
    }
    private static RunControl control() { return new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC()); }
}
