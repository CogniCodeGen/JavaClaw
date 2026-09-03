package com.javaclaw.desktop.component;

/** 危险动作执行前的确认策略；返回 {@code false} 时必须保持无副作用。 */
@FunctionalInterface
public interface DangerConfirmationPolicy {
    /**
     * 请求用户确认。
     *
     * @param request 包含动作名称、后果和最终按钮文字的请求
     * @return 仅在用户明确确认后返回 {@code true}
     */
    boolean confirm(DangerConfirmationRequest request);
}
