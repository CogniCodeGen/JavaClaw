package com.javaclaw.model;

/** Spring AI 通用适配层支持的 Provider。 */
public enum SpringAiProvider {
    /** OpenAI Chat Completions 或兼容端点。 */
    OPENAI_COMPATIBLE,
    /** Anthropic Messages。 */
    ANTHROPIC,
    /** Google Gen AI。 */
    GOOGLE_GENAI
}
