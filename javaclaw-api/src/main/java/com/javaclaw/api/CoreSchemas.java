package com.javaclaw.api;

/** Core Item 使用的稳定 schema 标识。 */
public final class CoreSchemas {
    /** 用户、系统或模型消息。 */
    public static final String MESSAGE = "javaclaw.core/message@1";
    /** 模型请求的工具调用。 */
    public static final String TOOL_CALL = "javaclaw.core/tool-call@1";
    /** 工具执行结果。 */
    public static final String TOOL_RESULT = "javaclaw.core/tool-result@1";
    /** 受控命令执行摘要。 */
    public static final String COMMAND = "javaclaw.core/command@1";
    /** 文件变更摘要。 */
    public static final String FILE_CHANGE = "javaclaw.core/file-change@1";
    /** 审批请求或决议。 */
    public static final String APPROVAL = "javaclaw.core/approval@1";
    /** 用户输入请求或结果。 */
    public static final String INPUT = "javaclaw.core/input@1";
    /** 子 Thread 生命周期。 */
    public static final String SUBAGENT = "javaclaw.core/subagent@1";
    /** 上下文压缩结果。 */
    public static final String COMPACTION = "javaclaw.core/compaction@1";
    /** 可恢复副作用凭据。 */
    public static final String EFFECT_RECEIPT = "javaclaw.core/effect-receipt@1";
    /** 结构化错误。 */
    public static final String ERROR = "javaclaw.core/error@1";

    private CoreSchemas() {}
}
