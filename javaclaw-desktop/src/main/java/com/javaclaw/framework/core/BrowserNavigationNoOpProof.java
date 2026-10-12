package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.BrowserReceiptProof;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recognizes settled host URL confirmations; never supplies current-page or acceptance evidence. */
final class BrowserNavigationNoOpProof {
    private static final String TOOL = "web_navigate";
    private BrowserNavigationNoOpProof() { }

    static Set<String> completed(List<RunEventEnvelope> events) {
        if (events == null || events.isEmpty()) return Set.of();
        String runId = events.getFirst().runId();
        if (events.stream().anyMatch(event -> !event.runId().equals(runId))) return Set.of();
        var created = events.stream().filter(event -> event.type().equals("core.run.created")).toList();
        if (created.size() != 1 || !host(created.getFirst(), "core.run.created", 1)
                || created.getFirst().sequence() != 1
                || !created.getFirst().payload().path("source").asText().equals("interaction")) return Set.of();
        Map<String, List<RunEventEnvelope>> calls = new HashMap<>();
        for (var event : events) {
            if (!Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type()))
                continue;
            String invocation = text(event.payload(), "invocationId");
            if (identifier(invocation)) calls.computeIfAbsent(invocation, ignored -> new ArrayList<>()).add(event);
        }
        Set<String> completed = new HashSet<>();
        for (var entry : calls.entrySet()) {
            try {
                if (settled(runId, entry.getKey(), entry.getValue())) completed.add(entry.getKey());
            } catch (RuntimeException malformed) {
                // An incomplete or conflicting historical result retains its original fence.
            }
        }
        return Set.copyOf(completed);
    }

    private static boolean settled(String runId, String invocation, List<RunEventEnvelope> events) {
        if (events.size() != 3) return false;
        var start = unique(events, "core.tool.started", 1);
        var complete = unique(events, "core.tool.completed", 2);
        var receipt = unique(events, "core.tool.receipt", 1);
        if (start == null || complete == null || receipt == null
                || start.sequence() >= complete.sequence() || complete.sequence() >= receipt.sequence()
                || start.timestamp().isAfter(complete.timestamp()) || complete.timestamp().isAfter(receipt.timestamp()))
            return false;
        JsonNode input = start.payload(), result = complete.payload(), value = receipt.payload();
        JsonNode raw = result.path("output"), metadata = value.path("metadata");
        if (!flag(input, "trustedDesktopTool", true) || !flag(input, "trustedBrowserNavigation", true)
                || !flag(input, "readOnlyNavigationNoOp", true) || !flag(input, "idempotent", false)
                || !text(input, "effectPolicy").equals("LEGACY")
                || !input.path("arguments").isObject()
                || !text(result, "status").equals("SUCCEEDED") || !text(result, "errorCode").isEmpty()
                || !raw.isObject() || !flag(raw, "reusedExisting", true) || !flag(raw, "dispatchAttempted", false)
                || !text(value, "operation").equals("navigate") || !text(value, "status").equals("ACCEPTED")
                || !text(value, "evidenceRef").equals("core.tool.completed:" + runId + ":" + invocation)
                || !text(metadata, "delivery").equals("NOT_SENT") || !text(metadata, "effect").equals("NONE")
                || !text(metadata, "dispatchAttempted").equals("false")
                || !text(metadata, "reusedExisting").equals("true")) return false;
        String destination = BrowserReceiptProof.canonicalUrl(text(input.path("arguments"), "url"));
        String fingerprint = ToolInvocationFingerprint.create(TOOL, input.path("arguments"));
        if (destination.isBlank() || !BrowserReceiptProof.urlMatches(destination, metadata)
                || !text(input, "fingerprint").equals(fingerprint)
                || !text(value, "fingerprint").equals(fingerprint)
                || !text(input, "effectKey").equals(ToolEffectKey.create(TOOL, input.path("arguments"), fingerprint))
                || !identifier(text(value, "target")) || !text(raw, "url").equals(text(value, "target"))
                || BrowserReceiptProof.canonicalUrl(text(raw, "url")).isBlank()
                || List.of("runtimeId", "contextId", "surfaceId", "documentId").stream()
                    .anyMatch(key -> !identifier(text(metadata, key)))) return false;
        long generation = positive(metadata, "generation");
        long capturedAt = positive(metadata, "capturedAtMillis");
        long observedAt = Instant.parse(text(value, "observedAt")).toEpochMilli();
        return generation > 0 && capturedAt > 0 && capturedAt == observedAt
                && observedAt >= start.timestamp().toEpochMilli()
                && observedAt <= complete.timestamp().toEpochMilli();
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> events, String type, int schema) {
        var matching = events.stream().filter(event -> event.type().equals(type)).toList();
        if (matching.size() != 1 || !host(matching.getFirst(), type, schema)
                || !text(matching.getFirst().payload(), "tool").equals(TOOL)) return null;
        return matching.getFirst();
    }

    private static boolean host(RunEventEnvelope event, String type, int schema) {
        return event.type().equals(type) && event.schemaVersion() == schema
                && event.producer().equals("framework.core");
    }

    private static boolean flag(JsonNode value, String field, boolean expected) {
        return value.path(field).isBoolean() && value.path(field).booleanValue() == expected;
    }

    private static String text(JsonNode value, String field) {
        return value.path(field).isTextual() ? value.path(field).textValue() : "";
    }

    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 512 && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static long positive(JsonNode value, String field) {
        String number = text(value, field);
        return number.matches("[1-9][0-9]{0,18}") ? Long.parseLong(number) : -1;
    }
}
