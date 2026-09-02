package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;

/** 从持久 Item、Profile 和已确认项目指令组装窗口的端口。 */
@FunctionalInterface
public interface ContextAssembler {
    /**
     * 组装未压缩窗口；不得把外部资料当作系统指令。
     *
     * @param command Turn 命令
     * @param cancellation 取消信号
     * @return 顺序窗口
     * @throws Exception 读取失败
     */
    ConversationWindow assemble(TurnExecutionCommand command, CancellationToken cancellation) throws Exception;
}
