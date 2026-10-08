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

    record Discovery(List<JsonNode> targets, String evidenceRef) {
        Discovery { targets = targets.stream().map(target -> (JsonNode) target.deepCopy()).toList(); }
    }

    static Discovery latest(RunId run, List<RunEventEnvelope> history, long boundary, String applicationId) {
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
                    || !receipt.payload().path("metadata").path("discoveryDigest").asText().equals(
                        InteractionStageContext.sha256(raw.toString()))) continue;
            try {
                Instant observed = Instant.parse(receipt.payload().path("observedAt").asText());
                if (observed.isBefore(start.timestamp()) || observed.isAfter(complete.timestamp())) continue;
            } catch (RuntimeException invalid) { continue; }
            valid.add(complete);
        }
        var latest = valid.stream().max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (latest == null) return null;
        List<JsonNode> targets = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode target : latest.payload().path("output").path("targets")) {
            if (!target.isObject() || !target.path("applicationId").asText().equals(applicationId)
                    || !identifier(target.path("targetId")) || !identifier(target.path("providerId"))
                    || !target.path("processId").isIntegralNumber() || !target.path("processId").canConvertToLong()
                    || target.path("processId").asLong() <= 0
                    || !target.path("visible").isBoolean() || !target.path("visible").booleanValue()
                    || !target.path("systemSurface").isBoolean() || target.path("systemSurface").booleanValue()) continue;
            if (!ids.add(target.path("targetId").asText())) return null; // Conflicting native identities grant no hint.
            targets.add(target);
        }
        return new Discovery(targets, "core.tool.completed:" + run.value() + ":"
                + latest.payload().path("invocationId").asText());
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> events, String type, int schema) {
        List<RunEventEnvelope> matches = events.stream().filter(event -> event.type().equals(type)
                && event.schemaVersion() == schema).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean identifier(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 512
                && value.asText().equals(value.asText().strip())
                && value.asText().codePoints().noneMatch(Character::isISOControl);
    }
}
