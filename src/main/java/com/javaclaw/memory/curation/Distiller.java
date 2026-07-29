package com.javaclaw.memory.curation;

import com.javaclaw.agent.TokenTracker;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
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
    private final EmbeddingGateway gate;
    private final TokenTracker tokenTracker;
    private final GenerateOptions generateOptions;

    public Distiller(ChatModelBase lightModel, MemoryStore store, EmbeddingGateway gate, TokenTracker tokenTracker) {
        this.lightModel = lightModel;
        this.store = store;
        this.gate = gate;
        this.tokenTracker = tokenTracker;
        this.generateOptions = GenerateOptions.builder().build();
    }

    /** 在当前线程蒸馏一次情景；失败静默，供受生命周期追踪的上层工作线程调用。 */
    public void distillNow(Episode ep) {
        if (ep == null || ep.userInput == null
                || ep.userInput.trim().length() < AgentConfig.getInstance().getMemoryDistillMinInput()) {
            return;
        }
        if (ep.assistantReply == null || ep.assistantReply.isBlank()) {
            return;
        }
        try {
            distillSync(ep);
        } catch (RuntimeException e) {
            log.warn("记忆蒸馏失败（已静默忽略）: {}", e.getMessage());
        }
    }

    /** 异步兼容入口；生命周期敏感调用应优先使用 {@link #distillNow(Episode)}。 */
    public Mono<Void> distill(Episode ep) {
        return Mono.fromRunnable(() -> distillNow(ep))
                .subscribeOn(Schedulers.boundedElastic())
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
        if (factsText.isEmpty()) {
            // 与「判定为无」区分开：空响应通常意味着轻量模型未配置/超时/限流，需可见告警
            log.warn("记忆蒸馏模型无响应，本轮跳过（请检查轻量模型配置/网络）");
            return;
        }
        if (isNoneAnswer(factsText)) {
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
            if (line.isEmpty() || isNoneAnswer(line)) continue; // 混排输出中的「无」行不落库

            float[] vec = gate.embed(line, EmbeddingPurpose.BACKGROUND_INDEX);
            if (vec == null) {
                // 嵌入不可用 → 降级：纯文本落 pending 暂存区（无向量、不进索引，仍在记忆中心可见）
                Fact pf = new Fact(null, line, null);
                pf.source = ep;
                pf.about = matchEntities(line, turnEntities);
                store.addPendingFact(pf, "distiller");
                skipped++;
                continue;
            }
            List<MemoryStore.Scored<Fact>> hit = store.searchFacts(vec, 1, dedup);
            if (!hit.isEmpty() && !hit.get(0).entity().userEdited) {
                // 命中既有相似事实 → 合并强化（mergeCount++，不重复落库，避免冗余）
                store.mergeFact(hit.get(0).entity(), "distiller", line);
                merged++;
            } else {
                // 非近重复的新事实：入库前先做取代检测，软删除被本条否定/替代的旧事实（防新旧矛盾并存）
                supersedeStale(line, vec, dedup);
                Fact f = new Fact(null, line, vec);
                f.source = ep;
                f.about = matchEntities(line, turnEntities); // 关联本轮实体（记忆图 about 边）
                store.addFact(f, "distiller");
                added++;
            }
        }
        log.info("记忆蒸馏完成：新增 {}，合并 {}，降级暂存 {}（无嵌入），实体 {}", added, merged, skipped, turnEntities.size());
    }

    /**
     * 取代检测 —— 新事实入库前，检索「相关但不重复」的旧事实，交轻量模型判定哪些被本条取代 / 否定，
     * 命中则将旧事实软删除（{@code superseded=true}）。杜绝新旧矛盾事实并存、导致召回到过时记忆。
     *
     * <p>受 {@code memory.supersede.enabled} 闸门；只对 [supersedeThreshold, dedup) 中区间候选生效
     * （≥dedup 的近重复由蒸馏合并处理，不在取代范围）；跳过 userEdited / pinned 保护事实；
     * 无候选时不调用模型（成本控制）；全程失败静默，不影响蒸馏主流程。</p>
     */
    private void supersedeStale(String newFact, float[] vec, double dedup) {
        AgentConfig cfg = AgentConfig.getInstance();
        if (!cfg.getMemorySupersedeEnabled()) return;
        double threshold = cfg.getMemorySupersedeThreshold();
        if (threshold >= dedup) return; // 阈值配置异常：取代区间须严格低于去重区间，否则无候选可判
        int maxCand = cfg.getMemorySupersedeMaxCandidates();

        // 中区间候选：相关但未达去重线，且非用户保护 / 置顶（保护事实不被自动取代）。
        // 多取一些再过滤——高分槽位可能被 ≥dedup 近重复或保护事实占据，若只取 maxCand 会把
        // 排在其后、真正矛盾的旧事实挤出候选窗口；故先取 maxCand*2+4，过滤后再截断到 maxCand。
        List<MemoryStore.Scored<Fact>> cands = store.searchFacts(vec, maxCand * 2 + 4, threshold).stream()
                .filter(s -> s.score() < dedup)
                .filter(s -> !s.entity().userEdited && !s.entity().pinned)
                .limit(maxCand)
                .toList();
        if (cands.isEmpty()) return;

        try {
            StringBuilder u = new StringBuilder("【新事实】\n").append(newFact).append("\n\n【已有事实】\n");
            for (int i = 0; i < cands.size(); i++) {
                u.append(i + 1).append(". ").append(cands.get(i).entity().text).append('\n');
            }
            Msg sys = Msg.builder().role(MsgRole.SYSTEM).name("system")
                    .textContent(MemoryPrompts.SUPERSEDE_JUDGE_PROMPT).build();
            Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(u.toString()).build();
            String verdict = streamCollect(sys, user).trim();
            if (verdict.isEmpty()) {
                log.warn("取代检测模型无响应，本条跳过（请检查轻量模型配置/网络）");
                return;
            }
            if (isNoneAnswer(verdict)) return;

            int superseded = 0;
            for (int idx : parseIndexes(verdict, cands.size())) {
                store.supersedeFact(cands.get(idx).entity(), "distiller", newFact);
                superseded++;
            }
            if (superseded > 0) {
                log.info("取代检测：新事实取代了 {} 条旧事实 —— {}", superseded, trunc(newFact));
            }
        } catch (Exception e) {
            log.warn("取代检测失败（已静默忽略）: {}", e.getMessage());
        }
    }

    /**
     * 解析取代判定输出（如 {@code "1,3"} / {@code "1、3"} / {@code "输出：1,3"}）为 0 基下标集合。
     *
     * <p><b>严格校验，误删优先保守</b>：软删除不可逆地影响召回，故只接受「纯编号 + 分隔符」的干净输出
     * （允许剥离一个前缀标签如「输出：」）。判定文本一旦掺杂其它文字（复述事实、版本号、解释、
     * 「取代」等字样），说明模型未按协议输出，宁可**整体跳过**也不冒把无关数字当编号误删的风险。</p>
     *
     * <p>越界编号丢弃、去重保序；限定 1~3 位数字，规避模型异常长串导致的解析越界。</p>
     */
    static List<Integer> parseIndexes(String verdict, int size) {
        if (verdict == null) return new java.util.ArrayList<>();
        String s = verdict.strip();
        // 剥离可能的前缀标签（如「输出：」「取代:」），只保留冒号后的正文
        int colon = Math.max(s.lastIndexOf('：'), s.lastIndexOf(':'));
        if (colon >= 0 && colon < s.length() - 1) s = s.substring(colon + 1).strip();
        // 严格：正文只允许「数字 + 分隔符（逗号/顿号/空白/和/及）+ 尾随标点」；掺杂其它文字则保守跳过
        if (!s.matches("[0-9,，、\\s和及。．.!！~～]*")) {
            log.warn("取代判定输出非纯编号格式，保守跳过（不冒误删风险）: {}", trunc(s));
            return new java.util.ArrayList<>();
        }
        java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{1,3}").matcher(s);
        while (m.find()) {
            int n = Integer.parseInt(m.group());
            if (n >= 1 && n <= size) out.add(n - 1);
        }
        return new java.util.ArrayList<>(out);
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
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
            if (text.isEmpty()) {
                log.warn("实体抽取模型无响应，本轮跳过（请检查轻量模型配置/网络）");
                return out;
            }
            if (isNoneAnswer(text)) {
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

    /**
     * 判定模型输出是否为「无」类回答。提示词要求只输出"无"一个字，但实际模型常输出
     * 「无。」「- 无」「None.」「没有值得记录的事实」等变体——严格 equals 会把这类回答
     * 当成事实落库（产生垃圾记忆），故做归一化后再比对。
     */
    static boolean isNoneAnswer(String text) {
        if (text == null) return true;
        String s = text.strip();
        if (s.startsWith("- ")) s = s.substring(2).strip();
        // 去掉尾部标点/空白（中英文句号、逗号、感叹号、波浪号）
        s = s.replaceAll("[\\s。．.,，!！~～]+$", "").strip();
        return s.isEmpty()
                || "无".equals(s) || "没有".equals(s)
                || s.equalsIgnoreCase("none")
                || "没有值得记录的事实".equals(s)
                || "没有值得记忆的事实".equals(s)
                || "无可抽取的实体".equals(s) || "没有可抽取的实体".equals(s);
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
