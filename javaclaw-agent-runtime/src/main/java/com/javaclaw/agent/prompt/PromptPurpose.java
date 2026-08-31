package com.javaclaw.agent.prompt;

/** 模型调用用途；用途决定模板与输出契约，不会创建第二套 Agent Runtime。 */
public enum PromptPurpose {
    CHAT,
    PLAN,
    LOOP_EXECUTION,
    LOOP_EVALUATION,
    WORKFLOW_NODE,
    SDD,
    SCHEDULE,
    SUBAGENT,
    REVIEW,
    COMPACTION,
    MEMORY_EXTRACTION,
    MEMORY_CONSISTENCY,
    SKILL_EXTRACTION,
    BROWSER,
    OCR,
    PROMPT_OPTIMIZATION,
    MCP_SAMPLING
}
