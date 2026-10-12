package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopObservationBaselineTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String RUN = "current-run";
    private static final String TOOL = "desktop_session_observe";
    private static final String INVOCATION = "model/step/observation-call";
    private static final Instant START = Instant.parse("2026-10-03T04:00:00Z");
    private static final long CAPTURED = START.toEpochMilli() + 500;
    private static final long OBSERVED = START.toEpochMilli() + 1_500;

    @Test
    void legacyHostTripleWithoutANewTrustMarkerProducesAnImmutableBoundFrame() {
        List<RunEventEnvelope> events = events();
        var frames = DesktopObservationBaseline.fromEvents(events);
        assertEquals(1, frames.size());
        var frame = frames.getFirst();
        assertEquals(RUN, frame.runId());
        assertEquals(INVOCATION, frame.invocationId());
        assertEquals("session", frame.sessionId());
        assertEquals("window", frame.targetId());
        assertEquals("observation", frame.observationId());
        assertEquals(4, frame.windowGeneration());
        assertEquals(7, frame.contentRevision());
        assertEquals(CAPTURED, frame.capturedAtMillis());
        assertEquals(OBSERVED, frame.observedAtMillis());
        assertEquals(3, frame.sequence());
        assertEquals(frame, DesktopObservationBaseline.latest(events).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> frames.clear());
        assertFalse(events.getFirst().payload().has("trustedDesktopTool"), "replay never mutates its journal");
    }

    @Test
    void trustedLiveResultUsesTheSameValidationWithoutClaimingAJournalSequence() {
        var frame = DesktopObservationBaseline.fromTrustedResult(new RunId(RUN), INVOCATION,
                arguments(), raw(), typedReceipt()).orElseThrow();
        assertEquals(0, frame.sequence());
        assertEquals("window", frame.targetId());
        assertEquals(CAPTURED, frame.capturedAtMillis());
        assertTrue(DesktopObservationBaseline.fromTrustedResult(new RunId("different-run"), INVOCATION,
                arguments(), raw(), typedReceipt()).isEmpty());
        assertTrue(DesktopObservationBaseline.fromTrustedResult(new RunId(RUN), "different-call",
                arguments(), raw(), typedReceipt()).isEmpty());
        assertTrue(DesktopObservationBaseline.fromTrustedResult(new RunId(RUN), INVOCATION,
                arguments().put("sessionId", "other"), raw(), typedReceipt()).isEmpty());
    }

    @Test
    void explicitFalseOrMalformedHostMarkerIsRejectedButTrueIsSupported() {
        for (int stage = 0; stage < 3; stage++) {
            for (JsonNode marker : List.of(NODES.booleanNode(false), NODES.textNode("true"), NODES.nullNode())) {
                List<RunEventEnvelope> events = events();
                var payload = (ObjectNode) events.get(stage).payload();
                payload.set("trustedDesktopTool", marker);
                replace(events, stage, payload);
                assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
            }
        }
        List<RunEventEnvelope> events = events();
        replace(events, 0, ((ObjectNode) events.getFirst().payload()).put("trustedDesktopTool", true));
        assertEquals(1, DesktopObservationBaseline.fromEvents(events).size());
    }

    @Test
    void everyStageMustBeExactHostProducerSchemaToolAndInvocation() {
        for (int stage = 0; stage < 3; stage++) {
            for (String variant : List.of("PRODUCER", "SCHEMA", "TOOL", "INVOCATION", "RUN")) {
                List<RunEventEnvelope> events = events();
                var original = events.get(stage);
                var payload = (ObjectNode) original.payload();
                if (variant.equals("TOOL")) payload.put("tool", "desktop_session_snapshot");
                if (variant.equals("INVOCATION")) payload.put("invocationId", "other");
                events.set(stage, new RunEventEnvelope(variant.equals("RUN") ? "other-run" : RUN,
                        original.sequence(), original.timestamp(), original.type(),
                        original.schemaVersion() + (variant.equals("SCHEMA") ? 1 : 0),
                        variant.equals("PRODUCER") ? "model" : "framework.core", null, null, payload));
                assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), stage + ":" + variant);
            }
        }
    }

    @Test
    void missingStagesAndDuplicateOrConflictingStagesAreNotProof() {
        for (int missing = 0; missing < 3; missing++) {
            List<RunEventEnvelope> events = events();
            events.remove(missing);
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
        }
        for (int duplicate = 0; duplicate < 3; duplicate++) {
            List<RunEventEnvelope> events = events();
            events.add(events.get(duplicate));
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
        }
        List<RunEventEnvelope> events = events();
        events.add(event(4, "core.tool.receipt", 1, receipt().put("status", "UNKNOWN")));
        assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
    }

    @Test
    void modelOutputAndDisplayTextCannotRepairMissingOrConflictingRawData() {
        List<RunEventEnvelope> events = events();
        var completed = (ObjectNode) events.get(1).payload();
        completed.set("modelOutput", completed.remove("output"));
        completed.put("displayMessage", raw().toString());
        replace(events, 1, completed);
        assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());

        events = events();
        completed = (ObjectNode) events.get(1).payload();
        completed.set("modelOutput", raw().put("sessionId", "untrusted-other-session"));
        replace(events, 1, completed);
        assertEquals(1, DesktopObservationBaseline.fromEvents(events).size(), "only the raw output is authoritative");
    }

    @Test
    void nonSucceededAdmissionOrNonObservedReceiptCannotEstablishAFreshBaseline() {
        for (String status : List.of("FAILED", "UNCERTAIN", "PENDING", "REOBSERVE", "TIMED_OUT", "")) {
            List<RunEventEnvelope> events = events();
            replace(events, 1, ((ObjectNode) events.get(1).payload()).put("status", status));
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
        }
        for (String status : List.of("FAILED", "ACCEPTED", "VERIFIED", "UNKNOWN", "")) {
            List<RunEventEnvelope> events = events();
            replace(events, 2, receipt().put("status", status));
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
        }
    }

    @Test
    void exactReceiptOperationEvidenceAndApplicationBindingAreRequired() {
        for (String field : List.of("operation", "evidenceRef", "target")) {
            List<RunEventEnvelope> events = events();
            replace(events, 2, receipt().put(field, "wrong"));
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), field);
        }
        List<RunEventEnvelope> events = events();
        var receipt = receipt();
        ((ObjectNode) receipt.path("metadata")).put("applicationId", "com.example.other");
        replace(events, 2, receipt);
        assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
    }

    @Test
    void rawAndMetadataIdentityAndEveryCaptureFieldMustAgree() {
        for (String field : List.of("sessionId", "targetId", "observationId", "windowGeneration",
                "contentRevision", "capturedAtMillis")) {
            List<RunEventEnvelope> events = events();
            var receipt = receipt();
            ((ObjectNode) receipt.path("metadata")).put(field, "wrong");
            replace(events, 2, receipt);
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), field);
        }
        for (String field : List.of("targetId", "windowGeneration", "contentRevision", "capturedAtMillis")) {
            List<RunEventEnvelope> events = events();
            var completed = (ObjectNode) events.get(1).payload();
            ((ObjectNode) completed.path("output").path("frame")).put(field, "wrong");
            replace(events, 1, completed);
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), field);
        }
    }

    @Test
    void numericCoercionMissingValuesFractionalValuesAndOverflowAreRejected() {
        for (String field : List.of("schemaVersion", "windowGeneration", "contentRevision", "capturedAtMillis")) {
            for (JsonNode bad : List.of(NODES.textNode("1"), NODES.numberNode(1.5), NODES.numberNode(-1),
                    NODES.numberNode(new java.math.BigInteger("9223372036854775808")), NODES.nullNode())) {
                List<RunEventEnvelope> events = events();
                var completed = (ObjectNode) events.get(1).payload();
                ((ObjectNode) completed.path("output")).set(field, bad);
                replace(events, 1, completed);
                assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), field + ":" + bad);
            }
        }
        for (String field : List.of("windowGeneration", "contentRevision", "capturedAtMillis")) {
            for (String bad : List.of("", " 1", "1.0", "+1", "01", "-1", "9223372036854775808")) {
                List<RunEventEnvelope> events = events();
                var receipt = receipt();
                ((ObjectNode) receipt.path("metadata")).put(field, bad);
                replace(events, 2, receipt);
                assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), field + ":" + bad);
            }
        }
    }

    @Test
    void captureMustPrecedeAValidObservedTimeWithinTheInvocationJournal() {
        for (String bad : List.of("", "not-an-instant", "2026-10-03T03:59:59Z",
                "2026-10-03T04:00:04Z", "+1000000000-12-31T23:59:59.999999999Z")) {
            List<RunEventEnvelope> events = events();
            replace(events, 2, receipt().put("observedAt", bad));
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), bad);
        }
        List<RunEventEnvelope> events = events();
        replace(events, 2, receipt().put("observedAt", START.plusMillis(100).toString()));
        assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
    }

    @Test
    void sequenceOrderingIsVerifiedIndependentlyOfListOrder() {
        List<RunEventEnvelope> events = events();
        Collections.reverse(events);
        assertEquals(1, DesktopObservationBaseline.fromEvents(events).size());
        events = events();
        var completed = events.get(1);
        events.set(1, new RunEventEnvelope(RUN, 4, completed.timestamp(), completed.type(),
                completed.schemaVersion(), completed.producer(), null, null, completed.payload()));
        assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty());
    }

    @Test
    void conflictingTargetBindingsForOneSessionOrCaptureRejectBothFrames() {
        for (String variant : List.of("SESSION", "OBSERVATION")) {
            List<RunEventEnvelope> events = events();
            events.addAll(otherEvents(variant.equals("SESSION") ? "session" : "other-session",
                    "other-window", variant.equals("OBSERVATION") ? "observation" : "other-observation"));
            assertTrue(DesktopObservationBaseline.fromEvents(events).isEmpty(), variant);
        }
    }

    @Test
    void latestSupportsAReopenedSessionForTheSamePhysicalWindowWithoutTaskProof() {
        List<RunEventEnvelope> events = events();
        events.addAll(otherEvents("reopened-session", "window", "new-observation"));
        var frame = DesktopObservationBaseline.latest(events).orElseThrow();
        assertEquals("reopened-session", frame.sessionId());
        assertEquals("new-observation", frame.observationId());
        assertEquals(6, frame.sequence());
        assertEquals(2, DesktopObservationBaseline.fromEvents(events).size());
        assertTrue(DesktopObservationBaseline.fromEvents(null).isEmpty());
        assertTrue(DesktopObservationBaseline.latest(List.of()).isEmpty());
    }

    private static ObjectNode arguments() {
        return NODES.objectNode().put("sessionId", "session").put("question", "what is visible?");
    }

    private static ObjectNode raw() {
        var raw = NODES.objectNode().put("schemaVersion", 1).put("protocol", "computer-use")
                .put("kind", "desktop.observation").put("sessionId", "session")
                .put("targetId", "window").put("application", "Example")
                .put("applicationId", "com.example.reader").put("observationId", "observation")
                .put("windowGeneration", 4).put("contentRevision", 7).put("capturedAtMillis", CAPTURED);
        raw.putObject("frame").put("targetId", "window").put("windowGeneration", 4)
                .put("contentRevision", 7).put("capturedAtMillis", CAPTURED)
                .put("width", 800).put("height", 600).put("coordinateSpace", "WINDOW_FRAME_PIXELS");
        // No activeView, subject or acceptance criterion is needed to observe current window state.
        raw.putObject("content").put("summary", "untrusted visible text");
        return raw;
    }

    private static EffectReceiptV1 typedReceipt() {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("sessionId", "session");
        metadata.put("targetId", "window");
        metadata.put("observationId", "observation");
        metadata.put("applicationId", "com.example.reader");
        metadata.put("windowGeneration", "4");
        metadata.put("contentRevision", "7");
        metadata.put("capturedAtMillis", Long.toString(CAPTURED));
        return new EffectReceiptV1(INVOCATION, TOOL, "observe", "Example", EffectReceiptV1.Status.OBSERVED,
                Instant.ofEpochMilli(OBSERVED), "core.tool.completed:" + RUN + ":" + INVOCATION,
                "live owned desktop frame observed", "", metadata);
    }

    private static ObjectNode receipt() { return typedReceipt().toJson(); }

    private static List<RunEventEnvelope> events() {
        var started = NODES.objectNode().put("tool", TOOL).put("invocationId", INVOCATION);
        started.set("arguments", arguments());
        var completed = NODES.objectNode().put("tool", TOOL).put("invocationId", INVOCATION)
                .put("status", "SUCCEEDED");
        completed.set("output", raw());
        return new ArrayList<>(List.of(event(1, "core.tool.started", 1, started),
                event(2, "core.tool.completed", 2, completed), event(3, "core.tool.receipt", 1, receipt())));
    }

    private static List<RunEventEnvelope> otherEvents(String session, String target, String observation) {
        List<RunEventEnvelope> events = events();
        for (int index = 0; index < events.size(); index++) {
            var original = events.get(index);
            var payload = (ObjectNode) original.payload();
            payload.put("invocationId", "second-call");
            if (index == 0) ((ObjectNode) payload.path("arguments")).put("sessionId", session);
            if (index == 1) {
                var raw = (ObjectNode) payload.path("output");
                raw.put("sessionId", session).put("targetId", target).put("observationId", observation);
                ((ObjectNode) raw.path("frame")).put("targetId", target);
            }
            if (index == 2) {
                payload.put("evidenceRef", "core.tool.completed:" + RUN + ":second-call");
                ((ObjectNode) payload.path("metadata")).put("sessionId", session).put("targetId", target)
                        .put("observationId", observation);
                payload.put("observedAt", START.plusSeconds(4).toString());
            }
            events.set(index, event(index + 4, original.type(), original.schemaVersion(), payload));
        }
        return events;
    }

    private static RunEventEnvelope event(long sequence, String type, int version, JsonNode payload) {
        return new RunEventEnvelope(RUN, sequence, START.plusSeconds(sequence - 1), type, version,
                "framework.core", null, null, payload);
    }

    private static void replace(List<RunEventEnvelope> events, int index, JsonNode payload) {
        var original = events.get(index);
        events.set(index, new RunEventEnvelope(original.runId(), original.sequence(), original.timestamp(),
                original.type(), original.schemaVersion(), original.producer(), null, null, payload));
    }
}
