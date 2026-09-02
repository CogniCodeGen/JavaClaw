package com.javaclaw.api;

/** Turn 在安全检查点观察到取消时抛出的非重试异常。 */
public final class TurnCancelledException extends RuntimeException {
    /**
     * 创建取消异常。
     *
     * @param reason 脱敏后的取消原因
     */
    public TurnCancelledException(String reason) {
        super(Preconditions.text(reason, "reason"));
    }
}
