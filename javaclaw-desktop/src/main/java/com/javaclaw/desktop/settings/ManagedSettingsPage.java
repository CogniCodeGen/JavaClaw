package com.javaclaw.desktop.settings;

import javafx.scene.Node;

/** 管理中心页面的统一激活、脏状态和离页保护契约。 */
public interface ManagedSettingsPage {
    /** @return 页面根节点 */
    Node content();

    /** 页面进入前刷新所需状态；实现不得阻塞 JavaFX Thread。 */
    void activate();

    /** 页面离开或窗口隐藏时停止非必要刷新。 */
    default void deactivate() {}

    /** @return 当前是否存在未保存草稿 */
    boolean dirty();

    /** 用户尝试离开脏页面时显示可操作提示。 */
    void warnUnsavedChanges();

    /** 丢弃本地草稿并恢复最近一次权威状态。 */
    void discardDraft();

    /** 释放页面级订阅和未完成的本地任务。 */
    default void dispose() {}
}
