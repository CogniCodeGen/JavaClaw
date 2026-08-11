package com.javaclaw.config;

import java.util.Objects;
import java.util.Properties;

import static com.javaclaw.config.AgentConfigSchema.*;

/** Typed access to context compression, recall, distillation and memory-graph settings. */
final class AgentMemorySettings {

    private final Properties properties;

    AgentMemorySettings(Properties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    long maxToken() { return longValue(properties, KEY_MEMORY_MAX_TOKEN, DEFAULT_MEMORY_MAX_TOKEN); }
    void maxToken(long value) { put(KEY_MEMORY_MAX_TOKEN, value); }
    int messageThreshold() { return integer(properties, KEY_MEMORY_MSG_THRESHOLD, DEFAULT_MEMORY_MSG_THRESHOLD); }
    void messageThreshold(int value) { put(KEY_MEMORY_MSG_THRESHOLD, value); }
    int lastMessagesToKeep() { return integer(properties, KEY_MEMORY_LAST_KEEP, DEFAULT_MEMORY_LAST_KEEP); }
    void lastMessagesToKeep(int value) { put(KEY_MEMORY_LAST_KEEP, value); }
    double tokenRatio() { return decimal(properties, KEY_MEMORY_TOKEN_RATIO, DEFAULT_MEMORY_TOKEN_RATIO); }
    void tokenRatio(double value) { put(KEY_MEMORY_TOKEN_RATIO, value); }

    int recallTopK() { return integer(properties, "memory.recall.topk", 8); }
    int recallEpisodes() { return integer(properties, "memory.recall.episodes", 3); }
    double recallThreshold() { return decimal(properties, "memory.recall.threshold", 0.3); }
    int recallCharacterBudget() { return integer(properties, "memory.recall.maxchars", 8000); }
    double distillationDeduplicationThreshold() {
        return decimal(properties, "memory.distill.dedup.threshold", 0.9);
    }
    int distillationMinimumInput() { return integer(properties, "memory.distill.min.input", 10); }
    boolean supersedeEnabled() { return bool("memory.supersede.enabled", true); }
    double supersedeThreshold() { return decimal(properties, "memory.supersede.threshold", 0.55); }
    int supersedeMaximumCandidates() {
        return integer(properties, "memory.supersede.max.candidates", 5);
    }
    boolean graphEntitiesEnabled() { return bool("memory.graph.entities.enabled", true); }
    double graphSemanticThreshold() {
        return decimal(properties, "memory.graph.semantic.threshold", 0.78);
    }
    int graphMaximumNodes() { return integer(properties, "memory.graph.max.nodes", 300); }
    boolean habitReviewEnabled() { return bool("memory.habit.review.enabled", true); }
    int habitReviewMinimumEpisodes() {
        return integer(properties, "memory.habit.review.min.episodes", 20);
    }
    int habitReviewIntervalHours() {
        return integer(properties, "memory.habit.review.interval.hours", 24);
    }
    int habitReviewMaximumEpisodes() {
        return integer(properties, "memory.habit.review.max.episodes", 60);
    }

    private boolean bool(String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)));
    }

    private void put(String key, Object value) {
        properties.setProperty(key, String.valueOf(value));
    }
}
