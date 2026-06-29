package com.javaclaw.memory.model;

/**
 * 记忆统计 —— 命中归因与规模计数（替代散落的 skill-usage 等统计 json，统一进对象图）。
 *
 * <p>P6 接入可观测后据此判断哪些事实该保留 / 淘汰。P1 仅承载字段。</p>
 *
 * @author JavaClaw
 */
public class MemoryStats {

    /** 累计检索次数 */
    public long totalRecalls;

    /** 累计命中并注入的事实条数 */
    public long totalFactHits;

    /** 累计蒸馏新增事实数 */
    public long totalFactsDistilled;

    /** 累计蒸馏去重合并数 */
    public long totalFactsMerged;

    public MemoryStats() {}
}
