package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.desktop.api.DesktopActionResult;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.EffectTargetProvider;
import com.javaclaw.framework.spi.FileContentProof;
import com.javaclaw.framework.spi.ToolEffectCapture;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.schedule.ScheduleTools;
import com.javaclaw.util.ProjectAccessPolicy;
import com.javaclaw.util.SensitiveDataRedactor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/**
 * Exact host-class adapters. External callbacks, subclassed host objects and all unadapted
 * tools remain UNKNOWN even if their returned text contains a plausible success marker.
 */
final class HostEffectReceiptAdapter {
    private static final Set<String> BROWSER_TYPES = Set.of(
            "com.javaclaw.browser.BrowserPageTools",
            "com.javaclaw.browser.BrowserReadTools",
            "com.javaclaw.browser.BrowserSessionTools",
            "com.javaclaw.browser.BrowserSiteTools");

    private HostEffectReceiptAdapter() { }

    /** Only adapters backed by an exact host implementation may elevate a receipt. */
    static boolean supports(Object source) {
        if (source == null) return false;
        Class<?> type = source.getClass();
        return type == DesktopSessionTools.class
                || (type.getClassLoader() == HostEffectReceiptAdapter.class.getClassLoader()
                    && BROWSER_TYPES.contains(type.getName())
                    && source instanceof EffectTargetProvider)
                || type == com.javaclaw.system.SystemTools.class
                || type == com.javaclaw.email.EmailTools.class
                || type == com.javaclaw.notification.NotificationTools.class
                || type == ScheduleTools.class
                || type == com.javaclaw.mcp.McpManageTools.class
                || type == com.javaclaw.system.CommandLineTools.class;
    }

    static EffectReceiptV1 receipt(Object source, String tool, JsonNode arguments,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at) {
        return receipt(source, tool, arguments, signal, context, at, null);
    }

    static EffectReceiptV1 receipt(Object source, String tool, JsonNode arguments,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at,
            String establishedTarget) {
        return receipt(source, tool, arguments, signal, context, at, establishedTarget, null);
    }

    static EffectReceiptV1 receipt(Object source, String tool, JsonNode arguments,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at,
            String establishedTarget, JsonNode rawOutput) {
        String evidence = "core.tool.completed:" + context.runId().value()
                + ":" + context.invocationId();
        if (source == null || signal == null) {
            return EffectReceiptV1.unknown(context.invocationId(), tool, at, evidence);
        }
        String type = source.getClass().getName();
        if (source.getClass() == DesktopSessionTools.class) {
            if (Set.of("desktop_session_probe", "desktop_session_applications", "desktop_session_targets")
                    .contains(tool)) {
                return desktopDiscovery(tool, arguments, rawOutput, signal, context, at, evidence);
            }
            if (tool.equals("desktop_session_snapshot")) {
                return desktopSnapshot(tool, arguments, rawOutput, signal, context, at, evidence);
            }
            return desktop((DesktopSessionTools) source, tool, arguments, signal, context, at, evidence);
        }
        if (source.getClass().getClassLoader() == HostEffectReceiptAdapter.class.getClassLoader()
                && BROWSER_TYPES.contains(type) && source instanceof EffectTargetProvider page) {
            return browser(page, tool, signal, context, at, evidence);
        }
        if (source.getClass() == com.javaclaw.system.SystemTools.class && tool.startsWith("sys_file_")) {
            return file(tool, arguments, signal, context, at, evidence);
        }
        if (source.getClass() == com.javaclaw.email.EmailTools.class) {
            return email(tool, arguments, signal, context, at, evidence, establishedTarget);
        }
        if (source.getClass() == com.javaclaw.notification.NotificationTools.class) {
            return notification(tool, signal, context, at, evidence);
        }
        if (source.getClass() == ScheduleTools.class) {
            return schedule((ScheduleTools) source, tool, arguments, signal, context, at, evidence);
        }
        if (source.getClass() == com.javaclaw.system.CommandLineTools.class) {
            return command(tool, arguments, signal, context, at, evidence);
        }
        if (source.getClass() == com.javaclaw.mcp.McpManageTools.class) {
            return mcpSecureInputCancellation(tool, arguments, rawOutput, signal, context, at, evidence);
        }
        return EffectReceiptV1.unknown(context.invocationId(), tool, at, evidence);
    }

