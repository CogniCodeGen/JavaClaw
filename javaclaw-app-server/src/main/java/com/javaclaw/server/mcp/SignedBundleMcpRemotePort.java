package com.javaclaw.server.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.McpCatalogPage;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpInvocationRequest;
import com.javaclaw.api.McpInvocationResult;
import com.javaclaw.api.McpProtocol;
import com.javaclaw.api.McpRemoteSession;
import com.javaclaw.api.McpTransport;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.extension.spi.McpClientInteractionPort;
import com.javaclaw.extension.spi.McpRemotePort;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerCommand;
import com.javaclaw.nativehost.sandbox.SandboxedWorkerLauncher;
import com.javaclaw.protocol.CanonicalJson;

/** 只从已验签 Bundle 派生命令并通过 Native Sandbox 执行 MCP stdio。 */
final class SignedBundleMcpRemotePort implements McpRemotePort {
    private static final int MAXIMUM_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final Duration MAXIMUM_LIFETIME = Duration.ofMinutes(10);

    private final SignedBundleMcpSource bundles;
    private final WorkerLauncher launcher;
    private final Path workerRoot;
    private final CanonicalJson json;
    private final Clock clock;
    private final McpCatalogWireCodec catalogs;
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 创建生产 stdio 端口。
     *
     * @param bundles 已验签 Bundle 实时来源
     * @param workerRoot data-v5 中的私有 Worker 目录
     * @param json 严格 JSON codec
     * @param clock 平台时钟
     */
    SignedBundleMcpRemotePort(SignedBundleMcpSource bundles, Path workerRoot, CanonicalJson json, Clock clock) {
        this(bundles, new SandboxedWorkerLauncher()::start, workerRoot, json, clock);
    }

