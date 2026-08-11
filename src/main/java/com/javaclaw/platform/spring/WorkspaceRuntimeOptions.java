package com.javaclaw.platform.spring;

import com.javaclaw.browser.PlaywrightBrowserManager;

import java.util.Objects;
import java.util.Set;

/** 创建工作区子 Context 所需、但不属于领域状态的宿主回调。 */
public record WorkspaceRuntimeOptions(
        PlaywrightBrowserManager browserManager,
        Runnable openTaskView,
        Runnable openWorkflowView,
        Runnable closeWorkflowView,
        Set<String> disabledModes) {

    public WorkspaceRuntimeOptions {
        browserManager = Objects.requireNonNull(browserManager, "browserManager");
        openTaskView = Objects.requireNonNull(openTaskView, "openTaskView");
        openWorkflowView = Objects.requireNonNull(openWorkflowView, "openWorkflowView");
        closeWorkflowView = Objects.requireNonNull(closeWorkflowView, "closeWorkflowView");
        disabledModes = disabledModes == null ? Set.of() : Set.copyOf(disabledModes);
    }
}
