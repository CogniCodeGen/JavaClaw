package com.javaclaw.agent.tool;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Connection-neutral user-input rendezvous used by the App Server. */
public final class PendingUserInputGateway implements UserInputGateway, AutoCloseable {
    private static final int MAX_RESPONSE_CHARS = 1_000_000;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final int maximumPending;

    /** 设置正数等待时长与待决数量上限；超时或 close 将问题收敛为取消。 */
    public PendingUserInputGateway(Duration timeout, int maximumPending) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("user-input timeout must be positive");
        }
        if (maximumPending < 1) {
            throw new IllegalArgumentException("maximumPending must be positive");
        }
        this.maximumPending = maximumPending;
    }

    @Override
    public Response ask(Request request, Runnable announcePending) throws Exception {
        if (pending.size() >= maximumPending) {
            throw new IllegalStateException("too many pending user-input requests");
        }
        Pending value = new Pending(request, new CompletableFuture<>());
        if (pending.putIfAbsent(request.requestId(), value) != null) {
            throw new IllegalStateException("duplicate user-input request id");
        }
        try {
            announcePending.run();
            return value.response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            return new Response("", true);
        } finally {
            pending.remove(request.requestId(), value);
        }
    }

    /** 提交回答或取消；拒绝超长回答和不在选项中的非取消回答，未知或已完成请求返回 false。 */
    public boolean respond(String requestId, String response, boolean cancelled) {
        Pending value = pending.get(requestId);
        if (value == null) {
            return false;
        }
        response = response == null ? "" : response;
        if (response.length() > MAX_RESPONSE_CHARS) {
            return false;
        }
        if (!cancelled
                && !value.request.choices().isEmpty()
                && !value.request.choices().contains(response)) {
            return false;
        }
        return value.response.complete(new Response(response, cancelled));
    }

    /** 判断问题是否仍注册；并发完成时必须以 respond 结果为准。 */
    public boolean hasPending(String requestId) {
        return pending.containsKey(requestId);
    }

    /** 返回当前用户输入等待数量，用于诊断，不保证下一时刻仍相同。 */
    public int pendingCount() {
        return pending.size();
    }

    @Override
    public void close() {
        pending.values().forEach(value -> value.response.complete(new Response("", true)));
        pending.clear();
    }

    private record Pending(Request request, CompletableFuture<Response> response) {}
}
