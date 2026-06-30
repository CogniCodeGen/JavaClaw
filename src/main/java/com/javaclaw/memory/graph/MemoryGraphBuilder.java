package com.javaclaw.memory.graph;

import com.javaclaw.memory.model.EntityNode;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆图谱构建器 —— 从 {@link MemoryStore} 一次性物化一张可视化用的 {@link MemoryGraph}。
 *
 * <p>纯读路径，不写库；向量近邻边通过既有 {@code factIndex} 即时检索得到（无额外 LLM 调用）。
 * 在调用线程执行（建议放后台线程，见 {@code MemoryGraphView}）。</p>
 *
 * <p>构图规则见 {@link MemoryGraph} 文档：事实/情景/实体三类节点 + source/about/semantic 三类边。
 * 为控制规模，事实按更新时间倒序取前 {@code maxNodes} 个，情景与实体仅纳入被选中事实实际引用到的部分
 * （避免孤立节点淹没图）。</p>
 *
 * @author JavaClaw
 */
public final class MemoryGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(MemoryGraphBuilder.class);

    private MemoryGraphBuilder() {}

    /**
     * 构图选项。
     *
     * @param maxNodes           纳入的事实上限（按更新时间倒序）
     * @param semanticThreshold  语义近邻边的最小相似度（0~1）
     * @param maxSemanticPerFact 每个事实最多保留的近邻边数
     * @param includeSemantic    是否计算语义近邻边
     * @param labelMaxChars      节点标签截断长度
     */
    public record Options(int maxNodes, double semanticThreshold, int maxSemanticPerFact,
                          boolean includeSemantic, int labelMaxChars) {
        public static Options defaults() {
            return new Options(300, 0.78, 3, true, 36);
        }
    }

    /** 从记忆库构建图谱快照；store 为空或未开则返回空图。 */
    public static MemoryGraph build(MemoryStore store, Options opt) {
        if (store == null || !store.isOpen()) {
            return MemoryGraph.empty();
        }
        try {
            return doBuild(store, opt);
        } catch (Exception e) {
            log.warn("构建记忆图谱失败（返回空图）: {}", e.getMessage());
            return MemoryGraph.empty();
        }
    }

    private static MemoryGraph doBuild(MemoryStore store, Options opt) {
        List<Fact> allFacts = store.allFacts();
        // 按更新时间倒序，取前 maxNodes 个事实参与构图
        allFacts.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        List<Fact> facts = allFacts.size() > opt.maxNodes()
                ? new ArrayList<>(allFacts.subList(0, opt.maxNodes()))
                : allFacts;

        List<MemoryGraph.Node> nodes = new ArrayList<>();
        List<MemoryGraph.Edge> edges = new ArrayList<>();

        // 事实节点 + 事实 id → 节点 id 映射（供语义边回查）
        Map<String, String> factKeyToNodeId = new HashMap<>();
        Set<Fact> included = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Fact f : facts) {
            String nodeId = "fact:" + factKey(f);
            factKeyToNodeId.put(factKey(f), nodeId);
            included.add(f);
            nodes.add(new MemoryGraph.Node(
                    nodeId,
                    truncate(f.text, opt.labelMaxChars()),
                    "fact",
                    blankToDefault(f.section, "未分类"),
                    f.text == null ? "" : f.text,
                    Math.max(1, f.hitCount)));
        }

        // 情景节点（仅纳入被选中事实 source 引用到的）+ source 边
        Map<String, String> episodeKeyToNodeId = new HashMap<>();
        for (Fact f : facts) {
            Episode ep = f.source;
            if (ep == null) continue;
            String epKey = episodeKey(ep);
            String epNodeId = episodeKeyToNodeId.get(epKey);
            if (epNodeId == null) {
                epNodeId = "episode:" + epKey;
                episodeKeyToNodeId.put(epKey, epNodeId);
                nodes.add(new MemoryGraph.Node(
                        epNodeId,
                        truncate(ep.userInput, opt.labelMaxChars()),
                        "episode",
                        "情景",
                        episodeDetail(ep),
                        1));
            }
            edges.add(new MemoryGraph.Edge(factKeyToNodeId.get(factKey(f)), epNodeId, "source", 1.0));
        }

        // 实体节点（仅纳入被选中事实 about 引用到的）+ about 边，weight=被引用度
        Map<String, String> entityKeyToNodeId = new HashMap<>();
        Map<String, Integer> entityRefCount = new HashMap<>();
        Map<String, EntityNode> entityByKey = new HashMap<>();
        for (Fact f : facts) {
            if (f.about == null) continue;
            for (EntityNode en : f.about) {
                if (en == null || en.name == null || en.name.isBlank()) continue;
                String enKey = entityKey(en);
                entityByKey.putIfAbsent(enKey, en);
                entityRefCount.merge(enKey, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, EntityNode> e : entityByKey.entrySet()) {
            EntityNode en = e.getValue();
            String enNodeId = "entity:" + e.getKey();
            entityKeyToNodeId.put(e.getKey(), enNodeId);
            nodes.add(new MemoryGraph.Node(
                    enNodeId,
                    truncate(en.name, opt.labelMaxChars()),
                    "entity",
                    blankToDefault(en.type, "topic"),
                    en.name + (en.type == null ? "" : "（" + en.type + "）"),
                    Math.max(1, entityRefCount.getOrDefault(e.getKey(), 1))));
        }
        for (Fact f : facts) {
            if (f.about == null) continue;
            String factNodeId = factKeyToNodeId.get(factKey(f));
            Set<String> seen = new HashSet<>();
            for (EntityNode en : f.about) {
                if (en == null || en.name == null || en.name.isBlank()) continue;
                String enNodeId = entityKeyToNodeId.get(entityKey(en));
                if (enNodeId != null && seen.add(enNodeId)) {
                    edges.add(new MemoryGraph.Edge(factNodeId, enNodeId, "about", 1.0));
                }
            }
        }

        // 语义近邻边（事实↔事实，无向去重）
        if (opt.includeSemantic()) {
            Set<String> pairSeen = new HashSet<>();
            for (Fact f : facts) {
                if (f.embedding == null) continue;
                String fromId = factKeyToNodeId.get(factKey(f));
                List<MemoryStore.Scored<Fact>> hits =
                        store.searchFacts(f.embedding, opt.maxSemanticPerFact() + 1, opt.semanticThreshold());
                for (MemoryStore.Scored<Fact> hit : hits) {
                    Fact other = hit.entity();
                    if (other == f || !included.contains(other)) continue;
                    String toId = factKeyToNodeId.get(factKey(other));
                    if (toId == null || toId.equals(fromId)) continue;
                    String pairKey = fromId.compareTo(toId) < 0 ? fromId + "|" + toId : toId + "|" + fromId;
                    if (pairSeen.add(pairKey)) {
                        edges.add(new MemoryGraph.Edge(fromId, toId, "semantic", round(hit.score())));
                    }
                }
            }
        }

        log.debug("记忆图谱构建完成: nodes={}, edges={}", nodes.size(), edges.size());
        return new MemoryGraph(nodes, edges);
    }

    // ==================== 工具 ====================

    private static String factKey(Fact f) {
        return f.id != null ? f.id : "e" + f.entityId;
    }

    private static String episodeKey(Episode ep) {
        return ep.id != null ? ep.id : "e" + ep.entityId;
    }

    private static String entityKey(EntityNode en) {
        return en.id != null ? en.id : ("n" + en.entityId + ":" + en.name);
    }

    private static String episodeDetail(Episode ep) {
        StringBuilder sb = new StringBuilder();
        if (ep.userInput != null) sb.append("用户：").append(ep.userInput.strip());
        if (ep.assistantReply != null && !ep.assistantReply.isBlank()) {
            String r = ep.assistantReply.strip();
            sb.append("\n助手：").append(r.length() > 240 ? r.substring(0, 240) + "…" : r);
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.strip().replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String blankToDefault(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.strip();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
