package com.javaclaw.desktop.settings;

import java.util.Optional;

import javafx.scene.Node;

import com.javaclaw.api.Workspace;

/** 管理中心页面的统一激活、脏状态和离页保护契约。 */
public interface ManagedSettingsPage {
    /** @return 页面根节点 */
    Node content();

    /**
     * 返回固定在管理中心底部的页面动作栏。
     *
     * <p>页面没有顶层提交动作时返回空；嵌套资源分区的局部动作不应移入此槽位。
     *
     * @return 页面顶层动作栏
     */
    default Optional<Node> actionContent() {
        return Optional.empty();
    }

    /** 页面进入前刷新所需状态；实现不得阻塞 JavaFX Thread。 */
    void activate();

    /** 页面离开或窗口隐藏时停止非必要刷新。 */
    default void deactivate() {}

    /** @return 当前是否存在未保存草稿 */
    boolean dirty();

    /**
     * 设置中心 Workspace 作用域发生变化。
     *
     * <p>全局页面可以忽略该回调；Workspace 页面必须使用此处传入的快照，不得在后台重新读取主窗口选择。
     *
     * @param workspace 已冻结的活动 Workspace；无可用作用域时为空
     */
    default void workspaceChanged(Optional<Workspace> workspace) {}

    /** @return 是否有不应被 Workspace 切换中断的写操作 */
    default boolean pending() {
        return false;
    }

    /** 用户尝试离开脏页面时显示可操作提示。 */
    void warnUnsavedChanges();

    /** 丢弃本地草稿并恢复最近一次权威状态。 */
    void discardDraft();

    /** 释放页面级订阅和未完成的本地任务。 */
    default void dispose() {}
}
