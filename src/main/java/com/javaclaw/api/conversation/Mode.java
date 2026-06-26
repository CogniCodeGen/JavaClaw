package com.javaclaw.api.conversation;

/**
 * 交互模式的基础契约（UI 无关）。
 *
 * <p>所有模式（对话类 / 动作类）共有的元数据和生命周期方法。具体交互能力由子接口定义：
 * <ul>
 *   <li>{@link ConversationMode}：消息流式对话类（普通聊天 / 规划讨论）</li>
 *   <li>{@link ActionMode}：触发独立窗口或动作（托管任务）</li>
 * </ul>
 *
 * <p>模式由 {@link ModeRegistry} 统一注册，UI 层按照 {@link #placement()} 决定渲染位置。
 * 工作区切换时 {@link #reload()} 被调用；应用退出时 {@link #shutdown()} 被调用。</p>
 *
 * <p>本接口不依赖任何 UI 框架，Web / 命令行 / JavaFX 等任何前端均可消费。</p>
 */
public interface Mode {

    /**
     * 模式唯一标识（小写英文，例如 "chat" / "plan" / "task"）。
     *
     * <p>用于按配置禁用、持久化当前选中模式 id 等场景。</p>
     */
    String id();

    /** 显示名（可含图标和中文，例如 "💬 对话" / "⤳ 规划" / "⚡ 任务"） */
    String displayName();

    /** 可选：提示文案（UI 可用作 Tooltip） */
    default String tooltip() {
        return null;
    }

    /** 渲染位置语义（UI 层可自由解释） */
    Placement placement();

    /** 能力声明；UI 按此决定是否显示附件按钮等控件 */
    default Capabilities capabilities() {
        return Capabilities.defaults();
    }

    /**
     * 工作区切换后重建模式依赖的内部资源。
     *
     * <p>默认空实现：不依赖工作区资源的模式无需覆盖。</p>
     */
    default void reload() {
    }

    /**
     * 应用退出时关闭模式持有的资源。
     *
     * <p>默认空实现：由模式自行决定是否释放。</p>
     */
    default void shutdown() {
    }
}
