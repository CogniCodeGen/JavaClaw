package com.javaclaw.mode;

import com.javaclaw.api.conversation.ActionMode;
import com.javaclaw.api.conversation.Capabilities;
import com.javaclaw.api.conversation.Placement;

/**
 * 托管任务模式的 {@link ActionMode} 适配器。
 *
 * <p>本身不关心 UI 类型——{@link #open()} 委托给一个 {@link Runnable}，由应用层注入
 * 具体的"如何打开任务视图"逻辑。这样 JavaFX / Web / CLI 都可以实现自己的 open 行为
 * 而不需要本类感知。</p>
 */
public final class TaskMode implements ActionMode {

    private final Runnable opener;

    /**
     * @param opener 激活此模式时执行的动作（由 UI 层提供，如打开一个 Stage 或导航到某路由）
     */
    public TaskMode(Runnable opener) {
        this.opener = opener;
    }

    @Override public String id() { return "task"; }
    @Override public String displayName() { return "⚡ 任务"; }
    @Override public String tooltip() { return "托管任务：规划 + 子任务循环 + 验收闭环"; }
    @Override public Placement placement() { return Placement.SIDEBAR_ACTION; }

    @Override public Capabilities capabilities() { return Capabilities.minimal(); }

    @Override
    public void open() {
        if (opener != null) {
            opener.run();
        }
    }

    @Override
    public void shutdown() {
        // TaskManager 单例由 App 层统一关闭
    }
}
