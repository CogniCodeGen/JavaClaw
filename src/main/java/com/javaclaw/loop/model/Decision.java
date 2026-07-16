package com.javaclaw.loop.model;

/**
 * 每轮体检的唯一裁决出口。
 *
 * <p>「完成 / 继续 / 停止」三个问题合流为同一个决策函数的三种结果。</p>
 */
public enum Decision {
    /** 目标达成，收工。 */
    DONE,
    /** 未完成但有进展，再来一轮。 */
    CONTINUE,
    /** 触发护栏或无法收敛，停下。 */
    STOP
}
