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
        return await(process, command, plan, cancellation, null);
    }

    static Outcome await(
            Process process,
            ValidatedSandboxCommand command,
            SandboxLaunchPlan plan,
            CancellationToken cancellation,
            WindowsHelperControl control)
            throws InterruptedException {
        long deadline = System.nanoTime() + command.timeout().toNanos();
        long processLimit =
                (long) plan.trustedDescendantProcesses() + command.limits().childProcesses();
        while (process.isAlive()) {
            if (cancellation.isCancelled()) {
                terminate(process, command, control);
                return new Outcome(false, true, false, false);
            }
            if (!plan.nativeTreeLimits()
                    && process.descendants().filter(ProcessHandle::isAlive).count() > processLimit) {
                terminate(process, command, control);
                return new Outcome(false, false, true, false);
            }
            if (!plan.nativeTreeLimits()
                    && SandboxMemoryMeter.treeBytes(process) > command.limits().memoryBytes()) {
                terminate(process, command, control);
                return new Outcome(false, false, false, true);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                terminate(process, command, control);
                return new Outcome(true, false, false, false);
            }
            long wait = Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            process.waitFor(wait, TimeUnit.MILLISECONDS);
        }
        return new Outcome(false, false, false, false);
    }

    static void terminate(Process process, ValidatedSandboxCommand command) {
        terminate(process, command, null);
    }

    static void terminate(Process process, ValidatedSandboxCommand command, WindowsHelperControl control) {
        boolean interrupted = Thread.interrupted();
        try {
            if (control != null) {
                control.requestTermination(process);
            } else {
                WindowsProxyCleanup.terminateBeforeHelper(process, command);
            }
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("Windows helper termination control failed", failure);
        } catch (InterruptedException failure) {
            interrupted = true;
            throw new IllegalStateException("Windows helper termination interrupted", failure);
        } finally {
            SandboxProcessTerminator.terminate(process);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    record Outcome(boolean timedOut, boolean cancelled, boolean processLimitExceeded, boolean memoryLimitExceeded) {}
}
