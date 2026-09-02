package com.javaclaw.api;

/** 平台支持的模型 Provider 适配器类型。 */
public enum ProviderAdapter {
    /** OpenAI Chat Completions 或兼容端点。 */
    OPENAI_COMPATIBLE,
    /** Anthropic Messages。 */
    ANTHROPIC,
    /** Google Gen AI。 */
    GOOGLE_GENAI,
    /** OpenAI Responses 原生端点。 */
    OPENAI_RESPONSES
}
