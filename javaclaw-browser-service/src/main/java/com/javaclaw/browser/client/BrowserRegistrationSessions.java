package com.javaclaw.browser.client;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.browser.protocol.BrowserRegistrationProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.WorkerStatus;
import com.javaclaw.protocol.CanonicalJson;

/** Workspace 登记连接的宿主所有者；每个 Entry 串行化完成与撤销，截止时间回收整个原生进程树。 */
final class BrowserRegistrationSessions implements BrowserRegistrationPort, AutoCloseable {
    private final InteractiveBrowserSessions.Launcher launcher;
    private final Duration timeout;
    private final CanonicalJson json = new CanonicalJson();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    BrowserRegistrationSessions(InteractiveBrowserSessions.Launcher launcher, Duration timeout) {
        this.launcher = launcher;
        this.timeout = timeout;
    }

    @Override
    public synchronized WorkerStatus begin(SiteRegistrationContracts.WorkerTask task,
            InteractiveBrowserNetworkExchange network, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (closed || entries.containsKey(task.sessionId()) || activeCount() >= 8) {
            throw new IllegalStateException("Browser registration cannot start");
        }
        if (!task.lease().active(Instant.now()) || task.lease().expiresAt().isAfter(Instant.now().plusSeconds(900))) {
            throw new IllegalArgumentException("Browser registration lease exceeds deadline");
        }
        try {
            Entry entry = new Entry(task, new InteractiveBrowserConnection(launcher.start(), task.lease(), network, timeout));
            entries.put(task.sessionId(), entry);
            try (Packet packet = entry.connection.open(BrowserRegistrationProtocol.OPEN, task, new byte[0], cancellation)) {
                entry.accept(json.decode(packet.frame().payload(), WorkerStatus.class));
                expireAtDeadline(entry);
                trimHistory();
                return entry.status;
            } catch (RuntimeException failure) {
                entry.stop(State.FAILED);
                throw failure;
            }
        } catch (IOException failure) {
            throw new BrowserWorkerException("Browser registration native runtime is unavailable", failure);
        }
    }

    @Override
    public WorkerStatus status(String id) {
        Entry entry = require(id);
        synchronized (entry) {
            if (entry.expiredOrTerminal()) {
                return entry.status;
            }
            try (Packet packet = entry.connection.call(InteractiveBrowserProtocol.STATUS, Map.of(), new byte[0],
                    InteractiveBrowserConnection.NONE)) {
                entry.accept(json.decode(packet.frame().payload(), WorkerStatus.class));
                return entry.status;
            } catch (RuntimeException failure) {
                entry.stop(Instant.now().isBefore(entry.lease.expiresAt()) ? State.FAILED : State.EXPIRED);
                return entry.status;
            }
        }
    }

    @Override
    public WorkerStatus updateLease(String id, BrowserContracts.AccessLease lease, CancellationToken cancellation) {
        Entry entry = require(id);
        synchronized (entry) {
            entry.requireActive();
            if (lease.mode() != BrowserContracts.ControlMode.HUMAN || lease.generation() <= entry.lease.generation()
                    || !lease.expiresAt().equals(entry.lease.expiresAt())) {
                throw new IllegalArgumentException("Registration lease must preserve HUMAN mode and deadline");
            }
            entry.lease = lease;
            entry.connection.lease(lease);
            try (Packet packet = entry.connection.call(InteractiveBrowserProtocol.LEASE, lease, new byte[0], cancellation)) {
                entry.accept(json.decode(packet.frame().payload(), WorkerStatus.class));
                return entry.status;
            } catch (RuntimeException failure) {
                entry.stop(State.FAILED);
                throw failure;
            }
        }
    }

    @Override
    public <T> T complete(String id, SiteRegistrationContracts.CompleteRequest request,
            BrowserRegistrationHandler<T> handler) {
        Entry entry = require(id);
        synchronized (entry) {
            entry.requireActive();
            if (!id.equals(request.sessionId()) || request.expectedGeneration() != entry.lease.generation()) {
                throw new IllegalArgumentException("Registration confirmation belongs to another lease");
            }
            try (Packet packet = entry.connection.call(BrowserRegistrationProtocol.COMPLETE, request, new byte[0],
                    InteractiveBrowserConnection.NONE)) {
                return save(entry, request, packet, handler);
            } catch (RuntimeException failure) {
                entry.stop(State.FAILED);
                throw failure;
            } finally {
                entry.connection.close();
            }
        }
    }

