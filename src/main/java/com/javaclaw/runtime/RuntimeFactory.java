package com.javaclaw.runtime;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.platform.spring.WorkspaceRuntimeOptions;
import com.javaclaw.platform.spring.WorkspaceSpringContextFactory;

import java.util.Objects;
import java.util.Set;

/** 通过 Spring 子 Context 创建完整工作区运行时的唯一工厂。 */
public final class RuntimeFactory {

    private final WorkspaceSpringContextFactory contextFactory;
    private final WorkspaceRuntimeOptions options;

    public RuntimeFactory(WorkspaceSpringContextFactory contextFactory,
                          PlaywrightBrowserManager browserManager,
                          Runnable openTaskView,
                          Runnable openWorkflowView,
                          Runnable closeWorkflowView,
                          Set<String> disabledModes) {
        this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
        this.options = new WorkspaceRuntimeOptions(browserManager, openTaskView,
                openWorkflowView, closeWorkflowView, disabledModes);
    }

    /**
     * 事务式刷新子 Context。任一 Bean 构造失败时 Spring 会关闭已创建对象，调用方不会
     * 收到半初始化的工作区运行时。
     */
    public WorkspaceRuntime create(WorkspaceContext context) {
        return new WorkspaceRuntime(contextFactory.create(context, options));
    }
}
