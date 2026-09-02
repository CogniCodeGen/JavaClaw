package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;

/** 能恢复 Provider opaque conversation state 的可选模型扩展。 */
public interface NativeConversationSupport {
    /**
     * 在 Provider 原生状态后继续一次调用。
     *
     * @param turnId Turn
     * @param invocation 新输入
     * @param state 已持久化 opaque state
     * @param events 受背压控制的事件 sink
     * @param cancellation 取消信号
     * @return 完整结果和新的 state
     * @throws Exception Provider 或状态校验失败
     */
    ModelInvocationResult invokeContinuing(
            TurnId turnId,
            ModelInvocation invocation,
            ProviderState state,
            ModelEventSink events,
            CancellationToken cancellation)
            throws Exception;
}
