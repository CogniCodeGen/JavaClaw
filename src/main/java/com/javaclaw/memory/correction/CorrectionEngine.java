package com.javaclaw.memory.correction;

import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.util.TextSimilarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 显式纠错的同步写入引擎。
 *
 * <p>与轮后异步蒸馏不同，本引擎在本轮模型调用前完成持久化：先写纠错审计，再撤销/争议化
 * 旧事实，最后写入用户或项目范围内的替代事实。即使嵌入不可用，纠错记录本身仍可立即召回，
 * 不会出现“下一轮抢在蒸馏完成前又读到旧错误”的竞态。</p>
 */
public final class CorrectionEngine {

    private static final int TARGET_EXCERPT_CAP = 600;
    private static final int RELEVANT_LIMIT = 6;
    private static final long RECENT_WINDOW_MILLIS = 10 * 60 * 1000L;
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(?:那|那么|所以|这个|它|继续|重新|再|应该|正确|怎么|为什么|请按|按这个|就这样|明白)");

    private final MemoryStore store;
    private final Function<String, float[]> embedder;

    public CorrectionEngine(MemoryStore store, Function<String, float[]> embedder) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.embedder = java.util.Objects.requireNonNull(embedder, "embedder");
    }

    public CorrectionTurnContext prepareTurn(String userInput, String previousAssistantReply) {
        Optional<CorrectionDetector.Candidate> detected = CorrectionDetector.detect(userInput);
        CorrectionRecord applied = detected
                .map(candidate -> apply(candidate, previousAssistantReply))
                .orElse(null);
        List<CorrectionRecord> relevant = selectRelevant(
                store.allCorrections(), userInput, RELEVANT_LIMIT);
        if (applied != null && relevant.stream().noneMatch(r -> applied.id.equals(r.id))) {
            List<CorrectionRecord> withApplied = new ArrayList<>();
            withApplied.add(applied);
            withApplied.addAll(relevant);
            relevant = List.copyOf(withApplied);
        }
        return relevant.isEmpty() && applied == null
                ? CorrectionTurnContext.empty()
                : new CorrectionTurnContext(relevant, applied);
    }

    private CorrectionRecord apply(
            CorrectionDetector.Candidate candidate, String previousAssistantReply) {
        CorrectionRecord record = new CorrectionRecord();
        record.id = UUID.randomUUID().toString();
        record.type = candidate.type();
        record.scope = candidate.scope();
        record.status = candidate.isTrustedUserScope() && candidate.hasReplacement()
                ? CorrectionRecord.Status.ACTIVE
                : CorrectionRecord.Status.DISPUTED;
        record.wrongClaim = candidate.wrongClaim();
        record.correctClaim = candidate.correctClaim();
        record.sourceInput = candidate.sourceInput();
        record.targetExcerpt = cap(previousAssistantReply, TARGET_EXCERPT_CAP);

        // durable-first：纠错记录先落库，后续嵌入/事实更新失败也不会丢掉用户的明确否定。
        store.addCorrection(record, "user");
        revokeOlderCorrections(record);

        List<Fact> targets = findTargetFacts(record, previousAssistantReply);
        String firstTargetFactId = null;
        for (Fact target : targets) {
            // userEdited / userAsserted / pinned 只防模型蒸馏静默覆盖；这里的来源仍是用户本人，
            // 因而“后一次显式纠错”应当有权取代“前一次用户编辑/断言”。
            if (record.status == CorrectionRecord.Status.ACTIVE) {
                store.supersedeFact(target, "user-correction", record.correctClaim);
            } else {
                store.contestFact(target, "user-correction",
                        record.hasCorrectClaim() ? record.correctClaim : record.sourceInput);
            }
            if (firstTargetFactId == null) {
                firstTargetFactId = target.id;
            }
        }
        if (firstTargetFactId != null) {
            String targetId = firstTargetFactId;
            String inferredWrong = record.hasWrongClaim() ? null : targets.stream()
                    .filter(f -> targetId.equals(f.id))
                    .map(f -> f.text)
                    .findFirst()
                    .orElse(null);
            store.updateCorrection(record, x -> {
                x.targetFactId = targetId;
                if ((x.wrongClaim == null || x.wrongClaim.isBlank())
                        && inferredWrong != null && !inferredWrong.isBlank()) {
                    x.wrongClaim = inferredWrong;
                }
            }, "user-correction");
        }

        if (record.status == CorrectionRecord.Status.ACTIVE
                && record.type == CorrectionRecord.Type.FACT_REPLACEMENT
                && record.hasCorrectClaim()) {
            addExplicitFact(record);
        }
        return record;
    }

    private void addExplicitFact(CorrectionRecord record) {
        String text = memoryText(record);
        String normalized = CorrectionGuard.normalize(text);
        boolean exists = store.allFacts().stream()
                .anyMatch(f -> !f.superseded && !f.contested
                        && normalized.equals(CorrectionGuard.normalize(f.text)));
        if (exists) return;

        float[] vec = safeEmbed(text);
        Fact fact = new Fact(
                record.scope == CorrectionRecord.Scope.PROJECT ? "项目背景" : "用户画像",
                text, vec);
        fact.userAsserted = true;
        fact.sourceKind = "USER_EXPLICIT_CORRECTION";
        fact.correctionId = record.id;
        if (vec == null) store.addPendingFact(fact, "user-correction");
        else store.addFact(fact, "user-correction");
    }

    private List<Fact> findTargetFacts(CorrectionRecord record, String previousAssistantReply) {
        String wrong = CorrectionGuard.normalize(record.wrongClaim);
        String correct = CorrectionGuard.normalize(record.correctClaim);
        String previous = CorrectionGuard.normalize(previousAssistantReply);
        LinkedHashMap<String, Fact> matched = new LinkedHashMap<>();

        for (Fact fact : store.allFacts()) {
            if (fact.superseded || fact.contested || fact.text == null) continue;
            String factText = CorrectionGuard.normalize(fact.text);
            boolean overlapIsCurrentClaim = !correct.isEmpty()
                    && correct.contains(wrong) && factText.contains(correct);
            boolean explicitMatch = !overlapIsCurrentClaim && wrong.length() >= 2
                    && (CorrectionGuard.containsClaim(fact.text, record.wrongClaim)
                    || (factText.length() >= 4 && wrong.contains(factText)));
            boolean previousMatch = wrong.isEmpty() && factText.length() >= 4
                    && !previous.isEmpty() && previous.contains(factText);
            if (explicitMatch || previousMatch) {
                matched.put(fact.id, fact);
            }
        }

        if (!matched.isEmpty() || wrong.isEmpty()) {
            return List.copyOf(matched.values());
        }

        float[] vec = safeEmbed(record.wrongClaim);
        if (vec == null) return List.of();
        for (MemoryStore.Scored<Fact> scored : store.searchFacts(vec, 5, 0.45)) {
            Fact fact = scored.entity();
            double lexical = TextSimilarity.bigramJaccard(
                    CorrectionGuard.normalize(fact.text), wrong);
            if (lexical >= 0.30) matched.put(fact.id, fact);
        }
        return List.copyOf(matched.values());
    }

    private void revokeOlderCorrections(CorrectionRecord current) {
        String wrong = CorrectionGuard.normalize(current.wrongClaim);
        if (wrong.isEmpty()) return;
        for (CorrectionRecord old : store.allCorrections()) {
            if (old == current || old.id == null || old.id.equals(current.id)
                    || !old.isEffective() || !old.hasCorrectClaim()) {
                continue;
            }
            String previousCorrect = CorrectionGuard.normalize(old.correctClaim);
            if (!previousCorrect.isEmpty()
                    && (wrong.contains(previousCorrect) || previousCorrect.contains(wrong))) {
                store.updateCorrection(old,
                        x -> x.status = CorrectionRecord.Status.REVOKED,
                        "user-correction");
            }
        }
    }

    private float[] safeEmbed(String text) {
        try {
            return embedder.apply(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String memoryText(CorrectionRecord record) {
        String prefix = record.scope == CorrectionRecord.Scope.PROJECT
                ? "项目事实（用户明确纠正）：" : "用户明确纠正：";
        // Canonical Fact 只保存当前正确值；“旧值 → 新值”的否定关系完整保留在
        // CorrectionRecord。把旧值也写进活跃 Fact 会让关键词检索再次命中错误内容。
        return prefix + record.correctClaim;
    }

    /**
     * 选择与本轮相关的有效纠错。直接词命中优先；刚发生的纠错在十分钟窗口内保留，
     * 保障用户紧接着追问时不受嵌入异步状态影响。
     */
    public static List<CorrectionRecord> selectRelevant(
            List<CorrectionRecord> records, String query, int limit) {
        if (records == null || records.isEmpty() || limit <= 0) return List.of();
        String q = CorrectionGuard.normalize(query);
        List<ScoredCorrection> scored = new ArrayList<>();
        for (CorrectionRecord record : records) {
            if (record == null || !record.isEffective()) continue;
            String wrong = CorrectionGuard.normalize(record.wrongClaim);
            String correct = CorrectionGuard.normalize(record.correctClaim);
            String source = CorrectionGuard.normalize(record.sourceInput);
            double score = 0;
            if (!q.isEmpty()) {
                if (wrong.length() >= 2 && (q.contains(wrong) || wrong.contains(q))) score += 3;
                if (correct.length() >= 2 && (q.contains(correct) || correct.contains(q))) score += 3;
                score += Math.max(
                        TextSimilarity.bigramJaccard(q, wrong + correct),
                        TextSimilarity.bigramJaccard(q, source));
            }
            if (score > 0.12) scored.add(new ScoredCorrection(record, score));
        }
        // 只有确实像承接上一轮的短追问，才用近因兜底；不能让十分钟内所有无关问题
        // 都携带纠错上下文并触发非流式回复守卫。
        if (scored.isEmpty() && isLikelyFollowUp(q)) {
            long cutoff = System.currentTimeMillis() - RECENT_WINDOW_MILLIS;
            records.stream()
                    .filter(r -> r != null && r.isEffective() && r.createdAt >= cutoff)
                    .max(Comparator.comparingLong(r -> r.createdAt))
                    .ifPresent(r -> scored.add(new ScoredCorrection(r, 0.5)));
        }
        scored.sort(Comparator
                .comparingDouble(ScoredCorrection::score).reversed()
                .thenComparingLong(x -> -x.record().createdAt));
        return scored.stream().limit(limit).map(ScoredCorrection::record).toList();
    }

    private record ScoredCorrection(CorrectionRecord record, double score) {}

    private static boolean isLikelyFollowUp(String normalizedQuery) {
        return !normalizedQuery.isEmpty()
                && normalizedQuery.length() <= 36
                && FOLLOW_UP.matcher(normalizedQuery).find();
    }

    private static String cap(String text, int max) {
        if (text == null) return "";
        String s = text.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
