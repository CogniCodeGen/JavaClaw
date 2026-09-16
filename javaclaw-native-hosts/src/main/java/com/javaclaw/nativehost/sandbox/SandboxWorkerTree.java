package com.javaclaw.nativehost.sandbox;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.javaclaw.nativehost.ffm.NativeResourceLimits;

/** 监护期间保留已观察到的进程身份；根进程退出后仍回收尚存的已知后代，避免按裸 PID 误杀。 */
final class SandboxWorkerTree {
    private static final Duration GRACE = Duration.ofSeconds(2);

    private final Process root;
    private final boolean posix;
    private final Map<Long, ProcessHandle> observed = new LinkedHashMap<>();

    SandboxWorkerTree(Process root, boolean posix) {
        this.root = root;
        this.posix = posix;
        observed.put(root.pid(), root.toHandle());
        refresh();
    }

    synchronized long refresh() {
        root.descendants().forEach(handle -> observed.putIfAbsent(handle.pid(), handle));
        observed.values().removeIf(handle -> !handle.isAlive());
        return observed.values().stream().filter(ProcessHandle::isAlive).count();
    }

    synchronized void terminate() {
        refresh();
        signalGroup(15);
        List<ProcessHandle> handles = List.copyOf(observed.values()).reversed();
        handles.forEach(ProcessHandle::destroy);
        await(handles);
        signalGroup(9);
        handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        await(handles);
        if (handles.stream().anyMatch(ProcessHandle::isAlive)) {
            throw new IllegalStateException("Worker process tree did not terminate");
        }
    }

    private void signalGroup(int signal) {
        // 仅在已观察身份仍存活时尝试原 helper 建立的进程组；不能在组已消失后凭裸 PID 再发送信号。
        if (!posix || observed.values().stream().noneMatch(ProcessHandle::isAlive)) {
            return;
        }
        try {
            NativeResourceLimits.signalProcessGroup(root.pid(), signal);
        } catch (UnsupportedOperationException | IllegalStateException unavailable) {
            // helper 可能尚未完成 setpgid，仍必须逐个回收已捕获身份的进程。
        }
    }

    private static void await(List<ProcessHandle> handles) {
        long deadline = System.nanoTime() + GRACE.toNanos();
        for (ProcessHandle handle : handles) {
            long remaining = deadline - System.nanoTime();
            if (!handle.isAlive() || remaining <= 0) {
                continue;
            }
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ended) {
                // 下一轮强制终止处理仍存活的进程。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
