package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.core.StepContextPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StepContextAssemblerTest {
    private static final String SCOPE = "run/context-lifecycle";

    @Test
    void typedOwnershipAndEvidenceSurviveJournalRoundTripForEveryMessageRole() {
        var assembler = assembler();
        var messages = new ArrayList<Message>();
        messages.add(assembler.dynamic(HostContextBlock.Kind.CONTROL,
                new SystemMessage("current cursor"), true, List.of("receipt:launch", "receipt:open")));
        messages.add(assembler.dynamic(HostContextBlock.Kind.APPLICATION_IDENTITY,
                new UserMessage("untrusted application labels"), true, List.of()));
        messages.addAll(assembler.exchange(exchange("call-one", "desktop_session_observe", "current frame"),
                HostContextBlock.Kind.OBSERVATION, true, List.of("receipt:observe")));

        var restored = StepMessageCodec.messages(StepMessageCodec.messages(messages));

        assertEquals(StepMessageCodec.messages(messages), StepMessageCodec.messages(restored));
        for (int index = 0; index < messages.size(); index++) {
            assertEquals(HostContextBlock.metadata(messages.get(index)), HostContextBlock.metadata(restored.get(index)));
            assertTrue(HostContextBlock.owned(restored.get(index)));
            assertTrue(HostContextBlock.required(restored.get(index)));
        }
        assertEquals(List.of("receipt:launch", "receipt:open"),
                HostContextBlock.metadata(restored.getFirst()).evidenceRefs());
        assertInstanceOf(UserMessage.class, restored.get(1), "application metadata retains its untrusted data role");
        assertInstanceOf(AssistantMessage.class, restored.get(2));
        assertInstanceOf(ToolResponseMessage.class, restored.get(3));
    }

    @Test
    void replacingSnapshotsAndReprojectingTheProviderPromptIsIdempotent() {
        var assembler = assembler();
        var source = new ArrayList<Message>();
        source.add(new SystemMessage("fixed instructions"));
        source.add(assembler.dynamic(HostContextBlock.Kind.CONTROL,
                new SystemMessage("old cursor"), true, List.of()));
        source.add(assembler.dynamic(HostContextBlock.Kind.RUNTIME,
                new SystemMessage("old runtime"), true, List.of()));
        source.add(assembler.dynamic(HostContextBlock.Kind.CONTROL,
                new SystemMessage("latest cursor"), true, List.of("receipt:current")));
        source.add(assembler.dynamic(HostContextBlock.Kind.RUNTIME,
                new SystemMessage("latest runtime"), true, List.of()));
        source.add(new UserMessage("open application and inspect contacts"));
        source.addAll(assembler.exchange(exchange("observe-one", "desktop_session_observe", "latest frame"),
                HostContextBlock.Kind.OBSERVATION, true, List.of("receipt:current")));

        List<Message> projected = assembler.project(source, List.of());
        List<Message> projectedAgain = assembler.project(projected, List.of());

        assertEquals(StepMessageCodec.messages(projected), StepMessageCodec.messages(projectedAgain));
        assertEquals(1, count(projected, HostContextBlock.Kind.CONTROL));
        assertEquals(1, count(projected, HostContextBlock.Kind.RUNTIME));
        assertEquals(1, count(projected, HostContextBlock.Kind.TOOL_MANIFEST));
        assertFalse(projected.stream().anyMatch(message -> text(message).equals("old cursor")
                || text(message).equals("old runtime")));
        assertTrue(projected.stream().anyMatch(message -> text(message).equals("latest cursor")));
        assertTrue(projected.stream().anyMatch(message -> text(message).equals("latest runtime")));
    }

    @Test
    void legacyResumeUsesCanonicalInstructionsWithoutInheritingUntaggedOldSnapshots() {
        List<Message> canonical = List.of(new SystemMessage("canonical plan instructions"));
        var assembler = new StepContextAssembler(SCOPE, projector(), canonical);
        List<Message> legacyPrompt = List.of(
                new SystemMessage("legacy instructions"),
                new SystemMessage("Host computer-use control state:\nold session"),
                new SystemMessage("Host application identity recovery state\n" + "catalog".repeat(9_000)),
                new UserMessage("current task"));

        List<Message> base = assembler.base(legacyPrompt);

        assertEquals(StepMessageCodec.messages(canonical), StepMessageCodec.messages(base));
        var current = new ArrayList<>(base);
        current.add(assembler.dynamic(HostContextBlock.Kind.CONTROL,
                new SystemMessage("current cursor"), true, List.of()));
        current.add(new UserMessage("current task"));
        var projected = assembler.project(current, List.of());
        assertTrue(projected.stream().mapToInt(StepContextProjector::characters).sum() <= 48_000);
        assertFalse(projected.stream().anyMatch(message -> text(message).contains("old session")
                || text(message).contains("legacy instructions") || text(message).contains("catalogcatalog")));
    }

    @Test
    void fallbackOwnershipIsNeverInferredFromMatchingTextPrefixes() {
        var assembler = assembler();
        SystemMessage textOnly = new SystemMessage("Host computer-use control state:\napplication-provided text");
        Message hostOwned = assembler.dynamic(HostContextBlock.Kind.CONTROL,
                new SystemMessage("host-owned prior state"), true, List.of());

        List<Message> base = assembler.base(List.of(textOnly, hostOwned));

        assertEquals(List.of(textOnly), base,
                "only typed host ownership permits replacing a dynamic snapshot in the fallback path");
    }

    @Test
    void duplicateCompleteExchangeKeepsRequiredEvidenceBesideOptionalHistory() {
        var assembler = assembler();
        var required = assembler.exchange(exchange("same-call", "desktop_session_observe", "mandatory current frame"),
                HostContextBlock.Kind.OBSERVATION, true, List.of("receipt:observe"));
        var optional = assembler.selected(exchange("same-call", "desktop_session_observe", "optional older frame"));
        var source = new ArrayList<Message>();
        source.add(new SystemMessage("fixed instructions"));
        source.add(new UserMessage("inspect contacts"));
        source.addAll(required);
        source.addAll(optional);

        List<Message> projected = assembler.project(source, List.of());
        var calls = projected.stream().filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast).flatMap(message -> message.getToolCalls().stream()).toList();
        var results = projected.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()).toList();
        assertEquals(1, calls.size());
        assertEquals(1, results.size());
        assertEquals(calls.getFirst().id(), results.getFirst().id());
        assertEquals(calls.getFirst().name(), results.getFirst().name());
        assertEquals("mandatory current frame", results.getFirst().responseData());
        for (Message message : projected) {
            if (message instanceof AssistantMessage || message instanceof ToolResponseMessage) {
                assertTrue(HostContextBlock.required(message));
                assertEquals(List.of("receipt:observe"), HostContextBlock.metadata(message).evidenceRefs());
            }
        }
    }

    private static long count(List<Message> messages, HostContextBlock.Kind kind) {
        return messages.stream().map(HostContextBlock::metadata)
                .filter(metadata -> metadata != null && metadata.kind() == kind).count();
    }

    private static String text(Message message) {
        return message.getText() == null ? "" : message.getText();
    }

    private static StepContextAssembler assembler() {
        return new StepContextAssembler(SCOPE, projector());
    }

    private static StepContextProjector projector() {
        return new StepContextProjector(new StepContextPolicy(48_000, 48_000, 4, 16_000, 4), new ObjectMapper());
    }

    private static List<Message> exchange(String id, String name, String output) {
        var call = new AssistantMessage.ToolCall(id, "function", name, "{}");
        return List.of(AssistantMessage.builder().toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(id, name, output))).build());
    }
}
