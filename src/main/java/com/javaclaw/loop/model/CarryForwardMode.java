package com.javaclaw.loop.model;

/**
 * 接力上下文策略：决定「把上几轮的多少内容喂给下一轮」。
 */
public enum CarryForwardMode {
    /** 全量转录：把此前每一轮的产出都带上（上下文最全，但会随轮数膨胀）。 */
    FULL_TRANSCRIPT,
    /**
     * 摘要接力（默认）：历轮各一句简述（优先取 loop_report 的 summary，零额外模型调用；
     * 缺失时截断产出兜底）+ 末轮完整产出。上下文有界增长又不丢历史脉络。
     */
    SUMMARY,
    /** 仅末轮：只带上最近一轮的产出（上下文最省）。 */
    LAST_RESULT_ONLY
}
