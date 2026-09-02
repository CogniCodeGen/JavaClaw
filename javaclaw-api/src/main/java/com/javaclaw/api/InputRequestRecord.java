package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 可查询且可乐观锁决议的输入请求快照。
 *
 * @param request 不包含答案的输入请求
 * @param state 当前生命周期
 * @param revision 资源版本，从 1 开始
 * @param response 已决议的规范输入；其他状态为空
 * @param resolutionReason 过期或取消原因；等待和正常决议时为空
 * @param updatedAt 最近状态更新时间
 */
public record InputRequestRecord(
        InputRequest request,
        InputRequestState state,
        long revision,
        Optional<CanonicalPayload> response,
        Optional<String> resolutionReason,
        Instant updatedAt) {
    /** 校验终态字段与生命周期的一致性。 */
    public InputRequestRecord {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(state, "state");
        revision = Preconditions.positive(revision, "revision");
        response = Objects.requireNonNull(response, "response");
        resolutionReason = Objects.requireNonNull(resolutionReason, "resolutionReason")
                .map(value -> Preconditions.text(value, "resolutionReason"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(request.createdAt())) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if ((state == InputRequestState.RESOLVED) != response.isPresent()) {
            throw new IllegalArgumentException("only resolved input may contain a response");
        }
        boolean needsReason = state == InputRequestState.EXPIRED || state == InputRequestState.CANCELLED;
        if (needsReason != resolutionReason.isPresent()) {
            throw new IllegalArgumentException("expired and cancelled input require a resolution reason");
        }
    }

    /**
     * 判断是否仍可由客户端决议。
     *
     * @return 等待中为 {@code true}
     */
    public boolean pending() {
        return state == InputRequestState.PENDING;
    }
}
