package com.javaclaw.loop.model;

import com.javaclaw.loop.LoopConstants;

/**
 * 循环节奏：模式 + 轮间延迟秒数。
 *
 * @param mode         节奏模式
 * @param delaySeconds 轮间延迟秒数（自驱通常为 0，定时为固定间隔；负数归零）
 */
public record Cadence(CadenceMode mode, long delaySeconds) {

    public Cadence {
        if (mode == null) mode = CadenceMode.SELF_PACED;
        if (delaySeconds < 0) delaySeconds = 0;
    }

    /** 自驱节奏（默认轮间延迟）。 */
    public static Cadence selfPaced() {
        return new Cadence(CadenceMode.SELF_PACED, LoopConstants.DEFAULT_SELF_PACED_DELAY_SECONDS);
    }

    /** 定时节奏，指定轮间延迟秒数。 */
    public static Cadence interval(long delaySeconds) {
        return new Cadence(CadenceMode.INTERVAL, delaySeconds);
    }
}
