package com.javaclaw.framework.builtin.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.spi.AdvisorRuntimeContext;
import com.javaclaw.util.TokenEstimator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistent incremental conversation compaction. It only rewrites the model request;
 * durable chat messages and run inputs remain untouched.
 */
final class ContextCompactionAdvisor implements BaseAdvisor {
    static final int HISTORY_TOKEN_LIMIT = 8_000;
    static final int MESSAGE_LIMIT = 16;
    static final int SUMMARY_TOKEN_LIMIT = 1_200;
    static final int RECENT_TOKEN_LIMIT = 6_800;
    static final int PREFERRED_RECENT_TURNS = 6;
    static final int MIN_RECENT_TURNS = 2;
    static final int MAX_SUMMARY_CHUNKS_PER_TURN = 3;
    private static final String SUMMARY_PREFIX =
            "Conversation summary (structured, persisted):\n";

    private final int order;
    private final AdvisorRuntimeContext runtime;
    private final ConversationContextSummaryStore store;
    private final ConversationHistorySource historySource;
    private final Clock clock;
    private final ConversationSummaryGenerator summaryGenerator;

    ContextCompactionAdvisor(
            int order,
            AdvisorRuntimeContext runtime,
            ConversationContextSummaryStore store,
            Clock clock) {
        this(order, runtime, store,
                (workspace, session, lastMessageId) -> Optional.empty(), clock);
    }

