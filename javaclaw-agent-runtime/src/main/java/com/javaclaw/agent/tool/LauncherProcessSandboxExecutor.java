package com.javaclaw.agent.tool;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxLaunchRequest;
import com.javaclaw.sandbox.api.SandboxLaunchResponse;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionControl;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;

/** The only process start point in the 4.0 runtime; child policy travels over stdin. */
public final class LauncherProcessSandboxExecutor implements SandboxExecutor {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_DIAGNOSTIC_BYTES = 64 * 1024;

    private final List<String> launcherCommand;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    /** 复制打包提供的固定 Launcher argv；禁止将模型或客户端字符串作为启动器命令。 */
    public LauncherProcessSandboxExecutor(List<String> launcherCommand) {
        if (launcherCommand == null || launcherCommand.isEmpty()) {
            throw new IllegalArgumentException("launcher command must not be empty");
        }
        this.launcherCommand = List.copyOf(launcherCommand);
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /** 为给定模块路径生成命名模块 Launcher argv；FFM 权限仅授予辅助 JVM，不授予 App Server。 */
    public static List<String> modularJavaCommand(Path modulePath) {
        return modularJavaCommand(List.of(Objects.requireNonNull(modulePath, "modulePath")));
    }

    /**
     * 为非空的模块路径列表生成 Launcher argv；同时支持发行目录、IDE 编译目录和依赖 JAR。 各项单独归一化后以平台路径分隔符连接，不把整个路径列表误当成单个文件路径。
     *
     * @param modulePaths 非空且不含 null 的路径列表，调用方负责限定为 Native Host 所需模块
     * @return 只向 Native Host 命名模块授予 FFM 权限的不可变 argv
     */
    public static List<String> modularJavaCommand(List<Path> modulePaths) {
        Objects.requireNonNull(modulePaths, "modulePaths");
        if (modulePaths.isEmpty()) {
            throw new IllegalArgumentException("sandbox module path must not be empty");
        }
        String modulePath = modulePaths.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                        .toString(),
                "--enable-native-access=com.javaclaw.nativehosts",
                "--module-path",
                modulePath,
                "-m",
                "com.javaclaw.nativehosts/com.javaclaw.nativehost.sandbox.SandboxLauncherMain");
    }