    /** Only the exact host's pre-save cancellation branch can establish no MCP write. */
    private static EffectReceiptV1 mcpSecureInputCancellation(String tool, JsonNode args, JsonNode output,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        if (!tool.equals("mcp_server_set_header_secure") || signal != ToolEffectCapture.Signal.SUCCESS
                || args == null || !args.isObject() || output == null || !output.isObject()
                || output.size() != 7
                || !output.path("schemaVersion").isIntegralNumber()
                || !output.path("schemaVersion").canConvertToInt()
                || output.path("schemaVersion").intValue() != 1
                || !output.path("kind").isTextual()
                || !output.path("kind").textValue().equals("mcp.header.input_cancelled")
                || !args.path("name").isTextual() || !args.path("headerName").isTextual()
                || !output.path("serverName").isTextual() || !output.path("headerName").isTextual()
                || output.path("serverName").textValue().isBlank()
                || output.path("serverName").textValue().length() > 512
                || !output.path("serverName").textValue().equals(args.path("name").textValue().strip())
                || !output.path("headerName").textValue().equals(args.path("headerName").textValue().strip())
                || !output.path("headerName").textValue().matches("[A-Za-z0-9][A-Za-z0-9-]{0,127}")
                || !output.path("saved").isBoolean() || output.path("saved").booleanValue()
                || !output.path("reconnected").isBoolean() || output.path("reconnected").booleanValue()
                || !output.path("retryAllowed").isBoolean() || output.path("retryAllowed").booleanValue()) {
            return EffectReceiptV1.unknown(context.invocationId(), tool, at, evidence);
        }
        // OBSERVED applies only to this local interaction, never to a Header write.
        return new EffectReceiptV1(context.invocationId(), tool, "input_cancelled",
                output.path("serverName").textValue(), EffectReceiptV1.Status.OBSERVED,
                at, evidence, "local secure input supplied no value; no save or reconnect was attempted",
                output.path("headerName").textValue(), java.util.Map.of("delivery", "NOT_SENT", "effect", "NONE",
                        "reasonCode", "SECURE_INPUT_CANCELLED", "retryAllowed", "false",
                        "headerName", output.path("headerName").textValue()));
    }

    /** A successful native discovery page proves only its exact typed data, never a live window. */
    private static EffectReceiptV1 desktopDiscovery(String tool, JsonNode args, JsonNode output,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        String operation = switch (tool) {
            case "desktop_session_probe" -> "probe";
            case "desktop_session_applications" -> "applications";
            case "desktop_session_targets" -> "targets";
            default -> throw new IllegalArgumentException("not a desktop discovery tool");
        };
        if (signal != ToolEffectCapture.Signal.SUCCESS) {
            return new EffectReceiptV1(context.invocationId(), tool, operation, "desktop",
                    signal == ToolEffectCapture.Signal.ERROR || signal == ToolEffectCapture.Signal.REOBSERVE
                            ? EffectReceiptV1.Status.FAILED
                            : EffectReceiptV1.Status.UNKNOWN,
                    at, evidence, "desktop discovery did not return a successful native result");
        }
        if (output == null || !output.isObject()
                || !output.path("protocol").asText().equals("computer-use")
                || !output.path("schemaVersion").isIntegralNumber()
                || !output.path("schemaVersion").canConvertToInt()
                || output.path("schemaVersion").intValue() != 1
                || !output.path("kind").asText().equals("desktop." + operation)) {
            return new EffectReceiptV1(context.invocationId(), tool, operation, "desktop",
                    EffectReceiptV1.Status.UNKNOWN, at, evidence,
                    "desktop discovery returned no complete typed native data");
        }
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        try {
            metadata.put("discoveryDigest", java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(output.toString().getBytes(StandardCharsets.UTF_8))));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        if (operation.equals("applications")) {
            metadata.put("query", args.path("query").asText("").strip());
            metadata.put("offset", Integer.toString(args.path("offset").asInt(0)));
            metadata.put("limit", Integer.toString(args.path("limit").asInt(64)));
            metadata.put("catalogId", output.path("catalogId").asText(""));
        }
        return new EffectReceiptV1(context.invocationId(), tool, operation, "desktop",
                EffectReceiptV1.Status.OBSERVED, at, evidence,
                "native desktop discovery data returned; application state and control remain unproved",
                "", metadata);
    }

