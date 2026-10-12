package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.springai.OnDemandHistoryCatalog.DesktopObservation;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Selection hints from a receipt-bound structured observation. Labels are display data only. */
record OnDemandDesktopTargetHints(String summary, boolean clickable) {
    private static final OnDemandDesktopTargetHints NONE = new OnDemandDesktopTargetHints("", false);
    private static final Comparator<Target> TARGET_ORDER = Comparator
            .comparing((Target target) -> target.label().isBlank())
            .thenComparing(Comparator.comparingDouble(Target::confidence).reversed());

    static OnDemandDesktopTargetHints from(DesktopObservation observation) {
        if (observation == null || observation.observationId().isBlank()) return NONE;
        JsonNode data = observation.data();
        if (data == null || !data.isObject() || !data.path("schemaVersion").isInt()
                || data.path("schemaVersion").intValue() != 1
                || !"desktop.observation".equals(data.path("kind").asText())
                || !observation.sessionId().equals(data.path("sessionId").asText())
                || !observation.targetId().equals(data.path("targetId").asText())
                || !observation.observationId().equals(data.path("observationId").asText())) {
            return NONE;
        }
        List<Target> accessibility = targets(data.path("elements"), observation.observationId(), true);
        List<Target> visual = targets(data.path("visualTargets"), observation.observationId(), false);
        if (accessibility.isEmpty() && visual.isEmpty()) return NONE;
        // Reserve one slot for each observation source. Within a source, prefer
        // a labelled target with stronger structured confidence; list order is
        // only a tie breaker and cannot hide a later high-confidence target.
        List<Target> selected = new ArrayList<>();
        accessibility.stream().sorted(TARGET_ORDER).findFirst().ifPresent(selected::add);
        visual.stream().sorted(TARGET_ORDER).findFirst().ifPresent(selected::add);
        Stream.concat(accessibility.stream(), visual.stream())
                .sorted(TARGET_ORDER).filter(target -> !selected.contains(target))
                .limit(3L - selected.size()).forEach(selected::add);
        // Task text and visible labels can be arbitrary languages or instructions. The
        // model interprets them; this boundary only advertises verified input targets.
        String summary = selected.stream()
                .map(target -> OnDemandContextSession.excerpt(target.role(), 40) + " "
                        + OnDemandContextSession.excerpt(target.label(), 80) + " id=" + target.id())
                .collect(java.util.stream.Collectors.joining("; "));
        return new OnDemandDesktopTargetHints(summary, true);
    }

    private static List<Target> targets(JsonNode candidates, String observationId,
            boolean accessibility) {
        if (!candidates.isArray()) return List.of();
        List<Target> result = new ArrayList<>();
        for (JsonNode candidate : candidates) {
            if (!candidate.path("pressable").isBoolean()
                    || !candidate.path("pressable").booleanValue()) continue;
            String id = candidate.path("id").asText("");
            if (!boundTargetId(observationId, id)) continue;
            double confidence = accessibility ? 1.0 : candidate.path("confidence").asDouble(0);
            result.add(new Target(id,
                    SensitiveDataRedactor.redactText(candidate.path("role").asText("")),
                    SensitiveDataRedactor.redactText(candidate.path("label").asText("")),
                    Double.isFinite(confidence) ? confidence : 0));
            if (result.size() >= 80) break;
        }
        return List.copyOf(result);
    }

    private static boolean boundTargetId(String observationId, String id) {
        if (!id.startsWith(observationId + ":")) return false;
        String local = id.substring(observationId.length() + 1);
        return local.length() >= 2 && local.length() <= 12
                && (local.charAt(0) == 'e' || local.charAt(0) == 'v')
                && local.substring(1).chars().allMatch(character -> character >= '0'
                        && character <= '9');
    }

    private record Target(String id, String role, String label, double confidence) { }
}
