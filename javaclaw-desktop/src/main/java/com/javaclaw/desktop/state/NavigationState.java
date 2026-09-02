package com.javaclaw.desktop.state;

import java.util.Objects;
import java.util.Optional;

/**
 * Desktop 导航与响应式面板快照。
 *
 * @param page 当前主页面
 * @param extensionViewId 扩展页面标识；非扩展页面为空
 * @param sidebarVisible 会话侧栏是否可见
 * @param progressVisible 审批与进度侧栏是否可见
 */
public record NavigationState(
        Page page, Optional<String> extensionViewId, boolean sidebarVisible, boolean progressVisible) {
    /** 校验页面与扩展视图关联。 */
    public NavigationState {
        Objects.requireNonNull(page, "page");
        extensionViewId = Objects.requireNonNull(extensionViewId, "extensionViewId")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        if ((page == Page.EXTENSION) != extensionViewId.isPresent()) {
            throw new IllegalArgumentException("extension page requires a view id");
        }
    }

    /** @return 默认聊天导航 */
    public static NavigationState initial() {
        return new NavigationState(Page.CHAT, Optional.empty(), true, true);
    }

    /** @return 切换侧栏后的快照 */
    public NavigationState toggleSidebar() {
        return new NavigationState(page, extensionViewId, !sidebarVisible, progressVisible);
    }

    /** @return 切换进度栏后的快照 */
    public NavigationState toggleProgress() {
        return new NavigationState(page, extensionViewId, sidebarVisible, !progressVisible);
    }

    /** 可显示页面。 */
    public enum Page {
        /** Thread 对话。 */
        CHAT,
        /** 平台渲染的扩展页面。 */
        EXTENSION
    }
}
