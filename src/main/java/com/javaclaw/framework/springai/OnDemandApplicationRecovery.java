package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Locale;

/** Host application-identity recovery; a failed launch label never supplies an installed identity. */
final class OnDemandApplicationRecovery {
    static final String APPLICATIONS = "desktop_session_applications";
    static final String LAUNCH = "desktop_session_launch_application";
    private static final Set<String> IDENTITY_ERRORS = Set.of(
            "APPLICATION_NOT_FOUND", "AMBIGUOUS_APPLICATION", "INVALID_ARGUMENTS");

    private OnDemandApplicationRecovery() { }

    static State derive(List<AgentStep> steps, List<RunEventEnvelope> events) {
        if (steps.isEmpty()) return new State(null, null, List.of(), "", "", false, null, null);
        String run = steps.getFirst().turnId().value();
        Map<String, JsonNode> receipts = new HashMap<>();
        for (RunEventEnvelope event : events) {
            if (event.runId().equals(run) && event.type().equals("core.tool.receipt")
                    && event.schemaVersion() == 1 && event.producer().equals("framework.core")) {
                String invocation = event.payload().path("invocationId").asText("");
                if (!invocation.isBlank()) receipts.put(invocation, event.payload());
            }
        }
        AgentStep failure = null;
        AgentStep applications = null;
        Map<Integer, AgentStep> pages = new LinkedHashMap<>();
        String reason = "";
        String requested = "";
        boolean needsIdentity = false;
        boolean catalogEligible = true;
        AgentStep selectedLaunch = null;
        JsonNode selectedReceipt = null;
        String catalogScope = "";
        for (AgentStep step : steps.stream().filter(value -> value.turnId().value().equals(run))
                .sorted(Comparator.comparingLong(AgentStep::startSequence)).toList()) {
            if (step.kind() != AgentStep.Kind.TOOL || step.input() == null) continue;
            String tool = step.input().path("tool").asText("");
            JsonNode receipt = receipts.get(step.input().path("invocationId").asText(""));
            if (tool.equals(LAUNCH)) {
                if (identityRejection(step, receipt)) {
                    selectedLaunch = null;
                    selectedReceipt = null;
                    failure = step;
                    requested = step.input().path("arguments").path("application").asText().strip();
                    reason = receipt.path("metadata").path("reasonCode").asText();
                    needsIdentity = true;
                    catalogEligible = true;
                } else {
                    selectedLaunch = step;
                    selectedReceipt = receipt != null && matchingReceiptTool(step, receipt)
                            && receipt.path("operation").asText().equals("launch_application")
                            ? receipt : null;
                    needsIdentity = false;
                    catalogEligible = false;
                    failure = null;
                    reason = "";
                    requested = "";
                }
            } else if (tool.equals(APPLICATIONS) && completed(step)) {
                boolean failed = step.output().path("status").asText().equals("FAILED")
                        && matchingReceipt(step, receipt, "FAILED")
                        && receipt.path("operation").asText().equals("applications");
                JsonNode data = step.output().path("rawOutput");
                boolean observed = step.output().path("status").asText().equals("SUCCEEDED")
                        && matchingReceipt(step, receipt, "OBSERVED")
                        && receipt.path("operation").asText().equals("applications")
                        && data.path("schemaVersion").isInt() && data.path("schemaVersion").intValue() == 1
                        && data.path("kind").asText().equals("desktop.applications")
                        && data.path("applications").isArray();
                // Enumeration is optional platform support. A settled failure
                // preserves its error and allows the original launch interface.
                if (failed || observed) {
                    applications = step;
                    if (observed) {
                        String scope = data.path("catalogId").asText("") + "\u0000"
                                + data.path("query").asText("");
                        if (!scope.equals(catalogScope)) pages.clear();
                        catalogScope = scope;
                        pages.put(data.path("offset").asInt(0), step);
                    }
                    needsIdentity = catalogEligible && failure != null;
                }
            } else if (tool.equals("desktop_session_open") && completed(step)
                    && step.output().path("status").asText().equals("SUCCEEDED")
                    && matchingReceipt(step, receipt, "ACCEPTED")
                    && receipt.path("operation").asText().equals("open")
                    && !receipt.path("metadata").path("sessionId").asText("").isBlank()
                    && step.input().path("arguments").path("targetId").asText()
                            .equals(receipt.path("metadata").path("targetId").asText())) {
                needsIdentity = false;
                catalogEligible = false;
            }
        }
        return new State(failure, applications, List.copyOf(pages.values()), requested, reason,
                needsIdentity, selectedLaunch, selectedReceipt);
    }

