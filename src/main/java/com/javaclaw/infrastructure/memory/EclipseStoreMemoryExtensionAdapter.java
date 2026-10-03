package com.javaclaw.infrastructure.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.builtin.memory.MemoryMutationGateway;
import com.javaclaw.framework.builtin.memory.MemoryRecallGateway;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.DeferredContextCandidate;
import com.javaclaw.framework.spi.DeferredContextSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.MemoryContextBody;
import com.javaclaw.memory.MemoryService;
import com.javaclaw.memory.MemoryGraphScope;
import com.javaclaw.memory.retrieval.RecallEligibility;
import com.javaclaw.memory.correction.CorrectionEngine;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.correction.CorrectionTurnContext;

import java.time.Duration;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Workspace adapter exposing the authoritative EclipseStore graph through framework SPI only. */
public final class EclipseStoreMemoryExtensionAdapter
        implements MemoryRecallGateway, MemoryMutationGateway, DeferredContextSource {
    private static final int RECENT_EPISODE_LIMIT = 3;
    private final MemoryService memory;
    private final ModelTaskGateway modelTasks;

    public EclipseStoreMemoryExtensionAdapter(MemoryService memory, ModelTaskGateway modelTasks) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.modelTasks = Objects.requireNonNull(modelTasks, "modelTasks");
    }

    @Override
    public String recall(RunRequest request, String query, int topK) {
        return memory.recall(MemoryGraphScope.thread(request.scope()), query, topK);
    }

    @Override public String id() { return "memory"; }
    @Override public String description() { return "Relevant facts and prior conversation evidence"; }
    @Override public String group() { return "memory"; }
    @Override public PermissionSet requiredPermissions() { return PermissionSet.of("tool.read"); }

    @Override
    public List<DeferredContextCandidate> search(RunRequest request, String query, int limit) {
        if (limit < 1) return List.of();
        int configured = com.javaclaw.framework.api.CapabilityRuntime.configuration(
                request, "memory.recall").path("topK").asInt(8);
        var scope = MemoryGraphScope.thread(request.scope());
        List<RankedCandidate> ranked = new ArrayList<>();
        List<RecentCandidate> recent = new ArrayList<>();
        addCandidates(ranked, recent, memory.inScope(scope), "thread", query, limit);
        addCandidates(ranked, recent, memory.inScope(scope.habits()), "habits", query, limit);
        ranked.sort(Comparator.comparingDouble(RankedCandidate::score).reversed()
                .thenComparing(RankedCandidate::id));
        int resultLimit = Math.min(Math.min(limit, configured), 50);
        if (resultLimit < 1) return List.of();
        recent.sort(Comparator.comparingLong(RecentCandidate::timestamp).reversed()
                .thenComparing(value -> value.candidate().id()));
        List<RankedCandidate> selected = new ArrayList<>();
        ranked.stream().filter(RankedCandidate::fact).findFirst().ifPresent(selected::add);
        for (RankedCandidate candidate : ranked) {
            if (selected.size() >= resultLimit) break;
            if (selected.stream().noneMatch(value -> value.id().equals(candidate.id()))) {
                selected.add(candidate);
            }
        }
        int recentAdded = 0;
        for (RecentCandidate fallback : recent) {
            if (selected.size() >= resultLimit || recentAdded >= RECENT_EPISODE_LIMIT) break;
            RankedCandidate candidate = fallback.candidate();
            if (selected.stream().noneMatch(value -> value.id().equals(candidate.id()))) {
                selected.add(candidate);
                recentAdded++;
            }
        }
        return selected.stream()
                .map(value -> new DeferredContextCandidate(value.id(), value.version(),
                        value.summary(), PermissionSet.of("tool.read"))).toList();
    }

    @Override
    public String fetch(RunRequest request, String candidateId, String version) {
        String[] parts = Objects.requireNonNull(candidateId, "candidateId").split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("unknown memory context ID");
        var scope = MemoryGraphScope.thread(request.scope());
        var selected = switch (parts[0]) {
            case "thread" -> memory.inScope(scope);
            case "habits" -> memory.inScope(scope.habits());
            default -> throw new IllegalArgumentException("unknown memory graph");
        };
        String body = switch (parts[1]) {
            case "fact" -> selected.facts().stream()
                    .filter(f -> parts[2].equals(f.id) && RecallEligibility.fact(f))
                    .findFirst().map(MemoryContextBody::fact).orElse(null);
            case "episode" -> selected.episodes().stream()
                    .filter(e -> parts[2].equals(e.id))
                    .findFirst().map(MemoryContextBody::episode).orElse(null);
            default -> throw new IllegalArgumentException("unknown memory context kind");
        };
        if (body == null) throw new IllegalStateException("memory context no longer exists: " + candidateId);
        if (!MemoryContextBody.digest(body).equals(version)) {
            throw new IllegalStateException("memory context version changed: " + candidateId);
        }
        return body;
    }

    private static void addCandidates(List<RankedCandidate> out, List<RecentCandidate> recent,
                                      MemoryService selected,
                                      String graph, String query, int limit) {
        var vectorScores = selected.deferredEvidenceScores(query, limit);
        for (Fact fact : selected.facts()) {
            if (fact.id == null || fact.deferredSummary == null || fact.deferredSummary.isBlank()
                    || !RecallEligibility.fact(fact)) continue;
            double score = Math.max(relevance(query, fact.deferredSearchText),
                    vectorScores.getOrDefault("fact:" + fact.id, 0.0))
                    + (fact.pinned ? .15 : 0);
            if (score <= 0) continue;
            if (fact.deferredContextDigest == null || fact.deferredContextDigest.isBlank()
                    || fact.deferredSearchText == null) {
                throw new IllegalStateException("memory fact has no indexed context metadata: " + fact.id);
            }
            out.add(new RankedCandidate(graph + ":fact:" + fact.id,
                    fact.deferredContextDigest, fact.deferredSummary, score, true));
        }
        boolean relevantEpisode = false;
        List<Episode> episodes = selected.episodes();
        for (Episode episode : episodes) {
            if (episode.id == null) continue;
            double score = Math.max(relevance(query, episode.deferredSearchText),
                    vectorScores.getOrDefault("episode:" + episode.id, 0.0));
            if (score <= 0) continue;
            relevantEpisode = true;
            if (episode.deferredContextDigest == null || episode.deferredContextDigest.isBlank()
                    || episode.deferredSearchText == null || episode.deferredSummary == null) {
                throw new IllegalStateException("memory episode has no indexed context metadata: " + episode.id);
            }
            out.add(new RankedCandidate(graph + ":episode:" + episode.id,
                    episode.deferredContextDigest, episode.deferredSummary, score, false));
        }
        // Offer bounded recent-turn summaries; load a body only when the planner selects it.
        if (!relevantEpisode || vectorScores.isEmpty()
                || (query != null && query.strip().length() < 8)) {
            episodes.stream().filter(episode -> episode.id != null
                            && episode.deferredContextDigest != null
                            && !episode.deferredContextDigest.isBlank()
                            && episode.deferredSearchText != null
                            && episode.deferredSummary != null)
                    .sorted(Comparator.comparingLong((Episode episode) -> episode.timestamp).reversed())
                    .limit(Math.min(RECENT_EPISODE_LIMIT, limit))
                    .forEach(episode -> recent.add(new RecentCandidate(
                            new RankedCandidate(graph + ":episode:" + episode.id,
                                    episode.deferredContextDigest, episode.deferredSummary, .05, false),
                            episode.timestamp)));
        }
    }

    private static double relevance(String query, String text) {
        if (query == null || query.isBlank()) return .01;
        String value = Objects.requireNonNullElse(text, "").toLowerCase(Locale.ROOT);
        String target = query.strip().toLowerCase(Locale.ROOT);
        if (value.contains(target)) return 1;
        String[] terms = target.split("\\s+");
        int hits = 0;
        for (String term : terms) if (!term.isBlank() && value.contains(term)) hits++;
        return hits == 0 ? 0 : hits / (double) terms.length;
    }

    private record RankedCandidate(String id, String version, String summary,
                                   double score, boolean fact) {}
    private record RecentCandidate(RankedCandidate candidate, long timestamp) {}

    @Override
    public JsonNode applyCorrection(
            RunId runId, RunRequest request, String userInput, String previousReply) {
        MemoryGraphScope scope = MemoryGraphScope.thread(request.scope());
        boolean userTurn = java.util.Set.of("chat", "plan").contains(request.source().kind())
                && !request.attributes().containsKey("memory.originThreadId");
        CorrectionTurnContext context = userTurn ? memory.prepareCorrectionTurn(scope, userInput, previousReply)
                : memory.inScope(scope).prepareCorrectionTurn(userInput, previousReply);
        if (!context.hasCorrections()) return null;
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("prompt", context.toPrompt());
        result.put("applied", context.newlyApplied() != null);
        if (context.newlyApplied() != null) result.put("correctionId", context.newlyApplied().id);
        return result;
    }

    @Override
    public JsonNode protectOutput(RunId runId, RunRequest request, JsonNode output) {
        String reply = output.path("text").asText("");
        if (reply.isBlank()) return output;
        String query = textInput(request);
        MemoryGraphScope scope = MemoryGraphScope.thread(request.scope());
        var relevant = CorrectionEngine.selectRelevant(memory.corrections(scope), query, 6);
        var violation = CorrectionGuard.findViolation(reply, relevant);
        if (violation.isEmpty()) return output;
        memory.inScope(scope).recordCorrectionGuardViolation(violation.get());

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("candidateReply", reply);
        input.put("userInput", query);
        input.put("requirement", "Rewrite the answer so it does not assert the rejected claim. "
                + "Preserve verified useful content, clearly state uncertainty, and return Simplified Chinese.");
        input.put("rejectedClaim", violation.get().wrongClaim());
        if (violation.get().correction().hasCorrectClaim()) {
            input.put("currentUserClaim", violation.get().correction().correctClaim);
        }
        try {
            JsonNode repaired = modelTasks.executeInline(new ModelTaskRequest(
                            "memory.correction.reply-repair", ModelTier.LIGHT, input, List.of(),
                            repairSchema(), runId, "memory", Duration.ofSeconds(30), 1,
                            () -> Thread.currentThread().isInterrupted(), false)).output();
            String text = repaired.path("text").asText("").strip();
            if (!text.isBlank() && CorrectionGuard.findViolation(text, relevant).isEmpty()) {
                return withText(output, text, true);
            }
        } catch (RuntimeException ignored) {
            // Deterministic safe fallback below; the primary Run must not fail because repair failed.
        }
        String fallback = "检测到候选回答可能重复了你已明确纠正的信息，因此本次未直接输出该结论。"
                + "请让我基于当前文件、工具结果或可靠来源重新核实后再回答。";
        return withText(output, fallback, true);
    }

    @Override
    public void distill(RunId runId, RunRequest request, JsonNode completedOutput) {
        // The transactional Thread outbox projects terminal turns. A lifecycle callback executes
        // before that commit and must never create memory for a turn that may still fail.
    }

    private static JsonNode withText(JsonNode output, String text, boolean corrected) {
        ObjectNode result = output instanceof ObjectNode object
                ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        result.put("text", text);
        result.put("correctionGuardApplied", corrected);
        return result;
    }

    private static String textInput(RunRequest request) {
        return request.inputs().stream().filter(block -> block.type().equals("core.text"))
                .map(block -> block.data().path("text").asText())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static ObjectNode repairSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("text");
        schema.putObject("properties").putObject("text").put("type", "string");
        return schema;
    }
}
