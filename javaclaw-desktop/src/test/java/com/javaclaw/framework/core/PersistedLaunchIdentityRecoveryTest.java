package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PersistedLaunchIdentityRecoveryTest {
    private static final String LAUNCH = "desktop_session_launch_application";
    private static final String APP = "com.example.reader";
    private static final String OTHER = "com.example.calendar";

    @Test
    void oldUnknownLaunchUsesThePreexistingTrustedDirectoryToFenceEveryAlias() {
        for (String policy : List.of("LEGACY", "<missing>", "DISCOVERY_GATED")) {
            List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "", false, 1));
            var start = launch("Reader", policy);
            events.add(event(4, "core.tool.started", 1, "framework.core", start));
            events.add(event(5, "core.tool.receipt", 1, "framework.core", receipt("UNKNOWN", "MAYBE_SENT", "")));
            RunControl inherited = control();
            PersistedRunStateRestorer.restoreUnresolvedEffects(events, inherited);

            var blocked = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> nextLaunch(inherited, APP));
            assertEquals("old-run", blocked.sourceRunId());
            assertEquals("desktop.application:" + APP, blocked.resourceKey());
            assertDoesNotThrow(() -> nextLaunch(inherited, OTHER));
            assertEquals(0, inherited.toolCallCount());
        }
    }

    @Test
    void anUnboundOldUnknownLaunchUsesAWildcardRatherThanItsRequestSpelling() {
        RunControl inherited = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", 1, "framework.core", launch("Reader", "LEGACY")),
                event(2, "core.tool.receipt", 1, "framework.core", receipt("UNKNOWN", "MAYBE_SENT", ""))), inherited);

        for (String target : List.of(APP, OTHER, "unknown")) {
            var blocked = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> nextLaunch(inherited, target));
            assertEquals("desktop.application:unknown", blocked.resourceKey());
        }
    }

    @Test
    void aPairedTypedLaunchReceiptCanBindAnUnknownResourceToItsActualIdentity() {
        RunControl inherited = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", 1, "framework.core", launch("Reader", "LEGACY")),
                event(2, "core.tool.receipt", 1, "framework.core",
                        receipt("UNKNOWN", "MAYBE_SENT", "ＣＯＭ.ＥＸＡＭＰＬＥ.ＲＥＡＤＥＲ"))), inherited);

        assertThrows(PendingEffectObservationRequiredException.class, () -> nextLaunch(inherited, APP));
        assertDoesNotThrow(() -> nextLaunch(inherited, OTHER));
    }

    @Test
    void currentRunRecoveryRestoresTheSameDirectoryAndLaunchFence() {
        List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "Reader", false, 1));
        events.add(event(4, "core.tool.started", 1, "framework.core", launch(APP, "LEGACY")));
        events.add(event(5, "core.tool.receipt", 1, "framework.core", receipt("UNKNOWN", "MAYBE_SENT", "")));
        RunControl restored = control();
        var deadline = restored.deadline();
        PersistedRunStateRestorer.restore(events, restored);

        assertEquals("desktop.application:" + APP, restored.desktopLaunchResourceKey(APP));
        assertThrows(PendingEffectObservationRequiredException.class, () -> nextLaunch(restored, APP));
        assertDoesNotThrow(() -> nextLaunch(restored, OTHER));
        assertEquals(deadline, restored.deadline());
        assertEquals(2, restored.toolCallCount(), "only started tools restore the existing usage behavior");
    }

    @Test
    void aConflictingActualIdentityCannotReplaceAnAlreadyBoundLaunchResource() {
        List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "", false, 1));
        events.add(event(4, "core.tool.started", 1, "framework.core", launch("Reader", "LEGACY")));
        events.add(event(5, "core.tool.receipt", 1, "framework.core", receipt("UNKNOWN", "MAYBE_SENT", OTHER)));
        RunControl inherited = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(events, inherited);

        for (String target : List.of(APP, OTHER)) {
            var blocked = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> nextLaunch(inherited, target));
            assertEquals("desktop.application:unknown", blocked.resourceKey());
        }
    }

    @Test
    void acceptedSentAndReusedNotSentLaunchesDoNotBecomeUncertainAfterRestart() {
        for (String delivery : List.of("SENT", "NOT_SENT")) {
            RunControl inherited = control();
            PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                    event(1, "core.tool.started", 1, "framework.core", launch("Reader", "LEGACY")),
                    event(2, "core.tool.receipt", 1, "framework.core", receipt("ACCEPTED", delivery, APP))), inherited);

            assertDoesNotThrow(() -> nextLaunch(inherited, APP));
            assertDoesNotThrow(() -> nextLaunch(inherited, OTHER));
            assertEquals(0, inherited.toolCallCount());
        }
    }

    @Test
    void wrongToolOperationProducerSchemaOrInvocationCannotBindOrSettleAnOldLaunch() {
        for (String variant : List.of("TOOL", "OPERATION", "PRODUCER", "SCHEMA", "INVOCATION", "STATUS")) {
            ObjectNode claimed = receipt("ACCEPTED", "SENT", APP);
            if (variant.equals("TOOL")) claimed.put("tool", "desktop_session_open");
            if (variant.equals("OPERATION")) claimed.put("operation", "open");
            if (variant.equals("INVOCATION")) claimed.put("invocationId", "different-call");
            if (variant.equals("STATUS")) claimed.put("status", "invented-status");
            RunControl inherited = control();
            PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                    event(1, "core.tool.started", 1, "framework.core", launch("Reader", "LEGACY")),
                    event(2, "core.tool.receipt", variant.equals("SCHEMA") ? 2 : 1,
                            variant.equals("PRODUCER") ? "model" : "framework.core", claimed)), inherited);

            var blocked = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> nextLaunch(inherited, OTHER), variant);
            assertEquals("desktop.application:unknown", blocked.resourceKey(), variant);
        }
    }

    @Test
    void aLaunchReceiptWithAnotherStartedToolCannotCreateALaunchResource() {
        ObjectNode wrongStart = launch("Reader", "LEGACY").put("tool", "email_send");
        RunControl inherited = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", 1, "framework.core", wrongStart),
                event(2, "core.tool.receipt", 1, "framework.core", receipt("ACCEPTED", "SENT", APP))), inherited);

        assertThrows(ToolPermissionDeniedException.class, () -> inherited.assertRepairRetryAllowed(
                "new-fingerprint", "old-effect", false, ToolEffectPolicy.LEGACY, ""));
    }

    @Test
    void exactCanonicalIdCanUseACompleteFilteredSnapshotButUnqueriedAliasesCannot() {
        var identities = DesktopApplicationIdentityBindings.fromEvents(catalog(1, "Reader", false, 1));
        assertEquals(APP, identities.canonicalIdentity("Reader", "old-run", 4));
        assertEquals(APP, identities.canonicalIdentity(APP, "old-run", 4));
        assertEquals("", identities.canonicalIdentity("Book Reader", "old-run", 4));
        assertEquals("", identities.canonicalIdentity(APP, "different-run", 4));
        assertEquals("", identities.canonicalIdentity(APP, "old-run", 3));
    }

    @Test
    void truncatedIncompleteOrModelOnlyDirectoriesDoNotBindLaunchResources() {
        for (String variant : List.of("TRUNCATED", "INCOMPLETE", "MODEL_OUTPUT", "PRODUCER")) {
            List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "Reader", variant.equals("TRUNCATED"),
                    variant.equals("INCOMPLETE") ? 2 : 1));
            if (variant.equals("MODEL_OUTPUT")) {
                var completed = (ObjectNode) events.get(1).payload();
                completed.set("modelOutput", completed.remove("output"));
                events.set(1, event(2, "core.tool.completed", 2, "framework.core", completed));
            }
            if (variant.equals("PRODUCER")) events.set(1,
                    event(2, "core.tool.completed", 2, "model", (ObjectNode) events.get(1).payload()));
            events.add(event(4, "core.tool.started", 1, "framework.core", launch(APP, "LEGACY")));
            events.add(event(5, "core.tool.receipt", 1, "framework.core", receipt("UNKNOWN", "MAYBE_SENT", "")));
            RunControl inherited = control();
            PersistedRunStateRestorer.restoreUnresolvedEffects(events, inherited);

            var blocked = assertThrows(PendingEffectObservationRequiredException.class,
                    () -> nextLaunch(inherited, OTHER), variant);
            assertEquals("desktop.application:unknown", blocked.resourceKey());
        }
    }

    @Test
    void aDirectoryObservedAfterDispatchCannotRetroactivelyBindTheOriginalRequest() {
        List<RunEventEnvelope> events = new ArrayList<>();
        events.add(event(1, "core.tool.started", 1, "framework.core", launch("Reader", "LEGACY")));
        events.add(event(2, "core.tool.receipt", 1, "framework.core", receipt("UNKNOWN", "MAYBE_SENT", "")));
        events.addAll(catalog(3, "", false, 1));
        RunControl inherited = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(events, inherited);

        assertThrows(PendingEffectObservationRequiredException.class, () -> nextLaunch(inherited, OTHER));
    }

    @Test
    void theHostsPersistedCanonicalResourceSurvivesRestartWithoutAnotherDirectory() {
        var started = launch("Reader Launch", "DISCOVERY_GATED")
                .put("resourceKey", "desktop.application:" + APP);
        RunControl inherited = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(
                event(1, "core.tool.started", 1, "framework.core", started),
                event(2, "core.tool.receipt", 1, "framework.core", receipt("UNKNOWN", "MAYBE_SENT", ""))), inherited);

        var blocked = assertThrows(PendingEffectObservationRequiredException.class,
                () -> nextLaunch(inherited, APP));
        assertEquals("desktop.application:" + APP, blocked.resourceKey());
        assertDoesNotThrow(() -> nextLaunch(inherited, OTHER));
    }

    @Test
    void malformedHostResourceOrLegacyResourceCannotNarrowAnUnknownLaunchBarrier() {
        for (String resource : List.of("desktop.application:", "desktop.application:../bad",
                "desktop.application:" + "x".repeat(257), "different.resource:" + APP)) {
            RunControl inherited = control();
            PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(event(1, "core.tool.started", 1,
                    "framework.core", launch("Reader", "DISCOVERY_GATED").put("resourceKey", resource))), inherited);
            assertThrows(PendingEffectObservationRequiredException.class, () -> nextLaunch(inherited, OTHER));
        }
        RunControl old = control();
        PersistedRunStateRestorer.restoreUnresolvedEffects(List.of(event(1, "core.tool.started", 1,
                "framework.core", launch("Reader", "LEGACY").put("resourceKey", "desktop.application:" + APP))), old);
        assertThrows(PendingEffectObservationRequiredException.class, () -> nextLaunch(old, OTHER));
    }

    private static void nextLaunch(RunControl control, String app) {
        control.assertRepairRetryAllowed("new-fingerprint", "new-effect", false,
                ToolEffectPolicy.DISCOVERY_GATED, "desktop.application:" + app);
    }

    private static RunControl control() { return new RunControl(RunBudget.UNBOUNDED, Clock.systemUTC()); }

    private static ObjectNode launch(String app, String policy) {
        var value = JsonNodeFactory.instance.objectNode().put("tool", LAUNCH).put("invocationId", "launch-call")
                .put("fingerprint", "launch-fingerprint").put("effectKey", "old-effect").put("idempotent", false);
        value.putObject("arguments").put("application", app);
        if (!policy.equals("<missing>")) value.put("effectPolicy", policy);
        return value;
    }

    private static ObjectNode receipt(String status, String delivery, String appId) {
        var value = JsonNodeFactory.instance.objectNode().put("tool", LAUNCH).put("operation", "launch_application")
                .put("invocationId", "launch-call").put("status", status);
        value.putObject("metadata").put("delivery", delivery).put("applicationId", appId);
        return value;
    }

    private static List<RunEventEnvelope> catalog(long sequence, String query, boolean truncated, int total) {
        String invocation = "catalog-" + sequence;
        var start = JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_applications")
                .put("invocationId", invocation).put("fingerprint", invocation).put("idempotent", true);
        start.putObject("arguments").put("query", query).put("offset", 0);
        var completed = JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_applications")
                .put("invocationId", invocation).put("status", "SUCCEEDED");
        var output = completed.putObject("output").put("schemaVersion", 1).put("protocol", "computer-use")
                .put("kind", "desktop.applications").put("catalogId", "a".repeat(64)).put("query", query)
                .put("offset", 0).put("count", 1).put("totalCount", total)
                .put("catalogTotalCount", query.isEmpty() ? total : 100)
                .put("truncated", truncated).put("hasMore", total > 1);
        if (total > 1) output.put("nextOffset", 1);
        output.putArray("applications").addObject().put("name", "Reader").put("displayName", "Reader")
                .put("applicationId", APP).put("launchName", APP).putArray("aliases").add("Book Reader");
        var receipt = JsonNodeFactory.instance.objectNode().put("tool", "desktop_session_applications")
                .put("invocationId", invocation).put("operation", "applications").put("status", "OBSERVED")
                .put("observedAt", Instant.ofEpochSecond(sequence + 2).toString())
                .put("evidenceRef", "core.tool.completed:old-run:" + invocation);
        return List.of(event(sequence, "core.tool.started", 1, "framework.core", start),
                event(sequence + 1, "core.tool.completed", 2, "framework.core", completed),
                event(sequence + 2, "core.tool.receipt", 1, "framework.core", receipt));
    }

    private static RunEventEnvelope event(long sequence, String type, int version, String producer, ObjectNode value) {
        return new RunEventEnvelope("old-run", sequence, Instant.ofEpochSecond(sequence),
                type, version, producer, null, null, value);
    }
}
