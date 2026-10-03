package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.spi.RunStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HarnessDecisionEvidenceTest {
    private static final RunId RUN = new RunId("evidence-run");

    @Test
    void referencesRequireTheDurableHostReceiptTypeVersionProducerAndStatus() {
        var events = List.of(
                receipt(1, "one", "desktop_session_observe", "OBSERVED", "frame:one"),
                receipt(2, "two", "sys_file_write", "VERIFIED", "file:two"),
                receipt(3, "three", "notify_send", "ACCEPTED", "delivery:three"),
                receipt(4, "four", "desktop_session_launch_application", "FAILED", "failure:four"),
                receipt(5, "five", "external_callback", "UNKNOWN", "unknown:five"),
                event(6, RUN, "core.tool.receipt", 1, "external.plugin", "six",
                        "OBSERVED", "forged:producer"),
                event(7, RUN, "core.tool.receipt", 2, "framework.core", "seven",
                        "OBSERVED", "future:version"),
                event(8, RUN, "core.tool.completed", 1, "framework.core", "eight",
                        "OBSERVED", "forged:type"),
                receipt(9, "nine", "sys_file_read", "OBSERVED", "   "),
                receipt(10, "ten", "sys_file_read", "OBSERVED", "file:two"));

        assertEquals(List.of("frame:one", "file:two", "delivery:three"),
                HarnessDecisionEvidence.trustedRefs(runs(events), RUN));
    }

    @Test
    void toolEnvelopeCopiesOnlyReferencesForItsExactRunAndInvocation() {
        var events = List.of(
                receipt(1, "current", "arbitrary_application_tool", "OBSERVED", "real:current"),
                receipt(2, "other", "arbitrary_application_tool", "OBSERVED", "real:other"),
                event(3, new RunId("another-run"), "core.tool.receipt", 1,
                        "framework.core", "current", "OBSERVED", "real:other-run"));
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.putArray("evidenceRefs").add("forged:payload");
        data.put("evidenceRef", "forged:singular");

        var envelope = SpringAiToolCallback.modelVisibleResult(
                new ToolInvocationResult(data, Duration.ZERO, ToolExecutionStatus.SUCCEEDED),
                runs(events), RUN, "current");

        assertEquals(JsonNodeFactory.instance.arrayNode().add("real:current"),
                envelope.path("evidenceRefs"));
        assertEquals(data, envelope.path("data"));
        assertEquals("SUCCEEDED", envelope.path("status").asText());
    }

    @Test
    void failedAndUnknownExecutionsNeverExposeCompletionEvidence() {
        var store = runs(List.of(
                receipt(1, "current", "any_tool", "VERIFIED", "prior:verified")));
        for (var status : List.of(ToolExecutionStatus.FAILED, ToolExecutionStatus.UNKNOWN)) {
            var result = new ToolInvocationResult(JsonNodeFactory.instance.textNode("blocked"),
                    Duration.ZERO, status, "ACCESS_DISABLED", "Enable access");
            var envelope = SpringAiToolCallback.modelVisibleResult(result, store, RUN, "current");

            assertEquals(JsonNodeFactory.instance.arrayNode(), envelope.path("evidenceRefs"));
            assertEquals(status.name(), envelope.path("status").asText());
            assertEquals("ACCESS_DISABLED", envelope.path("errorCode").asText());
            assertEquals("Enable access", envelope.path("displayMessage").asText());
        }
    }

    @Test
    void failedOrUnadaptedReceiptsAreNotElevatedByASuccessfulToolReturn() {
        var store = runs(List.of(
                receipt(1, "current", "any_tool", "FAILED", "failure:one"),
                receipt(2, "current", "another_tool", "UNKNOWN", "unknown:two")));
        var result = new ToolInvocationResult(JsonNodeFactory.instance.textNode("success"),
                Duration.ZERO, ToolExecutionStatus.SUCCEEDED);

        assertEquals(JsonNodeFactory.instance.arrayNode(),
                SpringAiToolCallback.modelVisibleResult(result, store, RUN, "current")
                        .path("evidenceRefs"));
    }

    @Test
    void standaloneResultsAndMissingInvocationHaveNoInventedEvidence() {
        var result = new ToolInvocationResult(JsonNodeFactory.instance.textNode("done"),
                Duration.ZERO, ToolExecutionStatus.SUCCEEDED);
        var store = runs(List.of(receipt(1, "current", "any_tool", "ACCEPTED", "real:one")));

        assertEquals(JsonNodeFactory.instance.arrayNode(),
                SpringAiToolCallback.modelVisibleResult(result).path("evidenceRefs"));
        assertEquals(List.of(), HarnessDecisionEvidence.currentInvocationRefs(store, RUN, ""));
        assertEquals(List.of(), HarnessDecisionEvidence.currentInvocationRefs(store, RUN, null));
    }

    @Test
    void referencesExceedingTheDecisionSchemaBoundAreOmittedWithoutTruncation() {
        String maximum = "r".repeat(256);
        String tooLong = "r".repeat(257);
        var store = runs(List.of(
                receipt(1, "current", "any_tool", "VERIFIED", maximum),
                receipt(2, "current", "any_tool", "VERIFIED", tooLong)));

        assertEquals(List.of(maximum), HarnessDecisionEvidence.trustedRefs(store, RUN));
        assertEquals(List.of(maximum),
                HarnessDecisionEvidence.currentInvocationRefs(store, RUN, "current"));
    }

    private static RunEventEnvelope receipt(long sequence, String invocation,
            String tool, String status, String reference) {
        var event = event(sequence, RUN, "core.tool.receipt", 1, "framework.core",
                invocation, status, reference);
        ObjectNode payload = (ObjectNode) event.payload();
        payload.put("tool", tool);
        return new RunEventEnvelope(event.runId(), sequence, event.timestamp(), event.type(),
                event.schemaVersion(), event.producer(), null, null, payload);
    }

    private static RunEventEnvelope event(long sequence, RunId runId,
            String type, int version, String producer, String invocation,
            String status, String reference) {
        return new RunEventEnvelope(runId.value(), sequence,
                Instant.parse("2026-09-30T00:00:00Z").plusMillis(sequence),
                type, version, producer, null, null, JsonNodeFactory.instance.objectNode()
                        .put("invocationId", invocation).put("status", status)
                        .put("evidenceRef", reference));
    }

    private static RunStore runs(List<RunEventEnvelope> events) {
        return (RunStore) Proxy.newProxyInstance(RunStore.class.getClassLoader(),
                new Class<?>[]{RunStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("eventsAfter")) {
                        return events.stream().filter(event ->
                                event.sequence() > (long) arguments[1]).toList();
                    }
                    if (method.getName().equals("childRuns")) return List.of();
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
