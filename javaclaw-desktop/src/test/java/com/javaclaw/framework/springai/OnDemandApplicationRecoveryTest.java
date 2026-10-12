package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.StepId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnDemandApplicationRecoveryTest {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final RunId RUN = new RunId("application-identity-recovery");

    @Test
    void confirmedIdentityRejectionRequiresInstalledApplicationsForAnyApplicationLabel() {
        for (String application : List.of("阅读器", "Calendar", "Diario")) {
            for (String reason : List.of("APPLICATION_NOT_FOUND", "AMBIGUOUS_APPLICATION", "INVALID_ARGUMENTS")) {
                var failed = rejected(application, reason);
                assertEquals(OnDemandApplicationRecovery.APPLICATIONS,
                        OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(
                                List.of(failed.step()), List.of(failed.receipt())));
                var cursor = ComputerUseSessionCursor.derive(List.of(failed.step()),
                        List.of(failed.receipt()), null, List.of());
                assertEquals(ComputerUseSessionCursor.Phase.DISCOVER_APPLICATIONS, cursor.phase());
                assertFalse(cursor.inputAllowed());
            }
        }
    }

    @Test
    void aLaterProbeCannotEraseTheConfirmedLaunchFailure() {
        var failed = rejected("本地应用", "APPLICATION_NOT_FOUND");
        var probe = step(3, "probe", "desktop_session_probe", "SUCCEEDED", NODES.objectNode(), null);
        var state = OnDemandApplicationRecovery.derive(List.of(failed.step(), probe), List.of(failed.receipt()));
        assertEquals(OnDemandApplicationRecovery.APPLICATIONS, state.requiredTool());
        assertEquals("本地应用", state.requestedApplication());
        assertEquals("APPLICATION_NOT_FOUND", state.reasonCode());
        assertEquals(List.of(failed.step()), state.contextSteps());
    }

    @Test
    void trustedCatalogOffersLaunchAndRetainsBothTheFailureAndIdentitiesAcrossProbe() {
        var failed = rejected("本地应用", "APPLICATION_NOT_FOUND");
        var catalog = catalog(3, "SUCCEEDED");
        var probe = step(5, "probe", "desktop_session_probe", "SUCCEEDED", NODES.objectNode(), null);
        var steps = List.of(failed.step(), catalog.step(), probe);
        var receipts = List.of(failed.receipt(), catalog.receipt());
        var state = OnDemandApplicationRecovery.derive(steps, receipts);
        assertEquals(OnDemandApplicationRecovery.LAUNCH, state.requiredTool());
        assertEquals(List.of(failed.step(), catalog.step()), state.contextSteps());
        assertEquals(OnDemandApplicationRecovery.LAUNCH,
                OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(steps, receipts));
        assertEquals(ComputerUseSessionCursor.Phase.SELECT_APPLICATION,
                ComputerUseSessionCursor.derive(steps, receipts, null, List.of()).phase());
    }

    @Test
    void initialCatalogBeforeAnyLaunchAllowsPrimaryToChooseAnInstalledIdentity() {
        var catalog = catalog(1, "SUCCEEDED");
        var state = OnDemandApplicationRecovery.derive(List.of(catalog.step()), List.of(catalog.receipt()));
        assertEquals(OnDemandApplicationRecovery.LAUNCH, state.requiredTool());
        assertTrue(OnDemandApplicationRecovery.applicationsAttempted(List.of(catalog.step())));
        assertEquals(List.of(catalog.step()), state.contextSteps());
    }

    @Test
    void anUnsupportedOrFailedCatalogDegradesToLaunchWithoutAnotherCatalogAttempt() {
        var failed = rejected("Reader", "APPLICATION_NOT_FOUND");
        var catalog = catalog(3, "FAILED");
        var probe = step(5, "probe", "desktop_session_probe", "SUCCEEDED", NODES.objectNode(), null);
        var state = OnDemandApplicationRecovery.derive(List.of(failed.step(), catalog.step(), probe),
                List.of(failed.receipt(), catalog.receipt()));
        assertEquals(OnDemandApplicationRecovery.LAUNCH, state.requiredTool());
        assertEquals("FAILED", state.payload().path("catalogStatus").asText());
        assertEquals(List.of(failed.step(), catalog.step()), state.contextSteps());
    }

    @Test
    void unknownLaunchIsNotClearedByACatalogOrOrdinaryObservation() {
        var unknown = step(1, "unknown", OnDemandApplicationRecovery.LAUNCH, "UNCERTAIN",
                launchData("Reader"), NODES.objectNode().put("application", "Reader"));
        var receipt = receipt(2, "unknown", OnDemandApplicationRecovery.LAUNCH,
                "launch_application", "UNKNOWN", NODES.objectNode().put("delivery", "MAYBE_SENT"));
        var catalog = catalog(3, "SUCCEEDED");
        var observed = step(5, "observe", "desktop_session_observe", "SUCCEEDED", NODES.objectNode(),
                NODES.objectNode().put("sessionId", "other-session"));
        var observedReceipt = receipt(6, "observe", "desktop_session_observe", "observe", "OBSERVED",
                NODES.objectNode().put("sessionId", "other-session").put("targetId", "other-window")
                        .put("observationId", "other-frame"));
        assertEquals("desktop_session_targets", OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(
                List.of(unknown, catalog.step(), observed),
                List.of(receipt, catalog.receipt(), observedReceipt)));
        assertFalse(OnDemandApplicationRecovery.derive(List.of(unknown, catalog.step(), observed),
                List.of(receipt, catalog.receipt(), observedReceipt)).needsIdentity(),
                "catalog pages must not schedule another launch after resolving an uncertain launch");
    }

    @Test
    void aFailedInvocationWithUnknownDeliveryStillRequiresTargetDiscovery() {
        var failed = step(1, "failure", OnDemandApplicationRecovery.LAUNCH, "FAILED",
                NODES.objectNode().put("detail", "APPLICATION_NOT_FOUND"),
                NODES.objectNode().put("application", "Reader"));
        var receipt = receipt(2, "failure", OnDemandApplicationRecovery.LAUNCH,
                "launch_application", "UNKNOWN", NODES.objectNode().put("delivery", "MAYBE_SENT"));
        assertEquals("desktop_session_targets",
                OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(List.of(failed), List.of(receipt)));
        assertFalse(OnDemandApplicationRecovery.derive(List.of(failed), List.of(receipt)).needsIdentity());
    }

    @Test
    void rejectedLaunchWithoutMatchingRawAndReceiptCannotCertifyNotSent() {
        var failed = rejected("Reader", "APPLICATION_NOT_FOUND");
        var wrongPayload = (ObjectNode) failed.receipt().payload();
        ((ObjectNode) wrongPayload.path("metadata")).put("requestedApplication", "Another application");
        var wrong = event(RUN, 2, "framework.core", wrongPayload);
        assertEquals("desktop_session_targets", OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(
                List.of(failed.step()), List.of(wrong)));
        var state = OnDemandApplicationRecovery.derive(List.of(failed.step()), List.of(wrong));
        assertFalse(state.needsIdentity());
        assertEquals("UNKNOWN", state.payload().path("selectedLaunch").path("status").asText());
        assertEquals("MAYBE_SENT", state.payload().path("selectedLaunch").path("delivery").asText(),
                "a receipt with mismatched arguments cannot certify safe retry in the model projection");
    }

    @Test
    void foreignRunOrExtensionReceiptCannotStartIdentityRecovery() {
        var failed = rejected("Reader", "APPLICATION_NOT_FOUND");
        for (var untrusted : List.of(event(new RunId("foreign-run"), 2, "framework.core", failed.receipt().payload()),
                event(RUN, 2, "extension.provider", failed.receipt().payload()))) {
            var state = OnDemandApplicationRecovery.derive(List.of(failed.step()), List.of(untrusted));
            assertFalse(state.needsIdentity());
            assertEquals("", state.requiredTool());
        }
    }

    @Test
    void deniedLaunchCannotBeReinterpretedAsAnApplicationIdentityError() {
        var denied = rejected("Reader", "ACCESS_DENIED");
        assertTrue(OnDemandApplicationRecovery.confirmedNotSent(denied.step(), denied.receipt().payload()));
        assertFalse(OnDemandApplicationRecovery.derive(List.of(denied.step()), List.of(denied.receipt())).needsIdentity());
    }

    @Test
    void pagedCatalogRetainsEarlierIdentitiesAndTheFailureAcrossProbe() {
        var failure = rejected("Reader", "APPLICATION_NOT_FOUND");
        var first = catalog(3, "SUCCEEDED", 0, true);
        var second = catalog(5, "SUCCEEDED", 64, false);
        var pending = OnDemandApplicationRecovery.derive(List.of(failure.step(), first.step()),
                List.of(failure.receipt(), first.receipt()));
        assertTrue(pending.catalogHasMore());
        assertEquals(64, pending.payload().path("nextOffset").asInt());
        var probe = step(7, "probe", "desktop_session_probe", "SUCCEEDED", NODES.objectNode(), null);
        var complete = OnDemandApplicationRecovery.derive(
                List.of(failure.step(), first.step(), second.step(), probe),
                List.of(failure.receipt(), first.receipt(), second.receipt()));
        assertEquals(List.of(failure.step(), second.step()), complete.contextSteps());
        assertEquals(2, complete.payload().path("applications").size());
        assertEquals("com.example.reader64", complete.payload().path("applications").get(1)
                .path("launchName").asText());
        assertFalse(complete.catalogHasMore());
        assertEquals(OnDemandApplicationRecovery.LAUNCH, complete.requiredTool());
    }

    @Test
    void typedRejectionBindsNormalizedLaunchArguments() {
        var rejected = rejected("  Reader \n", "APPLICATION_NOT_FOUND");
        assertTrue(OnDemandApplicationRecovery.confirmedNotSent(rejected.step(), rejected.receipt().payload()));
        assertEquals("Reader", OnDemandApplicationRecovery.derive(List.of(rejected.step()),
                List.of(rejected.receipt())).requestedApplication());
    }

    @Test
    void inspectingInstalledApplicationsAfterAnAcceptedLaunchDoesNotScheduleAnotherLaunch() {
        var launched = step(1, "launch", OnDemandApplicationRecovery.LAUNCH, "SUCCEEDED", launchData("Reader"),
                NODES.objectNode().put("application", "Reader"));
        var accepted = receipt(2, "launch", OnDemandApplicationRecovery.LAUNCH, "launch_application", "ACCEPTED",
                NODES.objectNode().put("requestedApplication", "Reader").put("delivery", "SENT"));
        var catalog = catalog(3, "SUCCEEDED");
        assertEquals("", OnDemandApplicationRecovery.derive(List.of(launched, catalog.step()),
                List.of(accepted, catalog.receipt())).requiredTool());
    }

    @Test
    void acceptedLaunchRetiresCatalogProjectionButPreservesExactIdentityAndReceipt() {
        var catalog = catalog(1, "SUCCEEDED");
        var data = launchData("com.example.meeting").put("applicationId", "com.example.meeting")
                .put("processId", 3243).put("delivery", "SENT");
        var launched = step(3, "launch", OnDemandApplicationRecovery.LAUNCH, "SUCCEEDED", data,
                NODES.objectNode().put("application", "com.example.meeting"));
        var accepted = receipt(4, "launch", OnDemandApplicationRecovery.LAUNCH, "launch_application", "ACCEPTED",
                NODES.objectNode().put("requestedApplication", "com.example.meeting").put("delivery", "SENT"));
        accepted = event(RUN, 4, "framework.core", ((ObjectNode) accepted.payload()).put("evidenceRef", "tool:launch-proof"));
        var state = OnDemandApplicationRecovery.derive(List.of(catalog.step(), launched), List.of(catalog.receipt(), accepted));
        assertFalse(state.needsIdentity());
        assertEquals(List.of(launched), state.contextSteps());
        assertEquals(1, state.pages().size(), "journal catalog remains available independently of prompt projection");
        assertFalse(state.catalogHasMore());
        var payload = state.payload("打开会议应用查看联系人", 12_000);
        assertFalse(payload.has("applications"));
        assertFalse(payload.has("catalogStatus"));
        assertEquals("com.example.meeting", payload.path("selectedLaunch").path("applicationId").asText());
        assertEquals(3243, payload.path("selectedLaunch").path("processId").asLong());
        assertEquals("ACCEPTED", payload.path("selectedLaunch").path("status").asText());
        assertEquals("SENT", payload.path("selectedLaunch").path("delivery").asText());
        assertEquals("tool:launch-proof", payload.path("selectedLaunch").path("evidenceRef").asText());
        assertTrue(payload.toString().length() < 1000);
    }

    @Test
    void uncertainLaunchRetiresCatalogWithoutLosingItsDeliveryFence() {
        var catalog = catalog(1, "SUCCEEDED");
        var launched = step(3, "unknown", OnDemandApplicationRecovery.LAUNCH, "UNCERTAIN", launchData("Reader"),
                NODES.objectNode().put("application", "Reader"));
        var unknown = receipt(4, "unknown", OnDemandApplicationRecovery.LAUNCH, "launch_application", "UNKNOWN",
                NODES.objectNode().put("requestedApplication", "Reader").put("delivery", "MAYBE_SENT"));
        var laterCatalog = catalog(5, "SUCCEEDED");
        var state = OnDemandApplicationRecovery.derive(List.of(catalog.step(), launched, laterCatalog.step()),
                List.of(catalog.receipt(), unknown, laterCatalog.receipt()));
        assertEquals(List.of(launched), state.contextSteps());
        assertEquals("", state.requiredTool());
        assertFalse(state.payload().has("applications"));
        assertEquals("unknown", state.payload().path("selectedLaunch").path("invocationId").asText());
        assertEquals("UNKNOWN", state.payload().path("selectedLaunch").path("status").asText());
        assertEquals("MAYBE_SENT", state.payload().path("selectedLaunch").path("delivery").asText());
    }

    @Test
    void existingApplicationProofPreservesAcceptedNotSentAndRoutesToDiscoveryWithoutAnotherLaunch() {
        var data = launchData("com.example.reader").put("applicationId", "com.example.reader")
                .put("processId", 42).put("dispatchAttempted", false).put("delivery", "NOT_SENT");
        var launched = step(1, "existing", OnDemandApplicationRecovery.LAUNCH, "SUCCEEDED", data,
                NODES.objectNode().put("application", "com.example.reader"));
        var accepted = receipt(2, "existing", OnDemandApplicationRecovery.LAUNCH, "launch_application", "ACCEPTED",
                NODES.objectNode().put("requestedApplication", "com.example.reader").put("delivery", "NOT_SENT")
                        .put("dispatchAttempted", "false").put("processId", "42")
                        .put("applicationId", "com.example.reader"));
        var state = OnDemandApplicationRecovery.derive(List.of(launched), List.of(accepted));
        assertFalse(state.needsIdentity());
        assertEquals("ACCEPTED", state.payload().path("selectedLaunch").path("status").asText());
        assertEquals("NOT_SENT", state.payload().path("selectedLaunch").path("delivery").asText());
        assertFalse(state.payload().path("selectedLaunch").path("dispatchAttempted").asBoolean(true));
        assertEquals("desktop_session_targets", OnDemandDesktopPrerequisites.pendingDesktopPrerequisite(
                List.of(launched), List.of(accepted)));
        assertEquals("desktop_session_targets", ComputerUseSessionCursor.derive(List.of(launched),
                List.of(accepted), null, List.of()).requiredTool());

        var mismatchedPayload = (ObjectNode) accepted.payload();
        ((ObjectNode) mismatchedPayload.path("metadata")).put("processId", "99");
        accepted = event(RUN, 2, "framework.core", mismatchedPayload);
        var mismatch = OnDemandApplicationRecovery.derive(List.of(launched), List.of(accepted));
        assertEquals("UNKNOWN", mismatch.payload().path("selectedLaunch").path("status").asText(),
                "a mismatched process cannot certify that this exact existing application was reused");
    }

    @Test
    void largeCatalogProjectionPrioritizesTheTaskAndRetainsWholeExactIdentities() {
        var first = largeCatalog(1, 0, 64, true);
        var second = largeCatalog(3, 64, 51, false);
        var state = OnDemandApplicationRecovery.derive(List.of(first.step(), second.step()),
                List.of(first.receipt(), second.receipt()));
        var payload = state.payload("打开会议应用查看联系人", 12_000);
        assertTrue(payload.toString().length() <= 12_000);
        assertEquals(115, payload.path("catalogObservedCount").asInt());
        assertTrue(payload.path("catalogProjectionTruncated").asBoolean());
        assertTrue(state.catalogProjectionNeedsQuery("打开会议应用查看联系人", 12_000));
        assertTrue(payload.path("catalogCoverageComplete").asBoolean());
        assertFalse(payload.path("catalogTruncated").asBoolean(), "projection coverage differs from native truncation");
        assertEquals("com.example.meeting", payload.path("applications").get(0).path("applicationId").asText());
        assertEquals("会议启动名", payload.path("applications").get(0).path("launchName").asText());
        assertEquals("会议应用", payload.path("applications").get(0).path("aliases").get(0).asText());
        assertEquals(2, state.pages().size());
        assertEquals(51, second.step().output().path("rawOutput").path("applications").size());
        for (int budget : List.of(1200, 2000, 4000, 8000, 12_000))
            assertTrue(state.payload("打开会议应用查看联系人", budget).toString().length() <= budget);
    }

    @Test
    void differentQueryOrSnapshotDoesNotMixCatalogPages() {
        var first = scopedCatalog(catalog(1, "SUCCEEDED", 0, true), "snapshot-one", "Reader");
        var second = scopedCatalog(catalog(3, "SUCCEEDED", 64, false), "snapshot-one", "Calendar");
        var state = OnDemandApplicationRecovery.derive(List.of(first.step(), second.step()),
                List.of(first.receipt(), second.receipt()));
        assertEquals(List.of(second.step()), state.pages());
        assertEquals(1, state.payload().path("applications").size());
        assertFalse(state.payload().path("catalogCoverageComplete").asBoolean(),
                "a last page from a new query must not claim that preceding pages were observed");
        var updated = scopedCatalog(second, "snapshot-two", "Reader");
        var changed = OnDemandApplicationRecovery.derive(List.of(first.step(), updated.step()),
                List.of(first.receipt(), updated.receipt()));
        assertEquals(List.of(updated.step()), changed.pages());
        assertEquals("snapshot-two", changed.payload().path("catalogId").asText());
    }

    private static Invocation largeCatalog(long sequence, int offset, int count, boolean hasMore) {
        var invocation = catalog(sequence, "SUCCEEDED", offset, hasMore);
        var data = (ObjectNode) invocation.step().output().path("rawOutput");
        data.put("catalogId", "complete-snapshot").put("query", "").put("count", count)
                .put("totalCount", 115).put("catalogTotalCount", 115);
        var apps = data.putArray("applications");
        for (int index = offset; index < offset + count; index++) {
            var entry = apps.addObject().put("name", "Utility " + index).put("displayName", "工具 " + index)
                    .put("applicationId", "com.example.utility" + index).put("launchName", "Utility Launcher " + index);
            entry.putArray("aliases").add("冗余名称".repeat(40));
            if (index == 114) {
                entry.put("applicationId", "com.example.meeting").put("launchName", "会议启动名");
                entry.putArray("aliases").add("会议应用");
            }
        }
        return withCatalogData(invocation, data);
    }

    private static Invocation scopedCatalog(Invocation source, String catalogId, String query) {
        return withCatalogData(source, ((ObjectNode) source.step().output().path("rawOutput"))
                .put("catalogId", catalogId).put("query", query));
    }

    private static Invocation withCatalogData(Invocation source, ObjectNode data) {
        var original = source.step();
        return new Invocation(step(original.startSequence(), original.input().path("invocationId").asText(),
                original.input().path("tool").asText(), original.output().path("status").asText(), data,
                (ObjectNode) original.input().path("arguments")), source.receipt());
    }

    private static Invocation rejected(String application, String reason) {
        ObjectNode data = launchData(application.strip()).put("admission", "REJECTED").put("status", "FAILED")
                .put("delivery", "NOT_SENT").put("reasonCode", reason).put("dispatchAttempted", false);
        var step = step(1, "rejected", OnDemandApplicationRecovery.LAUNCH, "FAILED", data,
                NODES.objectNode().put("application", application));
        var receipt = receipt(2, "rejected", OnDemandApplicationRecovery.LAUNCH, "launch_application", "FAILED",
                NODES.objectNode().put("delivery", "NOT_SENT").put("reasonCode", reason)
                        .put("requestedApplication", application.strip()));
        return new Invocation(step, receipt);
    }

    private static ObjectNode launchData(String application) {
        ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.launch")
                .put("requestedApplication", application).put("processId", 0);
        data.putArray("targets");
        return data;
    }

    private static Invocation catalog(long sequence, String status) {
        return catalog(sequence, status, 0, false);
    }

    private static Invocation catalog(long sequence, String status, int offset, boolean hasMore) {
        ObjectNode data = NODES.objectNode().put("schemaVersion", 1).put("kind", "desktop.applications");
        if (status.equals("SUCCEEDED")) {
            data.putArray("applications").addObject().put("name", "Reader").put("displayName", "Reader")
                    .put("applicationId", "com.example.reader" + offset).put("launchName", "com.example.reader" + offset)
                    .putArray("aliases").add("本地应用");
            data.put("count", 1).put("truncated", false).put("offset", offset).put("hasMore", hasMore);
            if (hasMore) data.put("nextOffset", 64);
        } else data.put("reasonCode", "UNSUPPORTED").put("detail", "catalog unsupported by this provider");
        var step = step(sequence, "applications-" + sequence, OnDemandApplicationRecovery.APPLICATIONS, status, data,
                NODES.objectNode().put("offset", offset));
        return new Invocation(step, receipt(sequence + 1, "applications-" + sequence, OnDemandApplicationRecovery.APPLICATIONS,
                "applications", status.equals("SUCCEEDED") ? "OBSERVED" : "FAILED", NODES.objectNode()));
    }

    private static AgentStep step(long sequence, String invocation, String tool, String status,
            JsonNode raw, ObjectNode arguments) {
        ObjectNode input = NODES.objectNode().put("tool", tool).put("invocationId", invocation);
        input.set("arguments", arguments == null ? NODES.objectNode() : arguments);
        ObjectNode output = NODES.objectNode().put("status", status);
        output.set("rawOutput", raw);
        return new AgentStep(StepId.tool(RUN, invocation), "thread", RUN, AgentStep.Kind.TOOL,
                AgentStep.State.COMPLETED, "model-step", input, output, null, null,
                Instant.EPOCH, Instant.EPOCH, sequence, sequence + 1);
    }

    private static RunEventEnvelope receipt(long sequence, String invocation, String tool,
            String operation, String status, ObjectNode metadata) {
        ObjectNode data = NODES.objectNode().put("invocationId", invocation).put("tool", tool)
                .put("operation", operation).put("status", status);
        data.set("metadata", metadata);
        return event(RUN, sequence, "framework.core", data);
    }

    private static RunEventEnvelope event(RunId run, long sequence, String producer, JsonNode data) {
        return new RunEventEnvelope(run.value(), sequence, Instant.EPOCH,
                "core.tool.receipt", 1, producer, null, null, data);
    }

    private record Invocation(AgentStep step, RunEventEnvelope receipt) { }
}
