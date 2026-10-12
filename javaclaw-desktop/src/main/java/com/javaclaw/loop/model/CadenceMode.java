package com.javaclaw.loop.model;

/**
 * 循环节奏模式。
 *
 * <p>统一引擎里两种形态的唯一区别就是「轮间怎么等」：</p>
 * <ul>
 *   <li>{@link #SELF_PACED}——自驱：上一轮结束立即开下一轮，靠执行体自评收敛；</li>
 *   <li>{@link #INTERVAL}——定时：每隔固定时长开一轮，携带上轮成果与停止条件。</li>
 * </ul>
 */
public enum CadenceMode {
    /** 自驱迭代（轮间不等或近乎不等）。 */
    SELF_PACED,
    /** 定时循环（轮间固定延迟）。 */
    INTERVAL
}
