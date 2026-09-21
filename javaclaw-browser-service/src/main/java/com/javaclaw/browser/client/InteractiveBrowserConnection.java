package com.javaclaw.browser.client;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PrivateNetworkGrant;
import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Frame;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Kind;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 宿主唯一接收线程分发命令回执与反向请求；超时和取消不重试，终止进程以避免迟到动作。 */
final class InteractiveBrowserConnection implements AutoCloseable {
    static final CancellationToken NONE = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Optional<String> reason() {
            return Optional.empty();
        }
    };
    private final InteractiveBrowserProcess process;
    private final InteractiveBrowserNetworkExchange network;
    private final Duration timeout;
    private final CanonicalJson json = new CanonicalJson();
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<Long, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private final Object outputLock = new Object();
    private final Object commandLock = new Object();
    private volatile BrowserContracts.SessionView view;
    private volatile BrowserContracts.AccessLease currentLease;
    private volatile CancellationToken cancellation = NONE;
    private volatile boolean closed;
    private long networkSequence;

    InteractiveBrowserConnection(
            InteractiveBrowserProcess process,
            BrowserContracts.OpenTask task,
            InteractiveBrowserNetworkExchange network,
            Duration timeout) {
        this(process, task.lease(), network, timeout);
        view = new BrowserContracts.SessionView(
                task.sessionId(), task.owner(), BrowserContracts.SessionState.OPEN, task.lease(), List.of());
    }

    InteractiveBrowserConnection(
            InteractiveBrowserProcess process,
            BrowserContracts.AccessLease lease,
            InteractiveBrowserNetworkExchange network,
            Duration timeout) {
        this.process = process;
        this.currentLease = lease;
        this.network = network;
        this.timeout = timeout;
    }

    Packet open(BrowserContracts.OpenTask task, byte[] state, CancellationToken token) {
        return open(InteractiveBrowserProtocol.OPEN, task, state, token);
    }

    Packet open(String operation, Object task, byte[] state, CancellationToken token) {
        long id = sequence.incrementAndGet();
        CompletableFuture<Packet> future = new CompletableFuture<>();
        pending.put(id, future);
        cancellation = token;
        try {
            synchronized (outputLock) {
                BrowserFrameIo.writeJson(
                        process.process().getOutputStream(),
                        json,
                        new BrowserWorkerProtocol.Command(
                                BrowserWorkerProtocol.VERSION, id, operation, json.encode(task), state.length));
                BrowserFrameIo.writeBinary(
                        process.process().getOutputStream(), state, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
            }
            Thread.ofVirtual().name("browser-interactive-replies").start(this::read);
            return await(id, future, token);
        } catch (IOException failure) {
            close();
            throw new BrowserWorkerException("Browser startup channel failed", failure);
        }
    }

    Packet call(String operation, Object payload, byte[] bytes, CancellationToken token) {
        synchronized (commandLock) {
            token.throwIfCancelled();
            requireOpen();
            process.touch().run();
            long id = sequence.incrementAndGet();
            CompletableFuture<Packet> future = new CompletableFuture<>();
            pending.put(id, future);
            if (token != NONE) {
                cancellation = token;
            }
            try {
                send(
                        new Frame(Kind.COMMAND, id, operation, json.encode(payload), bytes.length, Optional.empty()),
                        bytes);
                return await(id, future, token);
            } catch (IOException failure) {
                close();
                throw new BrowserWorkerException("Browser command channel failed", failure);
            }
        }
    }

    BrowserContracts.SessionView view() {
        return view;
    }

    synchronized void view(BrowserContracts.SessionView value) {
        if (!value.sessionId().equals(view.sessionId()) || !value.owner().equals(view.owner())) {
            close();
            throw new BrowserWorkerException("Browser reply owner mismatch");
        }
        if (!value.lease().equals(currentLease)) {
            throw new BrowserWorkerException("BROWSER_STALE_OBSERVATION");
        }
        if (closed) {
            throw new BrowserWorkerException("Browser session is closed");
        }
        view = value;
    }

    synchronized void lease(BrowserContracts.AccessLease lease) {
        if (lease.generation() <= currentLease.generation()) {
            throw new IllegalArgumentException("Browser control generation must increase");
        }
        currentLease = lease;
        if (view != null) {
            view = new BrowserContracts.SessionView(view.sessionId(), view.owner(), view.state(), lease, view.tabs());
        }
    }

    private Packet await(long id, CompletableFuture<Packet> future, CancellationToken token) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean delivered = false;
        try {
            while (true) {
                if (token.isCancelled()) {
                    close();
                    token.throwIfCancelled();
                }
                if (System.nanoTime() >= deadline) {
                    close();
                    throw new BrowserWorkerException("BROWSER_ACTION_UNCONFIRMED");
                }
                try {
                    Packet packet = future.get(50, TimeUnit.MILLISECONDS);
                    if (packet.frame().error().isPresent()) {
                        String code = packet.frame().error().orElseThrow();
                        packet.close();
                        throw new BrowserWorkerException(
                                code.equals("BROWSER_SCREENSHOT_PRIVATE_SESSION")
                                        ? "包含登录凭据或经过人工接管的会话仅提供文本观察；新建无凭据会话可使用截图"
                                        : code);
                    }
                    delivered = true;
                    return packet;
                } catch (TimeoutException waiting) {
                    // 短轮询只用于取消，不会重新发送命令。
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new BrowserWorkerException("BROWSER_ACTION_UNCONFIRMED");
        } catch (ExecutionException failed) {
            throw new BrowserWorkerException("BROWSER_ACTION_UNCONFIRMED");
        } finally {
            pending.remove(id);
            if (!delivered) {
                // 取消可与私有回复同时到达；未交给调用者的缓冲区必须由等待者回收，不能遗留在已完成 Future。
                future.thenAccept(Packet::close);
            }
        }
    }

    private void read() {
        try {
            while (!closed) {
                Frame frame = BrowserFrameIo.readJson(process.process().getInputStream(), json, Frame.class);
                byte[] bytes = BrowserFrameIo.readBinary(
                        process.process().getInputStream(),
                        frame.binaryBytes(),
                        BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
                try {
                    if (frame.kind() == Kind.NETWORK) {
                        exchange(frame, bytes);
                    } else if (frame.kind() == Kind.DENIED_ORIGIN) {
                        deniedOrigin(frame);
                    } else if (frame.kind() == Kind.REPLY) {
                        complete(frame, bytes);
                    } else {
                        throw new IOException("unexpected Browser frame direction");
                    }
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (closed) {
                return;
            }
            closed = true;
            markTerminal(BrowserContracts.SessionState.FAILED);
            pending.values().forEach(value -> value.completeExceptionally(new IOException("Browser connection ended")));
            process.close();
        }
    }

    private void complete(Frame frame, byte[] bytes) throws IOException {
        CompletableFuture<Packet> result = pending.get(frame.id());
        Packet packet = new Packet(frame, bytes);
        if (result == null || !result.complete(packet)) {
            packet.close();
            throw new IOException("unexpected Browser reply identity");
        }
    }

    private void deniedOrigin(Frame frame) throws IOException {
        if (frame.id() != ++networkSequence || frame.binaryBytes() != 0) {
            throw new IOException("Browser origin notification frame is invalid");
        }
        var access = currentLease;
        if (!access.active(Instant.now()) || !Long.toString(access.generation()).equals(frame.operation())) {
            return;
        }
        URI origin = PrivateNetworkGrant.normalizeOrigin(
                json.decode(frame.payload(), InteractiveBrowserProtocol.OriginNotice.class)
                        .origin());
        if (!access.allowedOrigins().contains(origin)) {
            network.deniedOrigin(origin, access.generation());
        }
    }

    private void exchange(Frame frame, byte[] body) throws IOException {
        if (frame.id() != ++networkSequence) {
            throw new IOException("Browser network sequence mismatch");
        }
        BrowserContracts.AccessLease initial = currentLease;
        try {
            requireLease(initial);
            if (!Long.toString(initial.generation()).equals(frame.operation())) {
                throw new IllegalStateException("Browser request belongs to a retired control lease");
            }
            BrowserContracts.NetworkRequest request =
                    json.decode(frame.payload(), BrowserContracts.NetworkRequest.class);
            requireOrigin(initial, request.uri());
            BrowserNetworkResult result = network.exchange(request, body, networkCancellation(initial));
            requireLease(initial);
            byte[] bytes = result.body();
            try {
                send(
                        new Frame(
                                Kind.NETWORK_REPLY,
                                frame.id(),
                                "",
                                json.encode(result.response()),
                                bytes.length,
                                Optional.empty()),
                        bytes);
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } catch (Exception denied) {
            send(
                    new Frame(
                            Kind.NETWORK_REPLY,
                            frame.id(),
                            "",
                            new CanonicalPayload("{}"),
                            0,
                            Optional.of("NETWORK_REQUEST_DENIED")),
                    new byte[0]);
        }
    }

    private CancellationToken networkCancellation(BrowserContracts.AccessLease initial) {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return closed
                        || !initial.equals(currentLease)
                        || !initial.active(Instant.now())
                        || initial.mode() == BrowserContracts.ControlMode.ASSISTANT && cancellation.isCancelled();
            }

            @Override
            public Optional<String> reason() {
                return isCancelled() ? Optional.of("Browser lease inactive") : Optional.empty();
            }
        };
    }

    void requireLease(BrowserContracts.AccessLease initial) {
        networkCancellation(initial).throwIfCancelled();
    }

    private static void requireOrigin(BrowserContracts.AccessLease lease, URI uri) {
        try {
            URI origin = PrivateNetworkGrant.normalizeOrigin(
                    new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null));
            if (uri.getUserInfo() != null || !lease.allowedOrigins().contains(origin)) {
                throw new IllegalArgumentException("Browser network origin is outside lease");
            }
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Browser network origin is invalid");
        }
    }

    private void send(Frame frame, byte[] bytes) throws IOException {
        synchronized (outputLock) {
            BrowserFrameIo.writeJson(process.process().getOutputStream(), json, frame);
            BrowserFrameIo.writeBinary(
                    process.process().getOutputStream(), bytes, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
        }
    }

    private void markTerminal(BrowserContracts.SessionState state) {
        if (view != null) {
            view = new BrowserContracts.SessionView(view.sessionId(), view.owner(), state, currentLease, List.of());
        }
    }

    private void requireOpen() {
        if (closed || !process.process().isAlive()) {
            throw new BrowserWorkerException("Browser session is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending.values().forEach(value -> value.completeExceptionally(new IOException("Browser connection closed")));
        try {
            process.close();
            markTerminal(BrowserContracts.SessionState.CLOSED);
        } catch (RuntimeException failure) {
            markTerminal(BrowserContracts.SessionState.FAILED);
            throw failure;
        }
    }
}
