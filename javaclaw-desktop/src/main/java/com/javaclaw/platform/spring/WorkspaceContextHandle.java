package com.javaclaw.platform.spring;

import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.runtime.WorkspaceContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 已刷新工作区子 Context 的类型安全生命周期句柄。 */
public final class WorkspaceContextHandle implements AutoCloseable {

    private final AnnotationConfigApplicationContext context;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    WorkspaceContextHandle(AnnotationConfigApplicationContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public WorkspaceContext workspace() {
        return context.getBean(WorkspaceContext.class);
    }

    public <T> T bean(Class<T> type) {
        if (closed.get()) {
            throw new IllegalStateException("工作区 Context 已关闭: " + workspaceId());
        }
        return context.getBean(type);
    }

    public boolean isClosed() {
        return closed.get();
    }

    private String workspaceId() {
        return context.getBean(WorkspaceContext.class).workspaceId();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        context.getBeansOfType(TaskScope.class).values().forEach(TaskScope::close);
        context.close();
    }
}
