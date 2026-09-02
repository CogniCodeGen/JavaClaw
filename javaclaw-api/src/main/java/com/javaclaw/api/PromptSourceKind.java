package com.javaclaw.api;

/** Prompt manifest 中可审计来源的稳定类别。 */
public enum PromptSourceKind {
    /** 随发行版审阅的 Core system instruction。 */
    CORE_TEMPLATE,
    /** 用户选择的精确 Agent Profile system instruction。 */
    AGENT_PROFILE,
    /** 全局或项目 AGENTS 层级约定。 */
    PROJECT_INSTRUCTION,
    /** 已发布并冻结的 Skill 指令。 */
    SKILL,
    /** 扩展在本 Turn 冻结的 Context。 */
    CONTEXT
}
