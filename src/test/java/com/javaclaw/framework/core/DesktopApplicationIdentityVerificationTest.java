package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskContractV2;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterion;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.spi.EffectReceiptV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopApplicationIdentityVerificationTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final TrustedCapabilityRegistry CAPABILITIES = TrustedCapabilityRegistry.builtins();
    private static final String QQ_ID = "com.tencent.qq";

    @Test
    void nativeCatalogBindsQqNameToLaunchAndObservedWindowAcrossBothContractSchemas() {
        List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "QQ", 0, 1, 115,
                false, "a", app("QQ", QQ_ID)));
        events.add(desktop(4, "launch_application", QQ_ID, QQ_ID, ""));
        events.add(desktop(5, "open", "QQ", QQ_ID, ""));
        events.add(desktop(6, "observe", "QQ", QQ_ID, "contacts list"));
        var v3 = contract("QQ");
        var result = TaskResultEvaluator.evaluateV3(v3, events, "", CAPABILITIES);
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
        assertEquals(List.of("desktop:4", "desktop:6"), result.evidenceRefs());
        assertEquals("QQ", v3.criteria().getFirst().target(), "the durable contract is unchanged");

        TaskContractV2 v2 = new TaskContractV2(2, "request", "QQ", List.of(
                new TaskCriterion("launch", "launch", "QQ", "launch_application", "ACCEPTED"),
                new TaskCriterion("open", "open", "QQ", "open", "ACCEPTED"),
                new TaskCriterion("view", "view", "QQ", "observe", "OBSERVED", "contacts list")),
                true, true, "definition");
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluateV2(v2, events, "").outcome());
    }

    @Test
    void catalogBindingSupportsOtherApplicationsAndUnicodeNormalizedExactAliases() {
        List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "Notes", 0, 1, 115,
                false, "a", app("Notes", "com.apple.Notes")));
        events.add(desktop(4, "launch_application", "com.apple.Notes", "com.apple.Notes", ""));
        events.add(desktop(5, "observe", "备忘录", "com.apple.Notes", "contacts list"));
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, TaskResultEvaluator.evaluateV3(
                contract("Ｎｏｔｅｓ"), events, "", CAPABILITIES).outcome());
    }

    @Test
    void anAmbiguousAliasCannotUseTheChosenAppEvenIfItsDisplayNameMatches() {
        List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "QQ", 0, 2, 115,
                false, "a", app("QQ", QQ_ID), app("QQ", "com.example.qq")));
        events.add(desktop(4, "launch_application", "QQ", QQ_ID, ""));
        events.add(desktop(5, "observe", "QQ", QQ_ID, "contacts list"));
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluateV3(contract("QQ"), events, "", CAPABILITIES).outcome());
    }

    @Test
    void mismatchedNativeAppIdCannotPassThroughTheExpectedDisplayName() {
        List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "QQ", 0, 1, 115,
                false, "a", app("QQ", QQ_ID)));
        events.add(desktop(4, "launch_application", "QQ", "com.example.qq", ""));
        events.add(desktop(5, "observe", "QQ", "com.example.qq", "contacts list"));
        assertEquals(TaskOutcome.UNVERIFIED,
                TaskResultEvaluator.evaluateV3(contract("QQ"), events, "", CAPABILITIES).outcome());
    }

    @Test
    void incompleteAndTruncatedCatalogsCannotBindAnAlias() {
        for (var catalog : List.of(catalog(1, "QQ", 0, 2, 115, false, "a", app("QQ", QQ_ID)),
                catalog(1, "QQ", 0, 1, 115, true, "a", app("QQ", QQ_ID)))) {
            var events = new ArrayList<>(catalog);
            events.add(desktop(4, "launch_application", QQ_ID, QQ_ID, ""));
            events.add(desktop(5, "observe", "QQ", QQ_ID, "contacts list"));
            assertEquals(TaskOutcome.UNVERIFIED,
                    TaskResultEvaluator.evaluateV3(contract("QQ"), events, "", CAPABILITIES).outcome());
        }
    }

    @Test
    void completeNativePaginationCanBindAnAliasButMissingPagesCannot() {
        var page1 = catalog(1, "", 0, 2, 2, false, "a", app("QQ", QQ_ID));
        var page2 = catalog(4, "", 1, 2, 2, false, "a", app("Notes", "com.apple.Notes"));
        List<RunEventEnvelope> events = new ArrayList<>(page1);
        events.addAll(page2);
        events.add(desktop(7, "launch_application", QQ_ID, QQ_ID, ""));
        events.add(desktop(8, "observe", "QQ", QQ_ID, "contacts list"));
        assertEquals(TaskOutcome.VERIFIED_COMPLETE,
                TaskResultEvaluator.evaluateV3(contract("QQ"), events, "", CAPABILITIES).outcome());
        assertFalse(DesktopApplicationIdentityBindings.fromEvents(page1)
                .matches("QQ", QQ_ID, QQ_ID, "run", 7));
    }

    @Test
    void metadataFromAnotherQueryDoesNotProveAnAliasIsUnique() {
        var bindings = DesktopApplicationIdentityBindings.fromEvents(catalog(1, "com.tencent.qq",
                0, 1, 115, false, "a", app("QQ", QQ_ID)));
        assertFalse(bindings.matches("QQ", QQ_ID, QQ_ID, "run", 4));
    }

    @Test
    void catalogCannotRetroactivelyBindEarlierReceiptsOrAnotherRun() {
        var bindings = DesktopApplicationIdentityBindings.fromEvents(catalog(4, "QQ", 0, 1,
                115, false, "a", app("QQ", QQ_ID)));
        assertFalse(bindings.matches("QQ", QQ_ID, QQ_ID, "run", 2));
        assertFalse(bindings.matches("QQ", QQ_ID, QQ_ID, "other-run", 10));
        assertTrue(bindings.matches("QQ", QQ_ID, QQ_ID, "run", 10));
    }

    @Test
    void aNewIncompleteSnapshotCannotReuseOldAliasBindings() {
        var events = new ArrayList<>(catalog(1, "QQ", 0, 1, 115,
                false, "a", app("QQ", QQ_ID)));
        events.addAll(catalog(4, "QQ", 0, 2, 116,
                false, "b", app("QQ", QQ_ID)));
        assertFalse(DesktopApplicationIdentityBindings.fromEvents(events)
                .matches("QQ", QQ_ID, QQ_ID, "run", 7));
    }

    @Test
    void aCompleteEmptyCatalogDoesNotProveAnExpectedDisplayName() {
        var bindings = DesktopApplicationIdentityBindings.fromEvents(catalog(1, "QQ", 0, 0,
                115, false, "a"));
        assertFalse(bindings.matches("QQ", "QQ", QQ_ID, "run", 4));
        assertTrue(bindings.matches(QQ_ID, "QQ", QQ_ID, "run", 4),
                "an exact native application ID needs no display-name alias inference");
    }

    @Test
    void modelOutputAndUnboundOrForeignCatalogEventsCannotCreateIdentityBindings() {
        var genuine = catalog(1, "QQ", 0, 1, 115, false, "a", app("QQ", QQ_ID));
        var completed = genuine.get(1);
        ObjectNode modelOnly = (ObjectNode) completed.payload();
        modelOnly.set("modelOutput", modelOnly.remove("output"));
        var modelEvents = List.of(genuine.getFirst(), event(2, "core.tool.completed", 2,
                "framework.core", modelOnly), genuine.getLast());
        assertFalse(DesktopApplicationIdentityBindings.fromEvents(modelEvents)
                .matches("QQ", QQ_ID, QQ_ID, "run", 4));
        assertFalse(DesktopApplicationIdentityBindings.fromEvents(genuine.subList(1, 3))
                .matches("QQ", QQ_ID, QQ_ID, "run", 4));
        assertFalse(DesktopApplicationIdentityBindings.fromEvents(genuine.subList(0, 2))
                .matches("QQ", QQ_ID, QQ_ID, "run", 4));
        var foreignEvents = List.of(genuine.getFirst(), event(2, "core.tool.completed", 2,
                "plugin", (ObjectNode) completed.payload()), genuine.getLast());
        assertFalse(DesktopApplicationIdentityBindings.fromEvents(foreignEvents)
                .matches("QQ", QQ_ID, QQ_ID, "run", 4));
    }

    @Test
    void observationCannotChangeTheAppBoundToAnOpenedSession() {
        var events = new ArrayList<>(catalog(1, "QQ", 0, 1, 115,
                false, "a", app("QQ", QQ_ID)));
        events.add(desktop(4, "open", "QQ", QQ_ID, ""));
        events.add(desktop(5, "observe", "QQ", "com.example.qq", "contacts list"));
        var contract = new TaskContractV2(2, "request", "QQ", List.of(
                new TaskCriterion("open", "open", "QQ", "open", "ACCEPTED"),
                new TaskCriterion("view", "view", "QQ", "observe", "OBSERVED", "contacts list")),
                true, true, "definition");
        assertEquals(TaskOutcome.PARTIAL,
                TaskResultEvaluator.evaluateV2(contract, events, "").outcome());
    }

    @Test
    void criterionSpecificCurrentFrameProofCanVerifyContactsWithoutAnEnglishScreenLabel() {
        List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "QQ", 0, 1, 115,
                false, "a", app("QQ", QQ_ID)));
        events.add(desktop(4, "launch_application", QQ_ID, QQ_ID, ""));
        events.add(desktop(5, "open", "QQ", QQ_ID, ""));
        var observed = conditionObservation(6, "view", "contacts list", "list", null);
        events.add(observed);
        var result = TaskResultEvaluator.evaluateV3(contract("QQ"), events, "", CAPABILITIES);
        assertEquals(TaskOutcome.VERIFIED_COMPLETE, result.outcome());
        assertEquals("", observed.payload().path("subject").asText(), "the raw subject stays unchanged");
    }

    @Test
    void mismatchedCriterionFrameOrNavigationProofCannotCompleteContactsCriterion() {
        for (var observed : List.of(conditionObservation(6, "unrelated", "contacts list", "list", null),
                conditionObservation(6, "view", "downloads", "list", null),
                conditionObservation(6, "view", "contacts list", "navigation", null),
                conditionObservation(6, "view", "contacts list", "list", "contentRevision"),
                conditionObservation(6, "view", "contacts list", "list", "observationId"))) {
            List<RunEventEnvelope> events = new ArrayList<>(catalog(1, "QQ", 0, 1, 115,
                    false, "a", app("QQ", QQ_ID)));
            events.add(desktop(4, "launch_application", QQ_ID, QQ_ID, ""));
            events.add(desktop(5, "open", "QQ", QQ_ID, ""));
            events.add(observed);
            assertEquals(TaskOutcome.PARTIAL,
                    TaskResultEvaluator.evaluateV3(contract("QQ"), events, "", CAPABILITIES).outcome());
        }
    }

    private static RunEventEnvelope conditionObservation(long sequence, String criterionId,
            String subject, String role, String changedFrameField) {
        ObjectNode payload = (ObjectNode) desktop(sequence, "observe", "QQ", QQ_ID, "").payload();
        ObjectNode metadata = (ObjectNode) payload.path("metadata");
        metadata.remove("viewEvidence");
        ObjectNode proof = JSON.objectNode().put("schemaVersion", 1).put("frameWidth", 640)
                .put("frameHeight", 1430);
        for (String field : List.of("sessionId", "targetId", "observationId", "windowGeneration",
                "contentRevision", "capturedAtMillis")) proof.set(field, metadata.path(field));
        if ("contentRevision".equals(changedFrameField)) proof.put("contentRevision", "99");
        if ("observationId".equals(changedFrameField)) proof.put("observationId",
                "123e4567-e89b-42d3-a456-426614174001");
        ObjectNode condition = proof.putArray("conditions").addObject().put("criterionId", criterionId)
                .put("subject", subject).put("region", "main-content").put("confidence", 0.95);
        condition.putObject("content").put("label", "联系人分组列表").put("role", role)
                .put("x", 80).put("y", 100).put("width", 500).put("height", 1000)
                .put("confidence", 0.95);
        metadata.put("conditionEvidence", proof.toString());
        return event(sequence, "core.tool.receipt", 1, "framework.core", payload);
    }

    private static TaskContractV3 contract(String target) {
        return new TaskContractV3(3, "request", List.of(
                new TaskCriterionV3("launch", "launch", "desktop.launch",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, target,
                        EffectReceiptV1.Status.ACCEPTED, ""),
                new TaskCriterionV3("view", "view", "desktop.observe",
                        CapabilityMetadata.TargetKind.DESKTOP_APPLICATION, target,
                        EffectReceiptV1.Status.OBSERVED, "contacts list")), true, true, "definition");
    }

    private static List<RunEventEnvelope> catalog(long sequence, String query, int offset,
            int total, int catalogTotal, boolean truncated, String id, ObjectNode... applications) {
        String invocation = "catalog-" + sequence;
        ObjectNode started = JSON.objectNode().put("tool", "desktop_session_applications")
                .put("invocationId", invocation);
        started.putObject("arguments").put("query", query).put("offset", offset);
        ObjectNode completed = JSON.objectNode().put("tool", "desktop_session_applications")
                .put("invocationId", invocation).put("status", "SUCCEEDED");
        ObjectNode output = completed.putObject("output").put("schemaVersion", 1)
                .put("protocol", "computer-use").put("kind", "desktop.applications")
                .put("catalogId", id.repeat(64)).put("query", query).put("offset", offset)
                .put("count", applications.length).put("totalCount", total)
                .put("catalogTotalCount", catalogTotal).put("truncated", truncated)
                .put("hasMore", offset + applications.length < total);
        if (offset + applications.length < total) output.put("nextOffset", offset + applications.length);
        var array = output.putArray("applications");
        for (var application : applications) array.add(application);
        ObjectNode receipt = JSON.objectNode().put("tool", "desktop_session_applications")
                .put("invocationId", invocation).put("operation", "applications")
                .put("status", "OBSERVED").put("observedAt", Instant.ofEpochSecond(sequence + 2).toString())
                .put("evidenceRef", "core.tool.completed:run:" + invocation);
        return List.of(event(sequence, "core.tool.started", 1, "framework.core", started),
                event(sequence + 1, "core.tool.completed", 2, "framework.core", completed),
                event(sequence + 2, "core.tool.receipt", 1, "framework.core", receipt));
    }

    private static ObjectNode app(String name, String id) {
        ObjectNode app = JSON.objectNode().put("name", name).put("displayName", name)
                .put("applicationId", id).put("launchName", id);
        app.putArray("aliases").add(name).add(id);
        return app;
    }

    private static RunEventEnvelope desktop(long sequence, String operation, String target,
            String applicationId, String subject) {
        ObjectNode receipt = JSON.objectNode().put("invocationId", "desktop-" + sequence)
                .put("tool", "desktop_session_" + operation).put("operation", operation)
                .put("target", target).put("status", operation.equals("observe") ? "OBSERVED" : "ACCEPTED")
                .put("evidenceRef", "desktop:" + sequence).put("subject", subject)
                .put("observedAt", Instant.ofEpochSecond(sequence).toString());
        var metadata = receipt.putObject("metadata").put("applicationId", applicationId);
        if (!operation.equals("launch_application")) metadata.put("sessionId", "session-1")
                .put("targetId", "window-1");
        if (operation.equals("observe")) metadata.put("observationId", "123e4567-e89b-42d3-a456-426614174000")
                .put("windowGeneration", "1").put("contentRevision", "1")
                .put("capturedAtMillis", Long.toString(sequence * 1000))
                .put("viewEvidence", "heading:1,1,20,10|content:1,20,90,40");
        return event(sequence, "core.tool.receipt", 1, "framework.core", receipt);
    }

    private static RunEventEnvelope event(long sequence, String type, int version,
            String producer, ObjectNode payload) {
        return new RunEventEnvelope("run", sequence, Instant.ofEpochSecond(sequence),
                type, version, producer, null, null, payload);
    }
}
