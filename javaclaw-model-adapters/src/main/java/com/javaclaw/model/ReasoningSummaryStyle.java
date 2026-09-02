package com.javaclaw.model;

/** OpenAI Responses reasoning summary 的公开粒度。 */
public enum ReasoningSummaryStyle {
    /** 由 Provider 选择。 */
    AUTO,
    /** 简短摘要。 */
    CONCISE,
    /** 详细摘要；仍不暴露隐藏推理链。 */
    DETAILED
}
