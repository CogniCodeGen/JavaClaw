package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.spi.BrowserReceiptProof;

/** Compares a frozen capability target with a host receipt's typed identity. */
final class CapabilityTargetMatcher {
    private CapabilityTargetMatcher() { }

    static boolean matches(TrustedCapabilityRegistry capabilities,
            TrustedCapabilityRegistry.CapabilityDescriptor descriptor,
            String expected, JsonNode payload) {
        // 站点范围仍使用既有 bare-host 规则；精确浏览器 URL 必须检查含查询参数的宿主摘要。
        if (descriptor.id().startsWith("browser.")
                && descriptor.targetKind() == TrustedCapabilityRegistry.TargetKind.URL
                && expected != null && (expected.contains(":") || expected.contains("/")
                    || expected.contains("?") || expected.contains("#"))
                && BrowserReceiptProof.hasUrlProof(payload.path("metadata"))) {
            return BrowserReceiptProof.urlMatches(expected, payload.path("metadata"));
        }
        if (capabilities.targetMatches(descriptor, expected,
                payload.path("target").asText(""))) return true;
        String applicationId = payload.path("metadata").path("applicationId").asText("");
        return descriptor.targetKind()
                == TrustedCapabilityRegistry.TargetKind.DESKTOP_APPLICATION
                && !applicationId.isBlank()
                && expected.strip().equalsIgnoreCase(applicationId.strip());
    }

    static boolean matches(TrustedCapabilityRegistry capabilities,
            TrustedCapabilityRegistry.CapabilityDescriptor descriptor,
            TaskCriterionV3 criterion, RunEventEnvelope event,
            DesktopApplicationIdentityBindings identities) {
        if (criterion.browserTargetPhase() == TaskCriterionV3.BrowserTargetPhase.INPUT_PAGE) {
            return descriptor.targetKind() == TrustedCapabilityRegistry.TargetKind.URL
                    && BrowserReceiptProof.inputUrlMatches(
                            criterion.target(), event.payload().path("metadata"));
        }
        return matches(capabilities, descriptor, criterion.target(), event, identities);
    }

    static boolean matches(TrustedCapabilityRegistry capabilities,
            TrustedCapabilityRegistry.CapabilityDescriptor descriptor,
            String expected, RunEventEnvelope event,
            DesktopApplicationIdentityBindings identities) {
        if (descriptor.targetKind() != TrustedCapabilityRegistry.TargetKind.DESKTOP_APPLICATION) {
            return matches(capabilities, descriptor, expected, event.payload());
        }
        var payload = event.payload();
        return identities.matches(expected, payload.path("target").asText(""),
                payload.path("metadata").path("applicationId").asText(""),
                event.runId(), event.sequence());
    }
}
