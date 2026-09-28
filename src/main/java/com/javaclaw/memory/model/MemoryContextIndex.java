package com.javaclaw.memory.model;

import java.util.Objects;

/** Bounded search metadata and a content version for deferred memory evidence. */
public final class MemoryContextIndex {
    private static final int FACT_SEARCH_CHARS = 768;
    private static final int KNOWLEDGE_SEARCH_CHARS = 768;
    private static final int EPISODE_INPUT_SEARCH_CHARS = 384;
    private static final int EPISODE_REPLY_SEARCH_CHARS = 384;
    private static final int SUMMARY_CHARS = 180;

    private MemoryContextIndex() { }

    public static void refresh(Fact fact) {
        fact.deferredContextDigest = MemoryContextBody.digest(MemoryContextBody.fact(fact));
        fact.deferredSearchText = prefix(fact.text, FACT_SEARCH_CHARS);
        fact.deferredSummary = summary(fact.text);
    }

    public static void refresh(Episode episode) {
        episode.deferredContextDigest = MemoryContextBody.digest(MemoryContextBody.episode(episode));
        episode.deferredSearchText = prefix(episode.userInput, EPISODE_INPUT_SEARCH_CHARS)
                + "\n" + prefix(episode.assistantReply, EPISODE_REPLY_SEARCH_CHARS);
        episode.deferredSummary = summary(episode.userInput);
    }

    public static void refresh(KnowledgeChunk chunk) {
        chunk.deferredContextDigest = MemoryContextBody.digest(MemoryContextBody.knowledge(chunk));
        chunk.deferredSearchText = prefix(chunk.content, KNOWLEDGE_SEARCH_CHARS);
        chunk.deferredSummary = summary(chunk.content);
    }

    private static String summary(String source) {
        String value = prefix(source, SUMMARY_CHARS + 1);
        return value.length() <= SUMMARY_CHARS ? value : value.substring(0, SUMMARY_CHARS) + "…";
    }

    private static String prefix(String source, int maxChars) {
        String value = Objects.requireNonNullElse(source, "");
        if (value.length() > maxChars) value = value.substring(0, maxChars);
        return value.replaceAll("\\s+", " ").strip();
    }
}