    /** Only the exact host snapshot operation supplies this capture and owner data after PNG success. */
    private static EffectReceiptV1 desktopSnapshot(String tool, JsonNode args, JsonNode output,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        if (signal != ToolEffectCapture.Signal.SUCCESS) {
            return new EffectReceiptV1(context.invocationId(), tool, "snapshot", "",
                    signal == ToolEffectCapture.Signal.ERROR || signal == ToolEffectCapture.Signal.REOBSERVE
                            ? EffectReceiptV1.Status.FAILED : EffectReceiptV1.Status.UNKNOWN,
                    at, evidence, "owned desktop PNG snapshot was not captured successfully");
        }
        try {
            if (output == null || !output.isObject()
                    || !snapshotText(output, "protocol").equals("computer-use")
                    || snapshotLong(output, "schemaVersion") != 1
                    || !snapshotText(output, "kind").equals("desktop.snapshot"))
                throw new IllegalArgumentException("invalid snapshot data");
            String session = snapshotText(output, "sessionId");
            String capture = snapshotText(output, "captureId");
            if (session.isBlank() || !session.equals(snapshotText(args, "sessionId"))
                    || !java.util.UUID.fromString(capture).toString().equals(capture)
                    || snapshotText(output, "imagePath").isBlank())
                throw new IllegalArgumentException("invalid snapshot identity");
            JsonNode target = output.path("target");
            JsonNode frame = output.path("frame");
            String targetId = snapshotText(target, "targetId");
            String provider = snapshotText(target, "providerId");
            String application = snapshotText(target, "application");
            String applicationId = target.has("applicationId")
                    ? snapshotText(target, "applicationId") : "";
            long processId = snapshotLong(target, "processId");
            long generation = snapshotLong(frame, "windowGeneration");
            long revision = snapshotLong(frame, "contentRevision");
            long capturedAt = snapshotLong(frame, "capturedAtMillis");
            long width = snapshotLong(frame, "width");
            long height = snapshotLong(frame, "height");
            if (targetId.isBlank() || provider.isBlank() || processId < 0
                    || !targetId.equals(snapshotText(frame, "targetId"))
                    || generation < 0 || revision < 1 || capturedAt < 1 || width < 1 || height < 1
                    || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE
                    || !snapshotText(frame, "coordinateSpace").equals("WINDOW_FRAME_PIXELS"))
                throw new IllegalArgumentException("invalid snapshot frame");
            String identity = application.isBlank() ? applicationId : application;
            if (identity.isBlank()) throw new IllegalArgumentException("missing snapshot application");
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put("sessionId", session);
            metadata.put("targetId", targetId);
            // Compatibility frame identifier only. Snapshot does not commit an observe/input baseline.
            metadata.put("observationId", capture);
            metadata.put("windowGeneration", Long.toString(generation));
            metadata.put("contentRevision", Long.toString(revision));
            metadata.put("capturedAtMillis", Long.toString(capturedAt));
            metadata.put("providerId", provider);
            metadata.put("processId", Long.toString(processId));
            metadata.put("frameWidth", Long.toString(width));
            metadata.put("frameHeight", Long.toString(height));
            if (!applicationId.isBlank()) metadata.put("applicationId", applicationId);
            return new EffectReceiptV1(context.invocationId(), tool, "snapshot", bounded(identity),
                    EffectReceiptV1.Status.OBSERVED, at, evidence,
                    "owned desktop PNG snapshot captured and saved; logical window content remains unproved",
                    "", metadata);
        } catch (RuntimeException invalidSnapshot) {
            return new EffectReceiptV1(context.invocationId(), tool, "snapshot", "",
                    EffectReceiptV1.Status.UNKNOWN, at, evidence,
                    "snapshot returned no complete trusted owner and capture data");
        }
    }

    private static String snapshotText(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (!node.isTextual()) throw new IllegalArgumentException("invalid snapshot text");
        return node.textValue();
    }

