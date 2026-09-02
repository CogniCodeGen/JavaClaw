package com.javaclaw.nativehost.sandbox;

import java.util.concurrent.TimeUnit;

import com.javaclaw.api.CancellationToken;

/** 轮询取消、墙钟超时与子进程上限；任一越界立即终止整个 Sandbox。 */
final class SandboxProcessMonitor {
    private static final long POLL_MILLIS = 20;

    private SandboxProcessMonitor() {}

    static Outcome await(
            Process process, ValidatedSandboxCommand command, SandboxLaunchPlan plan, CancellationToken cancellation)
            throws InterruptedException {
        long deadline = System.nanoTime() + command.timeout().toNanos();
        long processLimit =
                (long) plan.trustedDescendantProcesses() + command.limits().childProcesses();
        while (process.isAlive()) {
            if (cancellation.isCancelled()) {
                SandboxProcessTerminator.terminate(process);
                return new Outcome(false, true, false, false);
            }
            if (!plan.nativeTreeLimits()
                    && process.descendants().filter(ProcessHandle::isAlive).count() > processLimit) {
                SandboxProcessTerminator.terminate(process);
                return new Outcome(false, false, true, false);
            }
            if (!plan.nativeTreeLimits()
                    && SandboxMemoryMeter.treeBytes(process) > command.limits().memoryBytes()) {
                SandboxProcessTerminator.terminate(process);
                return new Outcome(false, false, false, true);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                SandboxProcessTerminator.terminate(process);
                return new Outcome(true, false, false, false);
            }
            long wait = Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            process.waitFor(wait, TimeUnit.MILLISECONDS);
        }
        return new Outcome(false, false, false, false);
    }

    record Outcome(boolean timedOut, boolean cancelled, boolean processLimitExceeded, boolean memoryLimitExceeded) {}
}