    SignedBundleMcpRemotePort(
            SignedBundleMcpSource bundles, WorkerLauncher launcher, Path workerRoot, CanonicalJson json, Clock clock) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.workerRoot = Objects.requireNonNull(workerRoot, "workerRoot")
                .toAbsolutePath()
                .normalize();
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        catalogs = new McpCatalogWireCodec(json);
    }

    @Override
    public McpRemoteSession initialize(McpEndpoint endpoint, String requiredProtocol, CancellationToken cancellation)
            throws Exception {
        requireFixedProtocol(requiredProtocol);
        try (Invocation invocation = open(endpoint, rejecting(), cancellation)) {
            return invocation.session().initialize(false);
        }
    }

    @Override
    public McpCatalogPage catalog(McpEndpoint endpoint, Optional<String> cursor, CancellationToken cancellation)
            throws Exception {
        try (Invocation invocation = open(endpoint, rejecting(), cancellation)) {
            requireInitialized(invocation.session().initialize(false));
            McpCatalogWireCodec.Request request = catalogs.request(Objects.requireNonNull(cursor, "cursor"));
            return catalogs.decode(request, invocation.session().call(request.method(), request.params()));
        }
    }

    @Override
    public McpInvocationResult invoke(
            McpEndpoint endpoint,
            McpInvocationRequest request,
            McpClientInteractionPort interactions,
            CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(request, "request");
        try (Invocation invocation = open(endpoint, interactions, cancellation)) {
            requireInitialized(invocation.session().initialize(true));
            CanonicalPayload params = json.encode(Map.of(
                    "name",
                    request.tool().name(),
                    "arguments",
                    request.arguments(),
                    "_meta",
                    Map.of("javaclawIdempotencyKey", request.idempotencyKey())));
            CanonicalPayload result = invocation.session().call("tools/call", params);
            requireCurrent(endpoint, invocation.launch());
            boolean successful = !json.booleanField(result, "isError").orElse(false);
            return new McpInvocationResult(successful, result, Optional.empty(), List.of());
        }
    }

    private Invocation open(McpEndpoint endpoint, McpClientInteractionPort interactions, CancellationToken cancellation)
            throws IOException {
        McpEndpoint checked = requireStdio(endpoint);
        SignedBundleMcpLaunch launch = current(checked);
        Path directory = workerRoot.resolve(checked.id()).normalize();
        if (!directory.startsWith(workerRoot)) {
            throw new SecurityException("MCP Worker directory escapes data-v5 root");
        }
        Files.createDirectories(directory);
        SandboxedWorkerCommand command = command(checked, launch, directory);
        Process process = launcher.start(command);
        return new Invocation(launch, new McpStdioSession(process, checked, interactions, cancellation, json, clock));
    }

    private SandboxedWorkerCommand command(McpEndpoint endpoint, SignedBundleMcpLaunch launch, Path directory) {
        ResourceLimits limits = launch.limits();
        ResourceLimits bounded = new ResourceLimits(
                limits.memoryBytes(),
                Math.min(limits.outputBytes(), MAXIMUM_OUTPUT_BYTES),
                limits.childProcesses(),
                limits.openFiles());
        return new SandboxedWorkerCommand(
                "mcp-" + endpoint.id() + '-' + sequence.incrementAndGet(),
                launch.argv(),
                directory,
                Map.of("JAVACLAW_MCP_PROTOCOL", McpProtocol.VERSION),
                List.of(launch.bundleRoot()),
                List.of(directory),
                List.of(launch.bundleRoot()),
                minimum(launch.lifetime(), MAXIMUM_LIFETIME),
                bounded);
    }

    private SignedBundleMcpLaunch current(McpEndpoint endpoint) {
        String bundleId = endpoint.spec().signedBundleId().orElseThrow();
        SignedBundleMcpLaunch launch = bundles.require(bundleId);
        String protocol = json.textField(launch.descriptor(), "protocolVersion")
                .orElseThrow(() -> new SecurityException("signed MCP descriptor omitted protocolVersion"));
        if (!McpProtocol.VERSION.equals(protocol)) {
            throw new SecurityException("signed MCP descriptor protocol changed");
        }
        return launch;
    }

    private void requireCurrent(McpEndpoint endpoint, SignedBundleMcpLaunch frozen) {
        SignedBundleMcpLaunch current = current(endpoint);
        if (current.bundleRevision() != frozen.bundleRevision()
                || !current.contributionId().equals(frozen.contributionId())
                || !current.descriptor().sha256().equals(frozen.descriptor().sha256())) {
            throw new SecurityException("signed MCP Bundle was revoked or changed during invocation");
        }
    }

    private static McpEndpoint requireStdio(McpEndpoint endpoint) {
        McpEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        if (checked.spec().transport() != McpTransport.SIGNED_BUNDLE_STDIO) {
            throw new IllegalArgumentException("signed Bundle MCP transport requires stdio");
        }
        return checked;
    }

    private static void requireFixedProtocol(String protocol) {
        if (!McpProtocol.VERSION.equals(protocol)) {
            throw new IllegalArgumentException("JavaClaw only supports MCP " + McpProtocol.VERSION);
        }
    }

    private static void requireInitialized(McpRemoteSession session) {
        if (!McpProtocol.VERSION.equals(session.protocolVersion())) {
            throw new IllegalStateException("signed MCP process rejected the fixed protocol version");
        }
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static McpClientInteractionPort rejecting() {
        return new McpClientInteractionPort() {
            @Override
            public Optional<CanonicalPayload> elicit(
                    com.javaclaw.api.McpElicitationRequest request, CancellationToken cancellation) {
                return Optional.empty();
            }

            @Override
            public Optional<CanonicalPayload> sample(
                    com.javaclaw.api.McpSamplingRequest request, CancellationToken cancellation) {
                return Optional.empty();
            }
        };
    }

    @FunctionalInterface
    interface WorkerLauncher {
        Process start(SandboxedWorkerCommand command) throws IOException;
    }

    private record Invocation(SignedBundleMcpLaunch launch, McpStdioSession session) implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }
}
