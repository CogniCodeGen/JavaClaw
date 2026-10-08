package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;

import java.time.Instant;
import java.util.*;

/** New stage authority requires new complete predicates; old frame labels never acquire it. */
public final class InteractionStageVerifier {
    public static final String EVENT_TYPE = "core.interaction.stage_verified";
    private static final Set<String> NATIVE_INPUTS = Set.of("desktop_session_click", "desktop_session_type",
            "desktop_session_key", "desktop_session_scroll");
    private InteractionStageVerifier() { }

    public static List<InteractionStageProofV1> candidates(RunId owner, List<RunEventEnvelope> events, ObjectMapper json) {
        try { return derive(owner, events, json); }
        catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) { return List.of(); }
    }

    public static boolean verifies(RunId owner, List<RunEventEnvelope> events, InteractionStageProofV1 proof, ObjectMapper json) {
        return proof != null && candidates(owner, events, json).contains(proof);
    }

    /** Revalidates authority already recorded by the host, without issuing new authority to old receipts. */
    public static boolean verifiesPersisted(RunId owner, List<RunEventEnvelope> fullEvents,
            RunEventEnvelope existingEvent, ObjectMapper json) {
        if (owner == null || fullEvents == null || existingEvent == null || json == null
                || !persistedStage(owner, existingEvent) || !fullEvents.contains(existingEvent)) return false;
        long sequence = 0;
        for (var event : fullEvents) {
            if (event == null || !event.runId().equals(owner.value()) || event.sequence() <= sequence) return false;
            sequence = event.sequence();
        }
        List<RunEventEnvelope> accepted = new ArrayList<>();
        for (var event : fullEvents) {
            if (event.sequence() > existingEvent.sequence()) break;
            if (persistedStage(owner, event)) {
                boolean valid = false;
                try {
                    var proof = json.treeToValue(event.payload().path("proof"), InteractionStageProofV1.class);
                    var view = persistedStageView(accepted, event, proof, json);
                    valid = view != null && verifies(owner, view, proof, json);
                    // A converted receipt is retained only after its complete original stage passes.
                    if (valid) accepted = new ArrayList<>(view);
                } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    // A malformed prior record cannot supply authority to the requested stage.
                }
                if (event.equals(existingEvent)) return valid;
            }
            accepted.add(event);
        }
        return false;
    }

    private static boolean persistedStage(RunId owner, RunEventEnvelope event) {
        return event != null && event.runId().equals(owner.value()) && host(event, EVENT_TYPE, 1)
                && event.payload().path("sourceRunId").asText().equals(owner.value())
                && event.payload().path("outcome").asText().equals("SATISFIED");
    }

    private static List<RunEventEnvelope> persistedStageView(List<RunEventEnvelope> prefix,
            RunEventEnvelope event, InteractionStageProofV1 proof, ObjectMapper json)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        Map<String, Triple> triples = triples(prefix);
        Triple after = triples.get(proof.afterInvocationId());
        if (after == null || event.timestamp().isBefore(after.receipt().timestamp())
                || !proof.afterInvocationId().equals(event.causationId())) return null;
        if (proof.mode() != InteractionMode.DESKTOP) return prefix;
        JsonNode metadata = after.receipt().payload().path("metadata");
        String encoded = metadata.path("conditionEvidence").asText("");
        if (encoded.isBlank()) return prefix;
        if (encoded.length() > EffectReceiptV1.MAX_CONDITION_EVIDENCE_CHARACTERS) return null;
        JsonNode old = json.readTree(encoded);
        if (!old.path("schemaVersion").isInt() || old.path("schemaVersion").intValue() != 1) return prefix;

        var frozenEvents = prefix.stream().filter(candidate -> candidate.producer().equals("framework.core")
                && Set.of("core.task.contract", "core.task.contract_revised").contains(candidate.type())).toList();
        if (frozenEvents.size() != 1) return null;
        var frozen = frozenEvents.getFirst();
        if (frozen.schemaVersion() != 3 || frozen.sequence() != proof.contractSequence()
                || !InteractionStageContext.sha256(frozen.payload().toString()).equals(proof.contractSha256())) return null;
        var contract = json.treeToValue(frozen.payload(), TaskContractV3.class);
        var criteria = contract.criteria().stream().filter(criterion -> criterion.id().equals(proof.observationCriterionId())).toList();
        if (criteria.size() != 1 || !criteria.getFirst().capabilityId().equals("desktop.observe")) return null;
        JsonNode stage = observation(after, InteractionMode.DESKTOP, frozen.sequence(), proof.contractSha256(),
                contract, frozen.payload(), json);
        Triple before = triples.get(proof.beforeInvocationId());
        if (stage == null || before == null) return null;
        // Old positive evidence must never be ignored to manufacture a FALSE baseline.
        String beforeEncoded = before.receipt().payload().path("metadata").path("conditionEvidence").asText("");
        if (!beforeEncoded.isBlank()) {
            if (beforeEncoded.length() > EffectReceiptV1.MAX_CONDITION_EVIDENCE_CHARACTERS) return null;
            JsonNode beforeEvidence = json.readTree(beforeEncoded);
            if (beforeEvidence.path("schemaVersion").asInt(-1) == 1) {
                if (!beforeEvidence.path("conditions").isArray()) return null;
                for (JsonNode condition : beforeEvidence.path("conditions"))
                    if (proof.observationCriterionId().equals(condition.path("criterionId").asText())) return null;
            }
        }
        JsonNode upgraded = DesktopConditionProof.persistedStageMetadata(criteria.getFirst().id(),
                criteria.getFirst().requiredSubject(), metadata, after.complete().payload().path("output"), stage);
        if (upgraded == null) return null;
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) after.receipt().payload();
        payload.set("metadata", upgraded);
        var original = after.receipt();
        var replacement = new RunEventEnvelope(original.runId(), original.sequence(), original.timestamp(),
                original.type(), original.schemaVersion(), original.producer(), original.correlationId(),
                original.causationId(), payload);
        return prefix.stream().map(candidate -> candidate.equals(original) ? replacement : candidate).toList();
    }

    /** Same original proof checks, exposed only for read-only session recovery acceptance. */
    static Optional<JsonNode> verifiedNativeObservation(List<RunEventEnvelope> events,
            RunEventEnvelope receipt, String criterionId, ObjectMapper json) {
        try {
            if (receipt == null || events.isEmpty()) return Optional.empty();
            long previous = 0;
            for (RunEventEnvelope event : events) {
                if (!event.runId().equals(receipt.runId()) || event.sequence() <= previous) return Optional.empty();
                previous = event.sequence();
            }
            var frozenEvents = events.stream().filter(event -> event.producer().equals("framework.core")
                    && Set.of("core.task.contract", "core.task.contract_revised").contains(event.type())).toList();
            if (frozenEvents.size() != 1 || frozenEvents.getFirst().schemaVersion() != 3) return Optional.empty();
            var frozen = frozenEvents.getFirst();
            var contract = json.treeToValue(frozen.payload(), TaskContractV3.class);
            if (!contract.reliable() || !contract.applicable() || !contract.desktopObservationSubjectsValid())
                return Optional.empty();
            Triple triple = triples(events).get(receipt.payload().path("invocationId").asText());
            if (triple == null || !triple.receipt().equals(receipt)) return Optional.empty();
            String hash = InteractionStageContext.sha256(frozen.payload().toString());
            JsonNode proof = observation(triple, InteractionMode.DESKTOP, frozen.sequence(), hash,
                    contract, frozen.payload(), json);
            if (proof == null) return Optional.empty();
            for (JsonNode original : frozen.payload().path("criteria")) {
                if (criterionId.equals(original.path("id").asText())
                        && original.path("capabilityId").asText().equals("desktop.observe")
                        && outcome(proof, original, "TRUE", InteractionMode.DESKTOP, triple))
                    return Optional.of(proof.deepCopy());
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            // Missing or ambiguous native facts never establish a logical session alias.
        }
        return Optional.empty();
    }

    private static List<InteractionStageProofV1> derive(RunId owner, List<RunEventEnvelope> events,
            ObjectMapper json) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (owner == null || events == null || events.isEmpty()) return List.of();
        long sequence = 0;
        for (var event : events) {
            if (!event.runId().equals(owner.value()) || event.sequence() <= sequence) return List.of();
            sequence = event.sequence();
        }
        var frozenEvents = events.stream().filter(event -> event.producer().equals("framework.core")
                && Set.of("core.task.contract", "core.task.contract_revised").contains(event.type())).toList();
        if (frozenEvents.size() != 1 || frozenEvents.getFirst().schemaVersion() != 3) return List.of();
        var frozen = frozenEvents.getFirst();
        var contract = json.treeToValue(frozen.payload(), TaskContractV3.class);
        var capabilities = TrustedCapabilityRegistry.builtins();
        if (!contract.applicable() || !contract.reliable() || !contract.desktopObservationSubjectsValid()
                || contract.criteria().stream().anyMatch(criterion -> !capabilities.supports(criterion))) return List.of();
        String hash = InteractionStageContext.sha256(frozen.payload().toString());
        Map<String, Triple> triples = triples(events);
        Set<String> alreadyProved = alreadyProved(owner, events, json);
        List<InteractionStageProofV1> result = new ArrayList<>();
        for (int index = 0; index < contract.criteria().size(); index++) {
            TaskCriterionV3 criterion = contract.criteria().get(index);
            InteractionMode mode = criterion.capabilityId().equals("desktop.observe") ? InteractionMode.DESKTOP
                    : criterion.capabilityId().equals("browser.observe") ? InteractionMode.BROWSER : null;
            if (mode == null || criterion.requiredEvidence() != EffectReceiptV1.Status.OBSERVED
                    || criterion.requiredSubject().isBlank() && criterion.requiredTextFragments().isEmpty()) continue;
            if (mode == InteractionMode.BROWSER && criterion.requiredTextFragments().isEmpty()) continue;
            JsonNode originalCriterion = frozen.payload().path("criteria").get(index);
            for (Triple after : triples.values()) {
                JsonNode afterProof = observation(after, mode, frozen.sequence(), hash, contract, frozen.payload(), json);
                if (afterProof == null || !outcome(afterProof, originalCriterion, "TRUE", mode, after)
                        || !targetMatches(capabilities, criterion, after.receipt(), events)) continue;
                List<Triple> pending = triples.values().stream().filter(triple -> triple.start().sequence() < after.start().sequence()
                        && businessInput(triple.tool()) && !alreadyProved.contains(triple.id())
                        && triple.receipt().payload().path("status").asText().equals("ACCEPTED")
                        && triple.receipt().payload().path("metadata").path("delivery").asText().equals("SENT")).toList();
                if (pending.isEmpty() || pending.size() > 128 || pending.stream().anyMatch(triple -> inputMode(triple.tool()) != mode)
                        || !allPendingCovered(events, after, pending, alreadyProved)) continue;
                Triple first = pending.getFirst();
                List<Triple> beforeFrames = triples.values().stream().filter(triple -> triple.receipt().sequence() < first.start().sequence())
                        .sorted(Comparator.comparingLong((Triple triple) -> triple.receipt().sequence()).reversed()).toList();
                for (Triple before : beforeFrames) {
                    JsonNode beforeProof = observation(before, mode, frozen.sequence(), hash, contract, frozen.payload(), json);
                    if (beforeProof == null || !outcome(beforeProof, originalCriterion, "FALSE", mode, before)
                            || !targetMatches(capabilities, criterion, before.receipt(), events)
                            || !sameObject(beforeProof, afterProof, mode)
                            || afterProof.path("generation").asLong() < beforeProof.path("generation").asLong()
                            || mode == InteractionMode.DESKTOP && afterProof.path("contentRevision").asLong(-1)
                                <= beforeProof.path("contentRevision").asLong(-1)
                            || mode == InteractionMode.BROWSER && afterProof.path("bodySha256").asText()
                                .equals(beforeProof.path("bodySha256").asText())
                            || beforeProof.path("observationId").asText().equals(afterProof.path("observationId").asText())
                            || afterProof.path("capturedAtMillis").asLong() <= beforeProof.path("capturedAtMillis").asLong()
                            || pending.stream().anyMatch(action -> action.start().sequence() <= before.receipt().sequence())) continue;
                    if (!completeInputChain(pending, before, after, beforeProof, afterProof, triples, events,
                            mode, frozen.sequence(), hash, contract, frozen.payload(), json)) continue;
                    int phaseStart = phaseStart(contract, index, mode);
                    var preceding = events.stream().filter(event -> event.sequence() < first.start().sequence()).toList();
                    if (phaseStart > 0 && TaskResultEvaluator.evaluateV3(prefix(contract, phaseStart), preceding,
                            "", capabilities).outcome() != TaskOutcome.VERIFIED_COMPLETE) continue;
                    var through = events.stream().filter(event -> event.sequence() <= after.receipt().sequence()).toList();
                    var selected = TaskResultEvaluator.criterionEvidenceV3(prefix(contract, index + 1), through, "", capabilities);
                    if (!after.receipt().payload().path("evidenceRef").asText().equals(selected.get(criterion.id()))
                            || selected.size() != index + 1) continue;
                    result.add(new InteractionStageProofV1(1, mode, frozen.sequence(), hash, criterion.id(),
                            before.id(), after.id(), pending.stream().map(Triple::id).toList()));
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean completeInputChain(List<Triple> inputs, Triple before, Triple after,
            JsonNode beforeProof, JsonNode afterProof, Map<String, Triple> triples, List<RunEventEnvelope> events,
            InteractionMode mode, long contractSequence, String hash, TaskContractV3 contract,
            JsonNode original, ObjectMapper json) throws com.fasterxml.jackson.core.JsonProcessingException {
        long previousEnd = before.receipt().sequence();
        long previousObservedAt = beforeProof.path("capturedAtMillis").asLong();
        for (Triple input : inputs) {
            var raw = input.complete().payload().path("output");
            var receipt = input.receipt().payload();
            var metadata = receipt.path("metadata");
            if (input.start().sequence() <= previousEnd || input.receipt().sequence() >= after.start().sequence()
                    || !receipt.path("status").asText().equals("ACCEPTED")
                    || !metadata.path("delivery").asText().equals("SENT") || !metadata.path("effect").asText().equals("UNKNOWN")
                    || !input.start().payload().path("idempotent").isBoolean()
                    || input.start().payload().path("idempotent").booleanValue()
                    || input.start().timestamp().toEpochMilli() < beforeProof.path("capturedAtMillis").asLong()
                    || observedAt(input.receipt()) < input.start().timestamp().toEpochMilli()
                    || observedAt(input.receipt()) > input.complete().timestamp().toEpochMilli()
                    || afterProof.path("capturedAtMillis").asLong() <= observedAt(input.receipt())) return false;
            if (mode == InteractionMode.DESKTOP) {
                if (!raw.path("protocol").asText().equals("computer-use")
                        || !raw.path("schemaVersion").isIntegralNumber() || raw.path("schemaVersion").asInt() != 1
                        || !raw.path("kind").asText().equals("desktop.action") || !raw.path("tool").asText().equals(input.tool())
                        || !raw.path("delivery").asText().equals("SENT") || !raw.path("effect").asText().equals("UNKNOWN")
                        || !raw.path("dispatchAttempted").isBoolean() || !raw.path("dispatchAttempted").booleanValue()
                        || !List.of("ACCEPTED", "VERIFIED").contains(raw.path("status").asText())
                        || !raw.path("status").asText().equals(metadata.path("desktopStatus").asText())
                        || !raw.path("admission").asText().equals("SUCCEEDED")
                        || !receipt.path("operation").asText().equals(input.tool().substring(16))
                        || !raw.path("actionKind").asText().equals(input.tool().substring(16).toUpperCase(Locale.ROOT))
                        || !input.start().payload().path("effectPolicy").asText().equals("OBSERVATION_GATED")
                        || !input.start().payload().path("resourceKey").asText().equals("desktop:" + metadata.path("targetId").asText())
                        || !raw.path("sessionId").asText().equals(metadata.path("sessionId").asText())
                        || !raw.path("targetId").asText().equals(metadata.path("targetId").asText())
                        || !raw.path("observationId").asText().equals(metadata.path("observationId").asText())
                        || raw.path("windowGeneration").asLong(-1) != beforeProof.path("generation").asLong()
                        || metadata.path("windowGeneration").asLong(-1) != beforeProof.path("generation").asLong()
                        || !input.start().payload().path("arguments").path("sessionId").asText().equals(metadata.path("sessionId").asText())
                        || !input.start().payload().path("arguments").path("observationId").asText().equals(metadata.path("observationId").asText())
                        || input.start().payload().path("arguments").path("generation").asLong(-1)
                            != beforeProof.path("generation").asLong()) return false;
                Triple baseline = triples.values().stream().filter(frame -> frame.tool().equals("desktop_session_observe")
                        && frame.receipt().sequence() < input.start().sequence()
                        && frame.receipt().payload().path("metadata").path("observationId").asText()
                            .equals(metadata.path("observationId").asText())).findFirst().orElse(null);
                JsonNode identity = baseline == null ? null : observation(baseline, mode, contractSequence, hash, contract, original, json);
                if (identity == null || baseline.receipt().sequence() < previousEnd
                        || (previousEnd == before.receipt().sequence()
                            ? identity.path("capturedAtMillis").asLong() < previousObservedAt
                            : identity.path("capturedAtMillis").asLong() <= previousObservedAt)
                        || identity.path("capturedAtMillis").asLong() > input.start().timestamp().toEpochMilli()
                        || !sameObject(identity, beforeProof, mode)
                        // The host request uses zero until the service resolves this exact observation.
                        // Its real revision comes from the committed baseline, never a later frame.
                        || !raw.path("contentRevision").isIntegralNumber()
                        || raw.path("contentRevision").asLong(-1) != 0
                            && raw.path("contentRevision").asLong(-1) != identity.path("contentRevision").asLong(-2)
                        || !metadata.path("sessionId").asText().equals(beforeProof.path("contextId").asText())
                        || !metadata.path("targetId").asText().equals(before.receipt().payload().path("metadata").path("targetId").asText())) return false;
            } else {
                if (CrossModeBusinessFence.BROWSER_SCRIPT_INPUTS.contains(input.tool()) || input.tool().equals("web_dialog_handle")) return false;
                JsonNode identity = parseStage(input, json);
                if (identity == null || !header(identity, mode, contractSequence, hash, "input")
                        || !sameObject(identity, beforeProof, mode)
                        || identity.path("generation").asLong() < beforeProof.path("generation").asLong()
                        || identity.path("generation").asLong() > afterProof.path("generation").asLong()
                        || identity.path("capturedAtMillis").asLong() < input.start().timestamp().toEpochMilli()
                        || identity.path("capturedAtMillis").asLong() > input.complete().timestamp().toEpochMilli()) return false;
                boolean fresh = false;
                for (Triple baseline : triples.values()) {
                    if (baseline.receipt().sequence() >= input.start().sequence()
                            || baseline.receipt().sequence() < before.receipt().sequence()) continue;
                    JsonNode frame = observation(baseline, mode, contractSequence, hash, contract, original, json);
                    if (frame != null
                            && sameObject(frame, identity, mode)
                            && frame.path("documentId").asText().equals(identity.path("documentId").asText())
                            && frame.path("generation").asLong() == identity.path("generation").asLong()) fresh = true;
                }
                if (!fresh) return false;
            }
            previousEnd = input.receipt().sequence();
            previousObservedAt = observedAt(input.receipt());
        }
        // No incomplete, overlapping, foreign-mode or unaccounted input may hide in the interval.
        for (var event : events) {
            if (event.sequence() <= before.receipt().sequence() || event.sequence() >= after.receipt().sequence()) continue;
            if (event.type().equals(InteractionExecutionPolicy.MODE_SELECTED_EVENT)) return false;
            if (host(event, "core.tool.started", 1) && businessInput(event.payload().path("tool").asText())
                    && inputs.stream().noneMatch(input -> input.id().equals(event.payload().path("invocationId").asText()))
                    && !definitelyNotSent(event, events)) return false;
        }
        return true;
    }

    private static JsonNode observation(Triple triple, InteractionMode mode, long sequence, String hash,
            TaskContractV3 contract, JsonNode original, ObjectMapper json) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (!triple.tool().equals(mode == InteractionMode.DESKTOP ? "desktop_session_observe" : "web_snapshot")
                && !(mode == InteractionMode.BROWSER && triple.tool().equals("web_get_text"))) return null;
        if (!triple.receipt().payload().path("status").asText().equals("OBSERVED")) return null;
        JsonNode proof = parseStage(triple, json);
        if (proof == null || !header(proof, mode, sequence, hash, "observation")
                || mode == InteractionMode.BROWSER && proof.path("capturedAtMillis").asLong() < triple.start().timestamp().toEpochMilli()
                || proof.path("capturedAtMillis").asLong() > triple.complete().timestamp().toEpochMilli()) return null;
        if (mode == InteractionMode.BROWSER && (!proof.path("scope").asText().equals("complete-visible-body")
                || !proof.path("documentId").asText().startsWith("document:")
                || !proof.path("bodySha256").asText().matches("[0-9a-f]{64}")
                || !proof.path("urlSha256").asText().equals(triple.receipt().payload().path("metadata").path("browserUrlSha256").asText()))) return null;
        if (mode == InteractionMode.DESKTOP) {
            if (!proof.equals(triple.complete().payload().path("output").path("interactionStage"))) return null;
            boolean bound = DesktopObservationBaseline.fromEvents(List.of(triple.start(), triple.complete(), triple.receipt())).stream()
                    .anyMatch(frame -> frame.sessionId().equals(proof.path("contextId").asText())
                        && frame.observationId().equals(proof.path("observationId").asText())
                        && frame.windowGeneration() == proof.path("generation").asLong()
                        && frame.capturedAtMillis() == proof.path("capturedAtMillis").asLong());
            if (!bound) return null;
        }
        List<JsonNode> expected = new ArrayList<>();
        for (JsonNode criterion : original.path("criteria")) if (criterion.path("capabilityId").asText()
                .equals(mode == InteractionMode.DESKTOP ? "desktop.observe" : "browser.observe")
                && (!criterion.path("requiredSubject").asText().isBlank()
                    || !criterion.path("requiredTextFragments").isMissingNode() && !criterion.path("requiredTextFragments").isEmpty())) expected.add(criterion);
        if (!proof.path("conditions").isArray() || proof.path("conditions").size() != expected.size()) return null;
        Set<String> ids = new HashSet<>();
        for (JsonNode condition : proof.path("conditions")) {
            if (!ids.add(condition.path("criterionId").asText()) || expected.stream().noneMatch(criterion ->
                    criterion.path("id").asText().equals(condition.path("criterionId").asText())
                        && InteractionStageContext.predicateSha256(criterion).equals(condition.path("predicateSha256").asText()))) return null;
        }
        return proof;
    }

    private static boolean outcome(JsonNode proof, JsonNode criterion, String expected, InteractionMode mode, Triple observation) {
        for (JsonNode condition : proof.path("conditions")) if (condition.path("criterionId").asText().equals(criterion.path("id").asText())) {
            return condition.path("outcome").asText().equals(expected) && condition.path("complete").isBoolean()
                    && condition.path("complete").booleanValue()
                    && (mode == InteractionMode.BROWSER || nativeDecision(proof, condition, criterion, expected, observation));
        }
        return false;
    }

    private static boolean nativeDecision(JsonNode proof, JsonNode condition, JsonNode criterion, String expected, Triple observation) {
        JsonNode raw = observation.complete().payload().path("output"), evidence = condition.path("evidence");
        JsonNode metadata = observation.receipt().payload().path("metadata");
        String label = evidence.path("label").asText(), visible = raw.path("content").path("visibleText").asText();
        if (!confidence(condition.path("confidence")) || !confidence(evidence.path("confidence"))
                || !evidence.path("region").asText().equals("main-content") || label.isBlank() || label.length() > 500
                || com.javaclaw.util.SensitiveDataRedactor.redactTextWithStatus(label).redacted()
                || !Set.of("content", "list", "table", "empty-state").contains(evidence.path("role").asText().toLowerCase(Locale.ROOT))
                || !visible.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT)
                    .contains(label.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT))) return false;
        for (String field : List.of("x", "y", "width", "height")) if (!evidence.path(field).isIntegralNumber()
                || !evidence.path(field).canConvertToInt()) return false;
        int x = evidence.path("x").asInt(), y = evidence.path("y").asInt();
        int width = evidence.path("width").asInt(), height = evidence.path("height").asInt();
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || (long) x + width > proof.path("frameWidth").asInt()
                || (long) y + height > proof.path("frameHeight").asInt()
                || proof.path("frameWidth").asInt() != raw.path("frame").path("width").asInt()
                || proof.path("frameHeight").asInt() != raw.path("frame").path("height").asInt()) return false;
        boolean positive = DesktopConditionProof.matches(criterion.path("id").asText(), criterion.path("requiredSubject").asText(), metadata);
        if (expected.equals("TRUE")) return positive;
        return !positive && !observation.receipt().payload().path("subject").asText().strip()
                    .equalsIgnoreCase(criterion.path("requiredSubject").asText().strip())
                && condition.path("contradiction").isBoolean() && condition.path("contradiction").booleanValue();
    }

    private static boolean confidence(JsonNode value) {
        double number = value.asDouble(Double.NaN);
        return value.isNumber() && Double.isFinite(number) && number >= .85 && number <= 1;
    }

    private static boolean header(JsonNode proof, InteractionMode mode, long sequence, String hash, String kind) {
        return proof.isObject() && proof.path("schemaVersion").isIntegralNumber() && proof.path("schemaVersion").asInt() == 1
                && proof.path("kind").asText().equals(kind) && proof.path("mode").asText().equals(mode.name())
                && proof.path("contractSequence").asLong(-1) == sequence && proof.path("contractSha256").asText().equals(hash)
                && proof.path("complete").isBoolean() && proof.path("complete").booleanValue()
                && !proof.path("runtimeId").asText().isBlank() && !proof.path("contextId").asText().isBlank()
                && !proof.path("surfaceId").asText().isBlank() && proof.path("generation").asLong(-1) > 0
                && proof.path("capturedAtMillis").asLong(-1) > 0
                && (!kind.equals("observation") || !proof.path("observationId").asText().isBlank());
    }

    private static boolean sameObject(JsonNode first, JsonNode second, InteractionMode mode) {
        return first.path("runtimeId").asText().equals(second.path("runtimeId").asText())
                && first.path("contextId").asText().equals(second.path("contextId").asText())
                && first.path("surfaceId").asText().equals(second.path("surfaceId").asText())
                && (mode == InteractionMode.BROWSER
                    || first.path("generation").asLong() == second.path("generation").asLong());
    }

    private static boolean allPendingCovered(List<RunEventEnvelope> events, Triple after,
            List<Triple> pending, Set<String> proved) {
        for (var start : events) {
            if (!host(start, "core.tool.started", 1) || start.sequence() >= after.start().sequence()
                    || !businessInput(start.payload().path("tool").asText())
                    || proved.contains(start.payload().path("invocationId").asText())) continue;
            String invocation = start.payload().path("invocationId").asText();
            var receipts = events.stream().filter(event -> host(event, "core.tool.receipt", 1)
                    && event.payload().path("invocationId").asText().equals(invocation)).toList();
            if (receipts.size() != 1) return false;
            var receipt = receipts.getFirst().payload();
            String status = receipt.path("status").asText(), delivery = receipt.path("metadata").path("delivery").asText();
            if (status.equals("FAILED") && delivery.equals("NOT_SENT") && definitelyNotSent(start, events)) continue;
            if (!status.equals("ACCEPTED") || !delivery.equals("SENT")
                    || pending.stream().noneMatch(input -> input.id().equals(invocation))) return false;
        }
        return true;
    }

    private static boolean definitelyNotSent(RunEventEnvelope start, List<RunEventEnvelope> events) {
        var receipts = events.stream().filter(event -> host(event, "core.tool.receipt", 1)
                && event.sequence() > start.sequence()
                && event.payload().path("invocationId").asText().equals(start.payload().path("invocationId").asText())).toList();
        return receipts.size() == 1
                && receipts.getFirst().payload().path("tool").asText().equals(start.payload().path("tool").asText())
                && receipts.getFirst().payload().path("status").asText().equals("FAILED")
                && receipts.getFirst().payload().path("metadata").path("delivery").asText().equals("NOT_SENT");
    }

    private static JsonNode parseStage(Triple triple, ObjectMapper json) throws com.fasterxml.jackson.core.JsonProcessingException {
        String value = triple.receipt().payload().path("metadata").path("interactionStage").asText("");
        return value.isBlank() || value.length() > EffectReceiptV1.MAX_CONDITION_EVIDENCE_CHARACTERS ? null : json.readTree(value);
    }

    private static Map<String, Triple> triples(List<RunEventEnvelope> events) {
        Map<String, List<RunEventEnvelope>> grouped = new LinkedHashMap<>();
        for (var event : events) if (Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type()))
            grouped.computeIfAbsent(event.payload().path("invocationId").asText(), ignored -> new ArrayList<>()).add(event);
        Map<String, Triple> result = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            var values = entry.getValue();
            if (values.size() != 3) continue;
            var start = values.get(0); var complete = values.get(1); var receipt = values.get(2);
            String tool = start.payload().path("tool").asText();
            if (!host(start, "core.tool.started", 1) || !host(complete, "core.tool.completed", 2) || !host(receipt, "core.tool.receipt", 1)
                    || !start.payload().path("trustedDesktopTool").isBoolean() || !start.payload().path("trustedDesktopTool").booleanValue()
                    || !complete.payload().path("status").asText().equals("SUCCEEDED")
                    || !complete.payload().path("tool").asText().equals(tool) || !receipt.payload().path("tool").asText().equals(tool)
                    || !receipt.payload().path("evidenceRef").asText().equals("core.tool.completed:" + start.runId() + ":" + entry.getKey())
                    || start.timestamp().isAfter(complete.timestamp()) || complete.timestamp().isAfter(receipt.timestamp())) continue;
            result.put(entry.getKey(), new Triple(entry.getKey(), tool, start, complete, receipt));
        }
        return result;
    }

    private static Set<String> alreadyProved(RunId owner, List<RunEventEnvelope> events, ObjectMapper json) throws com.fasterxml.jackson.core.JsonProcessingException {
        Set<String> result = new HashSet<>();
        for (var event : events) {
            if (host(event, BusinessEffectCheckpointVerifier.EVENT_TYPE, 1)
                    && event.payload().path("sourceRunId").asText().equals(owner.value())
                    && event.payload().path("outcome").asText().equals("SATISFIED")) {
                var value = event.payload();
                var effect = new EffectReconciliationV1(value.path("actionInvocationId").asText(),
                        value.path("sessionId").asText(), value.path("targetId").asText(),
                        value.path("actionObservationId").asText(), value.path("evidenceObservationId").asText());
                var checkpoint = new EffectCheckpointV1(effect, value.path("contractSequence").asLong(),
                        value.path("clickCriterionId").asText(), value.path("viewCriterionId").asText(),
                        value.path("requiredSubject").asText(), value.path("observationEvidenceRef").asText());
                if (BusinessEffectCheckpointVerifier.verifies(owner, events.stream().filter(previous ->
                        previous.sequence() < event.sequence()).toList(), checkpoint, json)) result.add(effect.actionInvocationId());
                continue;
            }
            if (!host(event, EVENT_TYPE, 1) || !event.payload().path("sourceRunId").asText().equals(owner.value())
                    || !event.payload().path("outcome").asText().equals("SATISFIED")) continue;
            var proof = json.treeToValue(event.payload().path("proof"), InteractionStageProofV1.class);
            if (verifies(owner, events.stream().filter(previous -> previous.sequence() < event.sequence()).toList(), proof, json))
                result.addAll(proof.actionInvocationIds());
        }
        return result;
    }

    private static int phaseStart(TaskContractV3 contract, int observation, InteractionMode mode) {
        String prefix = mode == InteractionMode.BROWSER ? "browser." : "desktop.";
        int first = observation;
        while (first > 0 && contract.criteria().get(first - 1).capabilityId().startsWith(prefix)
                && !contract.criteria().get(first - 1).capabilityId().endsWith(".observe")) first--;
        return first;
    }
    private static TaskContractV3 prefix(TaskContractV3 source, int count) {
        return new TaskContractV3(3, source.originalRequest(), source.criteria().subList(0, count), true, true,
                source.source(), source.reasonCodes(), source.unresolvedInputs(), source.desktopObservationPolicy(), source.intentStatus());
    }
    private static boolean targetMatches(TrustedCapabilityRegistry registry, TaskCriterionV3 criterion,
            RunEventEnvelope receipt, List<RunEventEnvelope> events) {
        var descriptor = registry.find(criterion.capabilityId()).orElse(null);
        return descriptor != null && CapabilityTargetMatcher.matches(registry, descriptor, criterion,
                receipt, DesktopApplicationIdentityBindings.fromEvents(events));
    }
    private static boolean businessInput(String tool) { return NATIVE_INPUTS.contains(tool) || CrossModeBusinessFence.isBrowserBusinessInput(tool); }
    private static InteractionMode inputMode(String tool) { return NATIVE_INPUTS.contains(tool) ? InteractionMode.DESKTOP : InteractionMode.BROWSER; }
    private static long observedAt(RunEventEnvelope receipt) { return Instant.parse(receipt.payload().path("observedAt").asText()).toEpochMilli(); }
    private static boolean host(RunEventEnvelope event, String type, int schema) {
        return event.type().equals(type) && event.schemaVersion() == schema && event.producer().equals("framework.core");
    }
    private record Triple(String id, String tool, RunEventEnvelope start, RunEventEnvelope complete, RunEventEnvelope receipt) { }
}
