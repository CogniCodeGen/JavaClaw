package com.javaclaw.api;

/** OpenAI Responses reasoning summary 的输出详细程度。 */
public enum ProviderReasoningSummary {
    /** 由 Provider 选择合适的详细程度。 */
    AUTO,
    /** 请求简短摘要。 */
    CONCISE,
    /** 请求详细摘要。 */
    DETAILED
}
