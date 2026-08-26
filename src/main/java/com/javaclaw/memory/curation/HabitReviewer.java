package com.javaclaw.memory.curation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskAttribution;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.prompt.MemoryPrompts;
import com.javaclaw.util.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cross-run habit review expressed as one bounded, structured ModelTaskGateway operation. */
public final class HabitReviewer {
    private static final Logger log = LoggerFactory.getLogger(HabitReviewer.class);
    private static final int EPISODE_SNIPPET_CHARS = 200;
    private static final double PROMOTION_CONFIDENCE = 0.82;

    private final ModelTaskGateway modelTasks;
    private final MemoryStore store;
    private final EmbeddingGateway embeddings;
    private final AgentConfig settings;
    private final AtomicBoolean reviewing = new AtomicBoolean();

    public HabitReviewer(
            ModelTaskGateway modelTasks,
            MemoryStore store,
            EmbeddingGateway embeddings,
            AgentConfig settings) {
        this.modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        this.store = Objects.requireNonNull(store, "store");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void maybeReviewNow(RunId ownerRunId) {
        if (ownerRunId == null || !settings.getMemoryHabitReviewEnabled()) return;
        if (!reviewing.compareAndSet(false, true)) return;
        try {
            reviewSync(ownerRunId, false);
        } catch (RuntimeException failure) {
            log.warn("习惯回顾失败（水位未推进）: {}", failure.getMessage());
        } finally {
            reviewing.set(false);
        }
    }

    public Mono<Void> maybeReview(RunId ownerRunId) {
        return Mono.fromRunnable(() -> maybeReviewNow(ownerRunId))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    public String reviewNow(RunId ownerRunId) {
        if (!settings.getMemoryHabitReviewEnabled()) {
            return "习惯回顾已关闭（memory.habit.review.enabled=false）";
        }
        if (ownerRunId == null) return "习惯回顾必须归属一个 Agent Run";
        if (!reviewing.compareAndSet(false, true)) return "习惯回顾正在进行中，已跳过本次触发";
        try {
            return reviewSync(ownerRunId, true);
        } finally {
            reviewing.set(false);
        }
    }

    private String reviewSync(RunId ownerRunId, boolean force) {
        long last = store.lastHabitReviewAt();
        long now = System.currentTimeMillis();
        if (!force && now - last < settings.getMemoryHabitReviewIntervalHours() * 3_600_000L) {
            return "未到回顾间隔，本次跳过";
        }
        List<Episode> episodes = store.episodesSince(
                last, settings.getMemoryHabitReviewMaxEpisodes());
        if (episodes.size() < settings.getMemoryHabitReviewMinEpisodes()) {
            return "自上次回顾以来仅 " + episodes.size() + " 轮情景，未达最小归纳量，跳过";
        }

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("instructions", MemoryPrompts.HABIT_REVIEW_PROMPT);
        input.put("episodeCount", episodes.size());
        input.put("digest", buildDigest(episodes));
        JsonNode output = modelTasks.execute(new ModelTaskRequest(
                        "memory.habit.review", ModelTier.LIGHT, input, habitSchema(episodes.size()),
                        ownerRunId, "memory", Duration.ofSeconds(45), 1,
                        () -> Thread.currentThread().isInterrupted(), false,
                        ModelTaskAttribution.BACKGROUND))
                .toCompletableFuture().join().output();

        double dedup = settings.getMemoryDistillDedupThreshold();
        List<CorrectionRecord> corrections = store.allCorrections();
        int added = 0;
        int merged = 0;
        int pending = 0;
        for (JsonNode candidate : output.path("habits")) {
            String text = candidate.path("text").asText("").strip();
            double confidence = candidate.path("confidence").asDouble(0);
            if (text.isEmpty() || SensitiveDataRedactor.containsLikelyCredential(text)) continue;
            if (!hasRepeatedEvidence(candidate.path("evidence"), episodes.size())
                    || confidence < PROMOTION_CONFIDENCE) continue;
            if (CorrectionGuard.findUnsafeMemoryClaim(text, corrections).isPresent()) continue;

            float[] vector = embeddings.embed(text, EmbeddingPurpose.BACKGROUND_INDEX);
            if (vector == null) {
                Fact fact = new Fact("习惯偏好", text, null);
                fact.sourceKind = "HABIT_REVIEW";
                store.addPendingFact(fact, "memory.habit");
                pending++;
                continue;
            }
            List<MemoryStore.Scored<Fact>> duplicate = store.searchFacts(vector, 1, dedup);
            if (!duplicate.isEmpty()) {
                Fact existing = duplicate.getFirst().entity();
                if (!existing.userEdited && !existing.userAsserted) {
                    store.mergeFact(existing, "memory.habit", text);
                    merged++;
                }
            } else {
                Fact fact = new Fact("习惯偏好", text, vector);
                fact.sourceKind = "HABIT_REVIEW";
                store.addFact(fact, "memory.habit");
                added++;
            }
        }
        String summary = "回顾 " + episodes.size() + " 轮：归纳新增 " + added
                + "、合并 " + merged + "、暂存 " + pending;
        store.markHabitReview(now, "memory.habit", summary);
        log.info("习惯回顾完成：{}", summary);
        return summary;
    }

    private static boolean hasRepeatedEvidence(JsonNode evidence, int episodeCount) {
        if (!evidence.isArray()) return false;
        HashSet<Integer> indexes = new HashSet<>();
        for (JsonNode value : evidence) {
            int index = value.asInt(0);
            if (index >= 1 && index <= episodeCount) indexes.add(index);
        }
        return indexes.size() >= 2;
    }

    private static String buildDigest(List<Episode> episodes) {
        StringBuilder result = new StringBuilder();
        int index = 1;
        for (Episode episode : episodes) {
            result.append('#').append(index++).append(" 用户：").append(snip(episode.userInput));
            if (episode.assistantReply != null && !episode.assistantReply.isBlank()) {
                result.append("｜助手：").append(snip(episode.assistantReply));
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static String snip(String value) {
        if (value == null) return "";
        String result = value.strip().replaceAll("\\s+", " ");
        return result.length() <= EPISODE_SNIPPET_CHARS ? result
                : result.substring(0, EPISODE_SNIPPET_CHARS) + "…";
    }

    private static ObjectNode habitSchema(int episodeCount) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("type", "object").put("additionalProperties", false);
        root.putArray("required").add("habits");
        ObjectNode habits = root.putObject("properties").putObject("habits");
        habits.put("type", "array").put("maxItems", 5);
        ObjectNode habit = JsonNodeFactory.instance.objectNode();
        habit.put("type", "object").put("additionalProperties", false);
        habit.putArray("required").add("text").add("evidence").add("confidence");
        ObjectNode properties = habit.putObject("properties");
        properties.putObject("text").put("type", "string");
        ObjectNode evidence = properties.putObject("evidence");
        evidence.put("type", "array").put("minItems", 2).put("uniqueItems", true);
        evidence.putObject("items").put("type", "integer")
                .put("minimum", 1).put("maximum", Math.max(1, episodeCount));
        properties.putObject("confidence").put("type", "number")
                .put("minimum", 0).put("maximum", 1);
        habits.set("items", habit);
        return root;
    }
}