    @Override
    public SandboxResult execute(SandboxCommand command) throws SandboxExecutionException {
        Objects.requireNonNull(command, "command");
        String nonce = nonce();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(launcherCommand);
            builder.environment().clear();
            process = builder.start();
            Process launched = process;
            try (OutputStreamWriter request =
                    new OutputStreamWriter(launched.getOutputStream(), StandardCharsets.UTF_8)) {
                request.write(json.writeValueAsString(SandboxLaunchRequest.from(nonce, command)));
                request.write('\n');
            }
            try (ExecutorService readers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                Future<byte[]> stdout =
                        readers.submit(() -> readBounded(launched.getInputStream(), MAX_RESPONSE_BYTES));
                Future<byte[]> stderr =
                        readers.submit(() -> readBounded(launched.getErrorStream(), MAX_DIAGNOSTIC_BYTES));
                // Native backends may need a bounded post-target cleanup window (notably Windows
                // ACL restoration and AppContainer deletion) before the launcher can exit.
                long timeoutMillis = command.policy().timeout().plusSeconds(30).toMillis();
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    terminateTree(process);
                    throw new SandboxExecutionException("sandbox launcher timed out"
                            + (isWindows()
                                    ? "; JAVACLAW_WINDOWS_ACL_RESTORE_FAILURE: "
                                            + "native helper cleanup status is unknown"
                                    : ""));
                }
                byte[] responseBytes = stdout.get(5, TimeUnit.SECONDS);
                byte[] diagnosticBytes = stderr.get(5, TimeUnit.SECONDS);
                if (process.exitValue() != 0) {
                    throw new SandboxExecutionException(
                            "sandbox launcher exited with " + process.exitValue() + diagnostic(diagnosticBytes));
                }
                SandboxLaunchResponse response = parseResponse(responseBytes);
                if (!constantTimeEquals(nonce, response.nonce())) {
                    throw new SandboxExecutionException("sandbox launcher nonce mismatch");
                }
                if (response.error() != null) {
                    throw new SandboxExecutionException("sandbox refused execution: " + response.error());
                }
                return response.result();
            }
        } catch (SandboxExecutionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SandboxExecutionException("sandbox launcher failed", failure);
        } finally {
            if (process != null && process.isAlive()) {
                terminateTree(process);
            }
        }
    }

    @Override
    public SandboxSession openSession(SandboxCommand command, SandboxSessionOptions options)
            throws SandboxExecutionException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(options, "options");
        String nonce = nonce();
        Process process = null;
        try {
            List<String> sessionCommand = new java.util.ArrayList<>(launcherCommand);
            sessionCommand.add("--session");
            ProcessBuilder builder = new ProcessBuilder(sessionCommand);
            builder.environment().clear();
            process = builder.start();
            LauncherSession session = new LauncherSession(command.id(), nonce, process, json);
            session.sendInitial(SandboxLaunchRequest.forSession(nonce, command, options));
            SandboxSessionFrame ready = session.read(Duration.ofSeconds(10));
            if (ready == null) {
                throw new SandboxExecutionException("sandbox launcher did not acknowledge session");
            }
            if (ready.kind() == SandboxSessionFrame.Kind.ERROR) {
                throw new SandboxExecutionException("sandbox refused session: " + ready.detail());
            }
            if (ready.kind() != SandboxSessionFrame.Kind.READY) {
                throw new SandboxExecutionException("sandbox launcher returned " + ready.kind() + " before READY");
            }
            return session;
        } catch (SandboxExecutionException failure) {
            if (process != null) {
                terminateTree(process);
            }
            throw failure;
        } catch (Exception failure) {
            if (process != null) {
                terminateTree(process);
            }
            throw new SandboxExecutionException("sandbox session launch failed", failure);
        }
    }

    private SandboxLaunchResponse parseResponse(byte[] bytes) throws IOException {
        String value = new String(bytes, StandardCharsets.UTF_8).strip();
        if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("launcher returned an invalid frame count");
        }
        return json.readValue(value, SandboxLaunchResponse.class);
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > limit) {
                throw new IOException("launcher output exceeds limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String diagnostic(byte[] value) {
        String text = new String(value, StandardCharsets.UTF_8).strip();
        return text.isEmpty() ? "" : ": " + text;
    }

    private static void terminateTree(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException unavailable) {
            // Some hardened hosts deny process enumeration. The launcher backend still owns the
            // sandboxed tree (die-with-parent/new session or the platform job boundary).
        }
        process.destroyForcibly();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }

    private String nonce() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static final class LauncherSession implements SandboxSession {
        private static final int MAX_FRAME_CHARS = 3 * 1024 * 1024;
        private static final int MAX_DIAGNOSTIC_BYTES = 64 * 1024;

        private final String id;
        private final String nonce;
        private final Process process;
        private final ObjectMapper json;
        private final BufferedWriter control;
        private final LinkedBlockingQueue<QueuedFrame> frames = new LinkedBlockingQueue<>(256);
        private final Semaphore queuedBytes = new Semaphore(8 * 1024 * 1024);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicBoolean terminating = new AtomicBoolean();
        private final Thread stdoutReader;
        private final Thread stderrReader;

        private LauncherSession(String id, String nonce, Process process, ObjectMapper json) {
            this.id = id;
            this.nonce = nonce;
            this.process = process;
            this.json = json;
            this.control =
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdoutReader =
                    Thread.ofVirtual().name("sandbox-session-frames-" + id).start(this::readFrames);
            this.stderrReader =
                    Thread.ofVirtual().name("sandbox-session-diagnostics-" + id).start(this::readDiagnostics);
            process.onExit().thenRun(() -> alive.set(false));
        }

        private synchronized void sendInitial(SandboxLaunchRequest request) throws IOException {
            control.write(json.writeValueAsString(request));
            control.newLine();
            control.flush();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public SandboxSessionFrame read(Duration timeout) throws InterruptedException {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("timeout is negative");
            }
            QueuedFrame frame = frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (frame == null) {
                return null;
            }
            queuedBytes.release(frame.weight());
            return frame.value();
        }

        @Override
        public void write(byte[] input) throws Exception {
            Objects.requireNonNull(input, "input");
            if (input.length == 0) {
                return;
            }
            if (input.length > 1024 * 1024) {
                throw new IllegalArgumentException("sandbox stdin frame exceeds 1 MiB");
            }
            send(new SandboxSessionControl(
                    nonce,
                    SandboxSessionControl.Operation.STDIN,
                    Base64.getEncoder().encodeToString(input),
                    null,
                    null,
                    null));
        }

        @Override
        public void closeInput() throws Exception {
            send(new SandboxSessionControl(nonce, SandboxSessionControl.Operation.CLOSE_INPUT, "", null, null, null));
        }

        @Override
        public void resize(int columns, int rows) throws Exception {
            send(new SandboxSessionControl(nonce, SandboxSessionControl.Operation.RESIZE, "", columns, rows, null));
        }

        @Override
        public void signal(SandboxSignal signal) throws Exception {
            send(new SandboxSessionControl(
                    nonce,
                    SandboxSessionControl.Operation.SIGNAL,
                    "",
                    null,
                    null,
                    Objects.requireNonNull(signal, "signal")));
        }

        @Override
        public boolean isAlive() {
            // 协议流关闭不表示 OS 进程已经退出；资源清理和取消必须检查真实受监督进程。
            return process.isAlive();
        }

        @Override
        public void terminate() {
            if (!terminating.compareAndSet(false, true)) {
                return;
            }
            // 子进程拒绝读取 stdin 时，其他写者可能持有 control 锁；取消线程不得等待该锁才能回收进程。
            Thread signal = Thread.ofVirtual()
                    .name("sandbox-session-stop-" + id)
                    .start(() -> {
                        try {
                            send(new SandboxSessionControl(
                                    nonce, SandboxSessionControl.Operation.TERMINATE, "", null, null, null));
                        } catch (IOException ignored) {
                            // 已断开的管道由下方有界强制回收处理。
                        }
                    });
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            } finally {
                alive.set(false);
                if (process.isAlive()) {
                    terminateTree(process);
                    try {
                        process.waitFor(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                signal.interrupt();
                // 关闭仍可能等待底层 Writer；进程终止路径不能被不响应的管道实现再次阻塞。
                Thread.ofVirtual().name("sandbox-session-close-" + id).start(() -> {
                    try {
                        control.close();
                    } catch (IOException ignored) {
                    }
                });
                stdoutReader.interrupt();
                stderrReader.interrupt();
            }
        }

        private synchronized void send(SandboxSessionControl frame) throws IOException {
            if (!alive.get() || !isAlive()) {
                throw new IOException("sandbox session is closed");
            }
            control.write(json.writeValueAsString(frame));
            control.newLine();
            control.flush();
        }

        private void readFrames() {
            try (BufferedReader input =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = boundedLine(input)) != null) {
                    SandboxSessionFrame frame = json.readValue(line, SandboxSessionFrame.class);
                    if (!constantTimeEquals(nonce, frame.nonce())) {
                        throw new IOException("sandbox session nonce mismatch");
                    }
                    if (!offer(frame)) {
                        throw new IOException("sandbox session consumer queue overflow");
                    }
                    if (frame.kind() == SandboxSessionFrame.Kind.EXIT
                            || frame.kind() == SandboxSessionFrame.Kind.ERROR) {
                        alive.set(false);
                    }
                }
            } catch (Exception failure) {
                fail("sandbox session protocol failure: " + diagnostic(failure));
            } finally {
                alive.set(false);
            }
        }

        private void readDiagnostics() {
            try {
                byte[] value = readBounded(process.getErrorStream(), MAX_DIAGNOSTIC_BYTES);
                if (value.length > 0 && alive.get()) {
                    fail("sandbox launcher diagnostic: " + new String(value, StandardCharsets.UTF_8).strip());
                }
            } catch (Exception failure) {
                if (alive.get()) {
                    fail("cannot read sandbox diagnostics");
                }
            }
        }

        private void fail(String message) {
            var failure = SandboxSessionFrame.error(
                    nonce, message == null || message.isBlank() ? "sandbox session failed" : message);
            if (!offer(failure)) {
                for (QueuedFrame discarded; (discarded = frames.poll()) != null; ) {
                    queuedBytes.release(discarded.weight());
                }
                offer(failure);
            }
            alive.set(false);
            if (process.isAlive()) {
                terminateTree(process);
            }
        }

        private boolean offer(SandboxSessionFrame value) {
            int weight = 256 + 2 * (value.dataBase64().length() + value.detail().length());
            if (!queuedBytes.tryAcquire(weight)) {
                return false;
            }
            if (!frames.offer(new QueuedFrame(value, weight))) {
                queuedBytes.release(weight);
                return false;
            }
            return true;
        }

        private static String boundedLine(BufferedReader input) throws IOException {
            var line = new StringBuilder(4096);
            for (int character; (character = input.read()) != -1; ) {
                if (character == '\n') {
                    return line.toString();
                }
                if (line.length() == MAX_FRAME_CHARS) {
                    throw new IOException("sandbox session frame exceeds limit");
                }
                line.append((char) character);
            }
            return line.isEmpty() ? null : line.toString();
        }

        private record QueuedFrame(SandboxSessionFrame value, int weight) {}

        private static String diagnostic(Exception failure) {
            String value = failure.getMessage();
            return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
        }
    }
}
