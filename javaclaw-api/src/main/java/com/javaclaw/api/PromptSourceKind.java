package com.javaclaw.api;

/** Prompt manifest 中可审计来源的稳定类别。 */
public enum PromptSourceKind {
    /** Provider 无关的模型基础指令。 */
    MODEL_BASE,
    /** 平台安全和行为约束。 */
    PLATFORM,
    /** 精确 Agent Role 开发者指令。 */
    AGENT_ROLE,
    /** 全局或项目 AGENTS 层级约定。 */
    PROJECT_INSTRUCTION,
    /** 本 Turn 实际可用能力摘要。 */
    RUNTIME_CAPABILITIES,
    /** 已发布并冻结的 Skill 指令。 */
    SKILL,
    /** 带来源的上下文数据，不参与系统授权。 */
    CONTEXT
}
