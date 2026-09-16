package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 先建立原生监护握手，再把常驻 Worker 管道交给可信宿主；启动失败时绝不回退未隔离目标。 */
final class SandboxResidentWorkerLauncher {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);

    private SandboxResidentWorkerLauncher() {}

    static SandboxedWorkerLease start(ValidatedSandboxCommand command, Duration idle) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean mac = os.contains("mac");
        SandboxLaunchPlan plan = mac
                ? new MacSandboxCommandBuilder().buildInteractive(command)
                : PlatformSandboxCommandBuilder.current().build(command);
        return start(command, idle, plan, mac, !os.contains("windows"));
    }

    static SandboxedWorkerLease start(
            ValidatedSandboxCommand command, Duration idle, SandboxLaunchPlan plan, boolean bootstrap, boolean posix)
            throws IOException {
        SandboxWorkerControl control = SandboxWorkerControl.create();
        Process process = null;
        try {
            requireHiddenControl(command, control.directory());
            ProcessBuilder builder = new ProcessBuilder(arguments(command, idle, plan, bootstrap, control));
            builder.directory(command.workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(plan.environment());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            awaitReady(process, control);
            return new SandboxedWorkerLease(process, control, posix);
        } catch (IOException | RuntimeException failure) {
            if (process != null) {
                new SandboxWorkerTree(process, posix).terminate();
            }
            try {
                control.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static List<String> arguments(
            ValidatedSandboxCommand command,
            Duration idle,
            SandboxLaunchPlan plan,
            boolean bootstrap,
            SandboxWorkerControl control)
            throws IOException {
        ArrayList<String> values = new ArrayList<>(List.of(
                control.directory().toString(),
                Long.toString(idle.toMillis()),
                Long.toString(command.limits().memoryBytes()),
                Integer.toString(command.limits().childProcesses() + plan.trustedDescendantProcesses()),
                Boolean.toString(plan.nativeTreeLimits()),
                Boolean.toString(bootstrap),
                "--"));
        values.addAll(plan.command());
        return SandboxJavaRuntime.forWorker(SandboxWorkerMonitorMain.class)
                .command(SandboxWorkerMonitorMain.class, values, 32);
    }

    private static void requireHiddenControl(ValidatedSandboxCommand command, Path control) {
        boolean visible = java.util.stream.Stream.concat(command.readRoots().stream(), command.writeRoots().stream())
                .anyMatch(control::startsWith);
        if (visible) {
            throw new SecurityException("Worker cannot receive read or write access to its monitor control directory");
        }
    }

    private static void awaitReady(Process process, SandboxWorkerControl control) throws IOException {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String status = control.status();
            if (status.startsWith("FAILED:")) {
                throw new IOException(status.substring("FAILED:".length()));
            }
            if (status.matches("READY:[1-9][0-9]*") && process.isAlive()) {
                return;
            }
            if (!process.isAlive()) {
                throw new IOException("Native Worker monitor exited before startup completed");
            }
            try {
                process.waitFor(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Native Worker startup interrupted", interrupted);
            }
        }
        throw new IOException("Native Worker monitor startup timed out");
    }
}
