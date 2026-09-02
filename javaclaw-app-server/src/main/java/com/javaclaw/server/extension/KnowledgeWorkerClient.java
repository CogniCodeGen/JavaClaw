package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerLauncher;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;

/** 为每次解析启动一个无原始网络、无宿主文件权限的独立 Knowledge Worker。 */
public final class KnowledgeWorkerClient implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final WorkerLauncher launcher;
    private final Duration timeout;
    private final CanonicalJson json = new CanonicalJson();
    private final Set<Process> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * 创建只通过平台原生 Sandbox 启动的客户端。
     *
     * @param command 无 HOME、Workspace 和原始网络的 Worker 描述
     * @param timeout 单次解析宿主上限
     */
    public KnowledgeWorkerClient(SandboxedWorkerCommand command, Duration timeout) {
        SandboxedWorkerLauncher sandbox = new SandboxedWorkerLauncher();
        SandboxedWorkerCommand checked = Objects.requireNonNull(command, "command");
        launcher = () -> sandbox.start(checked);
        this.timeout = timeout(timeout);
    }

    KnowledgeWorkerClient(WorkerLauncher launcher, Duration timeout) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.timeout = timeout(timeout);
    }

    /**
     * 把已校验 Core Attachment 通过私有二进制帧交给 Worker。
     *
     * @param attachment 元数据与原始内容
     * @param maxCharacters 最大提取字符数
     * @param cancellation Job 取消信号
     * @return 提取结果
     */
    public KnowledgeContracts.ExtractionResult extract(
            AttachmentContent attachment, int maxCharacters, CancellationToken cancellation) {
        requireOpen();
        AttachmentContent checked = Objects.requireNonNull(attachment, "attachment");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        byte[] content = checked.content();
        FutureTask<KnowledgeContracts.ExtractionResult> exchange =
                new FutureTask<>(() -> run(checked, maxCharacters, content));
        Thread.ofVirtual().name("javaclaw-knowledge-client").start(exchange);
        try {
            return await(exchange, checkedCancellation);
        } finally {
            Arrays.fill(content, (byte) 0);
            if (!exchange.isDone()) {
                exchange.cancel(true);
                active.forEach(KnowledgeWorkerClient::destroy);
            }
        }
    }

    private KnowledgeContracts.ExtractionResult run(AttachmentContent attachment, int maxCharacters, byte[] content)
            throws Exception {
        Process process = launcher.start();
        active.add(process);
        try {
            KnowledgeWorkerProtocol.Request request = new KnowledgeWorkerProtocol.Request(
                    KnowledgeWorkerProtocol.VERSION,
                    attachment.metadata().mediaType(),
                    attachment.metadata().digest(),
                    content.length,
                    maxCharacters);
            byte[] encoded = json.encode(request).json().getBytes(StandardCharsets.UTF_8);
            LengthPrefixedFraming.write(process.getOutputStream(), encoded, KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
            LengthPrefixedFraming.write(
                    process.getOutputStream(), content, KnowledgeWorkerProtocol.MAXIMUM_CONTENT_BYTES);
            byte[] responseFrame =
                    LengthPrefixedFraming.read(process.getInputStream(), KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
            KnowledgeWorkerProtocol.Response response = json.decode(
                    json.parse(new String(responseFrame, StandardCharsets.UTF_8)),
                    KnowledgeWorkerProtocol.Response.class);
            return response.result()
                    .orElseThrow(() -> new IllegalStateException("Knowledge Worker rejected request: "
                            + response.error().orElseThrow()));
        } finally {
            active.remove(process);
            closeInput(process);
            destroy(process);
        }
    }

    private KnowledgeContracts.ExtractionResult await(
            FutureTask<KnowledgeContracts.ExtractionResult> exchange, CancellationToken cancellation) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            cancellation.throwIfCancelled();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("Knowledge Worker call timed out");
            }
            try {
                return exchange.get(Math.min(remaining, POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException pending) {
                // 短轮询只用于传播取消和宿主总时限。
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Knowledge Worker call was interrupted", interrupted);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Knowledge Worker process failed", failure.getCause());
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Knowledge Worker client is closed");
        }
    }

    /** 终止全部活动 Worker；幂等。 */
    @Override
    public void close() {
        closed = true;
        active.forEach(KnowledgeWorkerClient::destroy);
        active.clear();
    }

    private static void closeInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Worker 退出时可能已经关闭同一管道。
        }
    }

    private static void destroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static Duration timeout(Duration value) {
        Duration checked = Objects.requireNonNull(value, "timeout");
        if (checked.compareTo(Duration.ofSeconds(1)) < 0 || checked.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 second and 2 minutes");
        }
        return checked;
    }

    @FunctionalInterface
    interface WorkerLauncher {
        Process start() throws IOException;
    }
}
