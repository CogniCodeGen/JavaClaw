package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.EffectCheckpointV1;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.EffectReconciliationV1;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Business uncertainty is separate from delivery uncertainty and from a fresh input baseline. */
public final class CrossModeBusinessFence {
    public static final Set<String> BROWSER_PAGE_INPUTS = Set.of("web_click", "web_dblclick", "web_fill",
            "web_type", "web_hover", "web_select", "web_check", "web_focus", "web_upload", "web_drag",
            "web_press_key", "web_scroll", "web_scroll_to_element", "web_mouse_move", "web_mouse_click_at",
            "web_dialog_handle", "web_set_viewport");
    public static final Set<String> BROWSER_SITE_INPUTS = Set.of("site_fill_password", "site_login_now");
    public static final Set<String> BROWSER_SCRIPT_INPUTS = Set.of("web_eval_js");
    private static final Set<String> DESKTOP_INPUTS = Set.of("desktop_session_click", "desktop_session_type",
            "desktop_session_key", "desktop_session_scroll");
    public static final Set<String> BROWSER_OBSERVATION_WAITS = Set.of("web_wait_for_element", "web_wait_for_text",
            "web_wait_for_url", "web_wait_for_load");

    private CrossModeBusinessFence() { }

    /** Exact tool names only; navigation and browser/session management are not page input. */
    public static boolean isBrowserBusinessInput(String tool) {
        return BROWSER_PAGE_INPUTS.contains(tool) || BROWSER_SITE_INPUTS.contains(tool)
                || BROWSER_SCRIPT_INPUTS.contains(tool);
    }

    public record Pending(RunId sourceRunId, RunEventEnvelope start, RunEventEnvelope receipt,
                          InteractionMode mode, boolean crossed) {
        public JsonNode toJson() {
            JsonNode value = receipt.payload();
            String target = value.path("target").asText("");
            return JsonNodeFactory.instance.objectNode().put("sourceRunId", sourceRunId.value())
                    .put("invocationId", value.path("invocationId").asText())
                    .put("tool", value.path("tool").asText()).put("status", value.path("status").asText())
                    .put("delivery", "SENT").put("uncertainty", "BUSINESS_UNVERIFIED")
                    .put("mode", mode.name()).put("crossedBackend", crossed)
                    .put("target", target.substring(0, Math.min(256, target.length())))
                    .put("evidenceRef", value.path("evidenceRef").asText());
        }
    }

    /** Complete history in the identical main scope; each child independently proves its own parent source. */
    public static List<StoredRun> authorizedSources(RunStore runs, ObjectMapper json, StoredRun current) {
        StoredRun main = parent(runs, json, current);
        List<StoredRun> result = new ArrayList<>();
        for (StoredRun source : runs.scopeRuns(current.request().scope())) {
            if (!InteractionExecutionPolicy.isInteraction(source.request())
                    || !source.request().scope().equals(current.request().scope())
                    || source.snapshot().createdAt().isAfter(current.snapshot().createdAt())) continue;
            // Do not silently drop a malformed owner and thereby remove its unresolved-effect history.
            StoredRun owner = parent(runs, json, source);
            if (!owner.request().scope().equals(main.request().scope()))
                throw new SecurityException("interaction business history crosses its main scope/source");
            result.add(source);
        }
        if (result.stream().noneMatch(source -> source.snapshot().id().equals(current.snapshot().id())))
            throw new SecurityException("interaction business history is incomplete");
        result.sort(Comparator.comparing((StoredRun source) -> source.snapshot().createdAt())
                .thenComparing(source -> source.snapshot().id().value()));
        return List.copyOf(result);
    }

