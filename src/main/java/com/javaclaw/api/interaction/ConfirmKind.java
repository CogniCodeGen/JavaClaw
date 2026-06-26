package com.javaclaw.api.interaction;

/**
 * 用户确认的强度等级。
 *
 * <p>UI 适配器根据强度选择对应的交互形式：</p>
 * <ul>
 *   <li>{@link #NOTIFY}：非阻塞通知/Toast，自动视为"放行"，不等待用户响应</li>
 *   <li>{@link #CONFIRM}：标准确认对话框（OK / Cancel）</li>
 *   <li>{@link #DOUBLE_CONFIRM}：需要用户键入关键词二次确认的高风险操作</li>
 * </ul>
 */
public enum ConfirmKind {
    NOTIFY,
    CONFIRM,
    DOUBLE_CONFIRM
}
