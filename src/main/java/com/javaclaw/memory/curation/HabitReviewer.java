package com.javaclaw.memory.curation;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.model.CorrectionRecord;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.prompt.MemoryPrompts;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 习惯回顾器 —— 定期批量回看近期情景，<b>跨轮归纳</b>重复出现的行为模式/偏好，落库为偏好事实。
 *
 * <p>补上逐轮蒸馏（{@link Distiller}）的先天盲区：逐轮只看单轮对话，永远归纳不出
 * "用户每次都要 markdown 格式"这类跨轮模式。回顾器一次看几十轮，模式直接可见，
 * 不依赖单轮判定敏感度与措辞归一化。</p>
 *
 * <p>触发挂在 {@code MemoryService.rememberTurn} 轮后链上（非独立定时器）：自上次回顾起
 * 新情景 ≥ {@code memory.habit.review.min.episodes} 且距上次回顾 ≥
 * {@code memory.habit.review.interval.hours} 小时才真正执行；回顾水位
 * （{@code MemoryStats.lastHabitReviewAt}）持久化在对象图内，重启不重复回顾。
 * 产出走与逐轮蒸馏相同的向量去重 upsert（命中既有事实 → mergeFact 强化，否则新增）。
 * 全程 boundedElastic 异步、失败静默不推进水位（下轮自然重试）、CAS 防并发重入。</p>
 *
 * @author JavaClaw
 */
public class HabitReviewer {

    private static final Logger log = LoggerFactory.getLogger(HabitReviewer.class);

    /** 单条情景摘要中用户输入/助手回复的截断长度（控制批量回顾的 token 规模） */
    private static final int EPISODE_SNIPPET_CHARS = 200;

    private final ChatModelBase lightModel;
    private final MemoryStore store;
    private final EmbeddingGateway gate;
    private final TokenTracker tokenTracker;
    private final GenerateOptions generateOptions;

    /** 防并发重入：相邻两轮几乎同时结束时只跑一次回顾 */
    private final AtomicBoolean reviewing = new AtomicBoolean(false);

