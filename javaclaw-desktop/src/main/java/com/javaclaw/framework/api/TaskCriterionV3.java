package com.javaclaw.framework.api;

import com.javaclaw.framework.spi.EffectReceiptV1;

import java.util.Objects;
import java.util.List;
import java.util.Set;

/** A completion condition referring to a host-registered capability. */
public record TaskCriterionV3(String id, String description, String capabilityId,
                              CapabilityMetadata.TargetKind targetType, String target,
                              EffectReceiptV1.Status requiredEvidence,
                              String requiredSubject, List<String> requiredTextFragments,
                              BrowserTargetPhase browserTargetPhase) {
    public enum BrowserTargetPhase { RETURNED_PAGE, INPUT_PAGE }

    private static final Set<String> BROWSER_PAGE_INPUT_CAPABILITIES = Set.of(
            "browser.click", "browser.double_click", "browser.fill", "browser.select",
            "browser.check", "browser.upload", "browser.type", "browser.press_key",
            "browser.drag", "browser.hover", "browser.scroll");

    /** Existing and persisted V3 criteria keep their single literal subject semantics. */
    public TaskCriterionV3(String id, String description, String capabilityId,
            CapabilityMetadata.TargetKind targetType, String target,
            EffectReceiptV1.Status requiredEvidence, String requiredSubject) {
        this(id, description, capabilityId, targetType, target, requiredEvidence,
                requiredSubject, List.of(), BrowserTargetPhase.RETURNED_PAGE);
    }

    /** A missing phase preserves the returned-page target used by existing contracts. */
    public TaskCriterionV3(String id, String description, String capabilityId,
            CapabilityMetadata.TargetKind targetType, String target,
            EffectReceiptV1.Status requiredEvidence, String requiredSubject,
            List<String> requiredTextFragments) {
        this(id, description, capabilityId, targetType, target, requiredEvidence,
                requiredSubject, requiredTextFragments, BrowserTargetPhase.RETURNED_PAGE);
    }

    public TaskCriterionV3 {
        id = required(id, "id");
        description = required(description, "description");
        capabilityId = required(capabilityId, "capabilityId");
        targetType = Objects.requireNonNull(targetType, "targetType");
        target = required(target, "target");
        requiredEvidence = Objects.requireNonNull(requiredEvidence, "requiredEvidence");
        if (requiredEvidence != EffectReceiptV1.Status.ACCEPTED
                && requiredEvidence != EffectReceiptV1.Status.OBSERVED
                && requiredEvidence != EffectReceiptV1.Status.VERIFIED) {
            throw new IllegalArgumentException("unsupported required evidence");
        }
        requiredSubject = Objects.requireNonNullElse(requiredSubject, "").strip();
        requiredTextFragments = requiredTextFragments == null ? List.of()
                : List.copyOf(requiredTextFragments);
        browserTargetPhase = Objects.requireNonNullElse(
                browserTargetPhase, BrowserTargetPhase.RETURNED_PAGE);
        if (browserTargetPhase == BrowserTargetPhase.INPUT_PAGE
                && (targetType != CapabilityMetadata.TargetKind.URL
                    || !BROWSER_PAGE_INPUT_CAPABILITIES.contains(capabilityId))) {
            throw new IllegalArgumentException("input-page target requires a browser page input capability");
        }
        // These are conjunctions from one browser body observation, never a replacement
        // for an existing subject or an extension to another capability's verifier.
        if (!requiredTextFragments.isEmpty()
                && (!capabilityId.equals("browser.observe") || requiredTextFragments.size() > 8
                    || requiredTextFragments.stream().anyMatch(value -> value.isBlank()
                        || !value.equals(value.strip()) || value.length() > 128)
                    || requiredTextFragments.stream().mapToInt(String::length).sum() > 256)) {
            throw new IllegalArgumentException("invalid browser text fragments");
        }
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
