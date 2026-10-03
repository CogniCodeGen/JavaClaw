package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.core.StepContextPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputerUseEvidenceProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 16_000;

    @Test
    void richObservationIsBoundedWithoutLosingFrameIdentityOrControlFidelity() throws Exception {
        ObjectNode original = observation(true);
        String raw = original.toString();
        assertTrue(raw.length() > 60_000);
        List<Message> source = exchange("desktop_session_observe", raw);

        List<Message> projected = ComputerUseEvidenceProjection.project(source, LIMIT);
        ObjectNode visible = payload(projected);
        JsonNode data = visible.path("data");
        JsonNode full = original.path("data");

        assertTrue(data.toString().length() <= LIMIT);
        assertTrue(responseData(projected).length() <= LIMIT + 512);
        for (String field : List.of("schemaVersion", "protocol", "kind", "sessionId", "targetId",
                "applicationId", "observationId", "windowGeneration", "contentRevision",
                "capturedAtMillis", "coordinateSpace", "frame", "activeView", "elementCount")) {
            assertEquals(full.path(field), data.path(field), field);
        }
        for (String field : List.of("status", "errorCode", "evidenceRefs")) {
            assertEquals(original.path(field), visible.path(field), field);
        }
        assertTrue(data.path("projectionTruncated").asBoolean());
        assertTrue(data.path("content").path("truncated").asBoolean());
        assertTrue(data.path("content").path("projectionTruncated").asBoolean());
        assertTrue(data.path("elementsTruncated").asBoolean());
        assertTrue(data.path("visualTargetsTruncated").asBoolean());
        assertTrue(data.path("elements").size() > 0);
        assertTrue(data.path("visualTargets").size() > 0);
        assertEquals(data.path("elements").size(), data.path("projectedElementCount").asInt());
        assertEquals(data.path("visualTargets").size(), data.path("projectedVisualTargetCount").asInt());
        assertEquals(40 - data.path("elements").size(), data.path("projectionOmittedElementCount").asInt());
        assertEquals(40 - data.path("visualTargets").size(), data.path("projectionOmittedVisualTargetCount").asInt());
        assertWholeEntriesInOriginalOrder(full.path("elements"), data.path("elements"));
        assertWholeEntriesInOriginalOrder(full.path("visualTargets"), data.path("visualTargets"));
        assertEquals(raw, responseData(source));
        assertEquals(raw, original.toString());
        assertEquals(StepMessageCodec.message(source.getFirst()), StepMessageCodec.message(projected.getFirst()));
        assertTrue(visible.path("displayMessage").asText().length() < 100);
        assertEquals(responseData(projected), responseData(
                ComputerUseEvidenceProjection.project(projected, LIMIT)));
    }

    @Test
    void compactObservationOnlyRemovesDuplicateDisplayTextAndProjectionIsIdempotent() throws Exception {
        ObjectNode original = observation(false);
        List<Message> source = exchange("desktop_session_observe", original.toString());

        List<Message> first = ComputerUseEvidenceProjection.project(source, LIMIT);
        List<Message> second = ComputerUseEvidenceProjection.project(first, LIMIT);

        assertEquals(original.path("data"), payload(first).path("data"));
        assertFalse(payload(first).path("data").path("projectionTruncated").asBoolean());
        assertEquals(StepMessageCodec.messages(first), StepMessageCodec.messages(second));
    }

    @Test
    void disabledCharacterLimitDoesNotOverflowOrTruncateObservationEvidence() throws Exception {
        ObjectNode original = observation(true);

        List<Message> projected = ComputerUseEvidenceProjection.project(
                exchange("desktop_session_observe", original.toString()), Integer.MAX_VALUE);

        assertEquals(original.path("data"), payload(projected).path("data"));
        assertFalse(payload(projected).path("data").path("projectionTruncated").asBoolean());
    }

    @Test
    void projectedObservationCanBeProtectedByTheStrictRequiredEvidenceBudget() throws Exception {
        List<Message> bounded = ComputerUseEvidenceProjection.project(
                exchange("desktop_session_observe", observation(true).toString()), LIMIT);
        var protectedMessages = new ArrayList<Message>();
        protectedMessages.add(new UserMessage("inspect requested application"));
        for (int index = 0; index < bounded.size(); index++) {
            protectedMessages.add(HostContextBlock.mark(bounded.get(index), new HostContextBlock.Metadata(
                    "observation/" + index, HostContextBlock.Kind.OBSERVATION, "bounded-revision",
                    "run", true, List.of("core.tool.completed:run:observation"))));
        }
        var projector = new StepContextProjector(StepContextPolicy.DEFAULT, JSON);

        var projected = projector.project(protectedMessages);

        projector.validateRequiredEvidence(protectedMessages, projected.messages());
        assertEquals(payload(bounded).path("data"), payload(projected.messages().subList(1, 3)).path("data"));
        assertEquals(StepMessageCodec.messages(projected.messages()),
                StepMessageCodec.messages(projector.project(projected.messages()).messages()));
    }

    @Test
    void actionAndUnknownDeliveryReceiptsAreNeverAltered() throws Exception {
        ObjectNode action = observation(true);
        action.put("status", "UNKNOWN").put("errorCode", "DELIVERY_UNKNOWN");
        ((ObjectNode) action.path("data")).put("kind", "desktop.action")
                .put("admission", "UNCERTAIN").put("delivery", "MAYBE_SENT")
                .put("dispatchAttempted", true);
        String raw = action.toString();

        assertEquals(raw, responseData(ComputerUseEvidenceProjection.project(
                exchange("desktop_session_observe", raw), LIMIT)));
        assertEquals(raw, responseData(ComputerUseEvidenceProjection.project(
                exchange("desktop_session_click", raw), LIMIT)));
    }

    @Test
    void malformedOrUnrecognizedObservationsRemainUnchanged() throws Exception {
        ObjectNode source = observation(true);
        List<ObjectNode> invalid = new ArrayList<>();
        ObjectNode badVersion = source.deepCopy();
        ((ObjectNode) badVersion.path("data")).put("schemaVersion", "1");
        invalid.add(badVersion);
        ObjectNode badFrame = source.deepCopy();
        ((ObjectNode) badFrame.path("data").path("frame")).put("targetId", "different-target");
        invalid.add(badFrame);
        ObjectNode missingIdentity = source.deepCopy();
        ((ObjectNode) missingIdentity.path("data")).remove("observationId");
        invalid.add(missingIdentity);
        ObjectNode badControl = source.deepCopy();
        ((ObjectNode) badControl.path("data").path("elements").get(0)).remove("id");
        invalid.add(badControl);
        for (ObjectNode candidate : invalid) {
            String raw = candidate.toString();
            assertEquals(raw, responseData(ComputerUseEvidenceProjection.project(
                    exchange("desktop_session_observe", raw), LIMIT)));
        }
        assertEquals(source.toString(), responseData(ComputerUseEvidenceProjection.project(
                exchange("other_business_tool", source.toString()), LIMIT)));
        assertEquals("invalid JSON", responseData(ComputerUseEvidenceProjection.project(
                exchange("desktop_session_observe", "invalid JSON"), LIMIT)));
    }

    @Test
    void irreducibleIdentityFailsPreciselyInsteadOfCuttingIdentifiers() throws Exception {
        ObjectNode source = observation(false);
        String sessionId = "exact-session-" + "x".repeat(20_000);
        ((ObjectNode) source.path("data")).put("sessionId", sessionId);
        String raw = source.toString();

        var failure = assertThrows(LocalContextBudgetExceededException.class,
                () -> ComputerUseEvidenceProjection.project(exchange("desktop_session_observe", raw), LIMIT));

        assertEquals("computer_use_observation_identity", failure.budgetKind());
        assertEquals(LIMIT, failure.budgetCharacters());
        assertTrue(failure.requiredCharacters() > LIMIT);
        assertEquals(List.of("core.tool.completed:run:observation", "desktop.frame:run:observation"),
                failure.requiredEvidenceRefs());
        assertEquals(sessionId, source.path("data").path("sessionId").asText());
        assertEquals(raw, source.toString());
    }

    @Test
    void escapedUnicodeTextFitsActualWireBudgetWithoutSplittingSurrogatePairs() throws Exception {
        ObjectNode source = observation(false);
        ((ObjectNode) source.path("data").path("content"))
                .put("summary", "😀\u0001\n".repeat(8_000)).put("visibleText", "😀\u0002\n".repeat(8_000));

        List<Message> projected = ComputerUseEvidenceProjection.project(
                exchange("desktop_session_observe", source.toString()), 4_000);
        JsonNode data = payload(projected).path("data");

        assertTrue(data.toString().length() <= 4_000);
        assertTrue(responseData(projected).length() <= 4_512);
        for (String field : List.of("summary", "visibleText")) {
            String text = data.path("content").path(field).asText();
            assertTrue(text.isEmpty() || !Character.isHighSurrogate(text.charAt(text.length() - 1)));
        }
    }

    @Test
    void conditionProofDisplayExcerptsCannotExhaustTheMandatoryObservationBudget() throws Exception {
        ObjectNode source = observation(true);
        ObjectNode originalData = (ObjectNode) source.path("data");
        ArrayNode conditions = originalData.putArray("conditionEvidence");
        for (int index = 0; index < 12; index++) {
            String excerpt = ("visible entry " + index + " " + "\"\\\u0001".repeat(180)).substring(0, 500);
            ObjectNode condition = conditions.addObject()
                    .put("criterionId", ("condition-" + index).repeat(12).substring(0, 120))
                    .put("subject", ("Logical condition " + index + " ").repeat(20).substring(0, 240))
                    .put("region", "main-content").put("confidence", 0.95);
            condition.putObject("content").put("label", excerpt).put("role", "list")
                    .put("x", 4).put("y", 25 + index).put("width", 90).put("height", 40)
                    .put("confidence", 0.95);
        }
        assertTrue(conditions.toString().length() > LIMIT,
                "Condition excerpts alone must exceed the wire budget after optional content is removed");
        String raw = source.toString();
        var exchange = exchange("desktop_session_observe", raw);

        List<Message> projected = ComputerUseEvidenceProjection.project(exchange, LIMIT);
        JsonNode envelope = payload(projected);
        JsonNode data = envelope.path("data");

        assertTrue(data.toString().length() <= LIMIT);
        assertTrue(responseData(projected).length() <= LIMIT + 512);
        assertEquals(source.path("evidenceRefs"), envelope.path("evidenceRefs"));
        for (String field : List.of("sessionId", "targetId", "applicationId", "observationId",
                "windowGeneration", "contentRevision", "capturedAtMillis", "frame", "activeView")) {
            assertEquals(originalData.path(field), data.path(field), field);
        }
        assertTrue(data.path("projectionTruncated").asBoolean());
        assertTrue(data.path("conditionEvidenceProjectionTruncated").asBoolean());
        assertEquals(12, data.path("conditionEvidenceOmittedExcerptCount").asInt());
        assertEquals(12, data.path("conditionEvidence").size());
        for (int index = 0; index < 12; index++) {
            JsonNode original = conditions.get(index);
            JsonNode visible = data.path("conditionEvidence").get(index);
            for (String field : List.of("criterionId", "subject", "region", "confidence"))
                assertEquals(original.path(field), visible.path(field), field);
            for (String field : List.of("role", "x", "y", "width", "height", "confidence"))
                assertEquals(original.path("content").path(field), visible.path("content").path(field), field);
            assertFalse(visible.path("content").has("label"));
            assertTrue(visible.path("content").path("excerptOmitted").asBoolean());
            assertEquals(500, original.path("content").path("label").asText().length());
        }
        assertEquals(raw, source.toString(), "Durable tool data must keep the full literal proof");
        assertEquals(raw, responseData(exchange));
        assertEquals(StepMessageCodec.messages(projected), StepMessageCodec.messages(
                ComputerUseEvidenceProjection.project(projected, LIMIT)));
    }

    @Test
    void compactConditionEvidenceRetainsFullExcerptsWhenItFits() throws Exception {
        ObjectNode source = observation(false);
        ObjectNode condition = ((ObjectNode) source.path("data")).putArray("conditionEvidence").addObject()
                .put("criterionId", "observe_contacts").put("subject", "Contacts List")
                .put("region", "main-content").put("confidence", 0.95);
        condition.putObject("content").put("label", "好友 18/41 朋友 6/19").put("role", "list")
                .put("x", 4).put("y", 25).put("width", 90).put("height", 40).put("confidence", 0.95);

        JsonNode projected = payload(ComputerUseEvidenceProjection.project(
                exchange("desktop_session_observe", source.toString()), LIMIT)).path("data");

        assertEquals(source.path("data").path("conditionEvidence"), projected.path("conditionEvidence"));
        assertFalse(projected.path("conditionEvidenceProjectionTruncated").asBoolean());
    }

    private static ObjectNode observation(boolean rich) {
        ObjectNode envelope = JSON.createObjectNode().put("status", "SUCCEEDED").put("errorCode", "");
        envelope.putArray("evidenceRefs").add("core.tool.completed:run:observation")
                .add("desktop.frame:run:observation");
        ObjectNode data = envelope.putObject("data").put("schemaVersion", 1)
                .put("protocol", "computer-use").put("kind", "desktop.observation")
                .put("sessionId", "session-2731cb89").put("targetId", "target-native-1823")
                .put("application", "Requested desktop application")
                .put("applicationId", "org.example.requested-application")
                .put("observationId", "observation-82ed3e51")
                .put("windowGeneration", 12).put("contentRevision", 37)
                .put("capturedAtMillis", 1_750_000_000_000L)
                .put("coordinateSpace", "WINDOW_FRAME_PIXELS");
        data.putObject("frame").put("targetId", "target-native-1823")
                .put("windowGeneration", 12).put("contentRevision", 37)
                .put("capturedAtMillis", 1_750_000_000_000L).put("width", 1440).put("height", 900)
                .put("coordinateSpace", "WINDOW_FRAME_PIXELS");
        data.putObject("content").put("trust", "UNTRUSTED_SCREEN_CONTENT")
                .put("summary", rich ? "Navigation and contact details. ".repeat(150) : "Contact list")
                .put("visibleText", rich ? "Name, group, account details. ".repeat(600) : "One visible contact")
                .put("truncated", false);
        data.putObject("activeView").put("label", "contact view").put("confidence", 0.94);
        data.put("elementCount", rich ? 72 : 1).put("elementsTruncated", rich);
        ArrayNode elements = data.putArray("elements");
        ArrayNode visual = data.putArray("visualTargets");
        for (int index = 0; index < (rich ? 40 : 1); index++) {
            elements.addObject().put("id", "native-element-" + index + "-identity")
                    .put("role", "button").put("label", rich ? ("control " + index + " ").repeat(40) : "Contacts")
                    .put("x", index * 10).put("y", 20).put("width", 70).put("height", 20)
                    .put("actions", 3).put("pressable", index % 2 == 1);
            visual.addObject().put("id", "visual-region-" + index + "-identity")
                    .put("role", "navigationitem").put("label", rich ? ("region " + index + " ").repeat(40) : "Contacts")
                    .put("x", index * 11).put("y", 25).put("width", 65).put("height", 18)
                    .put("confidence", 0.9).put("pressable", index % 2 == 1);
        }
        envelope.put("displayMessage", data.toString());
        return envelope;
    }

    private static List<Message> exchange(String toolName, String data) {
        AssistantMessage call = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("call-observe", "function", toolName,
                        "{\"sessionId\":\"session-2731cb89\"}"))).build();
        ToolResponseMessage response = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-observe", toolName, data))).build();
        return List.of(call, response);
    }

    private static String responseData(List<Message> messages) {
        return ((ToolResponseMessage) messages.getLast()).getResponses().getFirst().responseData();
    }

    private static ObjectNode payload(List<Message> messages) throws Exception {
        return (ObjectNode) JSON.readTree(responseData(messages));
    }

    private static void assertWholeEntriesInOriginalOrder(JsonNode original, JsonNode projected) {
        int previous = -1;
        for (JsonNode entry : projected) {
            int index = -1;
            for (int candidate = 0; candidate < original.size(); candidate++) {
                if (original.get(candidate).path("id").equals(entry.path("id"))) {
                    index = candidate;
                    break;
                }
            }
            assertTrue(index > previous);
            assertEquals(original.get(index), entry);
            previous = index;
        }
    }
}
