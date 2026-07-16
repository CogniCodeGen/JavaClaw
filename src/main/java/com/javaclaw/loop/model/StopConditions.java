package com.javaclaw.loop.model;

import com.javaclaw.loop.LoopConstants;

/**
 * 停止预算：循环的硬性上限，由 {@code Guardrails} 在轮边界检查。
 *
 * <p>三项均为「达到即停」，其中 {@code <= 0} 表示不限（墙钟为 {@code <= 0} 不限）。</p>
 *
 * @param maxIterations       最大迭代轮数；{@code <= 0} 不限
 * @param tokenBudget         用量额度上限；{@code <= 0} 不限
 * @param maxWallClockSeconds 整体墙钟超时秒数；{@code <= 0} 不限
 */
public record StopConditions(int maxIterations, long tokenBudget, long maxWallClockSeconds) {

    /** 采用内置默认阈值。 */
    public static StopConditions defaults() {
        return new StopConditions(
                LoopConstants.DEFAULT_MAX_ITERATIONS,
                LoopConstants.DEFAULT_TOKEN_BUDGET,
                LoopConstants.DEFAULT_MAX_WALLCLOCK_SECONDS);
    }
}