    private static StoredRun parent(RunStore runs, ObjectMapper json, StoredRun source) {
        if (!InteractionExecutionPolicy.isInteraction(source.request()) || source.request().linkage().parentRunId() == null)
            throw new SecurityException("interaction business history has no trusted parent");
        StoredRun parent = runs.find(source.request().linkage().parentRunId()).orElseThrow(() ->
                new SecurityException("interaction business history parent is missing"));
        if (InteractionExecutionPolicy.isInteraction(parent.request())
                || !runs.readable(source.request().scope()) || !runs.readable(parent.request().scope())
                || !InteractionDelegateCoordinator.childScope(parent.request().scope()).equals(source.request().scope())
                || !json.valueToTree(parent.request().scope()).equals(source.request().attributes()
                    .get(InteractionExecutionPolicy.PARENT_SCOPE_ATTRIBUTE))
                || !json.valueToTree(parent.request().source()).equals(source.request().attributes()
                    .get(InteractionExecutionPolicy.PARENT_SOURCE_ATTRIBUTE)))
            throw new SecurityException("interaction business history has invalid scope/source identity");
        return parent;
    }

    /** Recovery may verify a missed checkpoint on its original Run, including a terminal owner. */
    public static void recoverProofs(RunStore runs, ObjectMapper json, StoredRun current) {
        for (StoredRun source : authorizedSources(runs, json, current)) {
            var events = runs.eventsAfter(source.snapshot().id(), 0);
            for (var checkpoint : BusinessEffectCheckpointVerifier.candidates(source.snapshot().id(), events, json))
                runs.verifyBusinessEffectCheckpoint(source.snapshot().id(), checkpoint);
            // A later phase may depend on a verifier event for its preceding closed phase.
            for (int stage = 0; stage < 12; stage++) {
                events = runs.eventsAfter(source.snapshot().id(), 0);
                long last = events.isEmpty() ? 0 : events.getLast().sequence();
                for (var proof : InteractionStageVerifier.candidates(source.snapshot().id(), events, json))
                    runs.verifyInteractionStage(source.snapshot().id(), proof);
                var updated = runs.eventsAfter(source.snapshot().id(), last);
                if (updated.stream().noneMatch(event -> event.type().equals(InteractionStageVerifier.EVENT_TYPE))) break;
            }
        }
    }

    /** Read-only projection: mode history is a durable latch, not merely the current selected mode. */
    public static List<Pending> pending(RunStore runs, ObjectMapper json, StoredRun current) {
        List<StoredRun> sources = authorizedSources(runs, json, current);
        Map<RunId, List<RunEventEnvelope>> journals = new LinkedHashMap<>();
        for (StoredRun source : sources) journals.put(source.snapshot().id(), runs.eventsAfter(source.snapshot().id(), 0));
        List<Pending> result = new ArrayList<>();
        for (StoredRun source : sources) {
            List<RunEventEnvelope> events = journals.get(source.snapshot().id());
            Map<String, RunEventEnvelope> starts = new LinkedHashMap<>();
            Map<String, RunEventEnvelope> receipts = new LinkedHashMap<>();
            for (RunEventEnvelope event : events) {
                if (!host(event) || !event.runId().equals(source.snapshot().id().value())) continue;
                String invocation = event.payload().path("invocationId").asText();
                if (event.type().equals("core.tool.started")) starts.put(invocation, event);
                if (event.type().equals("core.tool.receipt")) receipts.put(invocation, event);
            }
            Set<String> proved = proved(source.snapshot().id(), events, json);
            for (var entry : starts.entrySet()) {
                RunEventEnvelope start = entry.getValue(), receipt = receipts.get(entry.getKey());
                if (receipt == null || receipt.sequence() <= start.sequence() || proved.contains(entry.getKey())) continue;
                JsonNode input = start.payload(), value = receipt.payload(), metadata = value.path("metadata");
                String tool = input.path("tool").asText();
                InteractionMode mode = DESKTOP_INPUTS.contains(tool) ? InteractionMode.DESKTOP
                        : isBrowserBusinessInput(tool) ? InteractionMode.BROWSER : null;
                if (mode == null || !input.path("trustedDesktopTool").isBoolean()
                        || !input.path("trustedDesktopTool").booleanValue()
                        || !input.path("idempotent").isBoolean() || input.path("idempotent").booleanValue()
                        || !tool.equals(value.path("tool").asText())
                        || !value.path("operation").asText().equals(mode == InteractionMode.DESKTOP
                            ? tool.substring(16) : tool.startsWith("web_") ? tool.substring(4) : tool)
                        || !value.path("status").asText().equals("ACCEPTED")) continue;
                // Older exact host browser receipts prove the call returned, not its business effect.
                boolean historicalBrowser = mode == InteractionMode.BROWSER && !metadata.has("delivery") && !metadata.has("effect");
                if (!historicalBrowser && (!metadata.path("delivery").asText().equals("SENT")
                        || !metadata.path("effect").asText().equals("UNKNOWN"))) continue;
                boolean crossed = crossed(source, start, mode, sources, journals);
                result.add(new Pending(source.snapshot().id(), start, receipt, mode, crossed));
            }
        }
        return List.copyOf(result);
    }