    static boolean identityRejection(AgentStep step, JsonNode receipt) {
        if (!confirmedNotSent(step, receipt)) return false;
        return IDENTITY_ERRORS.contains(receipt.path("metadata").path("reasonCode").asText());
    }

    static boolean confirmedNotSent(AgentStep step, JsonNode receipt) {
        if (!completed(step) || !step.output().path("status").asText().equals("FAILED")
                || !matchingReceipt(step, receipt, "FAILED")
                || !receipt.path("operation").asText().equals("launch_application")) return false;
        JsonNode data = step.output().path("rawOutput");
        JsonNode metadata = receipt.path("metadata");
        String requested = step.input().path("arguments").path("application").asText("").strip();
        String reason = data.path("reasonCode").asText(data.path("reason").asText(""));
        return data.path("schemaVersion").isInt() && data.path("schemaVersion").intValue() == 1
                && data.path("kind").asText().equals("desktop.launch")
                && Set.of("REJECTED", "FAILED").contains(data.path("admission").asText())
                && data.path("status").asText().equals("FAILED")
                && data.path("delivery").asText().equals("NOT_SENT")
                && metadata.path("delivery").asText().equals("NOT_SENT")
                && !reason.isBlank() && reason.equals(metadata.path("reasonCode").asText())
                && requested.equals(data.path("requestedApplication").asText().strip())
                && requested.equals(metadata.path("requestedApplication").asText().strip());
    }

    private static boolean matchingReceipt(AgentStep step, JsonNode receipt, String status) {
        return matchingReceiptTool(step, receipt)
                && receipt.path("status").asText().equals(status);
    }

    private static boolean matchingReceiptTool(AgentStep step, JsonNode receipt) {
        return receipt != null && receipt.path("tool").asText().equals(step.input().path("tool").asText());
    }

    private static boolean completed(AgentStep step) {
        return step.state() == AgentStep.State.COMPLETED && step.output() != null;
    }

    static boolean applicationsAttempted(List<AgentStep> steps) {
        return steps.stream().anyMatch(step -> step.kind() == AgentStep.Kind.TOOL && step.input() != null
                && step.input().path("tool").asText().equals(APPLICATIONS));
    }

    record State(AgentStep rejectedLaunch, AgentStep applications, List<AgentStep> pages,
            String requestedApplication, String reasonCode, boolean needsIdentity,
            AgentStep selectedLaunch, JsonNode selectedReceipt) {
        String requiredTool() {
            if (!needsIdentity) return "";
            return applications == null ? APPLICATIONS : LAUNCH;
        }

        List<AgentStep> contextSteps() {
            // Catalogs are evidence for identity selection, not permanent Run instructions.
            if (!needsIdentity) return selectedLaunch == null ? List.of() : List.of(selectedLaunch);
            if (rejectedLaunch == null) return applications == null ? List.of() : List.of(applications);
            return applications == null ? List.of(rejectedLaunch) : List.of(rejectedLaunch, applications);
        }

        boolean catalogHasMore() {
            return needsIdentity && applications != null && applications.output().path("status").asText().equals("SUCCEEDED")
                    && applications.output().path("rawOutput").path("hasMore").asBoolean(false);
        }

        ObjectNode payload() {
            return payload("", 12_000);
        }

        ObjectNode payload(String taskText, int maxCharacters) {
            var payload = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1)
                    .put("protocol", "computer-use").put("kind", "computer_use.application_identity")
                    .put("requestedApplication", SensitiveDataRedactor.redactText(requestedApplication))
                    .put("reasonCode", reasonCode).put("requiredTool", requiredTool());
            if (!needsIdentity) {
                appendSelectedLaunch(payload);
                return bounded(payload, maxCharacters);
            }
            if (rejectedLaunch != null) {
                payload.put("failedInvocationId", rejectedLaunch.input().path("invocationId").asText())
                        .put("admission", "REJECTED").put("delivery", "NOT_SENT");
            }
            if (applications != null) {
                payload.put("catalogStatus", applications.output().path("status").asText());
                payload.put("catalogHasMore", catalogHasMore());
                JsonNode nextOffset = applications.output().path("rawOutput").path("nextOffset");
                if (catalogHasMore() && nextOffset.isIntegralNumber()) payload.set("nextOffset", nextOffset);
                JsonNode data = applications.output().path("rawOutput");
                for (String field : List.of("catalogId", "query", "catalogTotalCount", "totalCount"))
                    if (data.has(field)) payload.set(field, data.path(field).deepCopy());
                appendIdentities(payload, taskText, maxCharacters);
            }
            return bounded(payload, maxCharacters);
        }

