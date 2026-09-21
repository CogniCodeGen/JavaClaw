package com.javaclaw.memory;

import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.ThreadStore;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Restore missing graphs and catch up committed raw turns before the next recall. */
final class MemoryHistoryRecovery {
    private final ThreadStore threads;
    private final Supplier<BiConsumer<ThreadSnapshot, List<ThreadEvent>>> replayer;
    private final Supplier<BiConsumer<ThreadSnapshot, List<ThreadEvent>>> catchup;
    private final ConcurrentHashMap<MemoryGraphScope, ReentrantLock> locks = new ConcurrentHashMap<>();
    MemoryHistoryRecovery(ThreadStore threads, Supplier<BiConsumer<ThreadSnapshot, List<ThreadEvent>>> replayer,
                          Supplier<BiConsumer<ThreadSnapshot, List<ThreadEvent>>> catchup) {
        this.threads = threads;
        this.replayer = replayer;
        this.catchup = catchup;
    }
    ThreadSnapshot validate(MemoryGraphScope scope) {
        ThreadSnapshot thread = threads.find(new RunScope(scope.workspaceId(), scope.userId(), scope.threadId()))
                .orElseThrow(() -> new IllegalStateException("会话不存在，不能创建记忆图谱"));
        if (thread.status() == ThreadStatus.DELETED || thread.status() == ThreadStatus.DELETING)
            throw new IllegalStateException("会话已删除，不能恢复记忆图谱");
        return thread;
    }
    void recover(MemoryGraphScope scope, MemoryService view) {
        var replay = replayer.get();
        if (replay == null) return;
        ReentrantLock lock = locks.computeIfAbsent(scope, ignored -> new ReentrantLock());
        if (lock.isHeldByCurrentThread()) return;
        lock.lock();
        try {
            ThreadSnapshot thread = validate(scope);
            var root = view.store().root();
            if (root.historyRecovered && root.observedThreadSequence >= thread.lastSequence()) return;
            List<ThreadEvent> history = threads.events(thread.scope(), 0);
            if (!root.historyRecovered) replay.accept(thread, history);
            else if (catchup.get() != null) catchup.get().accept(thread, history);
            // Replay replaces the root, so always update the current instance.
            root = view.store().root();
            root.historyRecovered = true;
            root.observedThreadSequence = history.stream().mapToLong(ThreadEvent::sequence).max().orElse(0);
            view.store().persistProjectionState();
        } finally { lock.unlock(); }
    }
}
