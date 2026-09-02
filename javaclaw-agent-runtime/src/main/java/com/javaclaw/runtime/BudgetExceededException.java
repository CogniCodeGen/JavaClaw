package com.javaclaw.runtime;

/** Turn 的 token、工具、子 Thread 或墙钟预算耗尽。 */
public final class BudgetExceededException extends RuntimeException {
    /**
     * 创建预算异常。
     *
     * @param message 不包含敏感数据的原因
     */
    public BudgetExceededException(String message) {
        super(message);
    }
}
