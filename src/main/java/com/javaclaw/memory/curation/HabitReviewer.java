package com.javaclaw.memory.curation;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.embed.EmbeddingGate;
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
    private final EmbeddingGate gate;
    private final TokenTracker tokenTracker;
    private final GenerateOptions generateOptions;

    /** 防并发重入：相邻两轮几乎同时结束时只跑一次回顾 */
    private final AtomicBoolean reviewing = new AtomicBoolean(false);

    public HabitReviewer(ChatModelBase lightModel, MemoryStore store, EmbeddingGate gate, TokenTracker tokenTracker) {
        this.lightModel = lightModel;
        this.store = store;
        this.gate = gate;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /** 轮后检查是否到达回顾条件，满足则异步执行一次习惯回顾；未满足/失败均静默。 */
    public Mono<Void> maybeReview() {
        return Mono.fromRunnable(() -> {
                    if (!AgentConfig.getInstance().getMemoryHabitReviewEnabled()) return;
                    if (!reviewing.compareAndSet(false, true)) return;
                    try {
                        reviewSync();
                    } finally {
                        reviewing.set(false);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    reviewing.set(false);
                    log.warn("习惯回顾失败（已静默忽略，水位不推进、下轮重试）: {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private void reviewSync() {
        AgentConfig cfg = AgentConfig.getInstance();
        long last = store.lastHabitReviewAt();
        long now = System.currentTimeMillis();
        if (now - last < cfg.getMemoryHabitReviewIntervalHours() * 3600_000L) {
            return;
        }
        List<Episode> episodes = store.episodesSince(last, cfg.getMemoryHabitReviewMaxEpisodes());
        if (episodes.size() < cfg.getMemoryHabitReviewMinEpisodes()) {
            return;
        }

        String digest = buildDigest(episodes);
        Msg sys = Msg.builder().role(MsgRole.SYSTEM).name("system")
                .textContent(MemoryPrompts.HABIT_REVIEW_PROMPT).build();
        Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(digest).build();
        String text = streamCollect(sys, user).trim();
        if (text.isEmpty()) {
            // 空响应 ≠ 判定为无：不推进水位，下次触发条件满足时重试
            log.warn("习惯回顾模型无响应，本次跳过（请检查轻量模型配置/网络）");
            return;
        }
        if (Distiller.isNoneAnswer(text)) {
            store.markHabitReview(now, "habit-reviewer", "回顾 " + episodes.size() + " 轮，无可归纳模式");
            log.info("习惯回顾完成：回顾 {} 轮，无可归纳模式", episodes.size());
            return;
        }

        double dedup = cfg.getMemoryDistillDedupThreshold();
        int added = 0, merged = 0, pending = 0;
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("- ")) line = line.substring(2).strip();
            // 剥掉句末的依据轮次标注（轮次编号只在本批摘要内有意义，不入库）
            line = line.replaceAll("[（(]依据[:：][^）)]*[）)]\\s*$", "").strip();
            if (line.isEmpty() || Distiller.isNoneAnswer(line)) continue;

            float[] vec = gate.embed(line);
            if (vec == null) {
                Fact pf = new Fact("习惯偏好", line, null);
                store.addPendingFact(pf, "habit-reviewer");
                pending++;
                continue;
            }
            List<MemoryStore.Scored<Fact>> hit = store.searchFacts(vec, 1, dedup);
            if (!hit.isEmpty() && !hit.get(0).entity().userEdited) {
                store.mergeFact(hit.get(0).entity(), "habit-reviewer", line);
                merged++;
            } else {
                store.addFact(new Fact("习惯偏好", line, vec), "habit-reviewer");
                added++;
            }
        }
        store.markHabitReview(now, "habit-reviewer",
                "回顾 " + episodes.size() + " 轮：归纳新增 " + added + "、合并 " + merged + "、暂存 " + pending);
        log.info("习惯回顾完成：回顾 {} 轮，新增 {}，合并 {}，降级暂存 {}", episodes.size(), added, merged, pending);
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
