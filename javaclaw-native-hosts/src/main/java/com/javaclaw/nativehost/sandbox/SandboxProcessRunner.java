package com.javaclaw.nativehost.sandbox;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.nativehost.ffm.NativeResourceLimits;
import com.javaclaw.nativehost.ffm.PosixPty;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSessionControl;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;

final class SandboxProcessRunner {
    SandboxResult run(SandboxBackend backend, SandboxCommand command) throws Exception {
        if (!backend.available()) {
            throw new UnsupportedOperationException("required sandbox backend is unavailable: " + backend.name());
        }
        List<String> wrapped = backend.wrap(command);
        ProcessBuilder builder = new ProcessBuilder(wrapped);
        builder.directory(command.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(command.policy().filteredEnvironment(command.environment()));
        long started = System.nanoTime();
        Process process = builder.start();
        AtomicLong remaining = new AtomicLong(command.policy().outputLimitBytes());
        Capture stdout = new Capture(process.getInputStream(), remaining);
        Capture stderr = new Capture(process.getErrorStream(), remaining);
        boolean timedOut;
        try (ExecutorService readers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> out = readers.submit(stdout);
            Future<String> err = readers.submit(stderr);
            Future<?> input = readers.submit(() -> {
                try (OutputStream sink = process.getOutputStream()) {
                    sink.write(command.standardInput().getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            timedOut =
                    !process.waitFor(backendWaitMillis(backend, command.policy().timeout()), TimeUnit.MILLISECONDS);
            if (timedOut) {
                terminateTree(process);
            }
            input.get(5, TimeUnit.SECONDS);
            String standardOut = out.get(5, TimeUnit.SECONDS);
            String standardError = err.get(5, TimeUnit.SECONDS);
            int exitCode = timedOut ? -1 : process.exitValue();
            if (timedOut && backend.name().startsWith("windows-")) {
                standardError = standardError + (standardError.isEmpty() ? "" : "\n")
                        + "JAVACLAW_WINDOWS_ACL_RESTORE_FAILURE: "
                        + "sandbox helper cleanup deadline exceeded";
            }
            boolean targetTimedOut = timedOut || windowsTargetTimedOut(backend, exitCode);
            return new SandboxResult(
                    exitCode,
                    standardOut,
                    standardError,
                    targetTimedOut,
                    stdout.truncated || stderr.truncated,
                    Duration.ofNanos(System.nanoTime() - started),
                    backend.name());
        } finally {
            if (process.isAlive()) {
                terminateTree(process);
            }
        }
    }

    void runSession(
            SandboxBackend backend,
            SandboxCommand command,
            SandboxSessionOptions options,
            BufferedReader controls,
            ObjectMapper json,
            String nonce)
            throws Exception {
        if (options.pseudoTerminal()) {
            if (isWindows()) {
                runWindowsPtySession(backend, command, options, controls, json, nonce);
            } else {
                runPtySession(backend, command, options, controls, json, nonce);
            }
            return;
        }
        if (!backend.available()) {
            throw new UnsupportedOperationException("required sandbox backend is unavailable: " + backend.name());
        }
        List<String> wrapped = backend.wrap(command);
        ProcessBuilder builder = new ProcessBuilder(wrapped);
        builder.directory(command.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(command.policy().filteredEnvironment(command.environment()));
        Process process = builder.start();
        AtomicLong remaining = new AtomicLong(command.policy().outputLimitBytes());
        AtomicBoolean truncated = new AtomicBoolean();
        Object outputLock = new Object();
        send(json, outputLock, SandboxSessionFrame.ready(nonce, backend.name()));

        try (ExecutorService tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> stdout = tasks.submit(() -> {
                pumpSessionStream(
                        process.getInputStream(),
                        SandboxSessionFrame.Kind.STDOUT,
                        remaining,
                        truncated,
                        process,
                        json,
                        outputLock,
                        nonce);
                return null;
            });
            Future<?> stderr = tasks.submit(() -> {
                pumpSessionStream(
                        process.getErrorStream(),
                        SandboxSessionFrame.Kind.STDERR,
                        remaining,
                        truncated,
                        process,
                        json,
                        outputLock,
                        nonce);
                return null;
            });
            Future<?> control = tasks.submit(() -> {
                try {
                    controlSession(process, controls, json, outputLock, nonce);
                } catch (Exception failure) {
                    String message = failure.getMessage();
                    send(
                            json,
                            outputLock,
                            SandboxSessionFrame.error(
                                    nonce,
                                    message == null || message.isBlank()
                                            ? failure.getClass().getSimpleName()
                                            : message));
                    terminateTree(process);
                    throw failure;
                }
                return null;
            });
            if (!command.standardInput().isEmpty()) {
                process.getOutputStream().write(command.standardInput().getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
            }
            boolean timedOut =
                    !process.waitFor(backendWaitMillis(backend, command.policy().timeout()), TimeUnit.MILLISECONDS);
            if (timedOut) {
                terminateTree(process);
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            stdout.get(5, TimeUnit.SECONDS);
            stderr.get(5, TimeUnit.SECONDS);
            control.cancel(true);
            int exitCode = timedOut ? -1 : process.exitValue();
            boolean targetTimedOut = timedOut || windowsTargetTimedOut(backend, exitCode);
            if (timedOut && backend.name().startsWith("windows-")) {
                send(
                        json,
                        outputLock,
                        SandboxSessionFrame.stream(
                                nonce,
                                SandboxSessionFrame.Kind.STDERR,
                                ("JAVACLAW_WINDOWS_ACL_RESTORE_FAILURE: " + "sandbox helper cleanup deadline exceeded")
                                        .getBytes(StandardCharsets.UTF_8)));
            }
            String detail = targetTimedOut
                    ? "sandbox session timed out"
                    : truncated.get() ? "sandbox session output limit exceeded" : "";
            send(json, outputLock, SandboxSessionFrame.exit(nonce, exitCode, truncated.get(), detail));
        } finally {
            if (process.isAlive()) {
                terminateTree(process);
            }
        }
    }

    private void runPtySession(
            SandboxBackend backend,
            SandboxCommand command,
            SandboxSessionOptions options,
            BufferedReader controls,
            ObjectMapper json,
            String nonce)
            throws Exception {
        if (!backend.available()) {
            throw new UnsupportedOperationException("required sandbox backend is unavailable: " + backend.name());
        }
        if (!PosixPty.isSupported()) {
            throw new UnsupportedOperationException("an exact PTY backend is unavailable on this platform");
        }
        try (PosixPty terminal = PosixPty.open(options.columns(), options.rows())) {
            List<String> wrapped = backend.wrapSession(command, options, terminal.slavePath());
            ProcessBuilder builder = new ProcessBuilder(wrapped);
            builder.directory(command.workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(command.policy().filteredEnvironment(command.environment()));
            builder.redirectInput(
                    ProcessBuilder.Redirect.from(terminal.slavePath().toFile()));
            builder.redirectOutput(
                    ProcessBuilder.Redirect.to(terminal.slavePath().toFile()));
            builder.redirectError(
                    ProcessBuilder.Redirect.to(terminal.slavePath().toFile()));
            Process process = builder.start();
            AtomicLong remaining = new AtomicLong(command.policy().outputLimitBytes());
            AtomicBoolean truncated = new AtomicBoolean();
            Object outputLock = new Object();
            send(json, outputLock, SandboxSessionFrame.ready(nonce, backend.name() + "-pty"));

            try (ExecutorService tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> output = tasks.submit(() -> {
                    pumpPtyStream(terminal, remaining, truncated, process, json, outputLock, nonce);
                    return null;
                });
                Future<?> control = tasks.submit(() -> {
                    try {
                        controlPtySession(process, terminal, controls, json, outputLock, nonce);
                    } catch (Exception failure) {
                        String message = failure.getMessage();
                        send(
                                json,
                                outputLock,
                                SandboxSessionFrame.error(
                                        nonce,
                                        message == null || message.isBlank()
                                                ? failure.getClass().getSimpleName()
                                                : message));
                        terminatePtyTree(process, terminal, 9);
                        throw failure;
                    }
                    return null;
                });
                if (!command.standardInput().isEmpty()) {
                    terminal.write(command.standardInput().getBytes(StandardCharsets.UTF_8));
                }
                boolean timedOut = !process.waitFor(
                        backendWaitMillis(backend, command.policy().timeout()), TimeUnit.MILLISECONDS);
                if (timedOut) {
                    terminatePtyTree(process, terminal, 9);
                }
                output.get(5, TimeUnit.SECONDS);
                control.cancel(true);
                int exitCode = timedOut ? -1 : process.exitValue();
                String detail = timedOut
                        ? "sandbox PTY session timed out"
                        : truncated.get() ? "sandbox PTY output limit exceeded" : "";
                send(json, outputLock, SandboxSessionFrame.exit(nonce, exitCode, truncated.get(), detail));
            } finally {
                if (process.isAlive()) {
                    terminatePtyTree(process, terminal, 9);
                }
            }
        }
    }

    private void runWindowsPtySession(
            SandboxBackend backend,
            SandboxCommand command,
            SandboxSessionOptions options,
            BufferedReader controls,
            ObjectMapper json,
            String nonce)
            throws Exception {
        if (!backend.available()) {
            throw new UnsupportedOperationException("required sandbox backend is unavailable: " + backend.name());
        }
        List<String> wrapped = backend.wrapSession(command, options, command.workingDirectory());
        ProcessBuilder builder = new ProcessBuilder(wrapped);
        builder.directory(command.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(command.policy().filteredEnvironment(command.environment()));
        Process process = builder.start();
        AtomicLong remaining = new AtomicLong(command.policy().outputLimitBytes());
        AtomicBoolean truncated = new AtomicBoolean();
        Object outputLock = new Object();
        Object controlLock = new Object();
        if (!command.standardInput().isEmpty()) {
            sendWindowsControl(
                    process.getOutputStream(),
                    json,
                    controlLock,
                    new SandboxSessionControl(
                            nonce,
                            SandboxSessionControl.Operation.STDIN,
                            java.util.Base64.getEncoder()
                                    .encodeToString(command.standardInput().getBytes(StandardCharsets.UTF_8)),
                            null,
                            null,
                            null));
        }
        send(json, outputLock, SandboxSessionFrame.ready(nonce, backend.name() + "-conpty"));
        try (ExecutorService tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> stdout = tasks.submit(() -> {
                pumpSessionStream(
                        process.getInputStream(),
                        SandboxSessionFrame.Kind.STDOUT,
                        remaining,
                        truncated,
                        process,
                        json,
                        outputLock,
                        nonce);
                return null;
            });
            Future<?> stderr = tasks.submit(() -> {
                pumpSessionStream(
                        process.getErrorStream(),
                        SandboxSessionFrame.Kind.STDERR,
                        remaining,
                        truncated,
                        process,
                        json,
                        outputLock,
                        nonce);
                return null;
            });
            Future<?> control = tasks.submit(() -> {
                try {
                    controlWindowsPtyBridge(process, controls, json, controlLock, nonce);
                } catch (Exception failure) {
                    String message = failure.getMessage();
                    send(
                            json,
                            outputLock,
                            SandboxSessionFrame.error(
                                    nonce,
                                    message == null || message.isBlank()
                                            ? failure.getClass().getSimpleName()
                                            : message));
                    terminateTree(process);
                    throw failure;
                }
                return null;
            });
            boolean timedOut =
                    !process.waitFor(backendWaitMillis(backend, command.policy().timeout()), TimeUnit.MILLISECONDS);
            if (timedOut) {
                terminateTree(process);
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            stdout.get(5, TimeUnit.SECONDS);
            stderr.get(5, TimeUnit.SECONDS);
            control.cancel(true);
            int exitCode = timedOut ? -1 : process.exitValue();
            boolean targetTimedOut = timedOut || windowsTargetTimedOut(backend, exitCode);
            if (timedOut) {
                send(
                        json,
                        outputLock,
                        SandboxSessionFrame.stream(
                                nonce,
                                SandboxSessionFrame.Kind.STDERR,
                                ("JAVACLAW_WINDOWS_ACL_RESTORE_FAILURE: " + "ConPTY helper cleanup deadline exceeded")
                                        .getBytes(StandardCharsets.UTF_8)));
            }
            String detail = targetTimedOut
                    ? "sandbox ConPTY session timed out"
                    : truncated.get() ? "sandbox ConPTY output limit exceeded" : "";
            send(json, outputLock, SandboxSessionFrame.exit(nonce, exitCode, truncated.get(), detail));
        } finally {
            if (process.isAlive()) {
                terminateTree(process);
            }
        }
    }

    private static void controlWindowsPtyBridge(
            Process process, BufferedReader input, ObjectMapper json, Object controlLock, String nonce)
            throws Exception {
        String line;
        while (process.isAlive() && (line = input.readLine()) != null) {
            if (line.length() > 2 * 1024 * 1024) {
                throw new IOException("sandbox ConPTY control frame exceeds limit");
            }
            SandboxSessionControl frame = json.readValue(line, SandboxSessionControl.class);
            if (!java.security.MessageDigest.isEqual(
                    nonce.getBytes(StandardCharsets.UTF_8), frame.nonce().getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("sandbox ConPTY session nonce mismatch");
            }
            sendWindowsControl(process.getOutputStream(), json, controlLock, frame);
        }
    }

    private static void sendWindowsControl(
            OutputStream output, ObjectMapper json, Object lock, SandboxSessionControl frame) throws IOException {
        synchronized (lock) {
            output.write(json.writeValueAsBytes(frame));
            output.write('\n');
            output.flush();
        }
    }

    private static void controlSession(
            Process process, BufferedReader input, ObjectMapper json, Object outputLock, String nonce)
            throws Exception {
        String line;
        while (process.isAlive() && (line = input.readLine()) != null) {
            if (line.length() > 2 * 1024 * 1024) {
                throw new IOException("sandbox control frame exceeds limit");
            }
            SandboxSessionControl frame = json.readValue(line, SandboxSessionControl.class);
            if (!java.security.MessageDigest.isEqual(
                    nonce.getBytes(StandardCharsets.UTF_8), frame.nonce().getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("sandbox session nonce mismatch");
            }
            switch (frame.operation()) {
                case STDIN -> {
                    if (frame.data().length > 1024 * 1024) {
                        throw new IOException("sandbox stdin frame exceeds 1 MiB");
                    }
                    process.getOutputStream().write(frame.data());
                    process.getOutputStream().flush();
                }
                case CLOSE_INPUT -> process.getOutputStream().close();
                case TERMINATE -> terminateTree(process);
                case SIGNAL -> applySignal(process, frame.signal());
                case RESIZE -> throw new UnsupportedOperationException("resize is unavailable for a pipe session");
            }
        }
    }

    private static void controlPtySession(
            Process process,
            PosixPty terminal,
            BufferedReader input,
            ObjectMapper json,
            Object outputLock,
            String nonce)
            throws Exception {
        String line;
        while (process.isAlive() && (line = input.readLine()) != null) {
            if (line.length() > 2 * 1024 * 1024) {
                throw new IOException("sandbox PTY control frame exceeds limit");
            }
            SandboxSessionControl frame = json.readValue(line, SandboxSessionControl.class);
            if (!java.security.MessageDigest.isEqual(
                    nonce.getBytes(StandardCharsets.UTF_8), frame.nonce().getBytes(StandardCharsets.UTF_8))) {
                throw new SecurityException("sandbox PTY session nonce mismatch");
            }
            switch (frame.operation()) {
                case STDIN -> {
                    if (frame.data().length > 1024 * 1024) {
                        throw new IOException("sandbox PTY stdin frame exceeds 1 MiB");
                    }
                    terminal.write(frame.data());
                }
                case CLOSE_INPUT -> terminal.closeInput();
                case TERMINATE -> terminatePtyTree(process, terminal, 15);
                case SIGNAL -> applyPtySignal(process, terminal, frame.signal());
                case RESIZE -> terminal.resize(frame.columns(), frame.rows());
            }
        }
    }

    private static void applySignal(Process process, SandboxSignal signal) {
        switch (signal) {
            case TERMINATE -> process.destroy();
            case KILL -> terminateTree(process);
            case INTERRUPT -> {
                if (isWindows()) {
                    throw new UnsupportedOperationException("Windows pipe sessions cannot emulate a console interrupt");
                }
                NativeResourceLimits.signalProcessGroup(process.pid(), 2);
            }
        }
    }

    private static void pumpSessionStream(
            InputStream input,
            SandboxSessionFrame.Kind kind,
            AtomicLong remaining,
            AtomicBoolean truncated,
            Process process,
            ObjectMapper json,
            Object outputLock,
            String nonce)
            throws Exception {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) {
                continue;
            }
            int read = count;
            long allowance = remaining.getAndUpdate(current -> Math.max(0, current - read));
            int accepted = (int) Math.min(count, Math.max(0, allowance));
            if (accepted > 0) {
                send(
                        json,
                        outputLock,
                        SandboxSessionFrame.stream(nonce, kind, java.util.Arrays.copyOf(buffer, accepted)));
            }
            if (accepted < count) {
                truncated.set(true);
                terminateTree(process);
                return;
            }
        }
    }

    private static void pumpPtyStream(
            PosixPty terminal,
            AtomicLong remaining,
            AtomicBoolean truncated,
            Process process,
            ObjectMapper json,
            Object outputLock,
            String nonce)
            throws Exception {
        byte[] value;
        while ((value = terminal.read(8192)) != null) {
            if (value.length == 0) {
                continue;
            }
            int read = value.length;
            long allowance = remaining.getAndUpdate(current -> Math.max(0, current - read));
            int accepted = (int) Math.min(read, Math.max(0, allowance));
            if (accepted > 0) {
                send(
                        json,
                        outputLock,
                        SandboxSessionFrame.stream(
                                nonce,
                                SandboxSessionFrame.Kind.STDOUT,
                                accepted == value.length ? value : java.util.Arrays.copyOf(value, accepted)));
            }
            if (accepted < read) {
                truncated.set(true);
                terminatePtyTree(process, terminal, 9);
                return;
            }
        }
    }

    private static void applyPtySignal(Process process, PosixPty terminal, SandboxSignal signal) {
        switch (signal) {
            case INTERRUPT -> terminal.signalForeground(2);
            case TERMINATE -> terminatePtyTree(process, terminal, 15);
            case KILL -> terminatePtyTree(process, terminal, 9);
        }
    }

    private static void terminatePtyTree(Process process, PosixPty terminal, int signal) {
        try {
            terminal.signalForeground(signal);
        } catch (RuntimeException ignored) {
        }
        if (signal == 9) {
            terminateTree(process);
        } else {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    terminateTree(process);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                terminateTree(process);
            }
        }
    }

    private static void send(ObjectMapper json, Object lock, SandboxSessionFrame frame) throws IOException {
        synchronized (lock) {
            System.out.println(json.writeValueAsString(frame));
            System.out.flush();
        }
    }

    private static long backendWaitMillis(SandboxBackend backend, Duration targetTimeout) {
        long value = targetTimeout.toMillis();
        if (backend.name().startsWith("windows-")) {
            // The Job enforces the target deadline. The trusted helper must remain alive long
            // enough to close the Job, restore every temporary Workspace ACL and delete the
            // ephemeral AppContainer profile.
            value = Math.addExact(value, Duration.ofSeconds(15).toMillis());
        }
        return value;
    }

    private static boolean windowsTargetTimedOut(SandboxBackend backend, int exitCode) {
        // Exit 124 is reserved by WindowsSandboxExecMain for its Job deadline.
        return backend.name().startsWith("windows-") && exitCode == 124;
    }

    private static void terminateTree(Process process) {
        if (isMac()) {
            try {
                NativeResourceLimits.killProcessGroup(process.pid());
            } catch (RuntimeException ignored) {
            }
        }
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException unavailable) {
            // Hardened hosts can deny enumeration; backend process-group/job containment remains
            // the authoritative tree boundary and closing the direct child activates it.
        }
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }

    private static final class Capture implements java.util.concurrent.Callable<String> {
        private final InputStream input;
        private final AtomicLong remaining;
        private volatile boolean truncated;

        private Capture(InputStream input, AtomicLong remaining) {
            this.input = input;
            this.remaining = remaining;
        }

        @Override
        public String call() throws IOException {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                int read = count;
                long allowance = remaining.getAndUpdate(current -> Math.max(0, current - read));
                int accepted = (int) Math.min(count, Math.max(0, allowance));
                if (accepted > 0) {
                    captured.write(buffer, 0, accepted);
                }
                if (accepted < count) {
                    truncated = true;
                }
            }
            return captured.toString(StandardCharsets.UTF_8);
        }
    }
}
