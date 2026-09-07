package com.javaclaw.server.coding;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.TurnId;
import com.javaclaw.server.turn.CodingExecutionAuthority;

/** 文件、依赖或平台记账共同属于一个工具调用 owner；结束 Turn 必须等待整个调用退出。 */
final class CodingCallScope implements AutoCloseable {
    private final ConcurrentHashMap<String, Owner> active = new ConcurrentHashMap<>();
    private final Set<TurnId> finishedTurns = ConcurrentHashMap.newKeySet();
    private final CodingExecutionAuthority authority;
    private boolean closing;

    CodingCallScope(CodingExecutionAuthority authority) {
        this.authority = authority;
    }

    <T> T run(CodingInvocation invocation, Work<T> work) throws Exception {
        var cancellation = new CodingCancellation(invocation, authority);
        var bound = new CodingInvocation(
                invocation.id(),
                invocation.request(),
                invocation.turn(),
                invocation.workspaceId(),
                invocation.permission(),
                cancellation,
                invocation.environment());
        var owner = new Owner(invocation.turn().id(), cancellation, new CompletableFuture<>());
        register(invocation.id(), owner);
        Exception failure = null;
        try {
            cancellation.throwIfCancelled();
            return work.execute(bound);
        } catch (Exception problem) {
            failure = problem;
            throw problem;
        } finally {
            // Work 的 finally 已释放代理并提交最后证据；信号不能早于这一边界。
            if (failure == null || CodingCleanup.cleanCancellation(failure)) {
                owner.finished().complete(null);
            } else {
                owner.finished().completeExceptionally(failure);
            }
            active.remove(invocation.id(), owner);
        }
    }

    private synchronized void register(String id, Owner owner) {
        if (closing || finishedTurns.contains(owner.turnId()) || active.putIfAbsent(id, owner) != null) {
            throw new IllegalStateException("Coding 调用作用域或 Turn 已结束，不能创建新资源");
        }
    }

    void finish(TurnId turnId) throws Exception {
        List<Owner> selected;
        synchronized (this) {
            finishedTurns.add(turnId);
            selected = active.values().stream()
                    .filter(owner -> owner.turnId().equals(turnId))
                    .toList();
        }
        stop(selected);
    }

    @Override
    public void close() throws Exception {
        List<Owner> selected;
        synchronized (this) {
            closing = true;
            selected = List.copyOf(active.values());
        }
        stop(selected);
    }

    private void stop(List<Owner> owners) throws Exception {
        owners.forEach(owner -> owner.cancellation().cancel("Coding 调用作用域结束"));
        CodingCleanup.awaitAll(owners.stream().map(Owner::finished).toList());
    }

    @FunctionalInterface
    interface Work<T> {
        T execute(CodingInvocation invocation) throws Exception;
    }

    private record Owner(TurnId turnId, CodingCancellation cancellation, CompletableFuture<Void> finished) {}
}