        boolean catalogProjectionNeedsQuery(String taskText, int maxCharacters) {
            return needsIdentity && applications != null
                    && applications.output().path("status").asText().equals("SUCCEEDED")
                    && payload(taskText, maxCharacters).path("catalogProjectionTruncated").asBoolean(false);
        }

        private ObjectNode bounded(ObjectNode payload, int maxCharacters) {
            int limit = Math.max(0, Math.min(12_000, maxCharacters));
            if (payload.has("applications")) {
                var identities = (com.fasterxml.jackson.databind.node.ArrayNode) payload.path("applications");
                while (payload.toString().length() > limit && !identities.isEmpty()) {
                    identities.remove(identities.size() - 1);
                    payload.put("catalogProjectedCount", identities.size()).put("catalogProjectionTruncated", true);
                }
            }
            int required = payload.toString().length();
            if (required > limit) throw new LocalContextBudgetExceededException("application identity projection",
                    limit, required, Map.of("applicationIdentity", required), List.of());
            return payload;
        }

        private void appendSelectedLaunch(ObjectNode payload) {
            if (selectedLaunch == null) return;
            String requested = selectedLaunch.input().path("arguments").path("application").asText("").strip();
            JsonNode metadata = selectedReceipt == null ? JsonNodeFactory.instance.nullNode()
                    : selectedReceipt.path("metadata");
            JsonNode data = completed(selectedLaunch) ? selectedLaunch.output().path("rawOutput")
                    : JsonNodeFactory.instance.nullNode();
            boolean bound = data.path("schemaVersion").asInt() == 1
                    && data.path("kind").asText().equals("desktop.launch")
                    && requested.equals(data.path("requestedApplication").asText().strip())
                    && requested.equals(metadata.path("requestedApplication").asText().strip());
            boolean existingApplication = bound && data.path("delivery").asText().equals("NOT_SENT")
                    && data.path("dispatchAttempted").isBoolean() && !data.path("dispatchAttempted").booleanValue()
                    && metadata.path("delivery").asText().equals("NOT_SENT")
                    && metadata.path("dispatchAttempted").asText().equals("false")
                    && data.path("processId").isIntegralNumber() && data.path("processId").asLong() > 0
                    && data.path("processId").asLong() == metadata.path("processId").asLong()
                    && !data.path("applicationId").asText().isBlank()
                    && data.path("applicationId").asText().equals(metadata.path("applicationId").asText());
            boolean accepted = bound && matchingReceipt(selectedLaunch, selectedReceipt, "ACCEPTED")
                    && selectedLaunch.output().path("status").asText().equals("SUCCEEDED")
                    && (metadata.path("delivery").asText().equals("SENT") || existingApplication);
            boolean rejected = confirmedNotSent(selectedLaunch, selectedReceipt);
            var launch = payload.putObject("selectedLaunch")
                    .put("invocationId", selectedLaunch.input().path("invocationId").asText())
                    .put("requestedApplication", SensitiveDataRedactor.redactText(requested))
                    .put("status", accepted ? "ACCEPTED" : rejected ? "FAILED" : "UNKNOWN")
                    .put("delivery", accepted ? metadata.path("delivery").asText()
                            : rejected ? "NOT_SENT" : "MAYBE_SENT");
            if (existingApplication && accepted) launch.put("dispatchAttempted", false)
                    .put("nextStep", "DISCOVER_TARGETS");
            if (selectedReceipt == null) return;
            if (requested.equals(metadata.path("requestedApplication").asText().strip()))
                for (String field : List.of("applicationId", "processId", "reasonCode"))
                    if (metadata.has(field)) launch.set(field, metadata.path(field).deepCopy());
            String evidence = selectedReceipt.path("evidenceRef").asText("");
            if (!evidence.isBlank()) launch.put("evidenceRef", evidence);
            if (bound)
                for (String field : List.of("applicationId", "processId"))
                    if (data.has(field)) launch.set(field, data.path(field).deepCopy());
        }

