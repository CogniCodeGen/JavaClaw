package com.javaclaw.application.turn;

/**
 * 一轮业务流水线中的命名步骤。
 *
 * <p>步骤按声明顺序执行，可修改本轮专属状态，但不得缓存状态到下一轮。异常会终止后续步骤并
 * 由 {@link TurnPipeline} 附加步骤名称后上抛。</p>
 */
public record TurnStage<S>(String id, Action<S> action) {

    public TurnStage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("TurnStage id 不能为空");
        }
        action = java.util.Objects.requireNonNull(action, "action");
    }

    void execute(S state) throws Exception {
        action.execute(state);
    }

    @FunctionalInterface
    public interface Action<S> {
        void execute(S state) throws Exception;
    }
}
