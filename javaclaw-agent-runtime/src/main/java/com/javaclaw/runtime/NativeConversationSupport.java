package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;

/** 能恢复 Provider opaque conversation state 的可选模型扩展。 */
public interface NativeConversationSupport {
    /**
     * 使用持久可见历史补齐旧格式缺少的 replay 输入；只能进行本地转换，不调用 Provider。
     *
     * @param modelId 精确模型路由
     * @param state 旧 opaque 状态，原始持久记录保持不变
     * @param coveredMessages 截至该状态覆盖位置的完整可见历史
     * @return 保留 opaque 输出的可续接状态
     */
    default ProviderState restoreCoveredState(
            String modelId, ProviderState state, java.util.List<ModelMessage> coveredMessages) {
        throw new UnsupportedOperationException("Provider 不支持旧状态输入恢复");
    }

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
