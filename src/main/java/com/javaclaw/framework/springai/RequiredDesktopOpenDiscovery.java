package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.core.ToolInvocationFingerprint;
import com.javaclaw.framework.spi.InteractionStageContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Own native discovery hints only. This supplies no session, input authority or acceptance proof. */
final class RequiredDesktopOpenDiscovery {
    private RequiredDesktopOpenDiscovery() { }

    record Discovery(List<JsonNode> targets, String evidenceRef, boolean complete, boolean hasMore,
            int nextOffset, String query, int offset, int totalCount, String inventoryId) {
        Discovery { targets = targets.stream().map(target -> (JsonNode) target.deepCopy()).toList(); }
    }

    static Discovery latest(RunId run, List<RunEventEnvelope> history, long boundary, String applicationId) {
        return latest(run, history, boundary, applicationId, false);
    }

    /** Discovery data retained for explicit selection, never a restored window/input handle. */
    static Discovery latestAll(RunId run, List<RunEventEnvelope> history) {
        return latest(run, history, 0, "", true);
    }

    private static Discovery latest(RunId run, List<RunEventEnvelope> history, long boundary,
            String applicationId, boolean allApplications) {
        List<RunEventEnvelope> events = history.stream().filter(event -> event.runId().equals(run.value())
                && event.producer().equals("framework.core")).toList();
        Map<String, List<RunEventEnvelope>> groups = new LinkedHashMap<>();
        for (var event : events) {
            if (!Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type())) continue;
            String invocation = event.payload().path("invocationId").asText("");
            if (!invocation.isBlank()) groups.computeIfAbsent(invocation, ignored -> new ArrayList<>()).add(event);
        }
        List<RunEventEnvelope> valid = new ArrayList<>();
        for (var group : groups.values()) {
            if (group.size() != 3) continue;
            var start = unique(group, "core.tool.started", 1);
            var complete = unique(group, "core.tool.completed", 2);
            var receipt = unique(group, "core.tool.receipt", 1);
            if (start == null || complete == null || receipt == null || start.sequence() <= boundary
                    || start.sequence() >= complete.sequence() || complete.sequence() >= receipt.sequence()
                    || start.timestamp().isAfter(complete.timestamp()) || complete.timestamp().isAfter(receipt.timestamp())
                    || !start.payload().path("trustedDesktopTool").isBoolean()
                    || !start.payload().path("trustedDesktopTool").booleanValue()
                    || !complete.payload().path("status").asText().equals("SUCCEEDED")
                    || !complete.payload().path("errorCode").asText("").isBlank()
                    || !receipt.payload().path("status").asText().equals("OBSERVED")
                    || !receipt.payload().path("operation").asText().equals("targets")
                    || !receipt.payload().path("target").asText().equals("desktop")
                    || !group.stream().allMatch(event -> event.payload().path("tool").asText().equals("desktop_session_targets"))
                    || !start.payload().path("arguments").isObject()) continue;
            String invocation = start.payload().path("invocationId").asText();
            if (!receipt.payload().path("evidenceRef").asText().equals("core.tool.completed:" + run.value() + ":" + invocation)) continue;
            String fingerprint = ToolInvocationFingerprint.create("desktop_session_targets", start.payload().path("arguments"));
            if (!fingerprint.equals(start.payload().path("fingerprint").asText())
                    || !fingerprint.equals(receipt.payload().path("fingerprint").asText())) continue;
            JsonNode raw = complete.payload().path("output");
            if (!raw.isObject() || !raw.path("schemaVersion").isIntegralNumber() || !raw.path("schemaVersion").canConvertToInt()
                    || raw.path("schemaVersion").intValue() != 1
                    || !raw.path("protocol").asText().equals("computer-use")
                    || !raw.path("kind").asText().equals("desktop.targets")
                    || !raw.path("targets").isArray() || !raw.path("count").isIntegralNumber() || !raw.path("count").canConvertToInt()
                    || raw.path("count").asLong() != raw.path("targets").size()
                    || !validPage(raw, start.payload().path("arguments"))
                    || !receipt.payload().path("metadata").path("discoveryDigest").asText().equals(
                        InteractionStageContext.sha256(raw.toString()))) continue;
            try {
                // Run event timestamps retain milliseconds, while receipts may retain nanoseconds.
                long observedMillis = Instant.parse(receipt.payload().path("observedAt").asText()).toEpochMilli();
                if (observedMillis < start.timestamp().toEpochMilli()
                        || observedMillis > complete.timestamp().toEpochMilli()) continue;
            } catch (RuntimeException invalid) { continue; }
            valid.add(complete);
        }
        var latest = valid.stream().max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (latest == null) return null;
        List<JsonNode> targets = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode target : latest.payload().path("output").path("targets")) {
            if (!target.isObject() || !allApplications && !target.path("applicationId").asText().equals(applicationId)
                    || !identifier(target.path("targetId")) || !identifier(target.path("providerId"))
                    || !target.path("processId").isIntegralNumber() || !target.path("processId").canConvertToLong()
                    || target.path("processId").asLong() <= 0
                    || !target.path("visible").isBoolean() || !allApplications && !target.path("visible").booleanValue()
                    || !target.path("systemSurface").isBoolean() || target.path("systemSurface").booleanValue()) continue;
            if (!ids.add(target.path("targetId").asText())) return null; // Conflicting native identities grant no hint.
            targets.add(target);
        }
        JsonNode output = latest.payload().path("output");
        boolean paged = output.has("inventoryId");
        return new Discovery(targets, "core.tool.completed:" + run.value() + ":"
                + latest.payload().path("invocationId").asText(),
                !paged || output.path("complete").asBoolean(), paged && output.path("hasMore").asBoolean(),
                paged && output.has("nextOffset") ? output.path("nextOffset").intValue() : -1,
                output.path("query").asText(""), output.path("offset").asInt(0),
                output.path("totalCount").asInt(output.path("count").intValue()),
                output.path("inventoryId").asText(""));
    }

    /** Old unpaged receipts remain readable; new pages must describe exactly the rows actually returned. */
    private static boolean validPage(JsonNode output, JsonNode arguments) {
        boolean paged = List.of("inventoryId", "query", "offset", "inventoryTotalCount", "totalCount",
                "complete", "hasMore", "nextOffset", "truncated").stream().anyMatch(output::has);
        if (!paged) return true;
        if (!output.path("inventoryId").isTextual() || !output.path("inventoryId").asText().matches("[a-f0-9]{64}")
                || !output.path("query").isTextual()
                || output.path("query").asText().length() > 256
                || output.path("query").asText().codePoints().anyMatch(Character::isISOControl)
                || !nonnegativeInt(output.path("offset")) || !nonnegativeInt(output.path("totalCount"))
                || !nonnegativeInt(output.path("inventoryTotalCount"))
                || !output.path("complete").isBoolean() || !output.path("hasMore").isBoolean()
                || !output.path("truncated").isBoolean()
                || !output.path("inputAuthority").isBoolean() || output.path("inputAuthority").booleanValue()
                || !output.path("freshObservation").isBoolean() || output.path("freshObservation").booleanValue()) return false;
        int count = output.path("count").intValue(), offset = output.path("offset").intValue();
        int total = output.path("totalCount").intValue();
        long next = (long) offset + count;
        boolean complete = offset == 0 && count == total;
        boolean hasMore = next < total;
        if (count > 32 || next > total || output.path("inventoryTotalCount").intValue() < total
                || hasMore && count == 0
                || output.path("complete").booleanValue() != complete
                || output.path("truncated").booleanValue() == complete
                || output.path("hasMore").booleanValue() != hasMore
                || hasMore != output.has("nextOffset")
                || hasMore && (!nonnegativeInt(output.path("nextOffset"))
                    || output.path("nextOffset").longValue() != next)) return false;
        JsonNode requestedOffset = arguments.path("offset");
        JsonNode requestedQuery = arguments.path("query");
        JsonNode requestedLimit = arguments.path("limit");
        if (!requestedOffset.isMissingNode() && !requestedOffset.isNull() && !nonnegativeInt(requestedOffset)
                || !requestedQuery.isMissingNode() && !requestedQuery.isNull() && !requestedQuery.isTextual()
                || !requestedLimit.isMissingNode() && !requestedLimit.isNull()
                    && (!nonnegativeInt(requestedLimit) || requestedLimit.intValue() < 1 || requestedLimit.intValue() > 32))
            return false;
        return offset == requestedOffset.asInt(0)
                && output.path("query").asText().equals(requestedQuery.asText("").strip())
                && count <= requestedLimit.asInt(16);
    }

    private static boolean nonnegativeInt(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0;
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> events, String type, int schema) {
        List<RunEventEnvelope> matches = events.stream().filter(event -> event.type().equals(type)
                && event.schemaVersion() == schema).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean identifier(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank()
                && value.asText().equals(value.asText().strip())
                && value.asText().codePoints().noneMatch(Character::isISOControl);
    }
}
