package com.javaclaw.runtime;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnId;

/** Provider 无关的模型端口。 */
public interface ModelGateway {
    /**
     * 返回端点能力。
     *
     * @param modelId 端点标识
     * @return 能力快照
     */
    ModelCapabilities capabilities(String modelId);

    /**
     * 执行无 opaque state 的调用。
     *
     * @param turnId Turn
     * @param invocation 调用
     * @param events 受背压控制的事件 sink
     * @param cancellation 取消信号
     * @return 完整结果
     * @throws Exception Provider 或映射失败
     */
    ModelInvocationResult invoke(
            TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation)
            throws Exception;
}
