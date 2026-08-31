package com.javaclaw.server.extension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionFrame;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

/**
 * Supervises persistent JSON-RPC plugin processes through SandboxExecutor sessions. Each distinct
 * workspace/policy/environment gets an isolated child; authority is never reused across Turns.
 */
public final class PluginProcessRuntime implements AutoCloseable {
    private static final int PROTOCOL_VERSION = 1;
    private static final long SESSION_OUTPUT_LIMIT = 16L * 1024L * 1024L;
    private static final int MAX_STDERR_BYTES = 64 * 1024;

    private final PluginCatalog plugins;
    private final SandboxExecutor sandbox;
    private final ObjectMapper json;
    private final ConcurrentHashMap<SessionKey, ManagedProcess> sessions = new ConcurrentHashMap<>();

    /** 绑定验证后目录、沙箱执行器和协议编码器；进程复用按工作区、策略和环境隔离。 */
    public PluginProcessRuntime(PluginCatalog plugins, SandboxExecutor sandbox, ObjectMapper json) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 在受监督进程会话执行一个 JSON-RPC 请求；权限不同则使用独立会话，失败终止并移除会话。
     *
     * @throws Exception 请求非法、沙箱拒绝、超时或插件协议失败
     */
    public JsonNode invoke(String pluginId, String processId, JsonNode request, PluginInvocationContext context)
            throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        requireJsonRpcRequest(request);
        LoadedPlugin plugin = plugins.require(pluginId);
        LoadedPlugin.ResolvedProcess process = plugin.processMap().get(processId);
        if (process == null) {
            throw new IllegalArgumentException("plugin process is not declared: " + processId);
        }
        SandboxPolicy requested = requestedPolicy(plugin, process.declaration(), context);
        SandboxPolicy effective = withInfrastructureRead(context.authorityCeiling(), plugin.bundleRoot())
                .intersect(requested);
        Map<String, String> environment = effective.filteredEnvironment(context.environment());
        SessionKey key = new SessionKey(
                pluginId, processId, plugin.bundleRoot(), context.workspaceRoot(), effective, environment);
        ManagedProcess managed;
        try {
            managed = sessions.compute(key, (ignored, current) -> {
                if (current != null && current.isAlive()) {
                    return current;
                }
                if (current != null) {
                    current.terminate();
                }
                try {
                    return start(plugin, process, context, effective, environment, key);
                } catch (Exception failure) {
                    throw new SessionStartFailure(failure);
                }
            });
        } catch (SessionStartFailure failure) {
            throw failure.unwrap();
        }
        try {
            return managed.invoke(
                    request,
                    process.declaration().timeoutMillis(),
                    process.declaration().outputLimitBytes());
        } catch (Exception failure) {
            sessions.remove(key, managed);
            managed.terminate();
            throw failure;
        }
    }

    /** Stops every process belonging to a disabled, quarantined or uninstalled plugin. */
    public void stopPlugin(String pluginId) {
        sessions.forEach((key, process) -> {
            if (key.pluginId().equals(pluginId) && sessions.remove(key, process)) {
                process.terminate();
            }
        });
    }

    /** 返回当前仍存活的受控插件会话数量，仅用于诊断。 */
    public int activeSessionCount() {
        return (int) sessions.values().stream().filter(ManagedProcess::isAlive).count();
    }

    @Override
    public void close() {
        sessions.values().forEach(ManagedProcess::terminate);
        sessions.clear();
    }

    private ManagedProcess start(
            LoadedPlugin plugin,
            LoadedPlugin.ResolvedProcess process,
            PluginInvocationContext context,
            SandboxPolicy effective,
            Map<String, String> environment,
            SessionKey key)
            throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add(process.entrypoint().toString());
        argv.addAll(process.declaration().arguments());
        SandboxPolicy sessionPolicy = new SandboxPolicy(
                effective.mode(),
                effective.readableRoots(),
                effective.writableRoots(),
                effective.protectedRoots(),
                effective.network(),
                effective.inheritedEnvironment(),
                minimum(context.authorityCeiling().timeout(), Duration.ofMinutes(10)),
                Math.max(effective.outputLimitBytes(), SESSION_OUTPUT_LIMIT));
        String commandId = "plugin_" + UUID.randomUUID().toString().replace("-", "");
        SandboxSession session = sandbox.openSession(
                new SandboxCommand(commandId, argv, plugin.bundleRoot(), environment, sessionPolicy, ""),
                SandboxSessionOptions.pipes());
        ManagedProcess managed = new ManagedProcess(session, key);
        managed.startReader();
        try {
            ObjectNode initialize = json.createObjectNode();
            initialize.put("jsonrpc", "2.0");
            initialize.put("id", "initialize_" + UUID.randomUUID().toString().replace("-", ""));
            initialize.put("method", "initialize");
            ObjectNode params = initialize.putObject("params");
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("pluginId", plugin.manifest().id());
            params.put("processId", process.declaration().id());
            JsonNode response = managed.invoke(
                    initialize,
                    process.declaration().timeoutMillis(),
                    process.declaration().outputLimitBytes());
            if (response.has("error")) {
                throw new IllegalStateException("plugin initialize failed: "
                        + response.path("error").path("message").asText("unknown error"));
            }
            int negotiated = response.path("result").path("protocolVersion").asInt(PROTOCOL_VERSION);
            if (negotiated != PROTOCOL_VERSION) {
                throw new IllegalStateException("plugin negotiated unsupported protocol version: " + negotiated);
            }
            return managed;
        } catch (Exception failure) {
            managed.terminate();
            throw failure;
        }
    }

    private static Duration minimum(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static void requireJsonRpcRequest(JsonNode request) {
        if (!request.isObject()
                || !"2.0".equals(request.path("jsonrpc").asText())
                || !request.has("id")
                || request.get("id").isNull()
                || !request.path("method").isTextual()) {
            throw new IllegalArgumentException("plugin request must be JSON-RPC 2.0 with an id");
        }
    }

    private static SandboxPolicy requestedPolicy(
            LoadedPlugin plugin, PluginProcessContribution process, PluginInvocationContext context) {
        LinkedHashSet<Path> readable = new LinkedHashSet<>();
        readable.add(plugin.bundleRoot());
        if (process.workspaceRead()) {
            readable.add(context.workspaceRoot());
        }
        Set<Path> writable = process.workspaceWrite() ? Set.of(context.workspaceRoot()) : Set.of();
        NetworkPolicy network = process.networkAllowlist().isEmpty()
                ? NetworkPolicy.disabled()
                : new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, process.networkAllowlist());
        return new SandboxPolicy(
                process.workspaceWrite() ? SandboxMode.WORKSPACE_WRITE : SandboxMode.READ_ONLY,
                Set.copyOf(readable),
                writable,
                context.protectedRoots(),
                network,
                Set.of("PATH", "LANG", "LC_ALL", "TERM"),
                Duration.ofMillis(process.timeoutMillis()),
                process.outputLimitBytes());
    }

    /** The declared executable must be readable, but this never grants workspace authority. */
    private static SandboxPolicy withInfrastructureRead(SandboxPolicy ceiling, Path bundleRoot) {
        LinkedHashSet<Path> readable = new LinkedHashSet<>(ceiling.readableRoots());
        readable.add(bundleRoot);
        return new SandboxPolicy(
                ceiling.mode(),
                Set.copyOf(readable),
                ceiling.writableRoots(),
                ceiling.protectedRoots(),
                ceiling.network(),
                ceiling.inheritedEnvironment(),
                ceiling.timeout(),
                ceiling.outputLimitBytes());
    }

    private final class ManagedProcess {
        private final SandboxSession session;
        private final SessionKey key;
        private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private volatile Thread reader;

        private ManagedProcess(SandboxSession session, SessionKey key) {
            this.session = session;
            this.key = key;
        }

        private void startReader() {
            reader = Thread.ofVirtual()
                    .name("plugin-jsonrpc-" + key.pluginId() + "-" + key.processId())
                    .start(this::readLoop);
        }

        private JsonNode invoke(JsonNode request, long timeoutMillis, long outputLimit) throws Exception {
            if (!isAlive()) {
                throw new IllegalStateException("plugin process is not running");
            }
            String requestId = request.get("id").toString();
            CompletableFuture<JsonNode> response = new CompletableFuture<>();
            if (pending.putIfAbsent(requestId, response) != null) {
                throw new IllegalArgumentException("duplicate in-flight plugin request id");
            }
            byte[] frame = (json.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8);
            if (frame.length > 1024 * 1024) {
                pending.remove(requestId);
                throw new IllegalArgumentException("plugin request exceeds 1 MiB");
            }
            try {
                session.write(frame);
                JsonNode value = response.get(timeoutMillis, TimeUnit.MILLISECONDS);
                if (json.writeValueAsBytes(value).length > outputLimit) {
                    throw new IllegalStateException("plugin response exceeded its declared output limit");
                }
                return value;
            } catch (java.util.concurrent.TimeoutException failure) {
                throw new IllegalStateException("plugin request timed out", failure);
            } finally {
                pending.remove(requestId, response);
            }
        }

        private void readLoop() {
            try {
                while (alive.get()) {
                    SandboxSessionFrame frame = session.read(Duration.ofSeconds(1));
                    if (frame == null) {
                        if (!session.isAlive()) {
                            throw new IOException("sandbox plugin session exited without an exit frame");
                        }
                        continue;
                    }
                    switch (frame.kind()) {
                        case STDOUT -> acceptStdout(frame.data());
                        case STDERR -> acceptStderr(frame.data());
                        case ERROR -> throw new IOException(frame.detail());
                        case EXIT ->
                            throw new IOException("plugin process exited with " + frame.exitCode()
                                    + (frame.detail().isBlank() ? "" : ": " + frame.detail()));
                        case READY -> throw new IOException("duplicate sandbox READY frame");
                    }
                }
            } catch (Exception failure) {
                fail(failure);
            }
        }

        private void acceptStdout(byte[] value) throws IOException {
            synchronized (stdout) {
                stdout.write(value);
                if (stdout.size() > SESSION_OUTPUT_LIMIT) {
                    throw new IOException("plugin JSON-RPC frame buffer exceeded limit");
                }
                byte[] all = stdout.toByteArray();
                int start = 0;
                for (int index = 0; index < all.length; index++) {
                    if (all[index] != '\n') {
                        continue;
                    }
                    int end = index;
                    if (end > start && all[end - 1] == '\r') {
                        end--;
                    }
                    if (end == start) {
                        throw new IOException("plugin emitted an empty frame");
                    }
                    acceptJsonFrame(new String(all, start, end - start, StandardCharsets.UTF_8));
                    start = index + 1;
                }
                if (start > 0) {
                    stdout.reset();
                    stdout.write(all, start, all.length - start);
                }
            }
        }

        private void acceptJsonFrame(String line) throws IOException {
            JsonNode response = json.readTree(line);
            if (response == null
                    || !response.isObject()
                    || !"2.0".equals(response.path("jsonrpc").asText())
                    || !response.has("id")
                    || response.get("id").isNull()
                    || (!response.has("result") && !response.has("error"))) {
                throw new IOException("plugin returned an invalid JSON-RPC response");
            }
            CompletableFuture<JsonNode> target = pending.get(response.get("id").toString());
            if (target == null || !target.complete(response)) {
                throw new IOException("plugin returned an unknown or duplicate response id");
            }
        }

        private void acceptStderr(byte[] value) throws IOException {
            synchronized (stderr) {
                if (stderr.size() + value.length > MAX_STDERR_BYTES) {
                    throw new IOException("plugin stderr exceeded 64 KiB");
                }
                stderr.write(value);
            }
        }

        private boolean isAlive() {
            return alive.get() && session.isAlive();
        }

        private void fail(Exception failure) {
            if (!alive.getAndSet(false)) {
                return;
            }
            sessions.remove(key, this);
            String message = failure.getMessage();
            if (message == null || message.isBlank()) {
                message = failure.getClass().getSimpleName();
            }
            IllegalStateException terminal = new IllegalStateException("plugin process failed: " + message, failure);
            pending.values().forEach(value -> value.completeExceptionally(terminal));
            pending.clear();
            session.terminate();
        }

        private void terminate() {
            if (alive.getAndSet(false)) {
                IllegalStateException stopped = new IllegalStateException("plugin process stopped");
                pending.values().forEach(value -> value.completeExceptionally(stopped));
                pending.clear();
                session.terminate();
                Thread current = reader;
                if (current != null) {
                    current.interrupt();
                }
            }
        }
    }

    private record SessionKey(
            String pluginId,
            String processId,
            Path bundleRoot,
            Path workspaceRoot,
            SandboxPolicy policy,
            Map<String, String> environment) {
        private SessionKey {
            bundleRoot = bundleRoot.toAbsolutePath().normalize();
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
            environment = Map.copyOf(environment);
        }
    }

    private static final class SessionStartFailure extends RuntimeException {
        private SessionStartFailure(Exception cause) {
            super(cause);
        }

        private Exception unwrap() {
            return getCause() instanceof Exception exception ? exception : this;
        }
    }
}