        private void appendIdentities(ObjectNode payload, String taskText, int maxCharacters) {
            payload.put("catalogTrust", "UNTRUSTED_APPLICATION_METADATA");
            var identities = payload.putArray("applications");
            Set<String> seen = new HashSet<>();
            boolean truncated = pages.stream().anyMatch(page -> page.output().path("rawOutput")
                    .path("truncated").asBoolean(false));
            List<JsonNode> observed = new ArrayList<>();
            for (AgentStep page : pages) {
                for (JsonNode identity : page.output().path("rawOutput").path("applications")) {
                    String id = identity.path("applicationId").asText("");
                    String launch = identity.path("launchName").asText("");
                    if (!identity.isObject() || id.isBlank() || launch.isBlank()) { truncated = true; continue; }
                    if (!seen.add(id + "\u0000" + launch)) continue;
                    observed.add(identity);
                }
            }
            String query = normalized(taskText == null ? "" : taskText);
            if (!requestedApplication.isBlank()) query += " " + normalized(requestedApplication);
            final String search = query;
            // Ranking is deterministic host projection; application labels stay data, never instructions.
            observed.sort(Comparator.comparing((JsonNode value) -> !matchesTask(value, search)));
            payload.put("catalogObservedCount", observed.size()).put("catalogProjectedCount", 0)
                    .put("catalogProjectionTruncated", false).put("catalogTruncated", truncated)
                    .put("catalogCoverageComplete", catalogCoverageComplete());
            int limit = Math.max(0, Math.min(12_000, maxCharacters));
            for (JsonNode identity : observed) {
                identities.add(identity.deepCopy());
                payload.put("catalogProjectedCount", identities.size());
                if (payload.toString().length() > limit) {
                    identities.remove(identities.size() - 1);
                    payload.put("catalogProjectedCount", identities.size());
                    break;
                }
            }
            payload.put("catalogProjectionTruncated", identities.size() < observed.size());
            payload.put("catalogTruncated", truncated);
        }

        private boolean catalogCoverageComplete() {
            if (pages.isEmpty() || catalogHasMore()) return false;
            int next = 0;
            for (AgentStep page : pages.stream().sorted(Comparator.comparingInt(value -> value.output()
                    .path("rawOutput").path("offset").asInt(0))).toList()) {
                JsonNode data = page.output().path("rawOutput");
                if (data.path("offset").asInt(0) != next) return false;
                next += data.path("applications").size();
            }
            JsonNode last = applications.output().path("rawOutput");
            return next == last.path("totalCount").asInt(next);
        }

        private static boolean matchesTask(JsonNode identity, String query) {
            if (query.isBlank()) return false;
            for (String field : List.of("name", "displayName", "applicationId", "launchName")) {
                String value = normalized(identity.path(field).asText(""));
                if (!value.isBlank() && query.contains(value)) return true;
            }
            for (JsonNode alias : identity.path("aliases")) {
                String value = normalized(alias.asText(""));
                if (!value.isBlank() && query.contains(value)) return true;
            }
            return false;
        }

        private static String normalized(String value) {
            return java.text.Normalizer.normalize(value.strip(), java.text.Normalizer.Form.NFKC)
                    .toLowerCase(Locale.ROOT);
        }
    }
}