    private static Set<String> proved(RunId source, List<RunEventEnvelope> events, ObjectMapper json) {
        Set<String> result = new HashSet<>();
        for (RunEventEnvelope event : events) {
            if (host(event) && event.type().equals(InteractionStageVerifier.EVENT_TYPE)
                    && event.payload().path("sourceRunId").asText().equals(source.value())
                    && event.payload().path("outcome").asText().equals("SATISFIED")) {
                try {
                    var proof = json.treeToValue(event.payload().path("proof"), com.javaclaw.framework.spi.InteractionStageProofV1.class);
                    if (InteractionStageVerifier.verifiesPersisted(source, events, event, json))
                        result.addAll(proof.actionInvocationIds());
                } catch (Exception malformed) { /* Stage authority is never inferred from missing fields. */ }
                continue;
            }
            if (!host(event) || !event.type().equals(BusinessEffectCheckpointVerifier.EVENT_TYPE)) continue;
            JsonNode value = event.payload();
            if (!value.path("sourceRunId").asText().equals(source.value())
                    || !value.path("outcome").asText().equals("SATISFIED")) continue;
            try {
                var effect = new EffectReconciliationV1(value.path("actionInvocationId").asText(),
                        value.path("sessionId").asText(), value.path("targetId").asText(),
                        value.path("actionObservationId").asText(), value.path("evidenceObservationId").asText());
                var checkpoint = new EffectCheckpointV1(effect, value.path("contractSequence").asLong(),
                        value.path("clickCriterionId").asText(), value.path("viewCriterionId").asText(),
                        value.path("requiredSubject").asText(), value.path("observationEvidenceRef").asText());
                // Only evidence established before the verifier event may authorize it.
                if (BusinessEffectCheckpointVerifier.verifies(source, events.stream()
                        .filter(candidate -> candidate.sequence() < event.sequence()).toList(), checkpoint, json))
                    result.add(effect.actionInvocationId());
            } catch (RuntimeException malformed) { /* Never let malformed proof clear a fence. */ }
        }
        return result;
    }

    private static boolean crossed(StoredRun source, RunEventEnvelope action, InteractionMode mode,
                                   List<StoredRun> sources, Map<RunId, List<RunEventEnvelope>> journals) {
        for (StoredRun next : sources) {
            boolean own = next.snapshot().id().equals(source.snapshot().id());
            for (RunEventEnvelope event : journals.get(next.snapshot().id())) {
                if (!host(event) || own && event.sequence() <= action.sequence()
                        || !own && event.timestamp().isBefore(action.timestamp())) continue;
                InteractionMode selected = null;
                if (event.type().equals(InteractionExecutionPolicy.MODE_SELECTED_EVENT)) {
                    selected = InteractionExecutionPolicy.activeMode(next.request(), List.of(event));
                } else if (!own && event.type().equals("core.run.started")) {
                    selected = InteractionExecutionPolicy.activeMode(next.request(), List.of());
                }
                if (selected != null && selected != mode) return true;
            }
        }
        return false;
    }

