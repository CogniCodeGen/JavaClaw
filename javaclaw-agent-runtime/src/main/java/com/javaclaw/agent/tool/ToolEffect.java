package com.javaclaw.agent.tool;

/** 工具的业务副作用边界，独立于风险和文件沙箱；未知效果不能被 PLAN 当作只读操作。 */
public enum ToolEffect {
    READ_ONLY,
    MUTATION,
    UNKNOWN
}
