package com.javaclaw.agent.tool;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Connection-neutral approval rendezvous used by the App Server protocol adapter. */
public final class PendingApprovalGateway implements ApprovalGateway, AutoCloseable {
    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final int maximumPending;

    /** 设置正数等待时长与待决数量上限；close 将所有尚未回答的审批决议为拒绝。 */
    public PendingApprovalGateway(Duration timeout, int maximumPending) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("approval timeout must be positive");
        }
        if (maximumPending < 1) {
            throw new IllegalArgumentException("maximumPending must be positive");
        }
        this.maximumPending = maximumPending;
    }

    @Override
    public boolean approve(Request request, Runnable announcePending) throws Exception {
        if (pending.size() >= maximumPending) {
            throw new IllegalStateException("too many pending approvals");
        }
        CompletableFuture<Boolean> response = new CompletableFuture<>();
        if (pending.putIfAbsent(request.approvalId(), response) != null) {
            throw new IllegalStateException("duplicate approval id");
        }
        try {
            announcePending.run();
            return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            return false;
        } finally {
            pending.remove(request.approvalId(), response);
        }
    }

    /** 完成尚未回答的审批 Future；未知标识或已完成请求返回 false，不重复恢复执行。 */
    public boolean respond(String approvalId, boolean approved) {
        CompletableFuture<Boolean> response = pending.get(approvalId);
        return response != null && response.complete(approved);
    }

    /** 返回当前注册的审批等待数量，用于诊断，不作为并发同步手段。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 判断审批是否仍注册；结果是瞬时观察，真正决议必须以 respond 返回值为准。 */
    public boolean hasPending(String approvalId) {
        return pending.containsKey(approvalId);
    }

    @Override
    public void close() {
        pending.values().forEach(value -> value.complete(false));
        pending.clear();
    }
}
