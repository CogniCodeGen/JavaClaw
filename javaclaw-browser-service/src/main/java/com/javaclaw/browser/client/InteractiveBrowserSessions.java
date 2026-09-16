package com.javaclaw.browser.client;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol.Packet;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 宿主进程内持有常驻浏览器连接；导航与 Turn 更替不改变固定 Thread/账号所有权。 */
final class InteractiveBrowserSessions implements AutoCloseable {
    private final Launcher launcher;
    private final Duration timeout;
    private final CanonicalJson json = new CanonicalJson();
    private final Map<String, InteractiveBrowserConnection> sessions = new ConcurrentHashMap<>();
    private boolean closed;

    InteractiveBrowserSessions(Launcher launcher, Duration timeout) {
        this.launcher = launcher;
        this.timeout = timeout;
    }

    Launcher launcher() {
        return launcher;
    }

    synchronized BrowserActionResult open(
            BrowserContracts.OpenTask task,
            byte[] state,
            InteractiveBrowserNetworkExchange network,
            CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        if (closed) {
            throw new IllegalStateException("Interactive Browser client is closed");
        }
        if (sessions.containsKey(task.sessionId())) {
            throw new IllegalArgumentException("Browser session already exists");
        }
        sessions.entrySet().removeIf(entry -> entry.getValue().view().state() != BrowserContracts.SessionState.OPEN);
        if (sessions.size() >= 8) {
            throw new IllegalStateException("Browser process quota exceeded");
        }
        byte[] bytes = Objects.requireNonNull(state, "state").clone();
        if (bytes.length > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("Browser state exceeds limit");
        }
        InteractiveBrowserConnection connection = null;
        try {
            connection = new InteractiveBrowserConnection(launcher.start(), task, network, timeout);
            sessions.put(task.sessionId(), connection);
            try (Packet packet = connection.open(task, bytes, cancellation)) {
                return observation(connection, packet);
            }
        } catch (IOException failure) {
            throw new BrowserWorkerException("Interactive Browser native runtime is unavailable", failure);
        } catch (RuntimeException failure) {
            if (connection != null) {
                connection.close();
            }
            throw failure;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    BrowserActionResult act(
            String id, BrowserContracts.Action action, byte[] privateInput, CancellationToken cancellation) {
        return act(id, require(id).view().lease(), action, privateInput, cancellation);
    }

    BrowserActionResult act(
            String id,
            BrowserContracts.AccessLease expectedLease,
            BrowserContracts.Action action,
            byte[] privateInput,
            CancellationToken cancellation) {
        InteractiveBrowserConnection connection = require(id);
        connection.requireLease(expectedLease);
        byte[] bytes = Objects.requireNonNull(privateInput, "privateInput").clone();
        var request = new InteractiveBrowserProtocol.ActionRequest(expectedLease, action);
        try (Packet packet = connection.call(InteractiveBrowserProtocol.ACTION, request, bytes, cancellation)) {
            return observation(connection, packet);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    BrowserContracts.SessionView lease(String id, BrowserContracts.AccessLease lease, CancellationToken cancellation) {
        InteractiveBrowserConnection connection = require(id);
        connection.lease(lease);
        try (Packet packet = connection.call(InteractiveBrowserProtocol.LEASE, lease, new byte[0], cancellation)) {
            BrowserContracts.SessionView view =
                    json.decode(packet.frame().payload(), BrowserContracts.SessionView.class);
            connection.view(view);
            return view;
        }
    }

    BrowserActionResult fillCredentials(
            String id,
            BrowserContracts.AccessLease expectedLease,
            URI expectedOrigin,
            BrowserContracts.CredentialsTarget target,
            byte[] credentials,
            CancellationToken cancellation) {
        InteractiveBrowserConnection connection = require(id);
        connection.requireLease(expectedLease);
        byte[] bytes = Objects.requireNonNull(credentials, "credentials").clone();
        var request = new InteractiveBrowserProtocol.CredentialsRequest(expectedLease, expectedOrigin, target);
        try (Packet packet =
                connection.call(InteractiveBrowserProtocol.FILL_CREDENTIALS, request, bytes, cancellation)) {
            return observation(connection, packet);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    BrowserContracts.SessionView status(String id) {
        return require(id).view();
    }

    BrowserContracts.SessionView close(String id) {
        InteractiveBrowserConnection connection = require(id);
        connection.close();
        return connection.view();
    }

    <T> T save(String id, BrowserStorageHandler<T> handler) {
        return privateResult(
                id, InteractiveBrowserProtocol.SAVE, Map.of(), handler, BrowserWorkerProtocol.MAXIMUM_STATE_BYTES);
    }

    <T> T capture(
            String id,
            BrowserContracts.AccessLease expectedLease,
            URI expectedOrigin,
            BrowserContracts.CredentialsTarget target,
            BrowserStorageHandler<T> handler) {
        require(id).requireLease(expectedLease);
        var request = new InteractiveBrowserProtocol.CredentialsRequest(expectedLease, expectedOrigin, target);
        return privateResult(id, InteractiveBrowserProtocol.CAPTURE, request, handler, 65_536);
    }

    List<BrowserContracts.LoginForm> prepare(
            String id, BrowserContracts.AccessLease expectedLease, URI expectedOrigin) {
        InteractiveBrowserConnection connection = require(id);
        connection.requireLease(expectedLease);
        var request = new InteractiveBrowserProtocol.FormsRequest(expectedLease, expectedOrigin);
        try (Packet packet = connection.call(
                InteractiveBrowserProtocol.PREPARE_CREDENTIALS,
                request,
                new byte[0],
                InteractiveBrowserConnection.NONE)) {
            var result = json.decode(packet.frame().payload(), InteractiveBrowserProtocol.FormsResult.class);
            connection.view(result.session());
            return result.forms();
        }
    }

    private <T> T privateResult(
            String id, String operation, Object request, BrowserStorageHandler<T> handler, int maximum) {
        InteractiveBrowserConnection connection = require(id);
        try (Packet packet = connection.call(operation, request, new byte[0], InteractiveBrowserConnection.NONE)) {
            connection.view(json.decode(packet.frame().payload(), BrowserContracts.SessionView.class));
            byte[] bytes = packet.bytes();
            try {
                if (bytes.length < 1 || bytes.length > maximum) {
                    throw new IllegalArgumentException("Browser private result exceeds limit");
                }
                return handler.handle(bytes);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new BrowserWorkerException("Browser private result could not be saved");
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    private BrowserActionResult observation(InteractiveBrowserConnection connection, Packet packet) {
        BrowserContracts.Observation value = json.decode(packet.frame().payload(), BrowserContracts.Observation.class);
        connection.view(value.session());
        byte[] bytes = packet.bytes();
        try {
            return new BrowserActionResult(value, bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private InteractiveBrowserConnection require(String id) {
        InteractiveBrowserConnection result = sessions.get(id);
        if (result == null) {
            throw new IllegalArgumentException("Browser session is unknown");
        }
        return result;
    }

    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        for (InteractiveBrowserConnection connection : sessions.values()) {
            try {
                connection.close();
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        sessions.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface Launcher {
        InteractiveBrowserProcess start() throws IOException;
    }
}
