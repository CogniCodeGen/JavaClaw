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
    private int reservedToolCalls;
    private long reservedModelInput;
    private long reservedModelOutput;

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
     * <p>墙钟截止时间始终以 Turn 创建时间为基准，重启不会重新获得预算。已观察用量允许超过估算上限，恢复后 checkpoint 阻止继续执行；不得截断已发生账单。
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
        if (consumedToolCalls < 0 || consumedToolCalls > limit.toolCalls()) {
            throw new BudgetExceededException("recovered tool calls exceed budget");
        }
        inputTokens = usage.inputTokens();
        outputTokens = usage.generatedTokens();
        toolCalls = consumedToolCalls;
    }

    /**
     * 扣减模型 usage。
     *
     * @param usage 本轮 usage
     */
    public synchronized void consume(ModelUsage usage) {
        long nextInput = Math.addExact(inputTokens, usage.inputTokens());
        long nextOutput = Math.addExact(outputTokens, usage.generatedTokens());
        requireAtMost(nextInput, limit.inputTokens(), "input token budget exceeded");
        requireAtMost(nextOutput, limit.outputTokens(), "output token budget exceeded");
        inputTokens = nextInput;
        outputTokens = nextOutput;
    }

    /**
     * 记录已经发生的 Provider 用量；超额不能抹掉账单，调用方持久化后再停止 Turn。
     *
     * @param usage 实际新增用量
     * @return 记录后仍在冻结预算内时为 true
     */
    public synchronized boolean recordObservedUsage(ModelUsage usage) {
        inputTokens = Math.addExact(inputTokens, usage.inputTokens());
        outputTokens = Math.addExact(outputTokens, usage.generatedTokens());
        return inputTokens <= limit.inputTokens() && outputTokens <= limit.outputTokens();
    }

    /**
     * 在外部压缩调用期间预留费用与下一轮输入余量，阻止并发子任务占用同一份额度。
     *
     * @param inputTokens 有限输入预留
     * @param outputTokens 有限生成预留
     * @return 完成或失败后必须释放的预留；释放不抹掉实际用量
     */
    public synchronized ModelReservation reserveModel(long inputTokens, long outputTokens) {
        if (inputTokens < 1 || outputTokens < 1) {
            throw new IllegalArgumentException("model reservation must be positive");
        }
        requireAtMost(inputTokens, remainingInputTokens(), "model input reservation exceeds budget");
        requireAtMost(outputTokens, remainingOutputTokens(), "model output reservation exceeds budget");
        reservedModelInput = Math.addExact(reservedModelInput, inputTokens);
        reservedModelOutput = Math.addExact(reservedModelOutput, outputTokens);
        return new ModelReservation(inputTokens, outputTokens);
    }

    /** 单次调用预留的所有权；close 幂等且只在账户锁内归还尚未消费的额度。 */
    public final class ModelReservation implements AutoCloseable {
        private final long input;
        private final long output;
        private boolean closed;

        private ModelReservation(long input, long output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void close() {
            synchronized (BudgetAccount.this) {
                if (!closed) {
                    reservedModelInput -= input;
                    reservedModelOutput -= output;
                    closed = true;
                }
            }
        }
    }

    /** 扣减一次工具调用。 */
    public synchronized void consumeToolCall() {
        if (toolCalls + reservedToolCalls >= limit.toolCalls()) {
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
        validateReservation(reservation);
        applyReservation(reservation);
    }

    /**
     * 锁内校验预算，先提交本地持久化预留，再发布内存扣减。
     *
     * <p>提交失败不扣减，提交成功后崩溃由持久预留恢复。回调只能执行本地事务，不得调用模型或外部工具。
     *
     * @param reservation 有限预留量
     * @param commit 创建子 Thread 与预留账本的同一事务
     * @param <T> 事务返回类型
     * @return 已提交结果
     * @throws Exception 事务失败，预算保持原值
     */
    public synchronized <T> T reserveChild(ReservedChildBudget reservation, java.util.concurrent.Callable<T> commit)
            throws Exception {
        validateReservation(reservation);
        T result = commit.call();
        applyReservation(reservation);
        return result;
    }

    private void validateReservation(ReservedChildBudget reservation) {
        Objects.requireNonNull(reservation, "reservation");
        if (childThreads >= Math.min(MAX_DIRECT_CHILDREN, limit.childThreads())) {
            throw new BudgetExceededException("direct child thread budget exceeded");
        }
        requireAtMost(
                Math.addExact(Math.addExact(inputTokens, reservedModelInput), reservation.inputTokens()),
                limit.inputTokens(),
                "child input reservation exceeds parent budget");
        requireAtMost(
                Math.addExact(Math.addExact(outputTokens, reservedModelOutput), reservation.outputTokens()),
                limit.outputTokens(),
                "child output reservation exceeds parent budget");
        requireAtMost(
                (long) toolCalls + reservedToolCalls + reservation.toolCalls(),
                limit.toolCalls(),
                "child tool reservation exceeds parent budget");
    }

    private void applyReservation(ReservedChildBudget reservation) {
        inputTokens += reservation.inputTokens();
        outputTokens += reservation.outputTokens();
        reservedToolCalls += reservation.toolCalls();
        childThreads++;
    }

    /**
     * 检查取消与墙钟截止时间。
     *
     * @param cancellation 取消信号
     */
    public synchronized void checkpoint(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireAtMost(inputTokens, limit.inputTokens(), "observed input usage exceeds budget");
        requireAtMost(outputTokens, limit.outputTokens(), "observed output usage exceeds budget");
        if (!clock.instant().isBefore(deadline)) {
            throw new BudgetExceededException("wall time budget exceeded");
        }
    }

    /**
     * 读取扣除已消费和已预留子任务后的剩余输入 token。
     *
     * @return 非负剩余输入预算
     */
    public synchronized long remainingInputTokens() {
        return Math.max(0, limit.inputTokens() - inputTokens - reservedModelInput);
    }

    /**
     * 返回剩余输出 token，至少为 1 才可继续调用模型。
     *
     * @return 剩余 token
     */
    public synchronized long remainingOutputTokens() {
        return Math.max(0, limit.outputTokens() - outputTokens - reservedModelOutput);
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