    public static void assertSideEffectAllowed(RunStore runs, ObjectMapper json, StoredRun current) {
        assertDeliveryKnown(runs, json, current);
        recoverProofs(runs, json, current);
        List<Pending> blocked = pending(runs, json, current).stream().filter(Pending::crossed).toList();
        if (blocked.isEmpty()) return;
        var context = JsonNodeFactory.instance.objectNode().put("kind", "interaction.business_effect_unverified")
                .put("reasonCode", "CROSS_MODE_BUSINESS_UNVERIFIED").put("dispatchAttempted", false)
                .put("text", "跨模式前的输入已投递，但业务效果缺少原动作前预声明、宿主可核验的证明；停止后续副作用并返回未满足条件。普通观察、模式切回、模型检查点或用户声称成功不能解除此保护。")
                .put("pendingEffectCount", blocked.size());
        var effects = context.putArray("unknownEffects");
        blocked.stream().limit(8).forEach(effect -> effects.add(effect.toJson()));
        throw new CrossModeBusinessEffectUnverifiedException(context);
    }

    /** Direct tool-node entry points must see previous Runs even without AgentEngine inheritance. */
    private static void assertDeliveryKnown(RunStore runs, ObjectMapper json, StoredRun current) {
        for (StoredRun source : authorizedSources(runs, json, current)) {
            Map<String, JsonNode> starts = new LinkedHashMap<>(), receipts = new LinkedHashMap<>();
            Set<String> reconciled = new HashSet<>();
            for (RunEventEnvelope event : runs.eventsAfter(source.snapshot().id(), 0)) {
                if (!host(event)) continue;
                JsonNode value = event.payload();
                String invocation = value.path("invocationId").asText();
                if (event.type().equals("core.tool.started")) starts.put(invocation, value);
                if (event.type().equals("core.tool.receipt")) receipts.put(invocation, value);
                if (event.type().equals("core.effect.reconciled") && value.path("outcome").asText().equals("SATISFIED"))
                    reconciled.add(value.path("actionInvocationId").asText());
            }
            for (var entry : starts.entrySet()) {
                JsonNode start = entry.getValue();
                String tool = start.path("tool").asText();
                if (!start.path("trustedDesktopTool").isBoolean() || !start.path("trustedDesktopTool").booleanValue()
                        || start.path("idempotent").asBoolean(true) || reconciled.contains(entry.getKey())
                        || BROWSER_OBSERVATION_WAITS.contains(tool)
                        || !tool.startsWith("web_") && !tool.startsWith("site_") && !tool.startsWith("desktop_session_")) continue;
                JsonNode receipt = receipts.get(entry.getKey());
                String delivery = receipt == null ? "MAYBE_SENT" : receipt.path("metadata").path("delivery").asText();
                EffectReceiptV1.Status status = null;
                if (receipt != null) try { status = EffectReceiptV1.Status.valueOf(receipt.path("status").asText()); }
                catch (IllegalArgumentException malformed) { status = EffectReceiptV1.Status.UNKNOWN; }
                if (status == null || status == EffectReceiptV1.Status.UNKNOWN || delivery.equals("MAYBE_SENT")
                        || status == EffectReceiptV1.Status.FAILED && !delivery.equals("NOT_SENT"))
                    throw new PendingEffectObservationRequiredException(source.snapshot().id().value(), entry.getKey(),
                            start.path("resourceKey").asText(), status, delivery,
                            PendingEffectObservationRequiredException.Reason.DELIVERY_UNCERTAIN);
            }
        }
    }

    private static boolean host(RunEventEnvelope event) {
        return event.schemaVersion() == 1 && event.producer().equals("framework.core");
    }
}
