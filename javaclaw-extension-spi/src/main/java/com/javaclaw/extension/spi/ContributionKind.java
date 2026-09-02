package com.javaclaw.extension.spi;

/** Extension Bundle 可声明的贡献点类别。 */
public enum ContributionKind {
    /** 可由 Turn 调用的 Tool。 */
    TOOL,
    /** 上下文贡献器。 */
    CONTEXT,
    /** 多 Turn 编排器。 */
    ORCHESTRATOR,
    /** 持久定时任务。 */
    TIMER,
    /** 允许 Schedule 通过固定 payload 调用的显式命令。 */
    SCHEDULABLE_ACTION,
    /** 有副作用且要求幂等键的命令。 */
    COMMAND,
    /** 无副作用查询。 */
    QUERY,
    /** 声明式页面。 */
    VIEW,
    /** 可检索 Skill。 */
    SKILL,
    /** MCP 端点声明。 */
    MCP,
    /** 生命周期 Hook。 */
    HOOK,
    /** 受控进程外服务。 */
    SERVICE
}
