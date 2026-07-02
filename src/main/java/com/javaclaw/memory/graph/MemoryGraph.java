package com.javaclaw.memory.graph;

import java.util.List;

/**
 * 记忆图谱快照 —— 由 {@link MemoryGraphBuilder} 从记忆库一次性物化的只读节点/边集合，
 * 供 UI（{@code MemoryGraphView}）以 Canvas 力导向图直接渲染。
 *
 * <p>三类节点：</p>
 * <ul>
 *   <li>{@code fact} —— 语义事实（group=主题 section）</li>
 *   <li>{@code episode} —— 情景对话轮</li>
 *   <li>{@code entity} —— 记忆图实体节点（group=实体类型 person/project/tool/topic…）</li>
 * </ul>
 *
 * <p>三类边：</p>
 * <ul>
 *   <li>{@code source} —— 事实 → 来源情景（{@code Fact.source}，有向、真实落库的边）</li>
 *   <li>{@code about} —— 事实 → 关联实体（{@code Fact.about}，由实体抽取产生）</li>
 *   <li>{@code semantic} —— 事实 ↔ 事实（向量近邻，构建时即时计算、无向、带相似度权重）</li>
 * </ul>
 *
 * @author JavaClaw
 */
public record MemoryGraph(List<Node> nodes, List<Edge> edges) {

    /**
     * 图节点。
     *
     * @param id    唯一 id（fact:/episode:/entity: 前缀确保跨类型不撞）
     * @param label 显示标签（已截断）
     * @param type  节点类型：fact / episode / entity
     * @param group 分组：事实=主题 section、实体=类型、情景=会话或空
     * @param detail 悬浮/点击展示的完整文本
     * @param weight 视觉权重（事实=命中次数、实体=被引用度），用于节点尺寸
     */
    public record Node(String id, String label, String type, String group, String detail, int weight) {}

    /**
     * 图边。
     *
     * @param from   源节点 id
     * @param to     目标节点 id
     * @param kind   边类型：source / about / semantic
     * @param weight 权重（semantic=相似度 0~1，其余=1）
     */
    public record Edge(String from, String to, String kind, double weight) {}

    /** 空图。 */
    public static MemoryGraph empty() {
        return new MemoryGraph(List.of(), List.of());
    }
}
