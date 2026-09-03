package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次不持久化的 Provider 模型目录发现操作。
 *
 * @param operationId 服务端随机生成的临时标识
 * @param revision 操作状态版本；状态变化时递增
 * @param endpointId 精确 Provider 标识
 * @param endpointRevision 精确 Provider 版本
 * @param state 当前状态
 * @param result 成功时的有界目录结果
 * @param failureCode 失败时的脱敏稳定错误码
 * @param createdAt 创建时间
 * @param updatedAt 最近状态变化时间
 */
public record ProviderModelDiscoveryOperation(
        String operationId,
        long revision,
        String endpointId,
        long endpointRevision,
        ProviderModelDiscoveryOperationState state,
        Optional<ProviderModelDiscoveryResult> result,
        Optional<String> failureCode,
        Instant createdAt,
        Instant updatedAt) {
    /** 校验操作状态与结果的不变量。 */
    public ProviderModelDiscoveryOperation {
        operationId = Preconditions.identifier(operationId, "operationId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        if (endpointRevision < 1) {
            throw new IllegalArgumentException("endpointRevision must be positive");
        }
        Objects.requireNonNull(state, "state");
        result = Objects.requireNonNull(result, "result");
        failureCode = Objects.requireNonNull(failureCode, "failureCode")
                .map(value -> Preconditions.identifier(value, "failureCode"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        boolean succeeded = state == ProviderModelDiscoveryOperationState.SUCCEEDED;
        boolean failed = state == ProviderModelDiscoveryOperationState.FAILED;
        if (result.isPresent() != succeeded || failureCode.isPresent() != failed) {
            throw new IllegalArgumentException("operation result does not match state");
        }
        if (result.isPresent()) {
            ProviderModelDiscoveryResult value = result.orElseThrow();
            if (!endpointId.equals(value.endpointId()) || endpointRevision != value.endpointRevision()) {
                throw new IllegalArgumentException("operation result endpoint does not match operation");
            }
        }
    }

    /**
     * 返回操作是否已经结束。
     *
     * @return 成功、失败或取消时为 true
     */
    public boolean terminal() {
        return state != ProviderModelDiscoveryOperationState.RUNNING;
    }
}