    ContextCompactionAdvisor(
            int order,
            AdvisorRuntimeContext runtime,
            ConversationContextSummaryStore store,
            ConversationHistorySource historySource,
            Clock clock) {
        this.order = order;
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.store = Objects.requireNonNull(store, "store");
        this.historySource = Objects.requireNonNull(historySource, "historySource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.summaryGenerator = new ConversationSummaryGenerator(runtime);
    }

    @Override public int getOrder() { return order; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String sourceKind = runtime.request().source().kind();
        if (!sourceKind.equals("chat") && !sourceKind.equals("plan")) return request;

        List<Message> all = request.prompt().getInstructions();
        int currentUserIndex = lastUserIndex(all);
        if (currentUserIndex < 0) return request;

        List<Integer> historyPositions = new ArrayList<>();
        List<Message> visibleHistory = new ArrayList<>();
        for (int index = 0; index < currentUserIndex; index++) {
            Message message = all.get(index);
            if (message.getMessageType() == MessageType.USER
                    || message.getMessageType() == MessageType.ASSISTANT) {
                historyPositions.add(index);
                visibleHistory.add(message);
            }
        }
        if (visibleHistory.isEmpty()) return request;

        int visibleTailStart = chooseVisibleTailStart(visibleHistory);
        long visibleTokens = estimateVisible(visibleHistory);
        String workspace = runtime.request().scope().workspaceId();
        String session = runtime.request().scope().sessionId();

        Optional<List<ConversationHistoryMessage>> durable;
        try {
            durable = lastStableMessageId(visibleHistory)
                    .flatMap(lastId -> historySource.loadThrough(workspace, session, lastId));
        } catch (RuntimeException failure) {
            return degraded(request, all, historyPositions, visibleTailStart,
                    visibleHistory.size(), visibleTokens, "history-source-failed", failure);
        }
        if (durable.isEmpty() || !alignedSuffix(durable.get(), visibleHistory)) {
            return degraded(request, all, historyPositions, visibleTailStart,
                    visibleHistory.size(), visibleTokens, "history-source-unavailable", null);
        }

        List<ConversationHistoryMessage> source = durable.get();
        long sourceTokens = estimateSource(source);
        if (source.size() <= MESSAGE_LIMIT && sourceTokens <= HISTORY_TOKEN_LIMIT) {
            store.delete(workspace, session);
            return request;
        }

        int sourceTailStart = chooseSourceTailStart(source);
        if (sourceTailStart <= 0 || sourceTailStart >= source.size()) {
            return degraded(request, all, historyPositions, visibleTailStart,
                    source.size(), sourceTokens, "recent-history-exceeds-budget", null);
        }
        int mappedVisibleTailStart = indexOfVisibleId(
                visibleHistory, source.get(sourceTailStart).messageId());
        if (mappedVisibleTailStart < 0) {
            return degraded(request, all, historyPositions, visibleTailStart,
                    source.size(), sourceTokens, "history-source-misaligned", null);
        }

        try {
            Optional<ConversationContextSummary> saved = store.find(workspace, session);
            SummaryProgress progress = initialProgress(saved, source, sourceTailStart);
            int cursor = progress.cursor();
            String summary = progress.summary();
            int chunks = 0;

            while (cursor < sourceTailStart && chunks < MAX_SUMMARY_CHUNKS_PER_TURN) {
                int end = nextChunkEnd(source, cursor, sourceTailStart);
                ConversationSummaryGenerator.GeneratedSummary generated =
                        summaryGenerator.summarize(summary, source.subList(cursor, end));
                summary = generated.rendered();
                cursor = end;
                chunks++;
                JsonNode structured = generated.structured();
                store.save(new ConversationContextSummary(
                        workspace,
                        session,
                        cursor,
                        source.get(cursor - 1).messageId(),
                        hash(source.subList(0, cursor)),
                        structured == null ? "{}" : structured.toString(),
                        summary,
                        Instant.now(clock)));
            }

            if (cursor < sourceTailStart) {
                return degradedWithProgress(request, all, historyPositions,
                        mappedVisibleTailStart, source, sourceTokens, cursor,
                        "summary-backlog", null);
            }

            boolean reused = chunks == 0;
            ObjectNode event = JsonNodeFactory.instance.objectNode();
            event.put("sourceMessages", source.size());
            event.put("summarizedMessages", sourceTailStart);
            event.put("cursorMessageId", source.get(sourceTailStart - 1).messageId());
            event.put("sourceTokensEstimated", sourceTokens);
            event.put("summaryTokensEstimated", TokenEstimator.estimate(summary));
            event.put("recentTokensEstimated", Math.min(RECENT_TOKEN_LIMIT,
                    estimateSource(source.subList(sourceTailStart, source.size()))));
            event.put("retainedTurns", countSourceTurns(
                    source.subList(sourceTailStart, source.size())));
            event.put("reused", reused);
            event.put("incremental", progress.incremental());
            event.put("rebuilt", progress.rebuilt());
            event.put("summaryChunks", chunks);
            event.put("source", "persistent");
            runtime.events().emit("core.context.compaction.completed", 2,
                    "framework.builtin", event);
            return request.mutate().prompt(rewritePrompt(request.prompt(), all,
                    historyPositions, mappedVisibleTailStart, summary, false)).build();
        } catch (RuntimeException failure) {
            return degraded(request, all, historyPositions, mappedVisibleTailStart,
                    source.size(), sourceTokens, "hard-truncation", failure);
        }
    }

    private SummaryProgress initialProgress(
            Optional<ConversationContextSummary> saved,
            List<ConversationHistoryMessage> source,
            int target) {
        if (saved.isEmpty()) return new SummaryProgress(0, "", false, true);
        ConversationContextSummary existing = saved.get();
        if (existing.cursorMessageId() == null || existing.cursorMessageId().isBlank()) {
            return new SummaryProgress(0, "", false, true);
        }
        int cursorIndex = indexOfSourceId(source, existing.cursorMessageId());
        int cursor = cursorIndex + 1;
        if (cursorIndex < 0 || cursor > target
                || !existing.sourceHash().equals(hash(source.subList(0, cursor)))) {
            return new SummaryProgress(0, "", false, true);
        }
        return new SummaryProgress(cursor, existing.renderedSummary(),
                cursor < target, false);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private ChatClientRequest degraded(
            ChatClientRequest request,
            List<Message> all,
            List<Integer> historyPositions,
            int tailStart,
            int sourceMessages,
            long sourceTokens,
            String reason,
            RuntimeException failure) {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("sourceMessages", sourceMessages);
        event.put("sourceTokensEstimated", sourceTokens);
        event.put("retainedMessages", historyPositions.size()
                - Math.min(tailStart, historyPositions.size()));
        event.put("degraded", reason);
        if (failure != null) event.put("errorType", failure.getClass().getName());
        runtime.events().emit("core.context.compaction.degraded", 2,
                "framework.builtin", event);
        return request.mutate().prompt(rewritePrompt(request.prompt(), all,
                historyPositions, tailStart, null, true)).build();
    }

    private ChatClientRequest degradedWithProgress(
            ChatClientRequest request,
            List<Message> all,
            List<Integer> historyPositions,
            int tailStart,
            List<ConversationHistoryMessage> source,
            long sourceTokens,
            int cursor,
            String reason,
            RuntimeException failure) {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("sourceMessages", source.size());
        event.put("sourceTokensEstimated", sourceTokens);
        event.put("summarizedMessages", cursor);
        if (cursor > 0) event.put("cursorMessageId", source.get(cursor - 1).messageId());
        event.put("retainedMessages", historyPositions.size()
                - Math.min(tailStart, historyPositions.size()));
        event.put("degraded", reason);
        if (failure != null) event.put("errorType", failure.getClass().getName());
        runtime.events().emit("core.context.compaction.degraded", 2,
                "framework.builtin", event);
        return request.mutate().prompt(rewritePrompt(request.prompt(), all,
                historyPositions, tailStart, null, true)).build();
    }

    private static Prompt rewritePrompt(
            Prompt original,
            List<Message> all,
            List<Integer> historyPositions,
            int tailStart,
            String summary,
            boolean degraded) {
        int boundedTailStart = Math.min(tailStart, historyPositions.size());
        java.util.Set<Integer> removed = new java.util.HashSet<>(
                historyPositions.subList(0, boundedTailStart));
        Map<Integer, Message> boundedRecent = boundRecentMessages(
                all, historyPositions.subList(boundedTailStart, historyPositions.size()));
        int insertion;
        if (!removed.isEmpty()) {
            insertion = removed.stream().mapToInt(Integer::intValue).min().orElse(-1);
        } else if (summary != null && !summary.isBlank() && !historyPositions.isEmpty()) {
            insertion = historyPositions.getFirst();
        } else {
            insertion = -1;
        }
        List<Message> rewritten = new ArrayList<>();
        for (int index = 0; index < all.size(); index++) {
            if (index == insertion && summary != null && !summary.isBlank()) {
                rewritten.add(new SystemMessage(SUMMARY_PREFIX + summary));
            }
            Message originalMessage = all.get(index);
            if (isPersistedSummary(originalMessage)) continue;
            if (!removed.contains(index)) {
                rewritten.add(boundedRecent.getOrDefault(index, originalMessage));
            }
        }
        if (degraded && insertion >= 0) {
            // No synthetic summary is inserted: only the bounded recent source survives.
        }
        return new Prompt(rewritten, original.getOptions());
    }

    private static int chooseVisibleTailStart(List<Message> history) {
        int cursor = history.size();
        int start = cursor;
        int tokens = 0;
        int retainedTurns = 0;
        while (cursor > 0 && retainedTurns < PREFERRED_RECENT_TURNS) {
            int userIndex = previousVisibleUserIndex(history, cursor);
            if (userIndex < 0) break;
            int candidate = (int) Math.min(Integer.MAX_VALUE,
                    estimateVisible(history.subList(userIndex, cursor)));
            if (retainedTurns >= MIN_RECENT_TURNS
                    && tokens + candidate > RECENT_TOKEN_LIMIT) break;
            start = userIndex;
            cursor = userIndex;
            tokens += candidate;
            retainedTurns++;
        }
        return start;
    }

    private static int chooseSourceTailStart(List<ConversationHistoryMessage> history) {
        int cursor = history.size();
        int start = cursor;
        int tokens = 0;
        int retainedTurns = 0;
        while (cursor > 0 && retainedTurns < PREFERRED_RECENT_TURNS) {
            int userIndex = previousSourceUserIndex(history, cursor);
            if (userIndex < 0) break;
            int candidate = (int) Math.min(Integer.MAX_VALUE,
                    estimateSource(history.subList(userIndex, cursor)));
            if (retainedTurns >= MIN_RECENT_TURNS
                    && tokens + candidate > RECENT_TOKEN_LIMIT) break;
            start = userIndex;
            cursor = userIndex;
            tokens += candidate;
            retainedTurns++;
        }
        return start;
    }

    private static int previousVisibleUserIndex(List<Message> history, int exclusiveEnd) {
        for (int index = exclusiveEnd - 1; index >= 0; index--) {
            if (history.get(index).getMessageType() == MessageType.USER) return index;
        }
        return -1;
    }

    private static int previousSourceUserIndex(
            List<ConversationHistoryMessage> history, int exclusiveEnd) {
        for (int index = exclusiveEnd - 1; index >= 0; index--) {
            if (history.get(index).role() == ConversationHistoryMessage.Role.USER) return index;
        }
        return -1;
    }

    private static int countSourceTurns(List<ConversationHistoryMessage> history) {
        return (int) history.stream()
                .filter(message -> message.role() == ConversationHistoryMessage.Role.USER).count();
    }

    private static int nextChunkEnd(
            List<ConversationHistoryMessage> source, int start, int target) {
        int tokens = 0;
        int end = start;
        while (end < target) {
            int candidate = Math.min(ConversationSummaryGenerator.SOURCE_TOKEN_LIMIT,
                    TokenEstimator.estimate(source.get(end).content()));
            if (end > start
                    && tokens + candidate > ConversationSummaryGenerator.SOURCE_TOKEN_LIMIT) break;
            tokens += candidate;
            end++;
        }
        return Math.max(start + 1, end);
    }

    /**
     * Keeps the selected turn structure even when the newest two turns alone exceed 6.8K.
     * Newer messages receive the remaining budget first; every older message keeps a small
     * textual prefix so the model still sees both sides of the retained turns.
     */
    private static Map<Integer, Message> boundRecentMessages(
            List<Message> all, List<Integer> retainedPositions) {
        int total = retainedPositions.stream()
                .map(all::get).mapToInt(message -> TokenEstimator.estimate(safeText(message))).sum();
        if (total <= RECENT_TOKEN_LIMIT) return Map.of();

        Map<Integer, Message> replacements = new java.util.HashMap<>();
        int remaining = RECENT_TOKEN_LIMIT;
        final int minimumPerOlderMessage = 8;
        for (int offset = retainedPositions.size() - 1; offset >= 0; offset--) {
            int position = retainedPositions.get(offset);
            Message original = all.get(position);
            int reserveForOlder = offset * minimumPerOlderMessage;
            int allowed = Math.max(0, remaining - reserveForOlder);
            String text = TokenEstimator.truncateToTokens(safeText(original), allowed);
            replacements.put(position, copyWithText(original, text));
            remaining = Math.max(0, remaining - TokenEstimator.estimate(text));
        }
        return replacements;
    }

    private static Message copyWithText(Message original, String text) {
        if (original instanceof UserMessage user) {
            UserMessage.Builder builder = UserMessage.builder().text(text)
                    .metadata(original.getMetadata());
            if (!user.getMedia().isEmpty()) builder.media(user.getMedia());
            return builder.build();
        }
        if (original instanceof AssistantMessage assistant) {
            AssistantMessage.Builder<?> builder = AssistantMessage.builder().content(text)
                    .properties(original.getMetadata())
                    .toolCalls(assistant.getToolCalls());
            if (!assistant.getMedia().isEmpty()) builder.media(assistant.getMedia());
            return builder.build();
        }
        return original;
    }

    private static int lastUserIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).getMessageType() == MessageType.USER) return index;
        }
        return -1;
    }

    private static Optional<String> lastStableMessageId(List<Message> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            String id = stableMessageId(history.get(index));
            if (id != null) return Optional.of(id);
        }
        return Optional.empty();
    }

    private static String stableMessageId(Message message) {
        Object value = message.getMetadata().get(InputBlock.CONVERSATION_MESSAGE_ID_METADATA);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static boolean alignedSuffix(
            List<ConversationHistoryMessage> source, List<Message> visible) {
        if (source.isEmpty() || visible.isEmpty()) return false;
        String firstVisibleId = stableMessageId(visible.getFirst());
        if (firstVisibleId == null) return false;
        int start = indexOfSourceId(source, firstVisibleId);
        if (start < 0 || source.size() - start != visible.size()) return false;
        for (int offset = 0; offset < visible.size(); offset++) {
            Message visibleMessage = visible.get(offset);
            ConversationHistoryMessage durableMessage = source.get(start + offset);
            String id = stableMessageId(visibleMessage);
            if (id == null || !durableMessage.messageId().equals(id)
                    || !sameRole(durableMessage, visibleMessage)) {
                return false;
            }
            String visibleText = safeText(visibleMessage);
            boolean contentAligned = durableMessage.content().equals(visibleText)
                    // ChatConversationHistory may character-trim only the oldest visible message.
                    || offset == 0 && durableMessage.content().endsWith(visibleText);
            if (!contentAligned) return false;
        }
        return true;
    }

    private static boolean sameRole(
            ConversationHistoryMessage durable, Message visible) {
        return durable.role() == ConversationHistoryMessage.Role.USER
                ? visible.getMessageType() == MessageType.USER
                : visible.getMessageType() == MessageType.ASSISTANT;
    }

    private static int indexOfVisibleId(List<Message> visible, String messageId) {
        for (int index = 0; index < visible.size(); index++) {
            if (messageId.equals(stableMessageId(visible.get(index)))) return index;
        }
        return -1;
    }

    private static int indexOfSourceId(
            List<ConversationHistoryMessage> source, String messageId) {
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index).messageId().equals(messageId)) return index;
        }
        return -1;
    }

    private static long estimateVisible(List<Message> messages) {
        return messages.stream().mapToLong(
                message -> TokenEstimator.estimate(safeText(message))).sum();
    }

    private static long estimateSource(List<ConversationHistoryMessage> messages) {
        return messages.stream().mapToLong(
                message -> TokenEstimator.estimate(message.content())).sum();
    }

    private static String safeText(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }

    private static String hash(List<ConversationHistoryMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ConversationHistoryMessage message : messages) {
                digest.update(message.messageId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(message.role().name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(message.content().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0xff);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean isPersistedSummary(Message message) {
        return message.getMessageType() == MessageType.SYSTEM
                && safeText(message).startsWith(SUMMARY_PREFIX);
    }

    private record SummaryProgress(
            int cursor, String summary, boolean incremental, boolean rebuilt) { }
}
