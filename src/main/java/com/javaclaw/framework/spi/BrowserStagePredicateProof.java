package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.InteractionSurfaceEvent;

import java.text.Normalizer;
import java.util.UUID;

/** Predicates are evaluated against the complete same-document body, never its receipt excerpt. */
public final class BrowserStagePredicateProof {
    private BrowserStagePredicateProof() { }

    public static ObjectNode identity(InteractionStageContext.Binding binding,
            InteractionSurfaceEvent identity, String kind, long capturedAt) {
        var proof = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1).put("kind", kind)
                .put("mode", "BROWSER").put("contractSequence", binding.contractSequence())
                .put("contractSha256", binding.contractSha256()).put("runtimeId", identity.runtimeId())
                .put("contextId", identity.contextId()).put("surfaceId", identity.surfaceId())
                .put("documentId", identity.documentId()).put("generation", identity.generation())
                .put("capturedAtMillis", capturedAt).put("complete", true);
        return proof;
    }

    public static JsonNode observation(InteractionStageContext.Binding binding, InteractionSurfaceEvent identity,
            String url, String fullBody, long capturedAt) {
        if (identity.runtimeId().isBlank() || identity.contextId().isBlank() || identity.surfaceId().isBlank()
                || identity.documentId().isBlank() || BrowserReceiptProof.urlDigest(url).isBlank()) return null;
        var proof = identity(binding, identity, "observation", capturedAt)
                .put("observationId", UUID.randomUUID().toString()).put("scope", "complete-visible-body")
                .put("bodySha256", InteractionStageContext.sha256(normalize(fullBody)))
                .put("urlSha256", BrowserReceiptProof.urlDigest(url));
        var conditions = proof.putArray("conditions");
        String body = normalize(fullBody);
        for (JsonNode criterion : binding.contract().path("criteria")) {
            if (!criterion.path("capabilityId").asText().equals("browser.observe")) continue;
            String subject = criterion.path("requiredSubject").asText("");
            JsonNode fragments = criterion.path("requiredTextFragments");
            if (subject.isBlank() && (!fragments.isArray() || fragments.isEmpty())) continue;
            // This optional field explicitly declares same-body literal conjunctions.
            // A historical generic subject alone does not declare complete-body scope.
            boolean supported = fragments.isArray() && !fragments.isEmpty();
            boolean matches = subject.isBlank() || body.contains(normalize(subject));
            if (fragments.isArray()) for (JsonNode fragment : fragments)
                matches &= fragment.isTextual() && body.contains(normalize(fragment.asText()));
            conditions.addObject().put("criterionId", criterion.path("id").asText())
                    .put("predicateSha256", InteractionStageContext.predicateSha256(criterion))
                    .put("outcome", supported ? matches ? "TRUE" : "FALSE" : "UNKNOWN").put("complete", supported);
        }
        return proof;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
    }
}
