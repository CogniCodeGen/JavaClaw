package com.javaclaw.desktop.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.vision.DesktopVisualActiveView;
import com.javaclaw.desktop.api.DesktopApplicationLaunchResult;
import com.javaclaw.desktop.api.DesktopApplicationLaunchRejectedException;
import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopElement;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopSessionInfo;
import com.javaclaw.desktop.api.DesktopSessionState;
import com.javaclaw.desktop.api.DesktopTarget;
import com.javaclaw.desktop.api.DesktopVisualRegion;
import com.javaclaw.util.SensitiveDataRedactor;
import com.javaclaw.framework.api.ToolExecutionStatus;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Trusted machine data from the desktop service; labels remain untrusted observations. */
final class DesktopToolPayloads {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Set<String> INTERACTIVE_ROLES = Set.of("tab", "button", "menuitem",
            "link", "navigationitem", "checkbox", "radiobutton", "radio", "switch",
            "combobox", "listitem");

    private DesktopToolPayloads() { }

    static ObjectNode probe(DesktopAvailability availability) {
        ObjectNode data = base("desktop.probe");
        data.put("available", availability.available());
        data.put("providerId", availability.providerId());
        data.put("capabilities", availability.capabilities());
        data.put("detail", safe(availability.detail()));
        data.put("nextStep", availability.available() ? "DISCOVER_TARGETS" : "CHECK_PERMISSIONS");
        return data;
    }

    static ObjectNode targets(List<DesktopTarget> targets) {
        ObjectNode data = base("desktop.targets");
        appendTargets(data.putArray("targets"), targets);
        data.put("count", targets.size());
        return data;
    }

    static ObjectNode applications(com.javaclaw.desktop.api.DesktopApplicationCatalog catalog,
            int offset, int limit) {
        return applications(catalog, offset, limit, null);
    }

    static ObjectNode applications(com.javaclaw.desktop.api.DesktopApplicationCatalog catalog,
            int offset, int limit, String requestedQuery) {
        return applications(catalog, offset, limit, requestedQuery, 12_000);
    }

    static ObjectNode applications(com.javaclaw.desktop.api.DesktopApplicationCatalog catalog,
            int offset, int limit, String requestedQuery, int maxCharacters) {
        String query = requestedQuery == null ? "" : requestedQuery.strip();
        if (query.length() > 256 || query.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("应用目录 query 超出范围");
        String search = normalized(query);
        var matches = catalog.applications().stream().filter(app -> search.isBlank()
                || java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(app.name(), app.displayName(), app.applicationId(), app.launchName()),
                        app.aliases().stream()).map(DesktopToolPayloads::normalized)
                        .anyMatch(label -> !label.isBlank() && (label.contains(search) || search.contains(label))))
                .toList();
        if (offset < 0 || offset > matches.size() || limit < 1 || limit > 64)
            throw new IllegalArgumentException("应用目录 offset 或 limit 超出范围");
        ObjectNode data = base("desktop.applications");
        data.put("trust", "UNTRUSTED_APPLICATION_METADATA");
        data.put("catalogId", catalogId(catalog));
        data.put("query", query);
        data.put("offset", offset);
        data.put("catalogTotalCount", catalog.applications().size());
        data.put("totalCount", matches.size());
        data.put("truncated", catalog.truncated());
        ArrayNode entries = data.putArray("applications");
        applicationPageMetadata(data, offset, matches.size(), 0);
        if (data.toString().length() > maxCharacters)
            throw new CatalogMessageBudgetException(maxCharacters, data.toString().length(), offset);
        for (int index = offset; index < Math.min(matches.size(), offset + limit); index++) {
            var app = matches.get(index);
            ObjectNode entry = entries.addObject().put("name", app.name())
                    .put("displayName", app.displayName()).put("applicationId", app.applicationId())
                    .put("launchName", app.launchName());
            app.aliases().forEach(entry.putArray("aliases")::add);
            applicationPageMetadata(data, offset, matches.size(), entries.size());
            int required = data.toString().length();
            if (required > maxCharacters) {
                entries.remove(entries.size() - 1);
                if (entries.isEmpty()) throw new CatalogMessageBudgetException(maxCharacters, required, offset);
                applicationPageMetadata(data, offset, matches.size(), entries.size());
                break;
            }
        }
        return data;
    }

    private static void applicationPageMetadata(ObjectNode data, int offset, int total, int count) {
        int next = offset + count;
        data.put("count", count).put("hasMore", next < total).put("nextStep", "SELECT_APPLICATION");
        if (next < total) data.put("nextOffset", next);
        else data.remove("nextOffset");
    }

