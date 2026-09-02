package com.javaclaw.api;

/** MCP 受限 sampling 消息角色；刻意不提供 SYSTEM。 */
public enum McpSamplingRole {
    /** 用户消息。 */
    USER,
    /** 助手消息。 */
    ASSISTANT
}
