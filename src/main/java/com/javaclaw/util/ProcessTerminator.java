package com.javaclaw.util;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 外部进程树清理工具，避免只杀父 shell 后遗留编译器、测试或 sleep 子进程。 */
public final class ProcessTerminator {

    private ProcessTerminator() {
    }

    /** 强制终止进程及调用时仍存活的全部后代；清理失败按尽力语义忽略。 */
    public static void destroyTreeForcibly(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants()
                    .sorted(Comparator.comparingInt(ProcessTerminator::depth).reversed())
                    .toList();
        } catch (RuntimeException ignored) {
            // 某些受限环境不允许枚举系统进程；仍至少终止直接进程。
            descendants = List.of();
        }
        for (ProcessHandle child : descendants) {
            try {
                if (child.isAlive()) child.destroyForcibly();
            } catch (RuntimeException ignored) {
                // 进程可能已退出或当前平台拒绝访问，继续清理其余节点。
            }
        }
        // 先给父进程机会回收已终止的后代，再杀父进程；反过来同时强杀会在 Unix 上留下
        // 无父进程负责 wait() 的僵尸，ProcessHandle 仍长期显示 alive。
        awaitExit(descendants, 1, TimeUnit.SECONDS);
        try {
            if (process.isAlive()) process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 尽力清理；调用方仍会按超时/中断返回。
        }
    }

    /**
     * 限时等待进程；调用线程被中断时先强杀整棵进程树并短暂等待父进程退出，再恢复中断位并重抛。
     *
     * <p>进程型工具统一走此入口，避免 {@link Process#waitFor(long, TimeUnit)} 抛出
     * {@link InterruptedException} 后直接离开、把 Maven/Gradle/shell 子进程遗留在后台。</p>
     */
    public static boolean waitForOrTerminateOnInterrupt(
            Process process, long timeout, TimeUnit unit) throws InterruptedException {
        try {
            return process.waitFor(timeout, unit);
        } catch (InterruptedException interrupted) {
            destroyTreeForcibly(process);
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException cleanupInterrupted) {
                interrupted.addSuppressed(cleanupInterrupted);
                destroyTreeForcibly(process);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw interrupted;
        }
    }

    private static void awaitExit(List<ProcessHandle> handles, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean interrupted = false;
        for (ProcessHandle handle : handles) {
            while (handle.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    break;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static int depth(ProcessHandle handle) {
        try {
            int depth = 0;
            ProcessHandle current = handle;
            while (current.parent().isPresent() && depth < 64) {
                current = current.parent().orElseThrow();
                depth++;
            }
            return depth;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
