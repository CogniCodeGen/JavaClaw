package com.javaclaw.runtime;

import java.util.Objects;

/**
 * 一次压缩的明确目标；固定指令与工具定义不能由摘要删除。
 *
 * @param command 冻结执行命令
 * @param window 原窗口
 * @param targetInputTokens 本次请求的目标总输入 token
 * @param fixedInputTokens 不可压缩的指令和工具开销
 */
public record CompactionRequest(
        TurnExecutionCommand command, ConversationWindow window, long targetInputTokens, long fixedInputTokens) {
    /** 校验目标，禁止通过负数扩大预算。 */
    public CompactionRequest {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(window, "window");
        if (targetInputTokens < 1 || fixedInputTokens < 0) {
            throw new IllegalArgumentException("invalid compaction target");
        }
    }
}
