package com.javaclaw.memory.model;

import java.util.ArrayList;
import java.util.List;

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

    /** 上次习惯回顾完成时间，用于周期调度。 */
    public long lastHabitReviewAt;

    /** 已处理证据游标。 */
    public long habitCursorTimestamp;
    public String habitCursorEvidenceKey;
    /** 正在排空已满足首次归纳门槛的证据批次。 */
    public boolean habitReviewDraining;
    /** 每次完成回顾递增，防止并行回顾覆盖较新的线索台账。 */
    public long habitReviewRevision;

    /** 单条超过回顾输入预算的证据仍待处理，不阻塞后续证据。 */
    public List<String> pendingHabitEvidenceKeys = new ArrayList<>();

    /** 尚未形成跨轮习惯的单轮线索；与证据游标一起持久化。 */
    public List<HabitObservation> pendingHabitObservations = new ArrayList<>();

    /** 一条由模型提取、仍须由其他独立轮次复核的线索。 */
    public static final class HabitObservation {
        public String text;
        public String evidenceKey;

        public HabitObservation() {}

        public HabitObservation(String text, String evidenceKey) {
            this.text = text;
            this.evidenceKey = evidenceKey;
        }
    }

    public MemoryStats() {}
}
