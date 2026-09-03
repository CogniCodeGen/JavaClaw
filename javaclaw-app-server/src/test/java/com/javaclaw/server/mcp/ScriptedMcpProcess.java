package com.javaclaw.server.mcp;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;

/** 为 MCP stdio 测试提供双向、可阻塞且不依赖读写线程身份的脚本进程。 */
final class ScriptedMcpProcess extends Process {
    private static final int PIPE_BUFFER_BYTES = 64 * 1024;
    private static final Duration PEER_COMPLETION_TIMEOUT = Duration.ofSeconds(2);

    private final OutputStream clientInput;
    private final InputStream clientOutput;
    private final InputStream serverInput;
    private final OutputStream serverOutput;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CountDownLatch peerCompleted = new CountDownLatch(1);
    private final Thread server;

    ScriptedMcpProcess(Script script) throws IOException {
        Pipe requests = Pipe.open();
        Pipe responses = Pipe.open();
        clientInput = Channels.newOutputStream(requests.sink());
        serverInput = Channels.newInputStream(requests.source());
        serverOutput = Channels.newOutputStream(responses.sink());
        InputStream responseInput = Channels.newInputStream(responses.source());
        clientOutput = new PeerFailureInputStream(
                new BufferedInputStream(responseInput, PIPE_BUFFER_BYTES), failure, peerCompleted);
        server = Thread.startVirtualThread(() -> runPeer(script));
    }

    void assertSucceeded() throws InterruptedException {
        server.join(PEER_COMPLETION_TIMEOUT);
        if (failure.get() != null) {
            throw new AssertionError("scripted MCP peer failed", failure.get());
        }
        if (server.isAlive()) {
            throw new AssertionError("scripted MCP peer did not finish");
        }
    }

    @Override
    public OutputStream getOutputStream() {
        return clientInput;
    }

    @Override
    public InputStream getInputStream() {
        return clientOutput;
    }

    @Override
    public InputStream getErrorStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() throws InterruptedException {
        server.join();
        return exitCode();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        server.join(Duration.ofNanos(unit.toNanos(timeout)));
        return !server.isAlive();
    }

    @Override
    public int exitValue() {
        if (server.isAlive()) {
            throw new IllegalThreadStateException("scripted MCP peer is alive");
        }
        return exitCode();
    }

    @Override
    public void destroy() {
        closeQuietly(clientInput);
        closeQuietly(clientOutput);
    }

    @Override
    public Process destroyForcibly() {
        destroy();
        server.interrupt();
        return this;
    }

    @Override
    public boolean isAlive() {
        return server.isAlive();
    }

    private void runPeer(Script script) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
                BufferedWriter output =
                        new BufferedWriter(new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8))) {
            script.run(new Peer(input, output));
        } catch (Throwable problem) {
            failure.set(problem);
        } finally {
            peerCompleted.countDown();
        }
    }

    private int exitCode() {
        return failure.get() == null ? 0 : 1;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 测试进程可能已在完整帧或取消路径中关闭管道。
        }
    }

    @FunctionalInterface
    interface Script {
        void run(Peer peer) throws Exception;
    }

    record Peer(BufferedReader input, BufferedWriter output) {
        CanonicalPayload read(CanonicalJson json) throws IOException {
            String line = input.readLine();
            if (line == null) {
                throw new IOException("client closed scripted MCP pipe");
            }
            return json.parse(line);
        }

        void write(CanonicalJson json, Object value) throws IOException {
            output.write(json.encode(value).json());
            output.newLine();
            output.flush();
        }
    }

    /** EOF 只在脚本线程完成后向客户端发布；由 CountDownLatch 建立 failure 写入与读取之间的 happens-before。 */
    private static final class PeerFailureInputStream extends FilterInputStream {
        private final AtomicReference<Throwable> failure;
        private final CountDownLatch peerCompleted;

        private PeerFailureInputStream(
                InputStream input, AtomicReference<Throwable> failure, CountDownLatch peerCompleted) {
            super(input);
            this.failure = failure;
            this.peerCompleted = peerCompleted;
        }

        @Override
        public int read() throws IOException {
            return publishPeerFailure(super.read());
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return publishPeerFailure(super.read(bytes, offset, length));
        }

        private int publishPeerFailure(int read) throws IOException {
            if (read >= 0) {
                return read;
            }
            awaitPeerCompletion();
            Throwable problem = failure.get();
            if (problem != null) {
                throw new IOException("scripted MCP peer failed before completing a response", problem);
            }
            return -1;
        }

        private void awaitPeerCompletion() throws IOException {
            try {
                if (!peerCompleted.await(PEER_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("scripted MCP peer did not finish after closing stdout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while awaiting scripted MCP peer", interrupted);
            }
        }
    }
}
