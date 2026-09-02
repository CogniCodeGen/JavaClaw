package com.javaclaw.extension.spi;

import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpElicitationRequest;
import com.javaclaw.api.McpSamplingRequest;

/** MCP 远端反向请求的受治理平台边界。 */
public interface McpClientInteractionPort {
    /**
     * 请求用户输入；不得收集 Secret。
     *
     * @param request 已校验 Schema 的请求
     * @param cancellation 取消信号
     * @return 用户响应；拒绝或超时时为空
     * @throws Exception 持久交互失败
     */
    Optional<CanonicalPayload> elicit(McpElicitationRequest request, CancellationToken cancellation) throws Exception;

    /**
     * 发起受预算 Harness sampling；结果仍是不可信外部数据。
     *
     * @param request 无 system、工具或自动 Context 的请求
     * @param cancellation 取消信号
     * @return 采样结果；拒绝时为空
     * @throws Exception 治理或模型调用失败
     */
    Optional<CanonicalPayload> sample(McpSamplingRequest request, CancellationToken cancellation) throws Exception;
}
