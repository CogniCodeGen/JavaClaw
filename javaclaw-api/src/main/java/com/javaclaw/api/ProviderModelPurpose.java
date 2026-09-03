package com.javaclaw.api;

/** 单个 Provider 模型在 JavaClaw 中可以承担的用途。 */
public enum ProviderModelPurpose {
    /** 对话、结构化输出和工具调用。 */
    CHAT,
    /** 文本向量嵌入。 */
    EMBEDDING
}