    public HabitReviewer(ChatModelBase lightModel, MemoryStore store, EmbeddingGateway gate, TokenTracker tokenTracker) {
        this.lightModel = lightModel;
        this.store = store;
        this.gate = gate;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /** 在当前线程检查并按需回顾；失败静默，供受生命周期追踪的上层工作线程调用。 */
    public void maybeReviewNow() {
        if (!AgentConfig.getInstance().getMemoryHabitReviewEnabled()) return;
        if (!reviewing.compareAndSet(false, true)) return;
        try {
            reviewSync(false);
        } catch (RuntimeException e) {
            log.warn("习惯回顾失败（已静默忽略，水位不推进、下轮重试）: {}", e.getMessage());
        } finally {
            reviewing.set(false);
        }
    }

    /** 异步兼容入口；生命周期敏感调用应优先使用 {@link #maybeReviewNow()}。 */
    public Mono<Void> maybeReview() {
        return Mono.fromRunnable(this::maybeReviewNow)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * 手动强制回顾一次（绕过间隔水位闸门），阻塞执行并返回结果摘要。
     * 供定时任务模块「立即执行」调用；正在回顾中或已关闭时返回相应提示。
     */
    public String reviewNow() {
        if (!AgentConfig.getInstance().getMemoryHabitReviewEnabled()) return "习惯回顾已关闭（memory.habit.review.enabled=false）";
        if (!reviewing.compareAndSet(false, true)) return "习惯回顾正在进行中，已跳过本次触发";
        try {
            return reviewSync(true);
        } finally {
            reviewing.set(false);
        }
    }

    /**
     * 执行一次习惯回顾。
     *
     * @param force true=手动强制（绕过间隔水位）；false=轮后自动（受间隔与情景数闸门约束）
     * @return 结果摘要（供手动触发展示；自动触发忽略返回值）
     */
    private String reviewSync(boolean force) {
        AgentConfig cfg = AgentConfig.getInstance();
        long last = store.lastHabitReviewAt();
        long now = System.currentTimeMillis();
        if (!force && now - last < cfg.getMemoryHabitReviewIntervalHours() * 3600_000L) {
            return "未到回顾间隔，本次跳过";
        }
        List<Episode> episodes = store.episodesSince(last, cfg.getMemoryHabitReviewMaxEpisodes());
        if (episodes.size() < cfg.getMemoryHabitReviewMinEpisodes()) {
            return "自上次回顾以来仅 " + episodes.size() + " 轮情景，未达最小归纳量，跳过";
        }

        String digest = buildDigest(episodes);
        Msg sys = Msg.builder().role(MsgRole.SYSTEM).name("system")
                .textContent(MemoryPrompts.HABIT_REVIEW_PROMPT).build();
        Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(digest).build();
        String text = streamCollect(sys, user).trim();
        if (text.isEmpty()) {
            // 空响应 ≠ 判定为无：不推进水位，下次触发条件满足时重试
            log.warn("习惯回顾模型无响应，本次跳过（请检查轻量模型配置/网络）");
            return "轻量模型无响应，本次跳过（请检查模型配置/网络）";
        }
        if (Distiller.isNoneAnswer(text)) {
            store.markHabitReview(now, "habit-reviewer", "回顾 " + episodes.size() + " 轮，无可归纳模式");
            log.info("习惯回顾完成：回顾 {} 轮，无可归纳模式", episodes.size());
            return "回顾 " + episodes.size() + " 轮，无可归纳模式";
        }

        double dedup = cfg.getMemoryDistillDedupThreshold();
        List<CorrectionRecord> correctionRules = store.allCorrections();
        int added = 0, merged = 0, pending = 0;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("- ")) line = line.substring(2).strip();
            // 剥掉句末的依据轮次标注（轮次编号只在本批摘要内有意义，不入库）
            line = line.replaceAll("[（(]依据[:：][^）)]*[）)]\\s*$", "").strip();
            if (line.isEmpty() || Distiller.isNoneAnswer(line)) continue;
            if (CorrectionGuard.findUnsafeMemoryClaim(line, correctionRules).isPresent()) {
                log.warn("习惯回顾命中已废弃或未核验主张，确定性跳过: {}", line);
                continue;
            }

            float[] vec = gate.embed(line, EmbeddingPurpose.BACKGROUND_INDEX);
            if (vec == null) {
                Fact pf = new Fact("习惯偏好", line, null);
                pf.sourceKind = "HABIT_REVIEW";
                store.addPendingFact(pf, "habit-reviewer");
                pending++;
                continue;
            }
            List<MemoryStore.Scored<Fact>> hit = store.searchFacts(vec, 1, dedup);
            if (!hit.isEmpty()) {
                Fact existing = hit.get(0).entity();
                if (!existing.userEdited && !existing.userAsserted) {
                    store.mergeFact(existing, "habit-reviewer", line);
                    merged++;
                } else {
                    // 归纳结果与用户保护事实近重复时，用户事实保持唯一且不由模型强化/改写。
                    log.debug("习惯回顾命中用户保护事实，跳过: {}", line);
                }
            } else {
                Fact fact = new Fact("习惯偏好", line, vec);
                fact.sourceKind = "HABIT_REVIEW";
                store.addFact(fact, "habit-reviewer");
                added++;
            }
        }
        String summary = "回顾 " + episodes.size() + " 轮：归纳新增 " + added + "、合并 " + merged + "、暂存 " + pending;
        store.markHabitReview(now, "habit-reviewer", summary);
        log.info("习惯回顾完成：回顾 {} 轮，新增 {}，合并 {}，降级暂存 {}", episodes.size(), added, merged, pending);
        return summary;
    }

    /** 把情景批次压成带编号的对话摘要（供跨轮归纳，逐条截断控制 token）。 */
    private static String buildDigest(List<Episode> episodes) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Episode ep : episodes) {
            sb.append("#").append(i++).append(" 用户：").append(snip(ep.userInput));
            if (ep.assistantReply != null && !ep.assistantReply.isBlank()) {
                sb.append("｜助手：").append(snip(ep.assistantReply));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String snip(String s) {
        if (s == null) return "";
        s = s.strip().replaceAll("\\s+", " ");
        return s.length() > EPISODE_SNIPPET_CHARS ? s.substring(0, EPISODE_SNIPPET_CHARS) + "…" : s;
    }

    /** 轻量模型流式收集文本 + token 簿记（与 Distiller 一致）。 */
    private String streamCollect(Msg sys, Msg user) {
        StringBuilder sb = new StringBuilder();
        List<ChatResponse> responses = lightModel.stream(List.of(sys, user), List.of(), generateOptions)
                .collectList().block();
        if (responses == null) return "";
        for (ChatResponse resp : responses) {
            if (resp.getContent() == null) continue;
            for (ContentBlock block : resp.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null) {
                    sb.append(tb.getText());
                }
            }
        }
        if (tokenTracker != null) {
            long[] usage = TokenTracker.extractUsage(responses);
            tokenTracker.recordModelUsage("HabitReviewer.review", usage[0], usage[1]);
        }
        return sb.toString();
    }
}
