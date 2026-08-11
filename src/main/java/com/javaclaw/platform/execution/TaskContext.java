package com.javaclaw.platform.execution;

import java.util.Objects;
import java.util.Optional;

/**
 * 托管任务的只读执行上下文。
 *
 * <p>上下文通过 {@link ScopedValue} 绑定到任务调用栈，不继承到无关线程。插件和
 * 平台适配器可显式接收本对象；业务代码不得缓存它超过任务生命周期。</p>
 */
public record TaskContext(String taskId, CancellationToken cancellation) {

    private static final ScopedValue<TaskContext> CURRENT = ScopedValue.newInstance();

    public TaskContext {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public static Optional<TaskContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    static <T> T callWith(TaskContext context, ScopedValue.CallableOp<T, Exception> task)
            throws Exception {
        return ScopedValue.where(CURRENT, context).call(task);
    }
}
