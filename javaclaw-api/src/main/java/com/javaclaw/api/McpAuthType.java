package com.javaclaw.api;

/** MCP 端点认证方式。 */
public enum McpAuthType {
    /** 无认证。 */
    NONE,
    /** Vault 中的 Bearer token。 */
    BEARER,
    /** Vault 中的 API Key。 */
    API_KEY,
    /** OAuth 2.1 Authorization Code + PKCE。 */
    OAUTH_2_1_PKCE
}
