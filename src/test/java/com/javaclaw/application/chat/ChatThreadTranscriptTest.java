package com.javaclaw.application.chat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.ThreadEvent;
import com.javaclaw.framework.api.TurnId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatThreadTranscriptTest {
    private static final RunScope BRANCH = new RunScope("workspace", "user", "branch");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test void rebuildsOnlyOwnCutoffEventsAndDoesNotTreatInjectedHistoryAsNewUserMessages() {
        ObjectNode created = JSON.objectNode();
        var request = created.putObject("request");
        request.putObject("source").put("kind", "chat");
        var inputs = request.putArray("inputs");
        inputs.addObject().put("type", "core.message").putObject("data")
                .put("role", "assistant").put("text", "injected context only");
        inputs.addObject().put("type", "core.text").putObject("data").put("text", "branch goal");
        ObjectNode completed = wrapped();
        ((ObjectNode) completed.path("event").path("payload")).putObject("output").put("text", "cutoff answer");
        var transcript = ChatThreadTranscript.project(List.of(
                event(1, "turn/created", "turn", created), event(2, "turn/completed", "turn", completed)));
        assertEquals(List.of("branch goal", "cutoff answer"), transcript.stream()
                .map(ChatHistoryApplicationService.MessageSnapshot::content).toList());
        assertEquals(ChatHistoryApplicationService.MessageRole.USER, transcript.getFirst().role());
    }

    @Test void recoversInputContinuationAndClarificationButHidesMaintenanceTurns() {
        ObjectNode waiting = wrapped();
        var clarification = ((ObjectNode) waiting.path("event").path("payload"))
                .putObject("output").put("kind", "clarify_request").putObject("payload");
        clarification.put("reason", "scope missing").put("question", "which version?");
        ObjectNode resumed = wrapped();
        ((ObjectNode) resumed.path("event").path("payload")).put("commandType", "input")
                .putObject("command").put("text", "version three");
        ObjectNode maintenance = JSON.objectNode();
        maintenance.putObject("request").putObject("source").put("kind", "maintenance");
        ObjectNode maintenanceResult = wrapped();
        ((ObjectNode) maintenanceResult.path("event").path("payload")).putObject("output").put("text", "private extraction");
        var messages = ChatThreadTranscript.project(List.of(event(1, "turn/waiting_input", "t", waiting),
                event(2, "turn/resumed", "t", resumed), event(3, "turn/created", "maintenance", maintenance),
                event(4, "turn/completed", "maintenance", maintenanceResult)));
        assertEquals(2, messages.size());
        assertTrue(messages.getFirst().content().contains("which version?"));
        assertEquals("version three", messages.getLast().content());
    }

    @Test void fillsMissingDurableTailOnceWithoutLosingAdoptionOrCompletingUnacceptedDrafts() {
        var user = display(ChatHistoryApplicationService.MessageRole.USER, "question", false);
        var accepted = display(ChatHistoryApplicationService.MessageRole.ASSISTANT, "first answer", true);
        var followUp = display(ChatHistoryApplicationService.MessageRole.USER, "follow-up", false);
        var durableAnswer = display(ChatHistoryApplicationService.MessageRole.ASSISTANT, "crash-safe result", false);
        var draft = display(ChatHistoryApplicationService.MessageRole.USER, "unsent after error", false);
        var journal = List.of(user, display(accepted.role(), accepted.content(), false), followUp, durableAnswer);
        var recovered = ChatThreadTranscript.recoverTail(List.of(user, accepted, followUp, draft), journal);
        assertEquals(List.of(user, accepted, followUp, durableAnswer, draft), recovered);
        assertTrue(recovered.get(1).adopted());
        assertNull(recovered.getLast().deliveryStatus());
        assertEquals(recovered, ChatThreadTranscript.recoverTail(recovered, journal));
    }

    @Test void preservesAttachmentReferencesAndTerminalStatusWithoutFabricatingMissingResponses() {
        ObjectNode created = JSON.objectNode();
        var request = created.putObject("request");
        request.putObject("source").put("kind", "chat");
        var inputs = request.putArray("inputs");
        inputs.addObject().put("type", "core.text").putObject("data").put("text", "first line");
        inputs.addObject().put("type", "core.text").putObject("data").put("text", "second line");
        inputs.addObject().put("type", "core.image").putObject("data").put("uri", "file:/project/photo.png");
        inputs.addObject().put("type", "core.image").putObject("data").put("uri", "https://example.test/photo.png");
        inputs.addObject().put("type", "core.image").putObject("data").put("uri", "file:invalid path");
        ObjectNode failed = wrapped();
        ((ObjectNode) failed.path("event").path("payload")).put("message", "provider unavailable");
        ObjectNode cancelled = wrapped();
        ((ObjectNode) cancelled.path("event").path("payload")).putObject("output").put("value", "saved partial");
        var records = ChatThreadTranscript.project(List.of(event(1, "turn/created", "a", created),
                event(2, "turn/failed", "a", failed), event(3, "turn/cancelled", "b", cancelled),
                event(4, "turn/completed", "c", wrapped()), event(5, "turn/paused", "d", wrapped())));
        assertEquals(3, records.size());
        assertEquals("first line\nsecond line", records.getFirst().content());
        assertEquals(List.of("/project/photo.png"), records.getFirst().imagePaths());
        assertEquals(ChatHistoryApplicationService.MessageRole.SYSTEM, records.get(1).role());
        assertEquals(ChatHistoryApplicationService.DeliveryStatus.FAILED, records.get(1).deliveryStatus());
        assertEquals(ChatHistoryApplicationService.DeliveryStatus.CANCELLED, records.getLast().deliveryStatus());
    }

    private static ChatHistoryApplicationService.MessageSnapshot display(
            ChatHistoryApplicationService.MessageRole role, String text, boolean adopted) {
        return new ChatHistoryApplicationService.MessageSnapshot(role, text,
                java.time.LocalDateTime.of(2026, 9, 21, 10, 0), List.of(), adopted, null, null);
    }

    private static ObjectNode wrapped() {
        ObjectNode value = JSON.objectNode();
        value.putObject("event").putObject("payload");
        return value;
    }
    private static ThreadEvent event(long sequence, String type, String turn, ObjectNode payload) {
        return new ThreadEvent(BRANCH, sequence, Instant.ofEpochSecond(sequence), type, new TurnId(turn), payload);
    }
}
