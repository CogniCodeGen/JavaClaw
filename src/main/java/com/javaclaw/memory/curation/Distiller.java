package com.javaclaw.memory.curation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskAttribution;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.EntityNode;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.prompt.MemoryPrompts;
import com.javaclaw.util.SensitiveDataRedactor;
import com.javaclaw.util.TextSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Incremental EclipseStore memory distillation implemented as an audited framework model task.
 * The class owns neither a model nor a runtime. Every model-assisted decision is charged to the
 * originating Run and all durable writes remain behind the workspace {@link MemoryStore}.
 */
public final class Distiller {
    private static final Logger log = LoggerFactory.getLogger(Distiller.class);
    private static final int MAX_REPLY_CHARS = 6_000;
    private static final double PROMOTION_CONFIDENCE = 0.82;

    private final ModelTaskGateway modelTasks;
    private final MemoryStore store;
    private final EmbeddingGateway embeddings;
    private final AgentConfig settings;

    public Distiller(
            ModelTaskGateway modelTasks,
            MemoryStore store,
            EmbeddingGateway embeddings,
            AgentConfig settings) {
        this.modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
        this.store = Objects.requireNonNull(store, "store");
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Runs one bounded distillation task. A missing owner Run deliberately disables model use. */
    public void distillNow(RunId ownerRunId, Episode episode) {
        if (!eligible(episode) || ownerRunId == null) return;
        try {
            distillSync(ownerRunId, episode);
        } catch (RuntimeException failure) {
            log.warn("记忆蒸馏失败（已隔离，不影响主 Run）: {}", failure.getMessage());
        }
    }

    public Mono<Void> distill(RunId ownerRunId, Episode episode) {
        return Mono.fromRunnable(() -> distillNow(ownerRunId, episode))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    private boolean eligible(Episode episode) {
        if (episode == null || episode.userInput == null
                || episode.assistantReply == null || episode.assistantReply.isBlank()) return false;
        return episode.userInput.trim().length() >= settings.getMemoryDistillMinInput()
                || com.javaclaw.memory.correction.CorrectionDetector
                .isExplicitCorrection(episode.userInput);
    }

    private void distillSync(RunId ownerRunId, Episode episode) {
        String reply = episode.assistantReply.length() > MAX_REPLY_CHARS
                ? episode.assistantReply.substring(0, MAX_REPLY_CHARS) + "...(截断)"
                : episode.assistantReply;
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("instructions", MemoryPrompts.DISTILL_PROMPT);
        input.put("userInput", episode.userInput.trim());
        input.put("assistantReply", reply.trim());
        if (episode.toolTraceJson != null && !episode.toolTraceJson.isBlank()) {
            input.put("verifiedToolTrace", episode.toolTraceJson);
        }
        input.put("entityInstructions", settings.getMemoryGraphEntitiesEnabled()
                ? MemoryPrompts.ENTITY_EXTRACT_PROMPT : "Entity extraction is disabled; return [].");

        JsonNode result = execute(ownerRunId, "memory.distillation.extract", input,
                extractionSchema(), Duration.ofSeconds(45), 1);
        List<EntityNode> entities = materializeEntities(result.path("entities"));
        List<CorrectionRecord> corrections = store.allCorrections();
        double dedup = settings.getMemoryDistillDedupThreshold();
        int added = 0;
        int merged = 0;
        int pending = 0;
        for (JsonNode candidate : result.path("facts")) {
            String text = candidate.path("text").asText("").strip();
            double confidence = candidate.path("confidence").asDouble(0);
            if (text.isBlank() || isNoneAnswer(text)) continue;
            if (SensitiveDataRedactor.containsLikelyCredential(text)) {
                log.warn("记忆蒸馏命中疑似凭据，已确定性跳过");
                continue;
            }
            if (CorrectionGuard.findUnsafeMemoryClaim(text, corrections).isPresent()) {
                log.warn("记忆蒸馏命中已废弃或未核验主张，已跳过: {}", trunc(text));
                continue;
            }

            boolean evidence = hasDeterministicEvidence(text, episode);
            float[] vector = embeddings.embed(text, EmbeddingPurpose.BACKGROUND_INDEX);
            if (vector == null || confidence < PROMOTION_CONFIDENCE || !evidence) {
                Fact review = new Fact(null, text, null);
                review.source = episode;
                review.about = matchEntities(text, entities);
                review.sourceKind = evidence ? "DISTILLED_LOW_CONFIDENCE" : "DISTILLED_UNVERIFIED";
                store.addPendingFact(review, "memory.distillation");
                pending++;
                continue;
            }

            List<MemoryStore.Scored<Fact>> duplicate = store.searchFacts(vector, 1, dedup);
            if (!duplicate.isEmpty() && !duplicate.getFirst().entity().userEdited
                    && !duplicate.getFirst().entity().userAsserted) {
                store.mergeFact(duplicate.getFirst().entity(), "memory.distillation", text);
                merged++;
                continue;
            }
            supersedeStale(ownerRunId, text, vector, dedup);
            Fact fact = new Fact(null, text, vector);
            fact.source = episode;
            fact.about = matchEntities(text, entities);
            fact.sourceKind = "DISTILLED";
            store.addFact(fact, "memory.distillation");
            added++;
        }
        log.info("记忆蒸馏完成：晋级 {}，合并 {}，待复核 {}，实体 {}",
                added, merged, pending, entities.size());
    }

    private List<EntityNode> materializeEntities(JsonNode values) {
        if (!settings.getMemoryGraphEntitiesEnabled() || !values.isArray()) return List.of();
        List<EntityNode> result = new ArrayList<>();
        for (JsonNode value : values) {
            String name = value.path("name").asText("").strip();
            String type = value.path("type").asText("topic").strip();
            if (name.length() < 2 || SensitiveDataRedactor.containsLikelyCredential(name)) continue;
            EntityNode entity = store.getOrCreateEntity(name, type, "memory.distillation");
            if (entity != null) result.add(entity);
        }
        return List.copyOf(result);
    }

    private void supersedeStale(RunId ownerRunId, String newFact, float[] vector, double dedup) {
        if (!settings.getMemorySupersedeEnabled()) return;
        double threshold = settings.getMemorySupersedeThreshold();
        if (threshold >= dedup) return;
        int maximum = settings.getMemorySupersedeMaxCandidates();
        List<MemoryStore.Scored<Fact>> candidates = store.searchFacts(
                        vector, maximum * 2 + 4, threshold).stream()
                .filter(value -> value.score() < dedup)
                .filter(value -> !value.entity().userEdited
                        && !value.entity().userAsserted && !value.entity().pinned)
                .limit(maximum).toList();
        if (candidates.isEmpty()) return;

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("instructions", MemoryPrompts.SUPERSEDE_JUDGE_PROMPT);
        input.put("newFact", newFact);
        ArrayNode existing = input.putArray("existingFacts");
        for (int index = 0; index < candidates.size(); index++) {
            ObjectNode value = existing.addObject();
            value.put("index", index + 1);
            value.put("text", candidates.get(index).entity().text);
        }
        try {
            JsonNode verdict = execute(ownerRunId, "memory.distillation.supersede", input,
                    indexSchema(candidates.size()), Duration.ofSeconds(20), 0);
            for (JsonNode index : verdict.path("indexes")) {
                int position = index.asInt(0) - 1;
                if (position >= 0 && position < candidates.size()) {
                    store.supersedeFact(candidates.get(position).entity(),
                            "memory.distillation", newFact);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("记忆取代检测失败（保守保留旧事实）: {}", failure.getMessage());
        }
    }

    private JsonNode execute(
            RunId ownerRunId,
            String purpose,
            JsonNode input,
            JsonNode schema,
            Duration timeout,
            int retries) {
        CancellationToken cancellation = () -> Thread.currentThread().isInterrupted();
        return modelTasks.execute(new ModelTaskRequest(
                        purpose, ModelTier.LIGHT, input, schema, ownerRunId,
                        "memory", timeout, retries, cancellation, false,
                        ModelTaskAttribution.BACKGROUND))
                .toCompletableFuture().join().output();
    }

    /** High-confidence promotion requires a deterministic lexical link to user/tool evidence. */
    static boolean hasDeterministicEvidence(String fact, Episode episode) {
        String evidence = (episode.userInput == null ? "" : episode.userInput) + " "
                + (episode.toolTraceJson == null ? "" : episode.toolTraceJson);
        String normalizedFact = CorrectionGuardText.normalize(fact);
        String normalizedEvidence = CorrectionGuardText.normalize(evidence);
        if (normalizedFact.isEmpty() || normalizedEvidence.isEmpty()) return false;
        if (normalizedEvidence.contains(normalizedFact) || normalizedFact.contains(normalizedEvidence)) {
            return true;
        }
        return TextSimilarity.bigramJaccard(normalizedFact, normalizedEvidence) >= 0.16;
    }

    static List<Integer> parseIndexes(String verdict, int size) {
        if (verdict == null) return new ArrayList<>();
        String value = verdict.strip();
        int colon = Math.max(value.lastIndexOf('：'), value.lastIndexOf(':'));
        if (colon >= 0 && colon < value.length() - 1) value = value.substring(colon + 1).strip();
        if (!value.matches("[0-9,，、\\s和及。．.!！~～]*")) return new ArrayList<>();
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        var matcher = java.util.regex.Pattern.compile("\\d{1,3}").matcher(value);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group());
            if (number >= 1 && number <= size) result.add(number - 1);
        }
        return new ArrayList<>(result);
    }

    static boolean isNoneAnswer(String text) {
        if (text == null) return true;
        String value = text.strip();
        if (value.startsWith("- ")) value = value.substring(2).strip();
        value = value.replaceAll("[\\s。．.,，!！~～]+$", "").strip();
        return value.isEmpty() || "无".equals(value) || "没有".equals(value)
                || value.equalsIgnoreCase("none") || "没有值得记录的事实".equals(value)
                || "没有值得记忆的事实".equals(value) || "无可抽取的实体".equals(value)
                || "没有可抽取的实体".equals(value);
    }

    private static List<EntityNode> matchEntities(String text, List<EntityNode> entities) {
        if (text == null || entities.isEmpty()) return new ArrayList<>();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        List<EntityNode> result = new ArrayList<>();
        for (EntityNode entity : entities) {
            if (entity.name != null && entity.name.length() >= 2
                    && lower.contains(entity.name.toLowerCase(java.util.Locale.ROOT))) {
                result.add(entity);
            }
        }
        return result;
    }

    private static String trunc(String value) {
        return value == null ? "" : value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }

    private static ObjectNode extractionSchema() {
        ObjectNode root = objectSchema();
        ArrayNode required = root.putArray("required");
        required.add("facts").add("entities");
        ObjectNode properties = root.putObject("properties");
        ObjectNode facts = properties.putObject("facts");
        facts.put("type", "array").put("maxItems", 8);
        ObjectNode fact = objectSchema();
        fact.putArray("required").add("text").add("confidence");
        fact.putObject("properties").putObject("text").put("type", "string");
        ObjectNode confidence = fact.path("properties").withObject("confidence");
        confidence.put("type", "number").put("minimum", 0).put("maximum", 1);
        facts.set("items", fact);
        ObjectNode entities = properties.putObject("entities");
        entities.put("type", "array").put("maxItems", 8);
        ObjectNode entity = objectSchema();
        entity.putArray("required").add("name").add("type");
        entity.putObject("properties").putObject("name").put("type", "string");
        entity.path("properties").withObject("type").put("type", "string");
        entities.set("items", entity);
        return root;
    }

    private static ObjectNode indexSchema(int maximum) {
        ObjectNode root = objectSchema();
        root.putArray("required").add("indexes");
        ObjectNode indexes = root.putObject("properties").putObject("indexes");
        indexes.put("type", "array").put("uniqueItems", true).put("maxItems", maximum);
        ObjectNode item = indexes.putObject("items");
        item.put("type", "integer").put("minimum", 1).put("maximum", maximum);
        return root;
    }

    private static ObjectNode objectSchema() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("type", "object").put("additionalProperties", false);
        return result;
    }

    /** Package-private normalization avoids expanding the correction API solely for scoring. */
    private static final class CorrectionGuardText {
        private static String normalize(String value) {
            if (value == null) return "";
            return value.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[\\s\\p{P}\\p{S}]+", "");
        }
    }
}
