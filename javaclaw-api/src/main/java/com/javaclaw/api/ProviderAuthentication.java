package com.javaclaw.api;

/** Provider 连接使用的鉴权方式。 */
public enum ProviderAuthentication {
    /** 使用 Secret Vault 中绑定的 API Key。 */
    API_KEY,
    /** 不发送鉴权信息；只允许显式配置的 OpenAI-compatible 端点。 */
    NONE
}