    static final class CatalogMessageBudgetException extends IllegalStateException {
        CatalogMessageBudgetException(int limit, int required, int offset) {
            super("LOCAL_CONTEXT_BUDGET_EXCEEDED: application catalog entry cannot fit intact; limit="
                    + limit + ", required=" + required + ", offset=" + offset);
        }
    }

    private static String normalized(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static String catalogId(com.javaclaw.desktop.api.DesktopApplicationCatalog catalog) {
        var snapshot = NODES.objectNode().put("truncated", catalog.truncated());
        var identities = snapshot.putArray("applications");
        for (var app : catalog.applications()) {
            var entry = identities.addObject().put("name", app.name()).put("displayName", app.displayName())
                    .put("applicationId", app.applicationId()).put("launchName", app.launchName());
            app.aliases().forEach(entry.putArray("aliases")::add);
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static ObjectNode launch(String requestedApplication, DesktopApplicationLaunchResult result) {
        ObjectNode data = base("desktop.launch");
        data.put("requestedApplication", requestedApplication);
        data.put("status", "ACCEPTED");
        data.put("admission", "ACCEPTED");
        data.put("delivery", result.dispatchAttempted() ? "SENT" : "NOT_SENT");
        data.put("dispatchAttempted", result.dispatchAttempted());
        data.put("effect", "UNKNOWN");
        data.put("processId", result.processId());
        String applicationId = !result.applicationId().isBlank() ? result.applicationId()
                : result.targets().isEmpty() ? "" : result.targets().getFirst().applicationId();
        if (!applicationId.isBlank()) data.put("applicationId", applicationId);
        appendTargets(data.putArray("targets"), result.targets());
        data.put("nextStep", result.targets().isEmpty() ? "DISCOVER_TARGETS" : "OPEN_SESSION");
        return data;
    }

    static ObjectNode rejectedLaunch(String requestedApplication,
            DesktopApplicationLaunchRejectedException rejection) {
        ObjectNode data = base("desktop.launch");
        data.put("requestedApplication", requestedApplication);
        data.put("status", "FAILED");
        data.put("admission", "REJECTED");
        data.put("reasonCode", rejection.reasonCode());
        data.put("reason", rejection.reasonCode());
        data.put("delivery", "NOT_SENT");
        data.put("dispatchAttempted", rejection.dispatchAttempted());
        if (rejection.nativeCode() != 0) data.put("nativeCode", rejection.nativeCode());
        data.put("detail", safe(rejection.getMessage()));
        data.put("nextStep", rejectedLaunchNextStep(rejection));
        data.putArray("targets");
        return data;
    }

    static String rejectedLaunchNextStep(DesktopApplicationLaunchRejectedException rejection) {
        return switch (rejection.reason()) {
            case APPLICATION_NOT_FOUND, AMBIGUOUS_APPLICATION, INVALID_ARGUMENTS -> "DISCOVER_APPLICATIONS";
            case ACCESS_DENIED -> "CHECK_PERMISSIONS";
            default -> "PROBE_CAPABILITIES";
        };
    }

    static ObjectNode uncertainLaunch(String requestedApplication, long processId) {
        return uncertainLaunch(requestedApplication, processId, "");
    }

    static ObjectNode uncertainLaunch(String requestedApplication, long processId,
            String resolvedApplicationId) {
        ObjectNode data = base("desktop.launch");
        data.put("requestedApplication", requestedApplication);
        data.put("status", "UNKNOWN");
        data.put("admission", "UNCERTAIN");
        data.put("delivery", "MAYBE_SENT");
        if (processId > 0) data.put("processId", processId);
        String applicationId = resolvedApplicationId == null || resolvedApplicationId.isBlank()
                ? requestedApplicationId(requestedApplication) : resolvedApplicationId;
        if (!applicationId.isBlank()) data.put("applicationId", applicationId);
        data.putArray("targets");
        data.put("nextStep", "DISCOVER_TARGETS");
        return data;
    }

    static ObjectNode opened(DesktopSessionInfo session) {
        ObjectNode data = base("desktop.session");
        data.put("sessionId", session.sessionId());
        data.set("target", target(session.target()));
        data.put("controlGranted", session.controlGranted());
        data.put("nextStep", "OBSERVE");
        return data;
    }

    static ObjectNode observation(DesktopSessionInfo session, String observationId,
            DesktopFrame frame, List<DesktopElement> elements,
            List<DesktopVisualRegion> regions, DesktopVisualActiveView activeView,
            String elementDiagnostics) {
        return observation(session, observationId, frame, elements, regions,
                activeView, elementDiagnostics, "", "");
    }

    static ObjectNode observation(DesktopSessionInfo session, String observationId,
            DesktopFrame frame, List<DesktopElement> elements,
            List<DesktopVisualRegion> regions, DesktopVisualActiveView activeView,
            String elementDiagnostics, String summary, String visibleText) {
        return observation(session, observationId, frame, elements, regions, activeView,
                elementDiagnostics, summary, visibleText, List.of());
    }

    static ObjectNode observation(DesktopSessionInfo session, String observationId,
            DesktopFrame frame, List<DesktopElement> elements,
            List<DesktopVisualRegion> regions, DesktopVisualActiveView activeView,
            String elementDiagnostics, String summary, String visibleText,
            List<com.javaclaw.agent.vision.DesktopVisualConditionEvidence> conditionEvidence) {
        ObjectNode data = base("desktop.observation");
        data.put("sessionId", session.sessionId());
        data.put("controlGranted", session.controlGranted());
        data.put("targetId", session.target().id());
        data.put("application", session.target().application());
        if (!session.target().applicationId().isBlank())
            data.put("applicationId", session.target().applicationId());
        data.put("observationId", observationId);
        data.put("windowGeneration", frame.windowGeneration());
        data.put("contentRevision", frame.contentRevision());
        data.put("capturedAtMillis", frame.capturedAtMillis());
        data.set("frame", frame(frame));
        data.put("coordinateSpace", "WINDOW_FRAME_PIXELS");
        String safeSummary = safe(summary);
        String safeText = safe(visibleText);
        int summaryLimit = Math.min(10_000, safeSummary.length());
        int textLimit = Math.min(20_000 - summaryLimit, safeText.length());
        data.putObject("content").put("trust", "UNTRUSTED_SCREEN_CONTENT")
                .put("summary", safeSummary.substring(0, summaryLimit))
                .put("visibleText", safeText.substring(0, textLimit))
                .put("truncated", summaryLimit < safeSummary.length() || textLimit < safeText.length());
        data.put("elementDiagnostics", bounded(elementDiagnostics, 512));
        data.put("elementCount", elements.size());
        data.put("elementsTruncated", elements.size() > 40);
        ArrayNode accessibility = data.putArray("elements");
        elements.stream().limit(40).forEach(element -> accessibility.addObject()
                .put("id", element.id())
                .put("role", bounded(element.role(), 100))
                .put("label", bounded(element.label(), 500))
                .put("x", element.x()).put("y", element.y())
                .put("width", element.width()).put("height", element.height())
                .put("actions", element.actions())
                .put("pressable", (element.actions() & DesktopElement.PRESS) != 0));
        ArrayNode visual = data.putArray("visualTargets");
        regions.stream().limit(40).forEach(region -> visual.addObject()
                .put("id", region.id())
                .put("role", bounded(region.role(), 100))
                .put("label", bounded(region.label(), 500))
                .put("x", region.x()).put("y", region.y())
                .put("width", region.width()).put("height", region.height())
                .put("confidence", region.confidence())
                .put("pressable", INTERACTIVE_ROLES.contains(
                        region.role().toLowerCase(Locale.ROOT))));
        if (activeView != null) {
            data.putObject("activeView")
                    .put("label", bounded(activeView.label(), 500))
                    .put("confidence", activeView.confidence());
        }
        ArrayNode conditions = data.putArray("conditionEvidence");
        conditionEvidence.forEach(evidence -> {
            ObjectNode condition = conditions.addObject()
                    .put("criterionId", evidence.criterionId()).put("subject", evidence.subject())
                    .put("region", "main-content").put("confidence", evidence.confidence());
            var excerpt = evidence.content();
            condition.putObject("content").put("label", excerpt.label()).put("role", excerpt.role())
                    .put("x", excerpt.x()).put("y", excerpt.y()).put("width", excerpt.width())
                    .put("height", excerpt.height()).put("confidence", excerpt.confidence());
        });
        return data;
    }

    static ObjectNode action(String tool, String sessionId, String targetId,
            DesktopAction action, DesktopActionResult result) {
        ObjectNode data = base("desktop.action");
        data.put("tool", tool);
        data.put("sessionId", sessionId);
        data.put("targetId", targetId);
        data.put("observationId", action.observationId());
        data.put("windowGeneration", result.windowGeneration());
        data.put("contentRevision", action.contentRevision());
        data.put("actionKind", action.kind().name());
        data.put("status", result.status().name());
        data.put("admission", admission(result).name());
        data.put("delivery", result.delivery().name());
        // Input admission never establishes the intended application postcondition.
        data.put("effect", "UNKNOWN");
        data.put("mode", result.mode().name());
        data.put("reason", result.reason().name());
        data.put("dispatchAttempted", result.dispatchAttempted());
        data.put("nextStep", result.nextStep().name());
        data.put("detail", safe(result.detail()));
        return data;
    }

    private static ToolExecutionStatus admission(DesktopActionResult result) {
        return switch (result.status()) {
            case VERIFIED, ACCEPTED -> ToolExecutionStatus.SUCCEEDED;
            case UNKNOWN -> ToolExecutionStatus.UNCERTAIN;
            case STALE_FRAME -> ToolExecutionStatus.REOBSERVE;
            case UNSUPPORTED -> result.nextStep() == DesktopActionResult.NextStep.OBSERVE
                    ? ToolExecutionStatus.REOBSERVE : ToolExecutionStatus.FAILED;
            case DENIED, FAILED -> ToolExecutionStatus.FAILED;
        };
    }

    static ObjectNode error(String tool, String sessionId, String errorCode,
            DesktopActionResult.Reason reason, DesktopActionResult.NextStep nextStep, String detail) {
        ObjectNode data = base("desktop.error");
        data.put("tool", tool);
        if (sessionId != null && !sessionId.isBlank()) data.put("sessionId", sessionId);
        data.put("admission", ToolExecutionStatus.FAILED.name());
        data.put("errorCode", errorCode);
        data.put("reason", reason.name());
        data.put("nextStep", nextStep.name());
        data.put("detail", safe(detail));
        return data;
    }

    static ObjectNode snapshot(String sessionId, DesktopFrame frame, String imagePath) {
        ObjectNode data = base("desktop.snapshot");
        data.put("sessionId", sessionId);
        data.set("frame", frame(frame));
        data.put("imagePath", imagePath);
        return data;
    }

    static ObjectNode sessionState(String sessionId, DesktopSessionState.Kind state,
            boolean foregroundGranted, DesktopActionResult.NextStep nextStep) {
        ObjectNode data = base("desktop.state");
        data.put("sessionId", sessionId);
        data.put("state", state.name());
        data.put("foregroundGranted", foregroundGranted);
        data.put("nextStep", nextStep.name());
        return data;
    }

    private static ObjectNode frame(DesktopFrame frame) {
        return NODES.objectNode().put("targetId", frame.targetId())
                .put("windowGeneration", frame.windowGeneration())
                .put("contentRevision", frame.contentRevision())
                .put("capturedAtMillis", frame.capturedAtMillis())
                .put("width", frame.width()).put("height", frame.height())
                .put("coordinateSpace", "WINDOW_FRAME_PIXELS");
    }

    private static ObjectNode base(String kind) {
        ObjectNode data = NODES.objectNode();
        data.put("schemaVersion", 1);
        data.put("protocol", "computer-use");
        data.put("kind", kind);
        return data;
    }

    private static void appendTargets(ArrayNode array, List<DesktopTarget> targets) {
        for (DesktopTarget target : targets) array.add(target(target));
    }

    private static ObjectNode target(DesktopTarget target) {
        ObjectNode data = NODES.objectNode();
        data.put("providerId", target.providerId());
        data.put("targetId", target.id());
        data.put("processId", target.processId());
        data.put("application", safe(target.application()));
        if (!target.applicationId().isBlank()) data.put("applicationId", target.applicationId());
        data.put("title", safe(target.title()));
        data.put("minimized", target.minimized());
        data.put("visible", target.visible());
        data.put("systemSurface", target.systemSurface());
        return data;
    }

    private static String safe(String value) {
        return SensitiveDataRedactor.redactText(value == null ? "" : value);
    }

    private static String bounded(String value, int max) {
        String redacted = safe(value);
        return redacted.length() <= max ? redacted : redacted.substring(0, max);
    }

    /** A typed owner ID is usable only when the caller supplied that ID verbatim. */
    private static String requestedApplicationId(String requested) {
        if (requested == null) return "";
        String value = requested.strip();
        return value.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+){2,}")
                || value.matches("[A-Za-z0-9_-]+\\.exe") ? value : "";
    }
}
