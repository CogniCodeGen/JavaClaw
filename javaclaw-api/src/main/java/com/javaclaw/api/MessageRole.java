package com.javaclaw.api;

/** Core 消息角色。 */
public enum MessageRole {
    /** 平台固定行为说明。 */
    SYSTEM,
    /** 用户输入。 */
    USER,
    /** 模型输出。 */
    ASSISTANT,
    /** 工具结果回填。 */
    TOOL
}