    private <T> T save(Entry entry, SiteRegistrationContracts.CompleteRequest request, Packet packet,
            BrowserRegistrationHandler<T> handler) {
        var result = json.decode(packet.frame().payload(), BrowserRegistrationProtocol.PrivateResult.class);
        entry.accept(result.status());
        byte[] combined = packet.bytes();
        byte[] state = new byte[0];
        byte[] credentials = new byte[0];
        try {
            if (result.stateBytes() + result.credentialBytes() != combined.length
                    || result.status().page().pageRevision() != request.expectedPageRevision()
                    || (result.credentialBytes() > 0) != request.credentialId().isPresent()) {
                throw new IllegalArgumentException("Registration private result identity mismatch");
            }
            state = Arrays.copyOfRange(combined, 0, result.stateBytes());
            credentials = Arrays.copyOfRange(combined, result.stateBytes(), combined.length);
            T saved = handler.handle(result.status(), state, credentials);
            entry.stop(State.CANCELLED);
            return saved;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BrowserWorkerException("Registration private result could not be saved");
        } finally {
            Arrays.fill(combined, (byte) 0);
            Arrays.fill(state, (byte) 0);
            Arrays.fill(credentials, (byte) 0);
        }
    }

    @Override
    public WorkerStatus cancel(String id) {
        Entry entry = require(id);
        synchronized (entry) {
            if (!entry.expiredOrTerminal()) {
                entry.stop(State.CANCELLED);
            }
            return entry.status;
        }
    }

    private synchronized Entry require(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Browser registration is unknown");
        }
        return entry;
    }

    private long activeCount() {
        return entries.values().stream().filter(entry -> entry.status.state() == State.ACTIVE).count();
    }

    private void trimHistory() {
        var iterator = entries.values().iterator();
        while (entries.size() > 64 && iterator.hasNext()) {
            if (iterator.next().status.state() != State.ACTIVE) {
                iterator.remove();
            }
        }
    }

    private static void expireAtDeadline(Entry entry) {
        Thread.ofVirtual().name("browser-registration-deadline").start(() -> {
            try {
                Duration remaining = Duration.between(Instant.now(), entry.lease.expiresAt());
                if (remaining.isPositive()) {
                    Thread.sleep(remaining);
                }
                // 截止回收不等待业务锁，避免阻塞中的 Broker 或持久化让 Worker 延长生命周期。
                entry.connection.close();
                synchronized (entry) {
                    entry.expiredOrTerminal();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                entry.connection.close();
            }
        });
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Entry entry : entries.values()) {
            entry.connection.close();
        }
        entries.clear();
    }

    private static final class Entry {
        private final InteractiveBrowserConnection connection;
        private volatile BrowserContracts.AccessLease lease;
        private volatile WorkerStatus status;

        private Entry(SiteRegistrationContracts.WorkerTask task, InteractiveBrowserConnection connection) {
            this.connection = connection;
            lease = task.lease();
            status = new WorkerStatus(task.sessionId(), State.ACTIVE,
                    new SiteRegistrationContracts.Access(lease.generation(), lease.allowedOrigins(), java.util.Set.of(),
                            lease.expiresAt()),
                    new SiteRegistrationContracts.Page(0, Optional.empty(), "", List.of()));
        }

        private void accept(WorkerStatus next) {
            if (!next.sessionId().equals(status.sessionId()) || next.access().generation() != lease.generation()
                    || !next.access().allowedOrigins().equals(lease.allowedOrigins())
                    || !next.access().expiresAt().equals(lease.expiresAt())) {
                throw new BrowserWorkerException("Registration reply identity mismatch");
            }
            status = next;
        }

        private boolean expiredOrTerminal() {
            if (status.state() == State.ACTIVE && !Instant.now().isBefore(lease.expiresAt())) {
                stop(State.EXPIRED);
            }
            return status.state() != State.ACTIVE;
        }

        private void requireActive() {
            if (expiredOrTerminal()) {
                throw new IllegalStateException("Browser registration is no longer active");
            }
        }

        private void stop(State terminal) {
            status = new WorkerStatus(status.sessionId(), terminal, status.access(), status.page());
            connection.close();
        }
    }
}
