package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;

/** 在 Harness 决定需要压缩后执行 summary 或 Provider 原生压缩的端口。 */
@FunctionalInterface
public interface ContextCompactor {
    /**
     * 将窗口压入 Turn 输入预算。
     *
     * @param command Turn 命令
     * @param window 原窗口
     * @param gateway 当前模型 gateway，可检查 NativeCompactionSupport
     * @param cancellation 取消信号
     * @return 新窗口与审计 Item
     * @throws Exception 压缩失败
     */
    CompactionOutcome compact(
            TurnExecutionCommand command,
            ConversationWindow window,
            ModelGateway gateway,
            CancellationToken cancellation)
            throws Exception;
}
