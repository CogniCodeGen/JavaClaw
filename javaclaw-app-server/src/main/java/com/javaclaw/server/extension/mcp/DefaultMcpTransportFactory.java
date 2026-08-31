package com.javaclaw.server.extension.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.sandbox.api.NetworkBroker;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxExecutor;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.server.extension.LoadedPlugin;
import com.javaclaw.server.extension.PluginCatalog;
import com.javaclaw.server.extension.PluginProcessKind;
import com.javaclaw.server.security.SecretStore;

/** Secure transport factory: stdio always uses Sandbox Supervisor; HTTP always uses Broker. */
public final class DefaultMcpTransportFactory implements McpTransportFactory {
    private final PluginCatalog plugins;
    private final SandboxExecutor sandbox;
    private final java.util.function.BiFunction<McpConfiguration, TurnExecutionContext, NetworkBroker> brokers;
    private final SecretStore secrets;
    private final SandboxPolicy serverCeiling;
    private final ObjectMapper json;
    private final McpCodec codec;
    private final McpOAuthService oauth;

    /** 装配 stdio/HTTP 工厂；stdio 仅经 SandboxExecutor，HTTP/OAuth 仅经 NetworkBroker，凭据由 SecretStore 获取。 */
    public DefaultMcpTransportFactory(
            PluginCatalog plugins,
            SandboxExecutor sandbox,
            java.util.function.BiFunction<McpConfiguration, TurnExecutionContext, NetworkBroker> brokers,
            SecretStore secrets,
            SandboxPolicy serverCeiling,
            ObjectMapper json,
            McpCodec codec,
            McpOAuthService oauth) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.brokers = Objects.requireNonNull(brokers, "brokers");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.serverCeiling = Objects.requireNonNull(serverCeiling, "serverCeiling");
        this.json = Objects.requireNonNull(json, "json");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.oauth = oauth;
    }

    @Override
    public McpTransport open(McpConfiguration configuration, TurnExecutionContext turn, SandboxPolicy authority)
            throws Exception {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(authority, "authority");
        if (configuration.workspaceId() != null
                && !configuration.workspaceId().equals(turn.thread().workspaceId())) {
            throw new IllegalArgumentException("MCP connection is restricted to another workspace");
        }
        return switch (configuration.transport()) {
            case HTTP ->
                new BrokeredHttpMcpTransport(
                        configuration.endpoint(),
                        brokers.apply(configuration, turn),
                        new NetworkPolicy(NetworkPolicy.Mode.ALLOWLIST, configuration.networkAllowlist()),
                        configuration.authentication().type() == McpConfiguration.AuthenticationType.OAUTH
                                ? requireOauth().authorization(configuration)
                                : new McpCredentialAuthorization(
                                        configuration.id(), configuration.authentication(), secrets),
                        json,
                        codec);
            case STDIO -> openStdio(configuration, turn, authority);
        };
    }

    private McpOAuthService requireOauth() {
        if (oauth == null) {
            throw new IllegalStateException("MCP OAuth is unavailable");
        }
        return oauth;
    }

    private McpTransport openStdio(McpConfiguration configuration, TurnExecutionContext turn, SandboxPolicy authority)
            throws Exception {
        LoadedPlugin plugin = plugins.require(configuration.pluginId());
        LoadedPlugin.ResolvedProcess process = plugin.processMap().get(configuration.processId());
        if (process == null || process.declaration().kind() != PluginProcessKind.MCP_SERVER) {
            throw new IllegalStateException("plugin MCP process is unavailable");
        }
        LinkedHashSet<Path> readable = new LinkedHashSet<>();
        readable.add(plugin.bundleRoot());
        if (process.declaration().workspaceRead()) {
            readable.add(turn.thread().workingDirectory());
        }
        Set<Path> writable =
                process.declaration().workspaceWrite() ? Set.of(turn.thread().workingDirectory()) : Set.of();
        SandboxPolicy requested = new SandboxPolicy(
                process.declaration().workspaceWrite() ? SandboxMode.WORKSPACE_WRITE : SandboxMode.READ_ONLY,
                Set.copyOf(readable),
                writable,
                authority.protectedRoots(),
                NetworkPolicy.disabled(),
                Set.of("PATH", "LANG", "LC_ALL", "TERM"),
                minimum(configuration.timeout(), process.declaration().timeoutMillis()),
                Math.min(configuration.outputLimitBytes(), process.declaration().outputLimitBytes()));
        SandboxPolicy withBundle = withInfrastructureRead(authority, plugin.bundleRoot());
        SandboxPolicy effective = withBundle.intersect(requested).intersect(serverCeiling);
        if (effective.network().mode() != NetworkPolicy.Mode.DISABLED) {
            throw new IllegalStateException("stdio MCP raw network must remain disabled");
        }
        ArrayList<String> argv = new ArrayList<>();
        argv.add(process.entrypoint().toString());
        argv.addAll(process.declaration().arguments());
        SandboxCommand command = new SandboxCommand(
                "mcp_" + UUID.randomUUID().toString().replace("-", ""),
                List.copyOf(argv),
                plugin.bundleRoot(),
                effective.filteredEnvironment(System.getenv()),
                effective,
                "");
        SandboxSession session = sandbox.openSession(command, SandboxSessionOptions.pipes());
        try {
            return new SandboxedStdioMcpTransport(session, json, codec);
        } catch (RuntimeException failure) {
            session.terminate();
            throw failure;
        }
    }

    private static SandboxPolicy withInfrastructureRead(SandboxPolicy authority, Path bundleRoot) {
        if (authority.mode() == SandboxMode.HOST_FULL_ACCESS) {
            return authority;
        }
        LinkedHashSet<Path> roots = new LinkedHashSet<>(authority.readableRoots());
        roots.add(bundleRoot);
        return new SandboxPolicy(
                authority.mode(),
                Set.copyOf(roots),
                authority.writableRoots(),
                authority.protectedRoots(),
                authority.network(),
                authority.inheritedEnvironment(),
                authority.timeout(),
                authority.outputLimitBytes());
    }

    private static Duration minimum(Duration configured, long processMillis) {
        Duration process = Duration.ofMillis(processMillis);
        return configured.compareTo(process) <= 0 ? configured : process;
    }
}
