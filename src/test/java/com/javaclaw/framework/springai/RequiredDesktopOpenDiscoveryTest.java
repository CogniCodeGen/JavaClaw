package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.spi.InteractionStageContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiredDesktopOpenDiscoveryTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final RunId RUN = new RunId("desktop-discovery-run");
    private static final String APPLICATION = "com.example.reader";
    private static final String TOOL = "desktop_session_targets";
    private static final String INVOCATION = "targets-invocation";
    private static final Instant STARTED = Instant.parse("2026-10-09T08:20:00.100Z");
    private static final Instant COMPLETED = STARTED.plusMillis(5);
    private static final String EVIDENCE = "core.tool.completed:" + RUN.value() + ":" + INVOCATION;

    @Test
    void receiptNanosecondsWithinThePersistedCompletionMillisecondKeepDiscoveryAvailable() {
        List<RunEventEnvelope> events = events(COMPLETED.plusNanos(999_999));

        var discovery = RequiredDesktopOpenDiscovery.latest(RUN, events, 0, APPLICATION);

        assertNotNull(discovery, "receipt precision must not invalidate a settled native target discovery");
        assertEquals(EVIDENCE, discovery.evidenceRef());
        assertEquals(1, discovery.targets().size());
        assertEquals("native-window", discovery.targets().getFirst().path("targetId").asText());
        assertNotNull(RequiredDesktopOpenDiscovery.latestAll(RUN, events));
    }

    @Test
    void aReceiptInTheMillisecondBeforeTheStartIsRejected() {
        List<RunEventEnvelope> events = events(STARTED.minusNanos(1));

        assertNull(RequiredDesktopOpenDiscovery.latest(RUN, events, 0, APPLICATION));
        assertNull(RequiredDesktopOpenDiscovery.latestAll(RUN, events));
    }

    @Test
    void aReceiptInTheMillisecondAfterCompletionIsRejected() {
        List<RunEventEnvelope> events = events(COMPLETED.plusMillis(1));

        assertNull(RequiredDesktopOpenDiscovery.latest(RUN, events, 0, APPLICATION));
        assertNull(RequiredDesktopOpenDiscovery.latestAll(RUN, events));
    }

    @Test
    void aHostReceiptedPagePreservesItsScopeAndContinuationWithoutClaimingACompleteInventory() {
        ObjectNode arguments = NODES.objectNode().put("query", APPLICATION).put("offset", 16).put("limit", 16);
        ObjectNode output = pageOutput(16, 50, 1, true);
        var discovery = RequiredDesktopOpenDiscovery.latest(RUN,
                events(COMPLETED, arguments, output), 0, APPLICATION);

        assertNotNull(discovery);
        assertEquals(1, discovery.targets().size());
        assertFalse(discovery.complete());
        assertTrue(discovery.hasMore());
        assertEquals(17, discovery.nextOffset());
        assertEquals(16, discovery.offset());
        assertEquals(50, discovery.totalCount());
        assertEquals(APPLICATION, discovery.query());
        assertEquals("a".repeat(64), discovery.inventoryId());
    }

    @Test
    void aFinalPartialPageDoesNotBecomeACompleteInventoryAndAnInitialWholePageCanBeComplete() {
        var last = RequiredDesktopOpenDiscovery.latestAll(RUN, events(COMPLETED,
                NODES.objectNode().put("query", APPLICATION).put("offset", 16), pageOutput(16, 17, 1, false)));
        assertNotNull(last);
        assertFalse(last.complete());
        assertFalse(last.hasMore());
        assertEquals(-1, last.nextOffset());

        var whole = RequiredDesktopOpenDiscovery.latestAll(RUN, events(COMPLETED,
                NODES.objectNode().put("query", APPLICATION), pageOutput(0, 1, 1, false)));
        assertNotNull(whole);
        assertTrue(whole.complete());
    }

    @Test
    void evenADigestMatchedPageMustHaveTruthfulPaginationAndNoInputAuthority() {
        var arguments = NODES.objectNode().put("query", APPLICATION).put("offset", 16);
        for (String defect : List.of("count", "complete", "nextOffset", "query", "inputAuthority", "freshObservation")) {
            var output = pageOutput(16, 50, 1, true);
            switch (defect) {
                case "count" -> output.put("count", 2);
                case "complete" -> output.put("complete", true);
                case "nextOffset" -> output.put("nextOffset", 32);
                case "query" -> output.put("query", "different.exe");
                case "inputAuthority", "freshObservation" -> output.put(defect, true);
                default -> throw new AssertionError(defect);
            }
            assertNull(RequiredDesktopOpenDiscovery.latestAll(RUN, events(COMPLETED, arguments, output)), defect);
        }
    }

    @Test
    void aCompletePageWithALongIntactIdentityDoesNotBecomeAFalseEmptyInventory() {
        var output = pageOutput(0, 1, 1, false);
        String targetId = "native-window-" + "x".repeat(600);
        ((ObjectNode) output.path("targets").get(0)).put("targetId", targetId);

        var discovery = RequiredDesktopOpenDiscovery.latest(RUN,
                events(COMPLETED, NODES.objectNode().put("query", APPLICATION), output), 0, APPLICATION);

        assertNotNull(discovery);
        assertTrue(discovery.complete());
        assertEquals(1, discovery.targets().size());
        assertEquals(targetId, discovery.targets().getFirst().path("targetId").asText());
    }

    private static ObjectNode pageOutput(int offset, int total, int count, boolean hasMore) {
        ObjectNode output = legacyOutput().put("inventoryId", "a".repeat(64)).put("query", APPLICATION)
                .put("offset", offset).put("inventoryTotalCount", 100).put("totalCount", total)
                .put("count", count).put("hasMore", hasMore).put("complete", offset == 0 && count == total)
                .put("truncated", !(offset == 0 && count == total))
                .put("inputAuthority", false).put("freshObservation", false);
        if (hasMore) output.put("nextOffset", offset + count);
        return output;
    }

    private static List<RunEventEnvelope> events(Instant observedAt) {
        return events(observedAt, NODES.objectNode(), legacyOutput());
    }

    private static ObjectNode legacyOutput() {
        ObjectNode output = NODES.objectNode().put("schemaVersion", 1).put("protocol", "computer-use")
                .put("kind", "desktop.targets").put("count", 1);
        output.putArray("targets").addObject().put("targetId", "native-window")
                .put("providerId", "native-provider").put("applicationId", APPLICATION)
                .put("processId", 42L).put("visible", true).put("systemSurface", false);
        return output;
    }

    private static List<RunEventEnvelope> events(Instant observedAt, ObjectNode arguments, ObjectNode output) {
        String fingerprint = ToolInvocationFingerprint.create(TOOL, arguments);
        ObjectNode started = payload().put("trustedDesktopTool", true).put("fingerprint", fingerprint);
        started.set("arguments", arguments);
        ObjectNode completed = payload().put("status", "SUCCEEDED").put("errorCode", "");
        completed.set("output", output);
        ObjectNode receipt = payload().put("status", "OBSERVED").put("operation", "targets")
                .put("target", "desktop").put("evidenceRef", EVIDENCE).put("fingerprint", fingerprint)
                .put("observedAt", observedAt.toString());
        receipt.putObject("metadata").put("discoveryDigest", InteractionStageContext.sha256(output.toString()));
        return List.of(event(1, STARTED, "core.tool.started", 1, started),
                event(2, COMPLETED, "core.tool.completed", 2, completed),
                event(3, COMPLETED.plusMillis(2), "core.tool.receipt", 1, receipt));
    }

    private static ObjectNode payload() {
        return NODES.objectNode().put("tool", TOOL).put("invocationId", INVOCATION);
    }

    private static RunEventEnvelope event(long sequence, Instant timestamp, String type,
            int schemaVersion, ObjectNode payload) {
        return new RunEventEnvelope(RUN.value(), sequence, timestamp, type, schemaVersion,
                "framework.core", "discovery-test", null, payload);
    }
}
