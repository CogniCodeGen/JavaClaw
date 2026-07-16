package com.javaclaw.loop;

import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.loop.model.StopConditions;
import com.javaclaw.loop.model.StopReason;

import java.time.Clock;

/**
 * 停止护栏：一组确定性硬闸门，与执行体干得好不好无关，到点就拉闸。
 *
 * <p>分两批：</p>
 * <ul>
 *   <li><b>轮前</b>（{@link #preflight(int)}）——预算类：取消 / 轮数 / 用量 / 墙钟。
 *       别启动一个付不起的轮次。</li>
 *   <li><b>轮后</b>（{@link #postflight(IterationResult, boolean, boolean)}）——连续失败 / 原地打转，
 *       需要本轮结果才能判。</li>
 * </ul>
 *
 * <p>有状态（连败/空转计数、取消标志），非线程安全，只在单执行线程里顺序调用；
 * {@link #cancel()} 例外，允许其它线程触发取消。</p>
 */
public final class Guardrails {

    private final StopConditions conditions;
    private final Clock clock;
    private final long deadlineMillis;

    private volatile boolean cancelled;
    private int consecutiveFails;
    private int stalledRounds;
    /** 本循环累计用量：由控制器逐轮累加 {@code IterationResult} 的真实用量——
     * 只算循环自己的轮次，不读全局会话计数（循环与聊天并行时后者会被聊天用量污染）。 */
    private long tokensUsed;

    /**
     * @param conditions 停止预算
     * @param clock      时钟（可注入假时钟做超时测试）
     */
    public Guardrails(StopConditions conditions, Clock clock) {
        this.conditions = conditions == null ? StopConditions.defaults() : conditions;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        long wall = this.conditions.maxWallClockSeconds();
        this.deadlineMillis = wall <= 0 ? Long.MAX_VALUE : this.clock.millis() + wall * 1000L;
    }

    /** 触发取消（可跨线程）。 */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 仅重置连败计数（不触碰停滞计数）：供不经 {@link #postflight} 的成功轮调用——
     * 「准则全满足·执行体沉默」宽限轮在决策漏斗里提前返回，若不在此重置，
     * 被成功宽限轮隔开的两次失败会被误判为「连续」失败。
     */
    public void noteSuccess() {
        consecutiveFails = 0;
    }

    /** 累加一轮的真实用量（控制器每轮结束调用一次）。 */
    public void addRoundUsage(long tokens) {
        if (tokens > 0) {
            tokensUsed += tokens;
        }
    }

    /** 本循环迄今累计用量。 */
    public long tokensUsed() {
        return tokensUsed;
    }

    /**
     * 轮前预算检查：返回非 null 即应停止（连这一轮都不开）。
     *
     * @param iteration 即将开始的轮次（从 1 起）
     */
    public StopReason preflight(int iteration) {
        if (cancelled) {
            return StopReason.CANCELLED;
        }
        if (conditions.maxIterations() > 0 && iteration > conditions.maxIterations()) {
            return StopReason.MAX_ITERATIONS;
        }
        if (conditions.tokenBudget() > 0 && tokensUsed() >= conditions.tokenBudget()) {
            return StopReason.TOKEN_BUDGET;
        }
        if (clock.millis() >= deadlineMillis) {
            return StopReason.WALLCLOCK_TIMEOUT;
        }
        return null;
    }

    /**
     * 轮后检查：更新连败/空转计数，返回非 null 即应停止。<b>每轮调用一次</b>。
     *
     * @param result       本轮产出
     * @param madeProgress 本轮是否有进展（由 {@link ProgressGate} 给出）
     * @param waitRound    是否声明式等待轮（执行体明示在等外部条件，如轮询 CI）。
     *                     等待既不证明进展也不证明停滞——重复同样的检查动作是轮询的本分，
     *                     故等待轮<b>跳过</b>停滞计数（不加也不清零），由墙钟/轮数上限兜底防无限等
     */
    public StopReason postflight(IterationResult result, boolean madeProgress, boolean waitRound) {
        // 连续失败：先给一次重试机会（第一次不停）
        if (result.threw()) {
            if (++consecutiveFails >= LoopConstants.CONSECUTIVE_FAIL_LIMIT) {
                return StopReason.CONSECUTIVE_FAILURE;
            }
        } else {
            consecutiveFails = 0;
        }

        // 原地打转：连续无进展达上限则判收敛不了；声明式等待轮不参与计数。
        // 失败/超时轮（result.threw()）也跳过停滞计数——它已计入上面的连败预算，
        // 而 ProgressGate 对失败轮固定返回 madeProgress=false，若照常累加停滞，则「一次瞬时错误
        // + 一次真停滞」就凑够阈值误停，而连败与停滞本是两套独立预算（各自阈值 2）
        if (waitRound || result.threw()) {
            return null;
        }
        if (!madeProgress) {
            if (++stalledRounds >= LoopConstants.NO_PROGRESS_ROUND_LIMIT) {
                return StopReason.NO_PROGRESS;
            }
        } else {
            stalledRounds = 0;
        }
        return null;
    }
}
