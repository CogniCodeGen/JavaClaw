package com.javaclaw.server.security;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.server.toolchain.ToolchainArtifactDownloadPort;

/**
 * 固定 DNS 地址及 TLS 主机校验的公共制品下载器，逐跳检查 HTTPS/443 与全部解析地址。
 *
 * <p>无代理、凭据、Cookie 或自动重定向；最多五次重定向、512 MiB、十分钟。数据只以固定缓冲流入暂存目标，摘要与原子安装归安装器。
 */
public final class PinnedArtifactDownloader implements ToolchainArtifactDownloadPort {
    private static final long MAXIMUM_BYTES = 512L * 1024 * 1024;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private final BrokerTarget.HostResolver resolver;
    private final HttpWireExchange.Transport transport;
    private final Duration timeout;

    /** 使用系统解析器与不读取宿主代理配置的直接 TLS 传输。 */
    public PinnedArtifactDownloader() {
        this(new BrokerTarget.SystemHostResolver(), new SocketBrokerTransport(), Duration.ofMinutes(10));
    }

    PinnedArtifactDownloader(
            BrokerTarget.HostResolver resolver, HttpWireExchange.Transport transport, Duration timeout) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("artifact download deadline must be within ten minutes");
        }
    }

    @Override
    public void download(ToolchainArtifact artifact, OutputStream target, CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        long maximum = Math.min(MAXIMUM_BYTES, artifact.downloadBytes());
        BrokerDeadline deadline = BrokerDeadline.start(timeout);
        try (DownloadScope scope = new DownloadScope(target, deadline, cancellation)) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                transfer(artifact.downloadUri(), maximum, deadline, cancellation, scope);
                return null;
            });
            Thread worker =
                    Thread.ofVirtual().name("javaclaw-artifact-download").start(task);
            try {
                await(task, deadline, cancellation);
            } finally {
                // 先冻结写入并关闭当前 socket，再返回调用方；失败后安装器可安全删除暂存文件。
                scope.close();
                task.cancel(true);
                worker.interrupt();
            }
        }
    }

    private void transfer(
            URI initial, long maximum, BrokerDeadline deadline, CancellationToken cancellation, DownloadScope scope)
            throws IOException {
        URI current = initial;
        for (int redirects = 0; redirects <= 5; redirects++) {
            BrokerTarget target = resolve(current, deadline, cancellation);
            try (var connection = transport.open(target, deadline, cancellation, scope::resource)) {
                scope.resource(connection);
                writeRequest(connection.output(), target);
                var reader = new ArtifactHttpReader(new BufferedInputStream(connection.input(), 32 * 1024));
                var head = reader.head();
                if (head.status() == 200) {
                    reader.body(head, scope, maximum);
                    return;
                }
                if (!REDIRECTS.contains(head.status()) || redirects == 5) {
                    throw new IOException("artifact HTTP status or redirect count is invalid: " + head.status());
                }
                current = redirect(current, head.headers().getOrDefault("location", List.of()));
            }
        }
        throw new IOException("artifact redirect limit exceeded");
    }

    private BrokerTarget resolve(URI uri, BrokerDeadline deadline, CancellationToken cancellation) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new SecurityException("artifact redirects require credential-free HTTPS on port 443");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return BrokerTarget.resolve(
                URI.create(uri.toASCIIString()),
                new NetworkPermission(Set.of(host), Set.of(443), true),
                resolver,
                deadline,
                cancellation);
    }

    private static URI redirect(URI previous, List<String> locations) throws IOException {
        if (locations.size() != 1 || locations.getFirst().isBlank()) {
            throw new IOException("artifact redirect requires one Location header");
        }
        try {
            return previous.resolve(URI.create(locations.getFirst()));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid artifact redirect URI", invalid);
        }
    }

    private static void writeRequest(OutputStream output, BrokerTarget target) throws IOException {
        String head = "GET " + target.requestTarget() + " HTTP/1.1\r\nHost: " + target.hostHeader()
                + "\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n";
        if (head.length() > 16 * 1024) {
            throw new IOException("artifact request target exceeds byte limit");
        }
        output.write(head.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void await(FutureTask<Void> task, BrokerDeadline deadline, CancellationToken cancellation)
            throws Exception {
        try {
            while (true) {
                try {
                    task.get(deadline.timeoutMillis(cancellation, 100), TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException pending) {
                    // 等待使用跨 DNS、TLS、重定向及正文的同一个截止时间。
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("artifact download interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("artifact download failed", failure.getCause());
        }
    }

    /** 关闭与输出写入互斥，防止取消返回后后台线程继续修改安装暂存文件。 */
    private static final class DownloadScope extends OutputStream {
        private final OutputStream target;
        private final BrokerDeadline deadline;
        private final CancellationToken cancellation;
        private AutoCloseable resource;
        private boolean closed;

        private DownloadScope(OutputStream target, BrokerDeadline deadline, CancellationToken cancellation) {
            this.target = target;
            this.deadline = deadline;
            this.cancellation = cancellation;
        }

        synchronized void resource(AutoCloseable value) {
            if (closed) {
                closeResource(value);
                throw new IllegalStateException("artifact download is closed");
            }
            resource = value;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int count) throws IOException {
            if (closed) {
                throw new IOException("artifact download is closed");
            }
            deadline.remaining(cancellation);
            target.write(bytes, offset, count);
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                closeResource(resource);
            }
        }

        private static void closeResource(AutoCloseable resource) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // 传输已被撤销；输出目标归调用方，必须保持打开以便安装器清理。
                }
            }
        }
    }
}
