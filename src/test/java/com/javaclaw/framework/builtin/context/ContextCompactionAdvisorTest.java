package com.javaclaw.framework.builtin.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import com.javaclaw.util.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactionAdvisorTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void firstAndIncrementalCompactionUseStableCursorWithinBudget() {
        MemoryStore store = new MemoryStore();
        FakeModels models = new FakeModels(false);
        List<String> events = new ArrayList<>();
        List<ConversationHistoryMessage> firstSource = source(10, null);

        ChatClientRequest first = advisor(store, models, events, "run-1", "chat", firstSource)
                .before(request(firstSource, 0), null);
        assertEquals(1, models.requests.size());
        assertSummaryTask(models.requests.getFirst());
        assertNotNull(store.value);
        assertEquals(8, store.value.summarizedMessages());
        assertEquals("assistant-3", store.value.cursorMessageId());
        assertTrue(hasSummary(first));
        assertTrue(visibleHistoryTokens(first.prompt().getInstructions()) <= 8_000);
        assertTrue(events.contains("core.context.compaction.completed"));

        List<ConversationHistoryMessage> secondSource = source(11, null);
        ChatClientRequest second = advisor(store, models, events, "run-2", "chat", secondSource)
                .before(request(secondSource, 0), null);
        assertEquals(2, models.requests.size());
        assertTrue(models.requests.getLast().input().has("previousSummary"));
        assertEquals(10, store.value.summarizedMessages());
        assertEquals("assistant-4", store.value.cursorMessageId());
        assertTrue(visibleHistoryTokens(second.prompt().getInstructions()) <= 8_000);
    }

    @Test
    void planSourceUsesTheSameCompactionPolicy() {
        MemoryStore store = new MemoryStore();
        FakeModels models = new FakeModels(false);
        List<ConversationHistoryMessage> source = source(10, null);

        ChatClientRequest compacted = advisor(
                store, models, new ArrayList<>(), "plan-run", "plan", source)
                .before(request(source, 0), null);

        assertEquals(1, models.requests.size());
        assertTrue(hasSummary(compacted));
        assertTrue(visibleHistoryTokens(compacted.prompt().getInstructions()) <= 8_000);
    }

    @Test
    void editedOrDeletedPrefixInvalidatesCursorAndFailureHardTruncates() {
        MemoryStore store = new MemoryStore();
        FakeModels models = new FakeModels(false);
        List<ConversationHistoryMessage> initial = source(10, null);
        advisor(store, models, new ArrayList<>(), "run-a", "chat", initial)
                .before(request(initial, 0), null);

        List<ConversationHistoryMessage> edited = source(11, "edited-old-message");
        advisor(store, models, new ArrayList<>(), "run-b", "chat", edited)
                .before(request(edited, 0), null);
        assertFalse(models.requests.getLast().input().has("previousSummary"));

        List<ConversationHistoryMessage> deleted = edited.subList(2, edited.size());
        advisor(store, models, new ArrayList<>(), "run-c", "chat", deleted)
                .before(request(deleted, 0), null);
        assertFalse(models.requests.getLast().input().has("previousSummary"));

        FakeModels failing = new FakeModels(true);
        List<String> events = new ArrayList<>();
        ChatClientRequest degraded = advisor(
                new MemoryStore(), failing, events, "run-d", "chat", initial)
                .before(request(initial, 0), null);
        assertFalse(hasSummary(degraded));
        assertEquals(12, conversationHistory(degraded.prompt().getInstructions()).size());
        assertTrue(events.contains("core.context.compaction.degraded"));
    }

    @Test
    void advancingTwoHundredMessageWindowReusesPriorSummary() {
        MemoryStore store = new MemoryStore();
        FakeModels models = new FakeModels(false);
        List<ConversationHistoryMessage> firstSource = source(110, null);
        advisor(store, models, new ArrayList<>(), "run-window-a", "chat", firstSource)
                .before(request(firstSource, 20), null);
        assertEquals(1, models.requests.size());
        assertEquals(208, store.value.summarizedMessages());

        List<ConversationHistoryMessage> secondSource = source(111, null);
        advisor(store, models, new ArrayList<>(), "run-window-b", "chat", secondSource)
                .before(request(secondSource, 22), null);

        assertEquals(2, models.requests.size());
        assertTrue(models.requests.getLast().input().has("previousSummary"));
        assertEquals(210, store.value.summarizedMessages());
    }

    @Test
    void checkpointsAtMostThreeSummaryChunksAndResumesNextTurn() {
        String large = "chunk ".repeat(3_000);
        List<ConversationHistoryMessage> source = source(10, null).stream()
                .map(message -> new ConversationHistoryMessage(
                        message.messageId(), message.role(), large + message.content()))
                .toList();
        MemoryStore store = new MemoryStore();
        FakeModels models = new FakeModels(false);
        List<String> events = new ArrayList<>();

        ChatClientRequest first = advisor(store, models, events, "chunk-a", "chat", source)
                .before(request(source, 0), null);
        assertEquals(3, models.requests.size());
        assertEquals(3, store.value.summarizedMessages());
        assertFalse(hasSummary(first));
        assertTrue(events.contains("core.context.compaction.degraded"));

        advisor(store, models, events, "chunk-b", "chat", source)
                .before(request(source, 0), null);
        assertEquals(6, store.value.summarizedMessages());
        assertTrue(models.requests.get(3).input().has("previousSummary"));
    }

    @Test
    void keepsTwoOversizedRecentTurnsInsideHistoryBudget() {
        List<ConversationHistoryMessage> source = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            source.add(history("user-" + index, ConversationHistoryMessage.Role.USER,
                    "old-user-" + index));
            source.add(history("assistant-" + index, ConversationHistoryMessage.Role.ASSISTANT,
                    "old-assistant-" + index));
        }
        String huge = "长内容".repeat(5_000);
        for (int index = 7; index < 9; index++) {
            source.add(history("user-" + index, ConversationHistoryMessage.Role.USER,
                    "recent-user-" + index + huge));
            source.add(history("assistant-" + index, ConversationHistoryMessage.Role.ASSISTANT,
                    "recent-assistant-" + index + huge));
        }

        ChatClientRequest compacted = advisor(
                new MemoryStore(), new FakeModels(false), new ArrayList<>(),
                "run-large-turns", "chat", source)
                .before(request(source, 0), null);
        List<Message> recent = conversationHistory(compacted.prompt().getInstructions());
        assertEquals(4, recent.size());
        assertEquals(2, recent.stream().filter(UserMessage.class::isInstance).count());
        assertTrue(visibleHistoryTokens(compacted.prompt().getInstructions()) <= 8_000);
    }

    @Test
    void hardTruncationPreservesSupplementaryUnicodeBoundaries() {
        List<ConversationHistoryMessage> source = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            source.add(history("user-" + index, ConversationHistoryMessage.Role.USER,
                    "old-user-" + index));
            source.add(history("assistant-" + index, ConversationHistoryMessage.Role.ASSISTANT,
                    "old-assistant-" + index));
        }
        String huge = "😀🚀".repeat(4_000);
        for (int index = 7; index < 9; index++) {
            source.add(history("user-" + index, ConversationHistoryMessage.Role.USER,
                    "recent-user-" + index + huge));
            source.add(history("assistant-" + index,
                    ConversationHistoryMessage.Role.ASSISTANT,
                    "recent-assistant-" + index + huge));
        }

        ChatClientRequest compacted = advisor(
                new MemoryStore(), new FakeModels(false), new ArrayList<>(),
                "run-emoji-turns", "chat", source)
                .before(request(source, 0), null);

        assertTrue(visibleHistoryTokens(compacted.prompt().getInstructions()) <= 8_000);
        assertTrue(conversationHistory(compacted.prompt().getInstructions()).stream()
                .map(Message::getText).noneMatch(ContextCompactionAdvisorTest::hasUnpairedSurrogate));
    }

    @Test
    void requestMismatchKeepsExistingCheckpointAndFallsBackWithoutSummarizing() {
        List<ConversationHistoryMessage> durable = source(10, null);
        List<ConversationHistoryMessage> mismatched = new ArrayList<>(durable);
        mismatched.set(17, history("assistant-8",
                ConversationHistoryMessage.Role.ASSISTANT, "unsaved edited reply"));
        MemoryStore store = new MemoryStore();
        ConversationContextSummary checkpoint = new ConversationContextSummary(
                "workspace", "session", 4, "assistant-1", "old-hash", "{}",
                "existing summary", CLOCK.instant());
        store.value = checkpoint;
        FakeModels models = new FakeModels(false);
        List<String> events = new ArrayList<>();

        ChatClientRequest degraded = advisor(
                store, models, events, "run-mismatch", "chat", durable)
                .before(request(mismatched, 0), null);

        assertSame(checkpoint, store.value);
        assertTrue(models.requests.isEmpty());
        assertFalse(hasSummary(degraded));
        assertTrue(events.contains("core.context.compaction.degraded"));
        assertTrue(visibleHistoryTokens(degraded.prompt().getInstructions()) <= 8_000);
    }

    private static void assertSummaryTask(ModelTaskRequest request) {
        assertEquals(ModelTier.LIGHT, request.tier());
        assertEquals(java.time.Duration.ofSeconds(20), request.timeout());
        assertEquals(0, request.maxRetries());
        assertFalse(request.cacheAllowed());
    }

    private static ContextCompactionAdvisor advisor(
            ConversationContextSummaryStore store,
            ModelTaskGateway models,
            List<String> events,
            String runId,
            String sourceKind,
            List<ConversationHistoryMessage> source) {
        RunId id = new RunId(runId);
        RunRequest run = RunRequest.builder()
                .agent(AgentDefinitionRef.latest("test.agent"))
                .profile(RunProfileRef.latest(sourceKind.equals("plan") ? "plan" : "chat"))
                .source(new InvocationSource(sourceKind, "desktop"))
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text("current"))
                .permissionCeiling(PermissionSet.NONE)
                .budget(new RunBudget(java.time.Duration.ofMinutes(1), 100_000, 10_000,
                        0, BigDecimal.TEN)).build();
        ExtensionStateView state = new ExtensionStateView() {
            @Override public RunId runId() { return id; }
            @Override public Optional<JsonNode> get(String extensionId, String key) {
                return Optional.empty();
            }
        };
        AdvisorRuntimeContext runtime = new AdvisorRuntimeContext(
                id, run, state, models, () -> false,
                (type, version, producer, payload) -> events.add(type));
        ConversationHistorySource historySource = (workspace, session, lastMessageId) -> {
            for (int index = 0; index < source.size(); index++) {
                if (source.get(index).messageId().equals(lastMessageId)) {
                    return Optional.of(List.copyOf(source.subList(0, index + 1)));
                }
            }
            return Optional.empty();
        };
        return new ContextCompactionAdvisor(50, runtime, store, historySource, CLOCK);
    }

    private static ChatClientRequest request(
            List<ConversationHistoryMessage> source, int visibleStart) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("system"));
        for (ConversationHistoryMessage message : source.subList(visibleStart, source.size())) {
            Map<String, Object> metadata = Map.of(
                    InputBlock.CONVERSATION_MESSAGE_ID_METADATA, message.messageId());
            if (message.role() == ConversationHistoryMessage.Role.USER) {
                messages.add(UserMessage.builder().text(message.content())
                        .metadata(metadata).build());
            } else {
                messages.add(AssistantMessage.builder().content(message.content())
                        .properties(metadata).build());
            }
        }
        messages.add(new UserMessage("current question"));
        return new ChatClientRequest(new Prompt(messages), Map.of());
    }

    private static List<ConversationHistoryMessage> source(int turns, String editedFirst) {
        List<ConversationHistoryMessage> result = new ArrayList<>();
        for (int index = 0; index < turns; index++) {
            result.add(history("user-" + index, ConversationHistoryMessage.Role.USER,
                    index == 0 && editedFirst != null ? editedFirst : "user-" + index));
            result.add(history("assistant-" + index, ConversationHistoryMessage.Role.ASSISTANT,
                    "assistant-" + index));
        }
        return List.copyOf(result);
    }

    private static ConversationHistoryMessage history(
            String id, ConversationHistoryMessage.Role role, String content) {
        return new ConversationHistoryMessage(id, role, content);
    }

    private static boolean hasSummary(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
                .anyMatch(message -> message instanceof SystemMessage
                        && message.getText().contains("Conversation summary"));
    }

    private static int visibleHistoryTokens(List<Message> messages) {
        int current = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                current = index;
                break;
            }
        }
        int result = 0;
        for (int index = 0; index < current; index++) {
            Message message = messages.get(index);
            if (!message.getText().equals("system")) {
                result += TokenEstimator.estimate(message.getText());
            }
        }
        return result;
    }

    private static List<Message> conversationHistory(List<Message> messages) {
        int current = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                current = index;
                break;
            }
        }
        List<Message> result = new ArrayList<>();
        for (int index = 0; index < current; index++) {
            if (messages.get(index) instanceof UserMessage
                    || messages.get(index) instanceof AssistantMessage) {
                result.add(messages.get(index));
            }
        }
        return result;
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) return true;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static final class MemoryStore implements ConversationContextSummaryStore {
        private ConversationContextSummary value;
        @Override public Optional<ConversationContextSummary> find(
                String workspace, String session) {
            return Optional.ofNullable(value);
        }
        @Override public void save(ConversationContextSummary summary) { value = summary; }
        @Override public void delete(String workspace, String session) { value = null; }
    }

    private static final class FakeModels implements ModelTaskGateway {
        private final boolean fail;
        private final List<ModelTaskRequest> requests = new ArrayList<>();
        private FakeModels(boolean fail) { this.fail = fail; }

        @Override
        public java.util.concurrent.CompletionStage<ModelTaskResult> execute(
                ModelTaskRequest request) {
            requests.add(request);
            if (fail) return CompletableFuture.failedFuture(new IllegalStateException("offline"));
            ObjectNode output = JsonNodeFactory.instance.objectNode();
            output.putArray("goals").add("finish the task");
            output.putArray("facts").add("known fact");
            output.putArray("constraints").add("keep token budget");
            output.putArray("decisions").add("use summaries");
            output.putArray("openQuestions").add("none");
            return CompletableFuture.completedFuture(new ModelTaskResult(
                    output, "light", 100, 20, false, Map.of()));
        }
    }
}
