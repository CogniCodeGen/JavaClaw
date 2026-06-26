package com.javaclaw.api.conversation;

/**
 * 模式在 UI 中的位置语义（由 UI 层自行解释）。
 *
 * <ul>
 *   <li>{@link #TOP_SEGMENT} — 主对话区的模式切换（对话 / 规划）</li>
 *   <li>{@link #SIDEBAR_ACTION} — 侧边栏独立入口（任务管理等）</li>
 *   <li>{@link #CUSTOM} — 自定义渲染</li>
 * </ul>
 */
public enum Placement {
    TOP_SEGMENT,
    SIDEBAR_ACTION,
    CUSTOM
}
