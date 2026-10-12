package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.core.StepContextPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopDiscoveryModelProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 12_000;

    @Test
    void largeRequiredDiscoveryKeepsRealTargetsAndEvidenceWithinTheFinalEnvelopeBudget() throws Exception {
        ObjectNode original = envelope(0, 165, 165);
        String durable = original.toString();
        assertTrue(durable.length() > 40_000);
        var source = exchange("desktop_session_targets", durable);
        var assembler = new StepContextAssembler("run", new StepContextProjector(
                new StepContextPolicy(24_000, 24_000, 2, LIMIT, 12), JSON));
        var messages = new ArrayList<Message>();
        messages.add(new UserMessage("inspect QQ contacts"));
        messages.addAll(assembler.exchange(source, HostContextBlock.Kind.TOOL_EXCHANGE,
                true, List.of("core.tool.completed:run:targets")));

        List<Message> projected = assembler.project(messages, List.of());
        var response = projected.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).findFirst().orElseThrow();
        ObjectNode visible = (ObjectNode) JSON.readTree(response.getResponses().getFirst().responseData());
        JsonNode data = visible.path("data");
        int kept = data.path("targets").size();

        assertTrue(data.toString().length() <= LIMIT);
        assertTrue(response.getResponses().getFirst().responseData().length() <= LIMIT + 512);
        assertTrue(kept > 0 && kept < 165);
        assertTrue(HostContextBlock.required(response));
        assertEquals(original.path("evidenceRefs"), visible.path("evidenceRefs"));
        assertEquals(original.path("status"), visible.path("status"));
        assertEquals(original.path("errorCode"), visible.path("errorCode"));
        assertTrue(visible.path("displayMessage").asText().length() < 100);
        assertEquals(kept, data.path("count").asInt());
        assertEquals(kept, data.path("nextOffset").asInt());
        assertEquals(165, data.path("totalCount").asInt());
        assertEquals(165 - kept, data.path("projectionOmittedTargetCount").asInt());
        assertFalse(data.path("complete").asBoolean());
        assertTrue(data.path("hasMore").asBoolean());
        assertEquals(original.path("data").path("inventoryId"), data.path("inventoryId"));
        assertPrefix(original.path("data").path("targets"), data.path("targets"));
        assertEquals(durable, original.toString(), "The durable data and original display remain complete");
        assertEquals(durable, ((ToolResponseMessage) source.getLast()).getResponses().getFirst().responseData());
        assertEquals(StepMessageCodec.messages(projected),
                StepMessageCodec.messages(assembler.project(projected, List.of())));
    }

    @Test
    void finalEvidenceReferencesCanReduceAnAlreadyBoundedPageWithoutSkippingTargets() {
        ObjectNode original = envelope(128, 37, 165);
        JsonNode first = DesktopDiscoveryModelProjection.data("desktop_session_targets", original.path("data"), LIMIT);
        ObjectNode current = original.deepCopy();
        current.set("data", first);
        current.putArray("evidenceRefs").add("core.tool.completed:run:" + "exact-ref".repeat(700));

        ObjectNode visible = DesktopDiscoveryModelProjection.envelope("desktop_session_targets", current, LIMIT);
        JsonNode data = visible.path("data");
        int kept = data.path("targets").size();

        assertTrue(kept > 0 && kept < first.path("targets").size());
        assertEquals(128 + kept, data.path("nextOffset").asInt());
        assertEquals(37 - kept, data.path("projectionOmittedTargetCount").asInt());
        assertEquals(current.path("evidenceRefs"), visible.path("evidenceRefs"));
        assertEquals("QQ", data.path("query").asText());
        assertEquals(165, data.path("totalCount").asInt());
        assertPrefix(original.path("data").path("targets"), data.path("targets"));
        assertTrue(visible.toString().length() <= LIMIT + 512);
        assertEquals(visible, DesktopDiscoveryModelProjection.envelope("desktop_session_targets", visible, LIMIT));
    }

    @Test
    void compactLastPageKeepsItsOriginalPagingMetadata() {
        ObjectNode original = envelope(160, 5, 165);

        ObjectNode projected = DesktopDiscoveryModelProjection.envelope("desktop_session_targets", original, LIMIT);

        assertEquals(original.path("data"), projected.path("data"));
        assertFalse(projected.path("data").has("nextOffset"));
        assertFalse(projected.path("data").path("hasMore").asBoolean());
        assertFalse(projected.path("data").has("projectionTruncated"));
    }

    @Test
    void windowCandidatesPreserveSessionAndNativeRelationsWithoutInventingPaging() {
        ObjectNode original = envelope(0, 100, 100);
        ObjectNode data = (ObjectNode) original.path("data");
        data.put("kind", "desktop.window_candidates").put("sessionId", "exact-session")
                .put("sourceTargetId", "exact-root").put("sourceInvocationId", "input-invocation");
        data.set("candidates", data.remove("targets"));
        data.remove(List.of("offset", "totalCount", "nextOffset", "hasMore", "complete", "query", "inventoryId"));

        ObjectNode visible = DesktopDiscoveryModelProjection.envelope("desktop_session_window_candidates", original, 4_000);
        JsonNode projected = visible.path("data");

        assertEquals("exact-session", projected.path("sessionId").asText());
        assertEquals("exact-root", projected.path("sourceTargetId").asText());
        assertEquals("input-invocation", projected.path("sourceInvocationId").asText());
        assertFalse(projected.has("nextOffset"));
        assertFalse(projected.has("hasMore"));
        assertPrefix(data.path("candidates"), projected.path("candidates"));
        assertTrue(visible.toString().length() <= 4_512);
    }

    @Test
    void malformedAndUnrelatedKindsCannotBypassRequiredResultProtection() {
        ObjectNode original = envelope(0, 165, 165);
        ObjectNode missingId = original.deepCopy();
        ((ObjectNode) missingId.path("data").path("targets").get(0)).remove("targetId");
        assertFalse(DesktopDiscoveryModelProjection.recognizes("desktop_session_targets", missingId.path("data")));
        assertNull(DesktopDiscoveryModelProjection.envelope("desktop_session_targets", missingId, LIMIT));
        assertFalse(DesktopDiscoveryModelProjection.recognizes("unrelated_tool", original.path("data")));
        ((ObjectNode) original.path("data")).put("kind", "desktop.action");
        assertNull(DesktopDiscoveryModelProjection.envelope("desktop_session_targets", original, LIMIT));
        var response = HostContextBlock.mark(exchange("desktop_session_targets", missingId.toString()).getLast(),
                new HostContextBlock.Metadata("required", HostContextBlock.Kind.TOOL_EXCHANGE,
                        "revision", "run", true, List.of("evidence:exact")));
        assertThrows(LocalContextBudgetExceededException.class,
                () -> new StepContextProjector(new StepContextPolicy(24_000, 24_000, 2, LIMIT, 12), JSON)
                        .project(List.of(new UserMessage("task"), response)));
    }

    @Test
    void irreducibleEvidenceOrIdentityFailsWithoutCuttingIdentifiers() {
        ObjectNode original = envelope(0, 1, 1);
        String reference = "core.tool.completed:run:" + "r".repeat(20_000);
        original.putArray("evidenceRefs").add(reference);

        var failure = assertThrows(LocalContextBudgetExceededException.class,
                () -> DesktopDiscoveryModelProjection.envelope("desktop_session_targets", original, LIMIT));

        assertEquals("computer_use_discovery_identity", failure.budgetKind());
        assertEquals(List.of(reference), failure.requiredEvidenceRefs());
        assertTrue(failure.requiredCharacters() > LIMIT);
        original.putArray("evidenceRefs").add("exact-ref");
        String target = "target-" + "x".repeat(20_000);
        ((ObjectNode) original.path("data").path("targets").get(0)).put("targetId", target);
        assertThrows(LocalContextBudgetExceededException.class,
                () -> DesktopDiscoveryModelProjection.envelope("desktop_session_targets", original, LIMIT));
        assertEquals(target, original.path("data").path("targets").get(0).path("targetId").asText());
    }

    private static void assertPrefix(JsonNode source, JsonNode projected) {
        assertFalse(projected.isEmpty());
        for (int index = 0; index < projected.size(); index++) assertEquals(source.get(index), projected.get(index));
    }

    private static List<Message> exchange(String tool, String output) {
        var call = new AssistantMessage.ToolCall("call-discovery", "function", tool, "{}");
        return List.of(AssistantMessage.builder().toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(call.id(), tool, output))).build());
    }

    private static ObjectNode envelope(int offset, int count, int total) {
        ObjectNode envelope = JSON.createObjectNode().put("status", "SUCCEEDED").put("errorCode", "");
        envelope.put("displayMessage", "完整窗口列表\n".repeat(1_500));
        envelope.putArray("evidenceRefs").add("core.tool.completed:run:targets");
        ObjectNode data = envelope.putObject("data").put("schemaVersion", 1)
                .put("protocol", "computer-use").put("kind", "desktop.targets")
                .put("inventoryId", "exact-inventory").put("query", "QQ").put("offset", offset)
                .put("inventoryTotalCount", total).put("totalCount", total).put("count", count)
                .put("hasMore", offset + count < total).put("complete", offset == 0 && count == total)
                .put("truncated", offset != 0 || count != total).put("inputAuthority", false)
                .put("freshObservation", false).put("nextStep", "SELECT_TARGET");
        if (offset + count < total) data.put("nextOffset", offset + count);
        var rows = data.putArray("targets");
        for (int index = 0; index < count; index++) {
            rows.addObject().put("targetId", "exact-target-" + (offset + index)).put("providerId", "macos")
                    .put("processId", 2228).put("applicationId", "com.tencent.qq")
                    .put("application", "QQ").put("title", "QQ联系人 \"\\😀\n".repeat(8))
                    .put("visible", true).put("minimized", false).put("systemSurface", false)
                    .put("parentTargetId", "exact-native-parent").put("relationProof", "NATIVE_PARENT");
        }
        return envelope;
    }
}
