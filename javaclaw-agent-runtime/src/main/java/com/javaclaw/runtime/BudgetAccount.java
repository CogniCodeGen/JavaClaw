package com.javaclaw.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnBudget;

/**
 * 单 Turn 并发安全预算账户。
 *
 * <p>所有扣减在锁内完成；子 Thread token 先从父级预留，失败时不会出现部分扣减。直接子 Thread 固定上限为 4。
 */
public final class BudgetAccount {
    private static final int MAX_DIRECT_CHILDREN = 4;

    private final TurnBudget limit;
    private final Clock clock;
    private final Instant deadline;
    private long inputTokens;
    private long outputTokens;
    private int toolCalls;
    private int childThreads;

    /**
     * 创建预算账户。
     *
     * @param limit 冻结预算
     * @param clock 平台时钟
     */
    public BudgetAccount(TurnBudget limit, Clock clock) {
        this(limit, clock, Objects.requireNonNull(clock, "clock").instant(), ModelUsage.zero(), 0);
    }

    /**
     * 从持久用量恢复预算账户。
     *
     * <p>墙钟截止时间始终以 Turn 创建时间为基准，重启不会重新获得预算。恢复数据超过上限时立即失败，不能通过截断掩盖账本损坏。
     *
     * @param limit 冻结预算
     * @param clock 平台时钟
     * @param startedAt Turn 创建时间
     * @param consumedUsage 已提交模型用量
     * @param consumedToolCalls 已提交工具调用次数
     */
    public BudgetAccount(
            TurnBudget limit, Clock clock, Instant startedAt, ModelUsage consumedUsage, int consumedToolCalls) {
        this.limit = Objects.requireNonNull(limit, "limit");
        this.clock = Objects.requireNonNull(clock, "clock");
        deadline = Objects.requireNonNull(startedAt, "startedAt").plus(limit.wallTime());
        ModelUsage usage = Objects.requireNonNull(consumedUsage, "consumedUsage");
        requireAtMost(usage.inputTokens(), limit.inputTokens(), "recovered input usage exceeds budget");
        requireAtMost(usage.outputTokens(), limit.outputTokens(), "recovered output usage exceeds budget");
        if (consumedToolCalls < 0 || consumedToolCalls > limit.toolCalls()) {
            throw new BudgetExceededException("recovered tool calls exceed budget");
        }
        inputTokens = usage.inputTokens();
        outputTokens = usage.outputTokens();
        toolCalls = consumedToolCalls;
    }

    /**
     * 扣减模型 usage。
     *
     * @param usage 本轮 usage
     */
    public synchronized void consume(ModelUsage usage) {
        long nextInput = Math.addExact(inputTokens, usage.inputTokens());
        long nextOutput = Math.addExact(outputTokens, usage.outputTokens());
        requireAtMost(nextInput, limit.inputTokens(), "input token budget exceeded");
        requireAtMost(nextOutput, limit.outputTokens(), "output token budget exceeded");
        inputTokens = nextInput;
        outputTokens = nextOutput;
    }

    /** 扣减一次工具调用。 */
    public synchronized void consumeToolCall() {
        if (toolCalls >= limit.toolCalls()) {
            throw new BudgetExceededException("tool call budget exceeded");
        }
        toolCalls++;
    }

    /**
     * 原子预留一个直接子 Thread 的 token。
     *
     * @param reservation 预留量
     */
    public synchronized void reserveChild(ReservedChildBudget reservation) {
        int maximumChildren = Math.min(MAX_DIRECT_CHILDREN, limit.childThreads());
        if (childThreads >= maximumChildren) {
            throw new BudgetExceededException("direct child thread budget exceeded");
        }
        long nextInput = Math.addExact(inputTokens, reservation.inputTokens());
        long nextOutput = Math.addExact(outputTokens, reservation.outputTokens());
        requireAtMost(nextInput, limit.inputTokens(), "child input reservation exceeds parent budget");
        requireAtMost(nextOutput, limit.outputTokens(), "child output reservation exceeds parent budget");
        inputTokens = nextInput;
        outputTokens = nextOutput;
        childThreads++;
    }

    /**
     * 检查取消与墙钟截止时间。
     *
     * @param cancellation 取消信号
     */
    public void checkpoint(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (!clock.instant().isBefore(deadline)) {
            throw new BudgetExceededException("wall time budget exceeded");
        }
    }

    /**
     * 返回剩余输出 token，至少为 1 才可继续调用模型。
     *
     * @return 剩余 token
     */
    public synchronized long remainingOutputTokens() {
        return Math.max(0, limit.outputTokens() - outputTokens);
    }

    /**
     * 返回已执行工具数。
     *
     * @return 工具数
     */
    public synchronized int toolCalls() {
        return toolCalls;
    }

    private static void requireAtMost(long actual, long maximum, String message) {
        if (actual > maximum) {
            throw new BudgetExceededException(message);
        }
    }
}
