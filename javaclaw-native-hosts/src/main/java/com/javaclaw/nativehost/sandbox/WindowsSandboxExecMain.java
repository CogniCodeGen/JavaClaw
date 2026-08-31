package com.javaclaw.nativehost.sandbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.nativehost.ffm.WindowsSandbox;
import com.javaclaw.sandbox.api.SandboxSessionControl;
import com.javaclaw.sandbox.api.SandboxSignal;

/** Internal Windows helper invoked only by the isolated launcher JVM. */
public final class WindowsSandboxExecMain {
    private WindowsSandboxExecMain() {}

    /** 解析内部固定格式参数并运行 Job/ConPTY 目标；正常透传目标退出码，沙箱失败退出 72，ACL 恢复失败退出 73 供上层锁定 Workspace。 */
    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            int exit = parsed.pseudoTerminal()
                    ? runPseudoConsole(parsed)
                    : WindowsSandbox.run(
                            parsed.command(),
                            parsed.workingDirectory(),
                            parsed.readableRoots(),
                            parsed.writableRoots(),
                            parsed.protectedRoots(),
                            parsed.timeout());
            System.exit(exit);
        } catch (WindowsSandbox.AclRestorationException failure) {
            System.err.println("JAVACLAW_WINDOWS_ACL_RESTORE_FAILURE: " + safe(failure));
            System.exit(73);
        } catch (Throwable failure) {
            System.err.println("JAVACLAW_WINDOWS_SANDBOX_FAILURE: " + safe(failure));
            System.exit(72);
        }
    }

    private static String safe(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) {
            value = failure.getClass().getSimpleName();
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static int runPseudoConsole(Arguments parsed) throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        try (WindowsSandbox.PseudoConsoleSession session = WindowsSandbox.openPseudoConsole(
                parsed.command(),
                parsed.workingDirectory(),
                parsed.readableRoots(),
                parsed.writableRoots(),
                parsed.protectedRoots(),
                parsed.timeout(),
                parsed.columns(),
                parsed.rows())) {
            Thread output = Thread.ofVirtual().name("windows-conpty-output").start(() -> {
                try {
                    byte[] value;
                    while ((value = session.read(8192)) != null) {
                        System.out.write(value);
                        System.out.flush();
                        if (System.out.checkError()) {
                            throw new java.io.IOException("ConPTY output pipe is closed");
                        }
                    }
                } catch (Throwable failure) {
                    asynchronousFailure.compareAndSet(null, failure);
                    session.terminate(126);
                }
            });
            Thread control = Thread.ofVirtual().name("windows-conpty-control").start(() -> {
                try {
                    BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = readBoundedLine(input)) != null && session.isAlive()) {
                        SandboxSessionControl frame = json.readValue(line, SandboxSessionControl.class);
                        apply(session, frame);
                    }
                } catch (Throwable failure) {
                    if (alive(session)) {
                        asynchronousFailure.compareAndSet(null, failure);
                        session.terminate(126);
                    }
                }
            });
            Integer exitCode = session.awaitExit(parsed.timeout());
            if (exitCode == null) {
                session.terminate(124);
                exitCode = session.awaitExit(Duration.ofSeconds(5));
                if (exitCode == null) {
                    exitCode = 124;
                }
            }
            session.finishOutput();
            output.join(Duration.ofSeconds(5));
            if (output.isAlive()) {
                session.terminate(126);
                throw new java.io.IOException("ConPTY output did not reach EOF");
            }
            control.interrupt();
            Throwable failure = asynchronousFailure.get();
            if (failure != null) {
                if (failure instanceof Exception exception) {
                    throw exception;
                }
                throw new java.io.IOException("ConPTY bridge failed", failure);
            }
            return exitCode;
        }
    }

    private static boolean alive(WindowsSandbox.PseudoConsoleSession session) {
        try {
            return session.isAlive();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void apply(WindowsSandbox.PseudoConsoleSession session, SandboxSessionControl frame)
            throws Exception {
        switch (frame.operation()) {
            case STDIN -> session.write(frame.data());
            case CLOSE_INPUT -> session.closeInput();
            case RESIZE -> session.resize(frame.columns(), frame.rows());
            case TERMINATE -> session.terminate(143);
            case SIGNAL -> {
                if (frame.signal() == SandboxSignal.INTERRUPT) {
                    session.interrupt();
                } else {
                    session.terminate(frame.signal() == SandboxSignal.KILL ? 137 : 143);
                }
            }
        }
    }

    private static String readBoundedLine(BufferedReader input) throws java.io.IOException {
        StringBuilder result = new StringBuilder(4096);
        int value;
        while ((value = input.read()) >= 0 && value != '\n') {
            if (value != '\r') {
                result.append((char) value);
            }
            if (result.length() > 2 * 1024 * 1024) {
                throw new java.io.IOException("ConPTY control frame exceeds limit");
            }
        }
        if (value < 0 && result.isEmpty()) {
            return null;
        }
        return result.toString();
    }

    private record Arguments(
            Path workingDirectory,
            Duration timeout,
            Set<Path> readableRoots,
            Set<Path> writableRoots,
            Set<Path> protectedRoots,
            boolean pseudoTerminal,
            int columns,
            int rows,
            List<String> command) {
        private static Arguments parse(String[] arguments) {
            Path cwd = null;
            Duration timeout = null;
            LinkedHashSet<Path> readable = new LinkedHashSet<>();
            LinkedHashSet<Path> writable = new LinkedHashSet<>();
            LinkedHashSet<Path> protectedRoots = new LinkedHashSet<>();
            boolean pseudoTerminal = false;
            int columns = 0;
            int rows = 0;
            ArrayList<String> command = new ArrayList<>();
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if ("--".equals(option)) {
                    for (int tail = index + 1; tail < arguments.length; tail++) {
                        command.add(arguments[tail]);
                    }
                    break;
                }
                if (index + 1 >= arguments.length) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[++index];
                switch (option) {
                    case "--cwd" -> cwd = Path.of(value);
                    case "--timeout-millis" -> timeout = Duration.ofMillis(Long.parseLong(value));
                    case "--read-root" -> readable.add(Path.of(value));
                    case "--write-root" -> writable.add(Path.of(value));
                    case "--protected-root" -> protectedRoots.add(Path.of(value));
                    case "--pty" -> {
                        pseudoTerminal = Boolean.parseBoolean(value);
                        if (!pseudoTerminal) {
                            throw new IllegalArgumentException("--pty must be true when present");
                        }
                    }
                    case "--columns" -> columns = Integer.parseInt(value);
                    case "--rows" -> rows = Integer.parseInt(value);
                    default -> throw new IllegalArgumentException("unknown Windows sandbox helper option: " + option);
                }
            }
            if (cwd == null || timeout == null || command.isEmpty()) {
                throw new IllegalArgumentException("incomplete Windows sandbox helper request");
            }
            if (pseudoTerminal && (columns < 20 || columns > 1_000 || rows < 5 || rows > 1_000)) {
                throw new IllegalArgumentException("invalid Windows PTY dimensions");
            }
            if (!pseudoTerminal && (columns != 0 || rows != 0)) {
                throw new IllegalArgumentException("pipe mode cannot specify PTY dimensions");
            }
            return new Arguments(
                    cwd,
                    timeout,
                    Set.copyOf(readable),
                    Set.copyOf(writable),
                    Set.copyOf(protectedRoots),
                    pseudoTerminal,
                    columns,
                    rows,
                    List.copyOf(command));
        }
    }
}
