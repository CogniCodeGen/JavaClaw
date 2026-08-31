package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.extension.mcp.McpConfiguration;

/** Transaction-aware Plugin 4.0 installation, trust and health operations. */
public final class PluginService implements PluginUseCases {
    private static final long FAILURE_WINDOW_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int[] RESTART_BACKOFF_SECONDS = {1, 2, 5, 10, 30, 60};

    private final PluginRepository repository;
    private final PluginTrustStore trust;
    private final McpRepository mcp;
    private final PluginCatalog catalog;
    private final PluginBundleInstaller installer;
    private final PluginProcessRuntime processes;
    private final AttachmentRepository attachments;
    private final SandboxPolicy ceiling;
    private final ObjectMapper json;
    private final Path pluginRoot;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> failures = new ConcurrentHashMap<>();

    /** 装配插件仓库、信任、安装器及进程监督，重建已启用目录并维护健康状态；外部代码不进入 App Server JVM。 */
    public PluginService(
            PluginRepository repository,
            PluginTrustStore trust,
            McpRepository mcp,
            PluginCatalog catalog,
            PluginBundleInstaller installer,
            PluginProcessRuntime processes,
            AttachmentRepository attachments,
            SandboxPolicy ceiling,
            ObjectMapper json,
            Path pluginRoot) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.trust = Objects.requireNonNull(trust, "trust");
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.processes = Objects.requireNonNull(processes, "processes");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.ceiling = Objects.requireNonNull(ceiling, "ceiling");
        this.json = Objects.requireNonNull(json, "json");
        this.pluginRoot = Objects.requireNonNull(pluginRoot, "pluginRoot")
                .toAbsolutePath()
                .normalize();
        loadInstalled();
    }

    @Override
    public List<PluginStateView> list() {
        return repository.list().stream().map(PluginService::view).toList();
    }

    @Override
    public PluginStateView read(String id) {
        return view(require(id));
    }

    @Override
    public List<McpServerState> listMcp() {
        return mcp.list().stream().map(PluginService::mcpView).toList();
    }

    @Override
    public McpServerState configureMcp(
            String id,
            String pluginId,
            String name,
            String configurationJson,
            boolean enabled,
            long expectedRevision,
            String idempotencyKey) {
        JsonNode config;
        try {
            config = json.readTree(configurationJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP config is invalid", failure);
        }
        if (config == null || !config.isObject()) {
            throw new IllegalArgumentException("MCP config must be an object");
        }
        rejectSensitiveConfig(config, "config");
        String owner = pluginId == null || pluginId.isBlank() ? null : pluginId.strip();
        var current = mcp.find(id).orElse(null);
        if (current != null && current.pluginId() != null) {
            try {
                // 插件生成的 MCP 入口只可启停；同一 id 不能被独立 HTTP 配置夺取所有权。
                if (owner != null
                        || !current.name().equals(name)
                        || !json.readTree(current.configJson()).equals(config)) {
                    throw new IllegalArgumentException("plugin-owned MCP permits only enabled-state changes");
                }
                return mcpView(mcp.put(
                        new McpRepository.McpDraft(
                                current.id(), current.pluginId(), current.name(), current.configJson(), enabled),
                        expectedRevision,
                        idempotencyKey));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("stored MCP metadata is invalid", failure);
            }
        }
        if (owner != null) {
            throw new IllegalArgumentException("plugin-owned MCP configuration is generated by the server");
        }
        McpConfiguration.validateDraft(id, null, name, config, json);
        try {
            return mcpView(mcp.put(
                    new McpRepository.McpDraft(id, owner, name, json.writeValueAsString(config), enabled),
                    expectedRevision,
                    idempotencyKey));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP config cannot be encoded", failure);
        }
    }

    @Override
    public McpServerState mcpHealth(String id) {
        McpRepository.McpRecord record =
                mcp.find(id).orElseThrow(() -> new NoSuchElementException("MCP server not found: " + id));
        String state;
        if (!record.enabled()) {
            state = "DISABLED";
        } else if (record.pluginId() != null) {
            PluginStateView plugin = health(record.pluginId());
            state = "HEALTHY".equals(plugin.state()) ? "HEALTHY" : "DEGRADED";
        } else {
            // HTTP MCP connectivity is performed only through NetworkBroker. Configuration
            // validation itself cannot open a socket.
            state = "CONFIGURED";
        }
        return mcpView(mcp.setState(id, state, record.revision()));
    }

    @Override
    public PluginStateView install(
            String attachmentSha256,
            boolean sourceConfirmed,
            boolean permissionsApproved,
            boolean enabled,
            String idempotencyKey)
            throws IOException {
        var existing = repository.findByBundleSha256(attachmentSha256);
        if (existing.isPresent()) {
            return view(existing.get());
        }
        try (var input = attachments.openAttachment(attachmentSha256)) {
            var installed = installer.install(input, attachmentSha256, sourceConfirmed);
            LoadedPlugin plugin = installed.plugin();
            boolean requiresPermissions = plugin.processes().stream()
                    .anyMatch(process -> process.declaration().workspaceRead()
                            || process.declaration().workspaceWrite()
                            || !process.declaration().networkAllowlist().isEmpty());
            if (requiresPermissions && !permissionsApproved) {
                installer.uninstall(plugin.manifest().id());
                throw new IllegalStateException("plugin permissions require explicit interactive approval");
            }
            String manifest = json.writeValueAsString(plugin.manifest());
            String signer = plugin.manifest().signature() == null
                    ? null
                    : plugin.manifest().signature().keyId();
            PluginRepository.PluginRecord record;
            try {
                record = repository.install(
                        new PluginRepository.PluginRecordDraft(
                                plugin.manifest().id(),
                                plugin.manifest().version(),
                                plugin.bundleRoot().toString(),
                                manifest,
                                installed.bundleSha256(),
                                signer,
                                plugin.signatureVerified(),
                                sourceConfirmed,
                                permissionsApproved,
                                enabled),
                        idempotencyKey);
            } catch (RuntimeException failure) {
                installer.uninstall(plugin.manifest().id());
                throw failure;
            }
            syncPluginMcp(plugin, enabled, "plugin-install-" + installed.bundleSha256());
            if (!enabled) {
                catalog.unregister(plugin.manifest().id());
            }
            return view(record);
        }
    }

    @Override
    public PluginBundlePreview preview(String attachmentSha256) throws IOException {
        try (var input = attachments.openAttachment(attachmentSha256)) {
            return installer.preview(input, attachmentSha256);
        }
    }

    @Override
    public PluginStateView setEnabled(String id, boolean enabled, long expectedRevision, String idempotencyKey)
            throws IOException {
        PluginRepository.PluginRecord current = require(id);
        if (enabled && catalog.find(id).isEmpty()) {
            catalog.register(checkedInstallPath(current), current.sourceConfirmed());
        }
        PluginRepository.PluginRecord result;
        try {
            result = repository.setEnabled(id, enabled, expectedRevision, idempotencyKey);
        } catch (RuntimeException failure) {
            if (enabled) {
                catalog.unregister(id);
            }
            throw failure;
        }
        syncPluginMcp(pluginsForRecord(result), enabled, "plugin-enable-" + id + "-" + result.revision());
        if (!enabled) {
            processes.stopPlugin(id);
            catalog.unregister(id);
        }
        return view(result);
    }

    @Override
    public boolean uninstall(String id, long expectedRevision, String idempotencyKey) throws IOException {
        PluginRepository.PluginRecord current = repository.find(id).orElse(null);
        if (current == null) {
            return false;
        }
        PluginRepository.PluginRecord removing =
                repository.setState(id, PluginRepository.State.REMOVING, "", current.restartCount(), expectedRevision);
        processes.stopPlugin(id);
        if (catalog.find(id).isEmpty()) {
            catalog.register(checkedInstallPath(removing), removing.sourceConfirmed());
        }
        installer.uninstall(id);
        failures.remove(id);
        mcp.deleteForPlugin(id);
        return repository.delete(id, removing.revision(), idempotencyKey);
    }

    @Override
    public PluginStateView health(String id) {
        PluginRepository.PluginRecord current = require(id);
        if (!current.enabled()) {
            return view(current);
        }
        if (current.state() == PluginRepository.State.QUARANTINED) {
            return view(current);
        }
        LoadedPlugin plugin = catalog.require(id);
        try {
            for (var process : plugin.processes()) {
                ObjectNode request = json.createObjectNode();
                request.put("jsonrpc", "2.0");
                request.put(
                        "id", "health_" + java.util.UUID.randomUUID().toString().replace("-", ""));
                request.put("method", process.declaration().healthCheckMethod());
                request.putObject("params");
                JsonNode response = processes.invoke(
                        id,
                        process.declaration().id(),
                        request,
                        new PluginInvocationContext(
                                plugin.bundleRoot(),
                                ceiling.protectedRoots(),
                                ceiling,
                                ceiling.filteredEnvironment(System.getenv())));
                if (response.has("error")) {
                    throw new IllegalStateException(
                            response.path("error").path("message").asText("health check failed"));
                }
            }
            failures.remove(id);
            return view(repository.setState(id, PluginRepository.State.HEALTHY, "", 0, current.revision()));
        } catch (Exception failure) {
            FailureDecision decision = recordFailure(id);
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            PluginRepository.State state =
                    decision.quarantined() ? PluginRepository.State.QUARANTINED : PluginRepository.State.DEGRADED;
            if (decision.quarantined()) {
                processes.stopPlugin(id);
            }
            return view(repository.setState(
                    id,
                    state,
                    message + "; nextRestartDelaySeconds=" + decision.delaySeconds(),
                    decision.count(),
                    current.revision()));
        }
    }

    @Override
    public List<PluginTrustKeyView> trustList() {
        return trust.list().stream().map(PluginService::trustView).toList();
    }

    @Override
    public PluginTrustKeyView trustAdd(
            String keyId, String encodedPublicKey, String label, long expectedRevision, String idempotencyKey) {
        byte[] value;
        try {
            value = Base64.getDecoder().decode(encodedPublicKey);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("publicKey must be canonical base64", failure);
        }
        return trustView(trust.add(keyId, value, label, expectedRevision, idempotencyKey));
    }

    @Override
    public boolean trustRemove(String keyId, long expectedRevision, String idempotencyKey) {
        return trust.remove(keyId, expectedRevision, idempotencyKey);
    }

    PluginCatalog catalog() {
        return catalog;
    }

    private void loadInstalled() {
        for (PluginRepository.PluginRecord record : repository.list()) {
            try {
                if (record.state() == PluginRepository.State.REMOVING) {
                    Path installed =
                            Path.of(record.installPath()).toAbsolutePath().normalize();
                    if (java.nio.file.Files.exists(installed)) {
                        catalog.register(checkedInstallPath(record), record.sourceConfirmed());
                        installer.uninstall(record.id());
                    }
                    mcp.deleteForPlugin(record.id());
                    repository.delete(
                            record.id(),
                            record.revision(),
                            "plugin-startup-remove-" + record.id() + "-" + record.revision());
                    continue;
                }
                catalog.register(checkedInstallPath(record), record.sourceConfirmed());
                syncPluginMcp(
                        catalog.require(record.id()),
                        record.enabled(),
                        "plugin-startup-" + record.id() + "-" + record.revision());
                if (!record.enabled()) {
                    catalog.unregister(record.id());
                }
            } catch (Exception failure) {
                processes.stopPlugin(record.id());
                catalog.unregister(record.id());
                String message =
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                repository.setState(
                        record.id(),
                        PluginRepository.State.DEGRADED,
                        "startup validation failed: " + message,
                        record.restartCount(),
                        record.revision());
            }
        }
    }

    private Path checkedInstallPath(PluginRepository.PluginRecord record) throws IOException {
        Path path = Path.of(record.installPath()).toAbsolutePath().normalize();
        Path root = pluginRoot.toRealPath();
        Path real = path.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("plugin install path escaped the v4 plugin root");
        }
        return real;
    }

    private PluginRepository.PluginRecord require(String id) {
        return repository.find(id).orElseThrow(() -> new NoSuchElementException("plugin not found: " + id));
    }

    private LoadedPlugin pluginsForRecord(PluginRepository.PluginRecord record) throws IOException {
        LoadedPlugin loaded = catalog.find(record.id()).orElse(null);
        if (loaded != null) {
            return loaded;
        }
        return catalog.register(checkedInstallPath(record), record.sourceConfirmed());
    }

    private void syncPluginMcp(LoadedPlugin plugin, boolean enabled, String idempotencyPrefix) {
        plugin.processes().stream()
                .filter(value ->
                        value.declaration().kind() == com.javaclaw.server.extension.PluginProcessKind.MCP_SERVER)
                .forEach(value -> {
                    String id =
                            plugin.manifest().id() + "." + value.declaration().id();
                    McpRepository.McpRecord current = mcp.find(id).orElse(null);
                    ObjectNode config = json.createObjectNode();
                    config.put("transport", "stdio");
                    config.put("processId", value.declaration().id());
                    McpRepository.McpDraft draft = new McpRepository.McpDraft(
                            id,
                            plugin.manifest().id(),
                            plugin.manifest().name() + " / "
                                    + value.declaration().id(),
                            config.toString(),
                            enabled);
                    mcp.put(
                            draft,
                            current == null ? 0 : current.revision(),
                            idempotencyPrefix + "-mcp-" + value.declaration().id());
                });
    }

    private FailureDecision recordFailure(String id) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> window = failures.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.getFirst() < now - FAILURE_WINDOW_MILLIS) {
                window.removeFirst();
            }
            window.addLast(now);
            int count = window.size();
            int delay = RESTART_BACKOFF_SECONDS[Math.min(count - 1, RESTART_BACKOFF_SECONDS.length - 1)];
            return new FailureDecision(count, delay, count >= 5);
        }
    }

    private static PluginStateView view(PluginRepository.PluginRecord value) {
        return new PluginStateView(
                value.id(),
                value.version(),
                value.state().name(),
                value.enabled(),
                value.signatureVerified(),
                value.signerKeyId(),
                value.sourceConfirmed(),
                value.permissionsApproved(),
                value.restartCount(),
                value.lastError(),
                value.revision(),
                value.createdAt(),
                value.updatedAt());
    }

    private static PluginTrustKeyView trustView(PluginTrustStore.TrustedKey value) {
        return new PluginTrustKeyView(
                value.keyId(), value.label(), sha256(value.x509PublicKey()), value.revision(), value.createdAt());
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static McpServerState mcpView(McpRepository.McpRecord value) {
        return new McpServerState(
                value.id(),
                value.pluginId(),
                value.name(),
                value.configJson(),
                value.enabled(),
                value.state(),
                value.revision(),
                value.updatedAt());
    }

    private static void rejectSensitiveConfig(JsonNode value, String path) {
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                String normalized = entry.getKey()
                        .toLowerCase(java.util.Locale.ROOT)
                        .replace("_", "")
                        .replace("-", "")
                        .replace(".", "");
                if (normalized.equals("apikey")
                        || normalized.equals("password")
                        || normalized.equals("secret")
                        || normalized.equals("token")
                        || normalized.equals("accesstoken")
                        || normalized.equals("refreshtoken")
                        || normalized.equals("credential")
                        || normalized.equals("privatekey")) {
                    throw new IllegalArgumentException(
                            "MCP credentials must use SecretStore references: " + path + "." + entry.getKey());
                }
                rejectSensitiveConfig(entry.getValue(), path + "." + entry.getKey());
            });
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                rejectSensitiveConfig(value.get(index), path + "[" + index + "]");
            }
        }
    }

    private record FailureDecision(int count, int delaySeconds, boolean quarantined) {}
}
