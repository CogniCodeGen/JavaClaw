package com.javaclaw.api;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 一次非推理模型目录发现结果。
 *
 * @param endpointId Provider 稳定标识
 * @param endpointRevision 实际读取的精确版本
 * @param candidates 最多 1000 个模型候选
 * @param truncated 远端目录或响应超过边界时为 true
 * @param discoveredAt 服务端完成发现的时间
 */
public record ProviderModelDiscoveryResult(
        String endpointId,
        long endpointRevision,
        List<ProviderModelDiscoveryCandidate> candidates,
        boolean truncated,
        Instant discoveredAt) {
    /** 复制候选并拒绝重复模型标识。 */
    public ProviderModelDiscoveryResult {
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        endpointRevision = Preconditions.positive(endpointRevision, "endpointRevision");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (candidates.size() > 1_000) {
            throw new IllegalArgumentException("model discovery result must not exceed 1000 candidates");
        }
        HashSet<String> modelIds = new HashSet<>();
        if (candidates.stream().anyMatch(candidate -> !modelIds.add(candidate.modelId()))) {
            throw new IllegalArgumentException("model discovery result contains duplicate model IDs");
        }
        Objects.requireNonNull(discoveredAt, "discoveredAt");
    }
}
