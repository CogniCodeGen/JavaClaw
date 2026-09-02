package com.javaclaw.desktop.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.InputRequestRecord;

/**
 * Desktop 对平台 InputRequest 的不可变交互快照。
 *
 * <p>列表只保存仍可决议的请求；{@code requestEpoch} 用于拒绝重连、取消或后续操作之前发出的旧响应。提交期间保留用户草稿，直到权威列表确认请求进入终态。
 *
 * @param pendingRequests 全局待输入请求
 * @param submittingRequestId 正在提交或取消的请求 ID
 * @param error 最近一次输入读取或提交错误
 * @param requestEpoch 当前输入请求 epoch，从 0 开始单调递增
 */
public record InputInteractionState(
        List<InputRequestRecord> pendingRequests,
        Optional<String> submittingRequestId,
        Optional<String> error,
        long requestEpoch) {
    /** 复制列表并校验 pending、提交目标与 epoch 不变量。 */
    public InputInteractionState {
        List<InputRequestRecord> copiedRequests = List.copyOf(pendingRequests);
        pendingRequests = copiedRequests;
        submittingRequestId = Objects.requireNonNull(submittingRequestId, "submittingRequestId")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        error = Objects.requireNonNull(error, "error").map(String::strip).filter(value -> !value.isEmpty());
        if (requestEpoch < 0) {
            throw new IllegalArgumentException("requestEpoch must not be negative");
        }
        if (copiedRequests.stream().anyMatch(request -> !request.pending())) {
            throw new IllegalArgumentException("pending input list contains terminal state");
        }
        if (copiedRequests.stream()
                        .map(request -> request.request().id())
                        .distinct()
                        .count()
                != copiedRequests.size()) {
            throw new IllegalArgumentException("pending input list contains duplicate request IDs");
        }
        if (submittingRequestId
                .filter(id -> copiedRequests.stream()
                        .noneMatch(request -> request.request().id().equals(id)))
                .isPresent()) {
            throw new IllegalArgumentException("submitting input is not pending");
        }
    }

    /**
     * 判断请求是否正在提交或取消。
     *
     * @param request 输入请求
     * @return 精确 ID 正在处理时为 {@code true}
     */
    public boolean submitting(InputRequestRecord request) {
        String id = Objects.requireNonNull(request, "request").request().id();
        return submittingRequestId.filter(id::equals).isPresent();
    }

    /** @return 无请求、无操作且无错误的初始快照 */
    public static InputInteractionState initial() {
        return new InputInteractionState(List.of(), Optional.empty(), Optional.empty(), 0);
    }
}
