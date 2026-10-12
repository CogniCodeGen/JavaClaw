package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskCriterion;
import com.javaclaw.util.ProjectAccessPolicy;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** 统一收据字段解析与条件匹配，不授予输入权限或生成验收证据。 */
final class TaskReceiptMatcher {
    private final DesktopConditionProof conditionProof;

    TaskReceiptMatcher(ObjectMapper json) {
        conditionProof = new DesktopConditionProof(json);
    }

    static Optional<Receipt> receipt(RunEventEnvelope event) {
        var payload = event.payload();
        String operation = payload.path("operation").asText("").trim();
        String target = payload.path("target").asText("").trim();
        String status = payload.path("status").asText("").trim().toUpperCase(Locale.ROOT);
        String evidenceRef = payload.path("evidenceRef").asText("").trim();
        String invocationId = payload.path("invocationId").asText("").trim();
        String subject = payload.path("subject").asText("").trim();
        if (operation.isBlank() || target.isBlank() || evidenceRef.isBlank() || invocationId.isBlank()) {
            return Optional.empty();
        }
        if (!status.equals("ACCEPTED") && !status.equals("OBSERVED") && !status.equals("VERIFIED")) {
            return Optional.empty();
        }
        try { java.time.Instant.parse(payload.path("observedAt").asText()); }
        catch (RuntimeException failure) { return Optional.empty(); }
        return Optional.of(new Receipt(invocationId, operation, target, status, evidenceRef,
                subject, payload.path("metadata").path("applicationId").asText(""),
                payload.path("metadata")));
    }

    boolean matches(TaskCriterion criterion, Receipt receipt,
            RunEventEnvelope event, DesktopApplicationIdentityBindings identities,
            TrustedCapabilityRegistry capabilities) {
        return matches(criterion, receipt, event, identities, capabilities, true);
    }

    boolean matches(TaskCriterion criterion, Receipt receipt,
            RunEventEnvelope event, DesktopApplicationIdentityBindings identities,
            TrustedCapabilityRegistry capabilities, boolean allowConditionProof) {
        var descriptor = capabilities.forReceipt(event.payload().path("tool").asText(""),
                event.payload().path("operation").asText("")).orElse(null);
        boolean desktop = descriptor != null && descriptor.targetKind()
                == TrustedCapabilityRegistry.TargetKind.DESKTOP_APPLICATION;
        boolean target = desktop ? identities.matches(criterion.target(), receipt.target(),
                receipt.applicationId(), event.runId(), event.sequence()) : targetMatches(criterion, receipt);
        return target
                && normalized(criterion.requiredOperation()).equals(normalized(receipt.operation()))
                && (criterion.requiredSubject().isBlank()
                        || normalized(criterion.requiredSubject()).equals(normalized(receipt.subject()))
                        || (allowConditionProof && desktop && descriptor.id().equals("desktop.observe")
                                && conditionProof.matches(criterion.id(),
                                        criterion.requiredSubject(), receipt.metadata())))
                && rank(receipt.status()) >= rank(criterion.requiredEvidence());
    }

    static boolean matches(TaskCriterion criterion, Receipt receipt) {
        return targetMatches(criterion, receipt)
                && normalized(criterion.requiredOperation()).equals(normalized(receipt.operation()))
                && (criterion.requiredSubject().isBlank()
                        || normalized(criterion.requiredSubject()).equals(normalized(receipt.subject())))
                && rank(receipt.status()) >= rank(criterion.requiredEvidence());
    }

    static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean targetMatches(TaskCriterion criterion, Receipt receipt) {
        if (normalized(criterion.target()).equals(normalized(receipt.target()))) return true;
        if (!receipt.applicationId().isBlank()
                && Set.of("launch_application", "open", "observe", "snapshot", "click",
                        "type", "key", "scroll").contains(normalized(criterion.requiredOperation()))
                && normalized(criterion.target()).equals(normalized(receipt.applicationId()))) {
            return true;
        }
        if (receipt.target().startsWith("http://") || receipt.target().startsWith("https://")) {
            try {
                URI actual = URI.create(receipt.target());
                URI expected = URI.create(criterion.target());
                if (expected.getHost() != null) {
                    if (expected.getRawQuery() != null || expected.getRawFragment() != null) return false;
                    return normalized(expected.getScheme()).equals(normalized(actual.getScheme()))
                            && normalized(expected.getHost()).equals(normalized(actual.getHost()))
                            && expected.getPort() == actual.getPort()
                            && normalized(path(expected)).equals(normalized(path(actual)));
                }
                // 旧合同没有目标类型，无法区分裸主机名与普通内容；URL 收据只能匹配显式 URL。
                return false;
            } catch (RuntimeException ignored) { return false; }
        }
        if (SetOfFileOperations.contains(criterion.requiredOperation())) {
            try {
                Path actual = Path.of(receipt.target());
                Path expected = Path.of(criterion.target());
                if (actual.isAbsolute() && !expected.isAbsolute()) {
                    Path resolved = ProjectAccessPolicy.requireProjectFilePath(
                            ProjectAccessPolicy.projectRoot().resolve(expected));
                    return resolved.equals(actual.toAbsolutePath().normalize());
                }
            } catch (RuntimeException ignored) { return false; }
        }
        return false;
    }

    private static String path(URI uri) {
        return uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
    }

    private static final class SetOfFileOperations {
        private static final java.util.Set<String> VALUES = java.util.Set.of(
                "write", "copy", "move", "mkdir", "read", "list", "delete");
        static boolean contains(String operation) {
            return VALUES.contains(normalized(operation));
        }
    }

    private static int rank(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "ACCEPTED" -> 1;
            case "OBSERVED" -> 2;
            case "VERIFIED" -> 3;
            default -> 0;
        };
    }

    record Receipt(String invocationId, String operation, String target,
                           String status, String evidenceRef, String subject,
                           String applicationId, com.fasterxml.jackson.databind.JsonNode metadata) {
        Receipt(String invocationId, String operation, String target,
                String status, String evidenceRef, String subject, String applicationId) {
            this(invocationId, operation, target, status, evidenceRef, subject,
                    applicationId, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        }
        Receipt(String invocationId, String operation, String target,
                String status, String evidenceRef, String subject) {
            this(invocationId, operation, target, status, evidenceRef, subject, "");
        }
    }
}
