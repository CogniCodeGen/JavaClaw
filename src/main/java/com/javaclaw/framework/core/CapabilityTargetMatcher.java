package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;

/** Compares a frozen capability target with a host receipt's typed identity. */
final class CapabilityTargetMatcher {
    private CapabilityTargetMatcher() { }

    static boolean matches(TrustedCapabilityRegistry capabilities,
            TrustedCapabilityRegistry.CapabilityDescriptor descriptor,
            String expected, JsonNode payload) {
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