    private static long snapshotLong(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong())
            throw new IllegalArgumentException("invalid snapshot integer");
        return node.longValue();
    }

    private static EffectReceiptV1 desktop(DesktopSessionTools source, String tool, JsonNode args,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        String operation = switch (tool) {
            case "desktop_session_probe" -> "probe";
            case "desktop_session_targets" -> "targets";
            case "desktop_session_applications" -> "applications";
            case "desktop_session_launch_application" -> "launch_application";
            case "desktop_session_open" -> "open";
            case "desktop_session_snapshot" -> "snapshot";
            case "desktop_session_observe" -> "observe";
            case "desktop_session_click" -> "click";
            case "desktop_session_type" -> "type";
            case "desktop_session_key" -> "key";
            case "desktop_session_scroll" -> "scroll";
            case "desktop_session_takeover" -> "takeover";
            case "desktop_session_close" -> "close";
            default -> null;
        };
        if (operation == null) {
            return EffectReceiptV1.unknown(context.invocationId(), tool, at, evidence);
        }
        String target = operation.equals("launch_application") ? field(args, "application") : "";
        DesktopSessionTools.LaunchedApplicationProof launched = operation.equals("launch_application")
                ? source.receiptLaunchedApplication(target) : null;
        DesktopSessionTools.RejectedApplicationLaunchProof launchRejection = operation.equals("launch_application")
                ? source.receiptRejectedApplicationLaunch(target) : null;
        DesktopSessionTools.OpenedSessionProof opened = null;
        if (operation.equals("open")) {
            opened = source.receiptOpenedSession(field(args, "targetId"));
            if (opened != null) target = opened.application();
        }
        DesktopSessionTools.ClosedSessionProof closed = null;
        if (operation.equals("close")) {
            closed = source.receiptClosedSession(field(args, "sessionId"));
            if (closed != null) target = closed.application();
        }
        DesktopSessionTools.ObservationProof observed = null;
        if (!operation.equals("launch_application") && !operation.equals("probe")
                && !operation.equals("targets") && !operation.equals("applications") && !operation.equals("close")
                && !operation.equals("open")) {
            String sessionId = field(args, "sessionId");
            if (operation.equals("observe")) observed = source.receiptObservedFrame(sessionId);
            if (!sessionId.isBlank()) {
                try { target = observed != null ? observed.application()
                        : source.receiptApplicationForSession(sessionId); }
                catch (RuntimeException ignored) { /* no trustworthy owner */ }
            }
        }
        EffectReceiptV1.Status status = switch (signal) {
            case ERROR, REOBSERVE -> EffectReceiptV1.Status.FAILED;
            case PENDING, TIMEOUT, UNCERTAIN -> EffectReceiptV1.Status.UNKNOWN;
            case SUCCESS -> switch (operation) {
                case "observe", "snapshot", "targets", "applications" -> EffectReceiptV1.Status.OBSERVED;
                case "probe" -> EffectReceiptV1.Status.OBSERVED;
                default -> EffectReceiptV1.Status.ACCEPTED;
            };
        };
        // An observation without a service-resolved owning application cannot prove an app task.
        if ((operation.equals("observe") || operation.equals("snapshot"))
                && target.isBlank() && status == EffectReceiptV1.Status.OBSERVED) {
            status = EffectReceiptV1.Status.UNKNOWN;
        }
        if (Set.of("targets", "probe", "applications").contains(operation)) target = "desktop";
        String subject = operation.equals("observe") && status == EffectReceiptV1.Status.OBSERVED
                && observed != null ? observed.subject() : "";
        if (operation.equals("observe") && observed == null
                && status == EffectReceiptV1.Status.OBSERVED)
            status = EffectReceiptV1.Status.UNKNOWN;
        if (operation.equals("close") && closed == null
                && status == EffectReceiptV1.Status.ACCEPTED)
            status = EffectReceiptV1.Status.UNKNOWN;
        if (Set.of("click", "type", "key", "scroll").contains(operation)) {
            DesktopSessionTools.ActionProof proof = source.receiptActionProof();
            DesktopActionResult action = proof == null ? null : proof.result();
            if (action != null) {
                status = switch (action.status()) {
                    case VERIFIED, ACCEPTED -> EffectReceiptV1.Status.ACCEPTED;
                    case UNKNOWN -> EffectReceiptV1.Status.UNKNOWN;
                    case UNSUPPORTED, STALE_FRAME, DENIED, FAILED ->
                            action.dispatchAttempted() ? EffectReceiptV1.Status.UNKNOWN
                                    : EffectReceiptV1.Status.FAILED;
                };
                java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
                metadata.put("desktopStatus", action.status().name());
                metadata.put("deliveryMode", action.mode().name());
                metadata.put("reasonCode", action.reason().name());
                metadata.put("dispatchAttempted", Boolean.toString(action.dispatchAttempted()));
                metadata.put("delivery", action.delivery().name());
                metadata.put("effect", "UNKNOWN");
                metadata.put("observationId", proof.observationId());
                metadata.put("nextStep", action.nextStep().name());
                metadata.put("targetId", proof.targetId());
                metadata.put("sessionId", proof.sessionId());
                metadata.put("windowGeneration", Long.toString(proof.windowGeneration()));
                try {
                    String applicationId = source.receiptApplicationIdForSession(proof.sessionId());
                    if (!applicationId.isBlank()) metadata.put("applicationId", applicationId);
                } catch (RuntimeException ignored) { /* session may have closed */ }
                return new EffectReceiptV1(context.invocationId(), tool, operation,
                        bounded(target), status, at, evidence, bounded(action.detail()),
                        "", metadata);
            }
            java.util.Map<String, String> metadata = proof == null
                    ? java.util.Map.of("delivery", "MAYBE_SENT")
                    : java.util.Map.of("delivery", "MAYBE_SENT", "targetId", proof.targetId(),
                            "sessionId", proof.sessionId(), "observationId", proof.observationId(),
                            "windowGeneration", Long.toString(proof.windowGeneration()));
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), EffectReceiptV1.Status.UNKNOWN, at, evidence,
                    "desktop input returned no trusted action result", "", metadata);
        }
        if (launchRejection != null) {
            var rejection = launchRejection.rejection();
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put("requestedApplication", bounded(launchRejection.requestedApplication()));
            metadata.put("admission", "REJECTED");
            metadata.put("reasonCode", rejection.reasonCode());
            metadata.put("reason", rejection.reasonCode());
            metadata.put("delivery", "NOT_SENT");
            metadata.put("dispatchAttempted", Boolean.toString(rejection.dispatchAttempted()));
            if (rejection.nativeCode() != 0)
                metadata.put("nativeCode", Integer.toString(rejection.nativeCode()));
            metadata.put("nextStep", switch (rejection.reason()) {
                case APPLICATION_NOT_FOUND, AMBIGUOUS_APPLICATION, INVALID_ARGUMENTS -> "DISCOVER_APPLICATIONS";
                case ACCESS_DENIED -> "CHECK_PERMISSIONS";
                default -> "PROBE_CAPABILITIES";
            });
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), EffectReceiptV1.Status.FAILED, at, evidence,
                    bounded(rejection.getMessage()), "", metadata);
        }
        if (launched != null) {
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            boolean accepted = signal == ToolEffectCapture.Signal.SUCCESS && launched.processId() > 0;
            metadata.put("requestedApplication", bounded(launched.requestedApplication()));
            metadata.put("admission", accepted ? "ACCEPTED" : "UNCERTAIN");
            metadata.put("delivery", accepted ? launched.dispatchAttempted() ? "SENT" : "NOT_SENT"
                    : "MAYBE_SENT");
            metadata.put("effect", "UNKNOWN");
            metadata.put("nextStep", "DISCOVER_TARGETS");
            if (accepted) metadata.put("dispatchAttempted", Boolean.toString(launched.dispatchAttempted()));
            if (launched.processId() > 0)
                metadata.put("processId", Long.toString(launched.processId()));
            if (!launched.applicationId().isBlank())
                metadata.put("applicationId", launched.applicationId());
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), accepted ? EffectReceiptV1.Status.ACCEPTED
                            : EffectReceiptV1.Status.UNKNOWN, at, evidence,
                    accepted ? launched.dispatchAttempted() ? "application launch request reached the platform"
                            : "existing application discovered; no additional launch was dispatched"
                            : "application launch dispatch result remains unconfirmed", "", metadata);
        }
        if (operation.equals("launch_application")) {
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), EffectReceiptV1.Status.UNKNOWN, at, evidence,
                    "application launch returned no trusted dispatch proof", "",
                    java.util.Map.of("requestedApplication", bounded(target),
                            "admission", "UNCERTAIN", "delivery", "MAYBE_SENT",
                            "nextStep", "DISCOVER_TARGETS"));
        }
        if (opened != null) {
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put("targetId", opened.targetId());
            metadata.put("sessionId", opened.sessionId());
            metadata.put("controlRequested", Boolean.toString(args.path("control").asBoolean(false)));
            metadata.put("controlGranted", Boolean.toString(opened.controlGranted()));
            if (!opened.applicationId().isBlank())
                metadata.put("applicationId", opened.applicationId());
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), status, at, evidence,
                    "desktop session was established; window content remains unobserved", "",
                    metadata);
        }
        if (closed != null) {
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put("sessionId", closed.sessionId());
            metadata.put("targetId", closed.targetId());
            if (!closed.applicationId().isBlank())
                metadata.put("applicationId", closed.applicationId());
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), status, at, evidence,
                    "owned desktop session was closed", "", metadata);
        }
        if (observed != null) {
            java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
            metadata.put("targetId", observed.targetId());
            metadata.put("sessionId", observed.sessionId());
            metadata.put("observationId", observed.observationId());
            metadata.put("controlGranted", Boolean.toString(observed.controlGranted()));
            metadata.put("windowGeneration", Long.toString(observed.windowGeneration()));
            metadata.put("contentRevision", Long.toString(observed.contentRevision()));
            metadata.put("capturedAtMillis", Long.toString(observed.capturedAtMillis()));
            if (!observed.applicationId().isBlank())
                metadata.put("applicationId", observed.applicationId());
            String viewEvidence = viewEvidence(observed.activeView());
            if (!viewEvidence.isBlank()) metadata.put("viewEvidence", viewEvidence);
            String conditions = conditionEvidence(observed);
            if (!conditions.isBlank()) metadata.put("conditionEvidence", conditions);
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), status, at, evidence, "live owned desktop frame observed",
                    subject, metadata);
        }
        return of(context, tool, operation, target, status, at, evidence,
                status == EffectReceiptV1.Status.OBSERVED ? "live owned desktop frame observed"
                        : "desktop action result does not prove application content", subject);
    }

    private static String conditionEvidence(DesktopSessionTools.ObservationProof observed) {
        if (observed.conditionEvidence().isEmpty() || observed.frameWidth() < 1
                || observed.frameHeight() < 1) return "";
        var proof = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 1).put("sessionId", observed.sessionId())
                .put("targetId", observed.targetId()).put("observationId", observed.observationId())
                .put("windowGeneration", observed.windowGeneration())
                .put("contentRevision", observed.contentRevision())
                .put("capturedAtMillis", observed.capturedAtMillis())
                .put("frameWidth", observed.frameWidth()).put("frameHeight", observed.frameHeight());
        var conditions = proof.putArray("conditions");
        for (var evidence : observed.conditionEvidence()) {
            var condition = conditions.addObject().put("criterionId", evidence.criterionId())
                    .put("subject", evidence.subject()).put("confidence", evidence.confidence())
                    .put("region", "main-content");
            var target = evidence.content();
            condition.putObject("content").put("label", target.label()).put("role", target.role())
                    .put("x", target.x()).put("y", target.y()).put("width", target.width())
                    .put("height", target.height()).put("confidence", target.confidence());
        }
        return proof.toString();
    }

    private static String viewEvidence(com.javaclaw.agent.vision.DesktopVisualActiveView view) {
        if (view == null || view.heading() == null || view.content() == null) return "";
        return "heading:" + box(view.heading()) + "|content:" + box(view.content());
    }

    private static String box(com.javaclaw.agent.vision.DesktopVisualTarget target) {
        return target.x() + "," + target.y() + "," + target.width() + "," + target.height();
    }

    static EffectReceiptV1 browser(EffectTargetProvider page, String tool,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        String operation = tool.startsWith("web_") ? tool.substring(4) : tool;
        String target = safeUrl(page.effectTarget());
        if (operation.startsWith("get_") || operation.startsWith("is_")
                || operation.startsWith("wait_for_") || operation.equals("snapshot")
                || operation.equals("screenshot") || operation.equals("screenshot_annotated")) {
            operation = "observe";
        }
        EffectReceiptV1.Status status = signalStatus(signal);
        if (status == EffectReceiptV1.Status.ACCEPTED && operation.equals("observe")) {
            status = target.isBlank() ? EffectReceiptV1.Status.UNKNOWN : EffectReceiptV1.Status.OBSERVED;
        }
        return of(context, tool, operation, target, status, at, evidence,
                operation.equals("click") ? "click returned; page outcome requires a later observation"
                        : "browser tool boundary result");
    }

    private static EffectReceiptV1 file(String tool, JsonNode args,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        String operation = tool.substring("sys_file_".length());
        String requestedTarget = operation.equals("copy") || operation.equals("move")
                ? field(args, "target") : field(args, "path");
        String target = requestedTarget;
        EffectReceiptV1.Status status = signalStatus(signal);
        String observedContent = null;
        if (status == EffectReceiptV1.Status.ACCEPTED) {
            try {
                Path path = ProjectAccessPolicy.resolveProjectPath(requestedTarget);
                target = path.toAbsolutePath().normalize().toString();
                status = switch (operation) {
                    case "write" -> {
                        if (!Files.isRegularFile(path)) yield EffectReceiptV1.Status.UNKNOWN;
                        String content = Files.readString(path);
                        if (!content.equals(args.path("content").asText()))
                            yield EffectReceiptV1.Status.UNKNOWN;
                        observedContent = content;
                        yield EffectReceiptV1.Status.VERIFIED;
                    }
                    case "delete" -> !Files.exists(path)
                            ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.UNKNOWN;
                    case "mkdir" -> Files.isDirectory(path)
                            ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.UNKNOWN;
                    case "copy" -> copied(args, path)
                            ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.UNKNOWN;
                    case "move" -> moved(args, path)
                            ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.UNKNOWN;
                    case "read" -> {
                        // Reobserve the real file under the same bounds and credential policy as the tool.
                        if (!Files.isRegularFile(path) || Files.size(path) > 1024 * 1024)
                            yield EffectReceiptV1.Status.UNKNOWN;
                        String content = Files.readString(path);
                        if (content.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024
                                || SensitiveDataRedactor.containsLikelyCredential(content))
                            yield EffectReceiptV1.Status.UNKNOWN;
                        observedContent = content;
                        yield EffectReceiptV1.Status.OBSERVED;
                    }
                    case "list" -> EffectReceiptV1.Status.OBSERVED;
                    default -> EffectReceiptV1.Status.UNKNOWN;
                };
            } catch (Exception ignored) {
                status = EffectReceiptV1.Status.UNKNOWN;
            }
        }
        if (observedContent != null) {
            return new EffectReceiptV1(context.invocationId(), tool, operation,
                    bounded(target), status, at, evidence,
                    operation.equals("write") ? "filesystem content postcondition checked"
                            : "filesystem content independently observed",
                    "", FileContentProof.metadata(observedContent));
        }
        return of(context, tool, operation, target, status, at, evidence,
                status == EffectReceiptV1.Status.VERIFIED ? "filesystem postcondition checked"
                        : "filesystem result not independently verified");
    }

    private static boolean copied(JsonNode args, Path destination) throws Exception {
        Path source = ProjectAccessPolicy.resolveProjectPath(field(args, "source"));
        if (!Files.exists(source) || !Files.exists(destination)) return false;
        if (Files.isRegularFile(source) && Files.isRegularFile(destination)) {
            return Files.mismatch(source, destination) == -1L;
        }
        return Files.isDirectory(source) && Files.isDirectory(destination);
    }

    private static boolean moved(JsonNode args, Path destination) {
        Path source = ProjectAccessPolicy.resolveProjectPath(field(args, "source"));
        return !Files.exists(source) && Files.exists(destination);
    }

    static EffectReceiptV1 email(String tool, JsonNode args,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        return email(tool, args, signal, context, at, evidence, null);
    }

    private static EffectReceiptV1 notification(String tool,
            ToolEffectCapture.Signal signal, ToolExecutionContext context,
            Instant at, String evidence) {
        String target = switch (tool) {
            case "notify_send" -> "all";
            case "notify_dingtalk" -> "dingtalk";
            case "notify_wechat" -> "wechat";
            case "notify_feishu" -> "feishu";
            case "notify_email" -> "email";
            case "notify_custom_webhook" -> "custom";
            case "notify_list_channels" -> "notification";
            default -> null;
        };
        if (target == null) {
            return EffectReceiptV1.unknown(context.invocationId(), tool, at, evidence);
        }
        boolean observation = tool.equals("notify_list_channels");
        EffectReceiptV1.Status status = signalStatus(signal);
        if (observation && status == EffectReceiptV1.Status.ACCEPTED) {
            status = EffectReceiptV1.Status.OBSERVED;
        }
        return of(context, tool, observation ? "observe" : "send", target,
                status, at, evidence, observation ? "channel configuration read"
                        : "transport accepted request; recipient delivery unverified");
    }

    static EffectReceiptV1 email(String tool, JsonNode args,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at,
            String evidence, String establishedTarget) {
        boolean reply = tool.equals("email_reply");
        boolean sending = reply || tool.equals("email_send") || tool.equals("email_send_with_cc");
        String target = reply ? bounded(establishedTarget) : sending ? field(args, "to") : "inbox";
        EffectReceiptV1.Status status = signalStatus(signal);
        if (reply && target.isBlank() && status == EffectReceiptV1.Status.ACCEPTED) {
            status = EffectReceiptV1.Status.UNKNOWN;
        } else if (!sending && status == EffectReceiptV1.Status.ACCEPTED) {
            status = EffectReceiptV1.Status.OBSERVED;
        }
        return of(context, tool, sending ? "send" : "observe", target, status, at,
                evidence, reply && target.isBlank() ? "SMTP reply recipient was not captured"
                        : sending ? "SMTP transport returned; recipient delivery is unobserved"
                        : "mailbox read completed");
    }

    private static EffectReceiptV1 schedule(ScheduleTools source, String tool, JsonNode args,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        String operation = tool.startsWith("schedule_") ? tool.substring(9) : tool;
        if (operation.equals("run_now")) operation = "run";
        String target = operation.equals("create") ? field(args, "name")
                : operation.equals("list") ? "schedule" : field(args, "id");
        EffectReceiptV1.Status status = signalStatus(signal);
        if (status == EffectReceiptV1.Status.ACCEPTED) {
            if (operation.equals("create")) {
                status = source.receiptHasEnabledTask(target)
                        ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.UNKNOWN;
            } else if (operation.equals("get") || operation.equals("list")) {
                operation = "observe";
                status = EffectReceiptV1.Status.OBSERVED;
            } else if (operation.equals("disable")) {
                status = source.receiptTaskDisabled(target)
                        ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.UNKNOWN;
            } else if (operation.equals("delete")) {
                status = source.receiptTaskDeleted(target)
                        ? EffectReceiptV1.Status.VERIFIED : EffectReceiptV1.Status.UNKNOWN;
            }
        }
        return of(context, tool, operation, target, status, at, evidence,
                operation.equals("run") ? "run was started or queued; execution completion is separate"
                        : "schedule service state checked");
    }

    private static EffectReceiptV1 command(String tool, JsonNode args,
            ToolEffectCapture.Signal signal, ToolExecutionContext context, Instant at, String evidence) {
        String operation = tool.equals("cmd_execute") || tool.equals("cmd_session_exec")
                ? "execute" : tool.startsWith("cmd_session_") ? tool.substring(12)
                : tool.startsWith("cmd_") ? tool.substring(4) : tool;
        String target = operation.equals("execute") ? "command" : field(args, "sessionId");
        EffectReceiptV1.Status status = signalStatus(signal);
        if (status == EffectReceiptV1.Status.ACCEPTED && operation.equals("execute")) {
            status = EffectReceiptV1.Status.VERIFIED;
        } else if (status == EffectReceiptV1.Status.ACCEPTED && operation.equals("read")) {
            status = EffectReceiptV1.Status.OBSERVED;
        }
        return of(context, tool, operation, target, status, at, evidence,
                status == EffectReceiptV1.Status.VERIFIED
                        ? "command process terminated with exit code zero; external effects are unverified"
                        : "command session result");
    }

    private static EffectReceiptV1.Status signalStatus(ToolEffectCapture.Signal signal) {
        return switch (signal) {
            case SUCCESS -> EffectReceiptV1.Status.ACCEPTED;
            case ERROR -> EffectReceiptV1.Status.FAILED;
            case PENDING, TIMEOUT, UNCERTAIN -> EffectReceiptV1.Status.UNKNOWN;
            case REOBSERVE -> EffectReceiptV1.Status.FAILED;
        };
    }

    private static EffectReceiptV1 of(ToolExecutionContext context, String tool,
            String operation, String target, EffectReceiptV1.Status status,
            Instant at, String evidence, String reason) {
        return of(context, tool, operation, target, status, at, evidence, reason, "");
    }

    private static EffectReceiptV1 of(ToolExecutionContext context, String tool,
            String operation, String target, EffectReceiptV1.Status status,
            Instant at, String evidence, String reason, String subject) {
        return new EffectReceiptV1(context.invocationId(), tool, operation,
                bounded(target), status, at, evidence, reason, subject);
    }

    private static String field(JsonNode args, String name) {
        JsonNode value = args == null ? null : args.get(name);
        return value == null || !value.isTextual() ? "" : bounded(value.asText());
    }

    private static String safeUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI value = URI.create(raw);
            if (value.getHost() == null) return "";
            return bounded(new URI(value.getScheme(), null, value.getHost(), value.getPort(),
                    value.getPath(), null, null).toString());
        } catch (Exception ignored) { return ""; }
    }

    private static String bounded(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.length() > 512 ? clean.substring(0, 512) : clean;
    }
}
