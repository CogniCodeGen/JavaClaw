package com.javaclaw.task.sdd;

/**
 * 带阶段标签的 token 汇聚端口 —— 让 token 用量按 SDD 阶段（proposal/spec/design/plan/
 * implement/verify/remediate）分桶记账，便于定位"钱花在哪"并验证优化效果。
 *
 * <p>取代原先的 {@code BiConsumer<Long,Long>}：实现层（各阶段智能体）在构造 token 钩子时
 * 绑定本阶段标签，钩子每轮上报真实用量时带上标签。编排器/管理器据此累计总量 + 分阶段明细。</p>
 *
 * @author JavaClaw
 */
@FunctionalInterface
public interface SddTokenSink {

    /**
     * 记录一次模型调用的 token 用量。
     *
     * @param phase        阶段标签（proposal/spec/design/plan/implement/verify/remediate）
     * @param inputTokens  本次输入 token
     * @param outputTokens 本次输出 token
     */
    void record(String phase, long inputTokens, long outputTokens);

    /** 空实现（无记账）。 */
    SddTokenSink NOOP = (p, i, o) -> {};
}
