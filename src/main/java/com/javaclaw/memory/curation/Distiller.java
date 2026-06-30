package com.javaclaw.memory.curation;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.embed.EmbeddingGate;
import com.javaclaw.memory.model.EntityNode;
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

/**
 * 蒸馏器 —— 轮后从情景提炼事实，<b>向量去重 upsert</b> 写入语义记忆。
 *
 * <p>取代旧 {@code MemoryCurator} 的"蒸馏到日流水 → 攒 7 个 → 整文件重写 MEMORY.md"批处理：
 * 改为即时增量——每条候选事实嵌入后与既有事实做相似度查重，命中（且非用户保护）则记一次合并、
 * 否则新增。无日流水、无批量覆写，连带消除其数据丢失与灾难重写风险。</p>
 *
 * <p>全程 boundedElastic 异步、失败静默；嵌入不可用时跳过该事实（不写无向量的事实，规避索引空向量）。</p>
 *
 * @author JavaClaw
 */
public class Distiller {

    private static final Logger log = LoggerFactory.getLogger(Distiller.class);

    private static final int MAX_REPLY_CHARS = 6000;

    private final ChatModelBase lightModel;
    private final MemoryStore store;
    private final EmbeddingGate gate;
    private final TokenTracker tokenTracker;
    private final GenerateOptions generateOptions;

    public Distiller(ChatModelBase lightModel, MemoryStore store, EmbeddingGate gate, TokenTracker tokenTracker) {
        this.lightModel = lightModel;
        this.store = store;
        this.gate = gate;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /** 异步蒸馏一次情景；失败静默返回 empty。 */
    public Mono<Void> distill(Episode ep) {
        if (ep == null || ep.userInput == null
                || ep.userInput.trim().length() < AgentConfig.getInstance().getMemoryDistillMinInput()) {
            return Mono.empty();
        }
        if (ep.assistantReply == null || ep.assistantReply.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> distillSync(ep))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("记忆蒸馏失败（已静默忽略）: {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private void distillSync(Episode ep) {
        String reply = ep.assistantReply.length() > MAX_REPLY_CHARS
                ? ep.assistantReply.substring(0, MAX_REPLY_CHARS) + "...(截断)"
                : ep.assistantReply;
        String conversation = "[用户输入]\n" + ep.userInput.trim() + "\n\n[助手回复]\n" + reply.trim();

        Msg sys = Msg.builder().role(MsgRole.SYSTEM).name("system").textContent(MemoryPrompts.DISTILL_PROMPT).build();
        Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(conversation).build();
        String factsText = streamCollect(sys, user).trim();
        if (factsText.isEmpty() || "无".equals(factsText) || factsText.equalsIgnoreCase("none")) {
            log.debug("本轮无值得记忆的事实");
            return;
        }

        // 实体抽取（阶段二）：本轮先抽一次实体并 get-or-create，供新增事实按名称匹配关联 about 边。
        List<EntityNode> turnEntities = extractEntities(conversation);

        double dedup = AgentConfig.getInstance().getMemoryDistillDedupThreshold();
        int added = 0, merged = 0, skipped = 0;
        for (String raw : factsText.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("- ")) line = line.substring(2).strip();
            if (line.isEmpty()) continue;

            float[] vec = gate.embed(line);
            if (vec == null) {
                skipped++;
                continue; // 嵌入不可用 → 跳过（不写无向量事实）
            }
            List<MemoryStore.Scored<Fact>> hit = store.searchFacts(vec, 1, dedup);
            if (!hit.isEmpty() && !hit.get(0).entity().userEdited) {
                // 命中既有相似事实 → 记一次合并（不重复落库，避免冗余）
                store.appendChangeLog("MERGE", "Fact", hit.get(0).entity().id, "distiller", line);
                merged++;
            } else {
                Fact f = new Fact(null, line, vec);
                f.source = ep;
                f.about = matchEntities(line, turnEntities); // 关联本轮实体（记忆图 about 边）
                store.addFact(f, "distiller");
                added++;
            }
        }
        log.info("记忆蒸馏完成：新增 {}，合并 {}，跳过 {}（无嵌入），实体 {}", added, merged, skipped, turnEntities.size());
    }

    /**
     * 从对话抽取实体并 get-or-create 入库（记忆图谱 entity 节点）。
     * 受 {@code memory.graph.entities.enabled} 闸门；失败/关闭时返回空列表（蒸馏不受影响）。
     */
    private List<EntityNode> extractEntities(String conversation) {
        if (!AgentConfig.getInstance().getMemoryGraphEntitiesEnabled()) {
            return List.of();
        }
        List<EntityNode> out = new java.util.ArrayList<>();
        try {
            Msg sys = Msg.builder().role(MsgRole.SYSTEM).name("system")
                    .textContent(MemoryPrompts.ENTITY_EXTRACT_PROMPT).build();
            Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(conversation).build();
            String text = streamCollect(sys, user).trim();
            if (text.isEmpty() || "无".equals(text) || text.equalsIgnoreCase("none")) {
                return out;
            }
            for (String raw : text.lines().toList()) {
                String line = raw.strip();
                if (line.startsWith("- ")) line = line.substring(2).strip();
                if (line.isEmpty() || !line.contains("|")) continue;
                String[] parts = line.split("\\|", 2);
                String name = parts[0].strip();
                String type = parts.length > 1 ? parts[1].strip() : "topic";
                if (name.length() < 2) continue;
                EntityNode node = store.getOrCreateEntity(name, type, "distiller");
                if (node != null) out.add(node);
            }
        } catch (Exception e) {
            log.warn("实体抽取失败（已静默忽略）: {}", e.getMessage());
        }
        return out;
    }

    /** 在事实文本中按名称（忽略大小写、子串包含）匹配本轮实体，作为 about 边。 */
    private static List<EntityNode> matchEntities(String factText, List<EntityNode> entities) {
        if (entities.isEmpty() || factText == null) return new java.util.ArrayList<>();
        String lower = factText.toLowerCase();
        List<EntityNode> matched = new java.util.ArrayList<>();
        for (EntityNode en : entities) {
            if (en.name != null && en.name.length() >= 2
                    && lower.contains(en.name.toLowerCase()) && !matched.contains(en)) {
                matched.add(en);
            }
        }
        return matched;
    }

    /** 轻量模型流式收集文本 + token 簿记（与旧 MemoryCurator 一致）。 */
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
            tokenTracker.recordModelUsage("Distiller.distill", usage[0], usage[1]);
        }
        return sb.toString();
    }
}
