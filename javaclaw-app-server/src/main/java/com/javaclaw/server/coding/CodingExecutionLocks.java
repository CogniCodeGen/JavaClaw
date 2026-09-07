package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 执行根上的非阻塞写租约；PTY 由创建到关闭持续持有，避免内部补丁与命令互相覆盖。 */
final class CodingExecutionLocks {
    private final ConcurrentHashMap<Path, Object> roots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<com.javaclaw.api.TurnId, Object> turns = new ConcurrentHashMap<>();

    ProcessLease acquire(com.javaclaw.api.TurnId turnId, Path root) {
        Object owner = new Object();
        if (turns.putIfAbsent(turnId, owner) != null) {
            throw new IllegalStateException("TURN_PROCESS_BUSY: 当前 Turn 已有活动命令或终端");
        }
        try {
            return new ProcessLease(new Lease(() -> turns.remove(turnId, owner)), acquire(root));
        } catch (RuntimeException failure) {
            turns.remove(turnId, owner);
            throw failure;
        }
    }

    Lease acquire(Path root) {
        Path key = root.toAbsolutePath().normalize();
        Object owner = new Object();
        if (roots.putIfAbsent(key, owner) != null) {
            throw new IllegalStateException("EXECUTION_ROOT_BUSY: 当前执行根已有活动进程或补丁");
        }
        return new Lease(() -> roots.remove(key, owner));
    }

    record ProcessLease(Lease turn, Lease root) implements AutoCloseable {
        @Override
        public void close() {
            root.close();
            turn.close();
        }
    }

    static final class Lease implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }
}
