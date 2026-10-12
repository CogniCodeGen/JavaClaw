package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.desktop.api.DesktopApplicationInfo;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.spi.EffectReceiptV1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Proves only a host discovery response, never application existence, permission or UI content. */
final class DesktopDiscoveryEvidence {
    private DesktopDiscoveryEvidence() { }

    static boolean supports(String capabilityId) {
        return "desktop.probe".equals(capabilityId)
                || "desktop.applications".equals(capabilityId)
                || "desktop.targets".equals(capabilityId);
    }

    static boolean matches(TaskCriterionV3 criterion, RunEventEnvelope receipt,
                           List<RunEventEnvelope> events) {
        try {
            if (!supports(criterion.capabilityId())
                    || criterion.targetType() != CapabilityMetadata.TargetKind.RESOURCE
                    || !criterion.target().equals("desktop")
                    || criterion.requiredEvidence() != EffectReceiptV1.Status.ACCEPTED
                        && criterion.requiredEvidence() != EffectReceiptV1.Status.OBSERVED
                    || !host(receipt, "core.tool.receipt", 1)) return false;
            String operation = criterion.capabilityId().substring("desktop.".length());
            String tool = "desktop_session_" + operation;
            JsonNode proof = receipt.payload();
            String invocation = text(proof, "invocationId");
            if (invocation.isBlank() || !text(proof, "tool").equals(tool)
                    || !text(proof, "operation").equals(operation)
                    || !text(proof, "target").equals("desktop")
                    || !text(proof, "status").equals("OBSERVED")
                    || !text(proof, "subject").isEmpty()
                    || !text(proof, "evidenceRef").equals(
                            "core.tool.completed:" + receipt.runId() + ":" + invocation)) return false;
            Instant.parse(text(proof, "observedAt"));
            RunEventEnvelope started = boundary(events, receipt, "core.tool.started", 1, tool, invocation);
            RunEventEnvelope completed = boundary(events, receipt, "core.tool.completed", 2, tool, invocation);
            if (started == null || completed == null || started.sequence() >= completed.sequence()
                    || !text(completed.payload(), "status").equals("SUCCEEDED")) return false;
            JsonNode arguments = started.payload().path("arguments");
            JsonNode output = completed.payload().path("output"); // Never modelOutput or display text.
            JsonNode metadata = proof.path("metadata");
            if (!arguments.isObject() || !output.isObject() || !metadata.isObject()
                    || !text(output, "protocol").equals("computer-use")
                    || integer(output, "schemaVersion") != 1
                    || !text(output, "kind").equals("desktop." + operation)
                    || !text(metadata, "discoveryDigest").equals(HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(
                                    output.toString().getBytes(StandardCharsets.UTF_8))))) return false;
            return switch (operation) {
                case "probe" -> criterion.requiredSubject().isEmpty() && arguments.isEmpty()
                        && probe(output);
                case "applications" -> applications(criterion, arguments, output, metadata);
                case "targets" -> criterion.requiredSubject().isEmpty() && arguments.isEmpty()
                        && targets(output);
                default -> false;
            };
        } catch (Exception malformed) {
            return false;
        }
    }

    private static RunEventEnvelope boundary(List<RunEventEnvelope> events, RunEventEnvelope receipt,
            String type, int version, String tool, String invocation) {
        RunEventEnvelope match = null;
        for (RunEventEnvelope event : events) {
            if (!host(event, type, version) || !event.runId().equals(receipt.runId())
                    || event.sequence() >= receipt.sequence()) continue;
            JsonNode payload = event.payload();
            if (!text(payload, "tool").equals(tool)
                    || !text(payload, "invocationId").equals(invocation)) continue;
            if (match != null) return null; // One invocation cannot borrow another attempt's output.
            match = event;
        }
        return match;
    }

    private static boolean probe(JsonNode output) {
        if (!onlyFields(output, Set.of("schemaVersion", "protocol", "kind", "available",
                "providerId", "capabilities", "detail", "nextStep"))) return false;
        boolean available = bool(output, "available");
        text(output, "providerId");
        text(output, "detail");
        return integer(output, "capabilities") >= 0
                && text(output, "nextStep").equals(available ? "DISCOVER_TARGETS" : "CHECK_PERMISSIONS");
    }

    private static boolean applications(TaskCriterionV3 criterion, JsonNode arguments,
                                        JsonNode output, JsonNode metadata) {
        if (!onlyFields(arguments, Set.of("query", "offset", "limit"))
                || !onlyFields(output, Set.of("schemaVersion", "protocol", "kind", "trust",
                        "catalogId", "query", "offset", "catalogTotalCount", "totalCount",
                        "truncated", "applications", "count", "hasMore", "nextStep", "nextOffset"))) return false;
        String query = optionalText(arguments, "query", "").strip();
        int offset = optionalInteger(arguments, "offset", 0);
        int limit = optionalInteger(arguments, "limit", 64);
        if (query.length() > 256 || query.codePoints().anyMatch(Character::isISOControl)
                || !criterion.requiredSubject().equals(query)
                || !text(output, "query").equals(query)
                || !text(metadata, "query").equals(query)
                || offset < 0 || limit < 1 || limit > 64
                || integer(output, "offset") != offset
                || !text(metadata, "offset").equals(Integer.toString(offset))
                || !text(metadata, "limit").equals(Integer.toString(limit))) return false;
        String catalog = text(output, "catalogId");
        if (!catalog.matches("[0-9a-f]{64}") || !text(metadata, "catalogId").equals(catalog)
                || !text(output, "trust").equals("UNTRUSTED_APPLICATION_METADATA")
                || !text(output, "nextStep").equals("SELECT_APPLICATION")) return false;
        int count = integer(output, "count");
        int total = integer(output, "totalCount");
        int catalogTotal = integer(output, "catalogTotalCount");
        boolean hasMore = bool(output, "hasMore");
        bool(output, "truncated"); // Both values are facts; neither proves that an app is absent.
        JsonNode entries = output.path("applications");
        long next = (long) offset + count;
        if (!entries.isArray() || count < 0 || count > limit || count != entries.size()
                || total < 0 || catalogTotal < total || next > total
                || hasMore != (next < total) || count == 0 && hasMore
                || hasMore && integer(output, "nextOffset") != next
                || !hasMore && output.has("nextOffset")) return false;
        for (JsonNode entry : entries) {
            if (!onlyFields(entry, Set.of("name", "displayName", "applicationId", "launchName", "aliases"))
                    || !entry.path("aliases").isArray() || entry.path("aliases").size() > 16) return false;
            List<String> aliases = new ArrayList<>();
            for (JsonNode alias : entry.path("aliases")) {
                if (!alias.isTextual()) return false;
                aliases.add(alias.textValue());
            }
            // Reuse the native catalog's bounded identity validation; labels remain untrusted data.
            new DesktopApplicationInfo(text(entry, "name"), text(entry, "displayName"),
                    text(entry, "applicationId"), text(entry, "launchName"), aliases);
        }
        return true;
    }

    private static boolean targets(JsonNode output) {
        if (!onlyFields(output, Set.of("schemaVersion", "protocol", "kind", "targets", "count"))) return false;
        JsonNode entries = output.path("targets");
        int count = integer(output, "count");
        if (!entries.isArray() || count < 0 || count != entries.size()) return false;
        for (JsonNode entry : entries) {
            if (!onlyFields(entry, Set.of("providerId", "targetId", "processId", "application",
                    "applicationId", "title", "minimized", "visible", "systemSurface",
                    "parentTargetId", "relationProof"))
                    || text(entry, "providerId").isBlank() || text(entry, "targetId").isBlank()) return false;
            if (entry.has("parentTargetId") || entry.has("relationProof")) {
                if (!entry.has("parentTargetId") || !entry.has("relationProof")) return false;
                String parent = text(entry, "parentTargetId");
                String relation = text(entry, "relationProof");
                if (!(relation.equals("UNKNOWN") && parent.isEmpty()
                        || relation.equals("NATIVE_PARENT") && !parent.isBlank()
                            && !parent.equals(text(entry, "targetId")))) return false;
            }
            JsonNode process = entry.path("processId");
            if (!process.isIntegralNumber() || !process.canConvertToLong() || process.longValue() < 0) return false;
            text(entry, "application");
            text(entry, "title");
            if (entry.has("applicationId") && text(entry, "applicationId").isBlank()) return false;
            bool(entry, "minimized");
            bool(entry, "visible");
            bool(entry, "systemSurface");
        }
        return true;
    }

    private static boolean host(RunEventEnvelope event, String type, int version) {
        return event.type().equals(type) && event.schemaVersion() == version
                && event.producer().equals("framework.core") && event.payload().isObject();
    }

    private static boolean onlyFields(JsonNode value, Set<String> names) {
        if (!value.isObject()) return false;
        for (var fields = value.fieldNames(); fields.hasNext();) {
            if (!names.contains(fields.next())) return false;
        }
        return true;
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (!node.isTextual()) throw new IllegalArgumentException("discovery text field is invalid");
        return node.textValue();
    }

    private static int integer(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt())
            throw new IllegalArgumentException("discovery integer field is invalid");
        return node.intValue();
    }

    private static boolean bool(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (!node.isBoolean()) throw new IllegalArgumentException("discovery boolean field is invalid");
        return node.booleanValue();
    }

    private static String optionalText(JsonNode value, String field, String fallback) {
        return !value.hasNonNull(field) ? fallback : text(value, field);
    }

    private static int optionalInteger(JsonNode value, String field, int fallback) {
        return !value.hasNonNull(field) ? fallback : integer(value, field);
    }
}
