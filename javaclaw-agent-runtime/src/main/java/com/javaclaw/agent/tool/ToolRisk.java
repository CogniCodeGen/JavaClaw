package com.javaclaw.agent.tool;

/** 风险等级由低到高排列；审批逻辑依赖此顺序，不应为排版调整枚举顺序。 */
public enum ToolRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
