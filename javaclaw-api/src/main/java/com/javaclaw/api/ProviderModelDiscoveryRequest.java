package com.javaclaw.api;

/**
 * 对已保存 Provider 精确版本执行模型目录发现的请求。
 *
 * @param endpointId Provider 稳定标识
 * @param endpointRevision 精确不可变版本
 */
public record ProviderModelDiscoveryRequest(String endpointId, long endpointRevision) {
    /** 校验精确 Provider 版本。 */
    public ProviderModelDiscoveryRequest {
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        endpointRevision = Preconditions.positive(endpointRevision, "endpointRevision");
    }
}
