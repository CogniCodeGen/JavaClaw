package com.javaclaw.agent.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.core.api.TurnConfig;

/** 显式传递取消、时间与模型预算；可跨虚拟线程使用，不依赖 ThreadLocal 或静态当前 Turn。 */
public final class TurnScope implements AutoCloseable {
    private final AtomicBoolean cancelled;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final BudgetAccount budget;
    private final long deadline;
    private final TurnScope parent;

    /** 创建有界作用域；duration 须为正且不超过 24 小时，budget/cancelled 均非空。 */
    public TurnScope(AtomicBoolean cancelled, BudgetAccount budget, Duration duration) {
        this(null, cancelled, budget, duration);
    }

    private TurnScope(TurnScope parent, AtomicBoolean cancelled, BudgetAccount budget, Duration duration) {
        this.parent = parent;
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.budget = Objects.requireNonNull(budget, "budget");
        if (duration.isZero() || duration.isNegative() || duration.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("turn duration must be positive and at most 24 hours");
        }
        deadline = System.nanoTime()
                + Math.min(duration.toNanos(), parent == null ? Long.MAX_VALUE : parent.remainingNanos());
    }

    /** 从服务端解析的配置生成有限根预算；零或缺省继承默认上限，不意味着无限运行。 */
    public static TurnScope from(TurnConfig config, AtomicBoolean cancelled) {
        int calls = limit(config, "maxModelCalls", 16, 1_000);
        long tokens = limit(config, "maxTokens", 200_000, 10_000_000);
        return new TurnScope(
                cancelled,
                new BudgetAccount(calls, tokens),
                Duration.ofSeconds(limit(config, "maxDurationSeconds", 3_600, 86_400)));
    }

    /** 返回同一执行树共享的显式账户；调用者不得用新根账户替代已有任务预算。 */
    public BudgetAccount budget() {
        return budget;
    }

    /** 为子 Turn 分配父账户剩余额度的一半，且不超过子 Profile 上限；为父任务保留收尾额度。 只有活动父作用域能分配，子任务的截止时间、取消状态同时受父作用域限制。 */
    public TurnScope child(TurnConfig config, AtomicBoolean childCancelled) {
        try {
            check();
        } catch (InterruptedException interrupted) {
            throw new IllegalStateException("parent Turn is no longer active");
        }
        int calls = Math.min(limit(config, "maxModelCalls", 16, 1_000), budget.remainingCalls() / 2);
        long tokens = Math.min(limit(config, "maxTokens", 200_000, 10_000_000), budget.remainingTokens() / 2);
        if (calls < 1 || tokens < 1) {
            throw new BudgetExceededException("parent budget cannot fund a subagent and retain completion capacity");
        }
        Duration duration = Duration.ofSeconds(limit(config, "maxDurationSeconds", 3_600, 86_400));
        BudgetAccount allocation = budget.allocate(calls, tokens);
        return new TurnScope(this, childCancelled, allocation, duration);
    }

    /** 返回剩余墙钟时长（纳秒）；使用单调时钟，不受系统时间校准影响。 */
    public long remainingNanos() {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** 在模型和工具边界检查取消及截止时间；取消与预算耗尽均不能记为成功。 */
    public void check() throws InterruptedException {
        if (cancelled.get() || finished.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("turn interrupted");
        }
        if (parent != null) {
            parent.check();
        }
        if (remainingNanos() == 0) {
            throw new BudgetExceededException("Turn time budget exceeded");
        }
    }

    /** 结束作用域并撤销派生任务的继续执行权；未退出子账户的余额在其退出后归还，不丢失实际费用。 */
    @Override
    public void close() {
        if (finished.compareAndSet(false, true)) {
            budget.retire();
        }
    }

    private static int limit(TurnConfig config, String name, int fallback, int ceiling) {
        String value = config.attributes().get(name);
        if (value == null) {
            return fallback;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
        return parsed == 0 ? fallback : Math.min(ceiling, parsed);
    }
}
