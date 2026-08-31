package com.javaclaw.agent.runtime;

/** 显式传递的线程安全预算账户。子账户从父账户预留额度，不会产生新的调用或 token 额度。 同一账户树共享锁，调用计数、费用及归还一次提交，避免并行子任务重复使用未消费额度。 */
public final class BudgetAccount implements AutoCloseable {
    private final Object lock;
    private final BudgetAccount parent;
    private final int maximumCalls;
    private final long maximumTokens;
    private int calls;
    private long tokens;
    private int reservedCalls;
    private long reservedTokens;
    private int children;
    private boolean closed;
    private boolean retiring;

    /** 创建根账户；调用次数须为 1–1000，token 须为 1–10,000,000，禁止用零表达无限预算。 */
    public BudgetAccount(int maximumCalls, long maximumTokens) {
        this(null, maximumCalls, maximumTokens);
    }

    private BudgetAccount(BudgetAccount parent, int maximumCalls, long maximumTokens) {
        if (maximumCalls < 1 || maximumCalls > 1_000 || maximumTokens < 1 || maximumTokens > 10_000_000) {
            throw new IllegalArgumentException("budget limits must be positive and bounded");
        }
        this.parent = parent;
        this.lock = parent == null ? new Object() : parent.lock;
        this.maximumCalls = maximumCalls;
        this.maximumTokens = maximumTokens;
    }

    /** 为子任务预留有限额度；额度不足立即拒绝，调用方结束子任务后必须关闭子账户以归还余额。 */
    public BudgetAccount allocate(int modelCalls, long tokenLimit) {
        synchronized (lock) {
            requireOpen();
            BudgetAccount child = new BudgetAccount(this, modelCalls, tokenLimit);
            if (modelCalls > remainingCalls() || tokenLimit > remainingTokens()) {
                throw new BudgetExceededException("insufficient parent budget for subagent");
            }
            reservedCalls += modelCalls;
            reservedTokens += tokenLimit;
            children++;
            return child;
        }
    }

    /** 在真正发出请求之前消费一次模型调用并返回账户树内唯一的调用序号；失败的请求仍占用预算。 */
    public int consumeCall(String purpose) {
        synchronized (lock) {
            requireOpen();
            if (remainingCalls() < 1 || remainingTokens() < 1) {
                throw new BudgetExceededException("Turn model-call budget exceeded while invoking " + purpose);
            }
            calls++;
            int ordinal = calls;
            for (BudgetAccount ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
                ancestor.reservedCalls--;
                ancestor.calls++;
                ordinal = ancestor.calls;
            }
            return ordinal;
        }
    }

    /** 记入模型报告的输入与输出 token；超限的实际费用仍被记录，随后禁止继续调用，不重复累加推理子计数。 */
    public void chargeTokens(long amount) {
        synchronized (lock) {
            requireOpen();
            if (amount < 0) {
                throw new IllegalArgumentException("token usage must be non-negative");
            }
            // 每层都按自身尚未消耗的预留额结算；子账户超额报告不能留下祖先的幽灵预留。
            for (BudgetAccount account = this; account != null; account = account.parent) {
                long reservationUsed = Math.min(amount, Math.max(0, account.maximumTokens - account.tokens));
                account.tokens = Math.addExact(account.tokens, amount);
                if (account.parent != null) {
                    account.parent.reservedTokens -= reservationUsed;
                }
            }
        }
    }

    /** 返回尚未消费且未分配给子任务的模型调用次数。 */
    public int remainingCalls() {
        synchronized (lock) {
            return closed || retiring ? 0 : Math.max(0, maximumCalls - calls - reservedCalls);
        }
    }

    /** 返回尚未消费且未分配给子任务的 token 数；超过实际上限时返回零。 */
    public long remainingTokens() {
        synchronized (lock) {
            return closed || retiring ? 0 : Math.max(0, maximumTokens - tokens - reservedTokens);
        }
    }

    /** 返回本账户及其子账户累计发出的模型请求数。 */
    public int usedCalls() {
        synchronized (lock) {
            return calls;
        }
    }

    /** 返回本账户及其子账户已计费 token 数，不将未知费用伪装为确定值。 */
    public long usedTokens() {
        synchronized (lock) {
            return tokens;
        }
    }

    private void requireOpen() {
        if (closed || retiring) {
            throw new IllegalStateException("budget account is closed");
        }
    }

    /** 执行作用域结束后禁止新调用；仍在退出的子任务继续结算，最后一个子账户关闭后才归还余额。 不提前释放预留额度，避免取消与模型退出并发时另一任务重复使用尚未结算的预算。 */
    public void retire() {
        synchronized (lock) {
            retiring = true;
            if (children == 0) {
                close();
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (children != 0) {
                throw new IllegalStateException("close child budgets before their parent");
            }
            if (parent != null) {
                parent.reservedCalls -= Math.max(0, maximumCalls - calls);
                parent.reservedTokens -= Math.max(0, maximumTokens - tokens);
                parent.children--;
            }
            closed = true;
            if (parent != null && parent.retiring && parent.children == 0) {
                parent.close();
            }
        }
    }
}
