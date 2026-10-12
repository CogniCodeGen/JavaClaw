package com.javaclaw.agent;

/**
 * 工具风险等级
 *
 * <p>按操作的不可逆程度分三级，用于 {@link ToolConfirmationManager} 决定确认方式。</p>
 */
public enum ToolRiskLevel {

    /** 仅通知：展示非阻塞 Toast，自动允许执行（如截图、只读查询） */
    NOTIFY,

    /** 标准确认：弹出确认对话框，用户可查看详情后选择继续或取消 */
    CONFIRM,

    /** 二次确认：高风险操作（删除、执行命令），用户需输入"确认"关键词才可放行 */
    DOUBLE_CONFIRM
}
