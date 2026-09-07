package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.ToolExecutionFact;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.server.persistence.CodingNetworkGrantRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.security.CommandNetworkGrant;
import com.javaclaw.server.security.CommandProxyBroker;
import com.javaclaw.server.security.CommandProxyLease;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

/** 仅当前已授权 Turn 可调用的原生依赖准备；每次准备拥有独立代理并在返回前关闭。 */
final class CodingDependencyPreparer implements AutoCloseable {
    private final CodingPlatform.Dependencies dependencies;
    private final CodingOperationRepository operations;
    private final CodingNetworkGrantRepository grants;
    private final CodingProcessManager processes;
    private final ManagedCommandResolver resolver;
    private final CommandProxyBroker broker;
    private final CodingCallScope scope;
    private final Path controlDirectory;
    private final CodingDependencyEvidence evidence;

    CodingDependencyPreparer(
            CodingPlatform.Dependencies dependencies,
            CodingOperationRepository operations,
            CodingProcessManager processes,
            ManagedCommandResolver resolver)
            throws Exception {
        this.dependencies = dependencies;
        this.operations = operations;
        this.processes = processes;
        this.resolver = resolver;
        evidence = new CodingDependencyEvidence(dependencies);
        scope = new CodingCallScope(dependencies.authority());
        grants = new CodingNetworkGrantRepository(dependencies.database(), dependencies.json(), dependencies.clock());
        broker = new CommandProxyBroker(dependencies.clock());
        controlDirectory = dependencies.database().dataRoot().resolve("run");
        Files.createDirectories(controlDirectory);
    }

    CodingToolResult prepare(CodingInvocation invocation) throws Exception {
        return scope.run(invocation, this::prepareOwned);
    }

    private CodingToolResult prepareOwned(CodingInvocation invocation) throws Exception {
        var input =
                dependencies.json().decode(invocation.request().arguments(), CodingContracts.DependenciesPrepare.class);
        NetworkPermission destinations = destinations(invocation);
        WorkspaceFileAccess files = new WorkspaceFileAccess(invocation.turn().executionRoot(), invocation.permission());
        var observation = evidence.begin(files, invocation, input);
        invocation.cancellation().throwIfCancelled();
        Map<String, Optional<String>> before = observation.manifests();
        Instant expiry = expiry(invocation);
        var grant = new CommandNetworkGrant(
                "net-" + invocation.id(),
                invocation.turn().id(),
                invocation.id(),
                dependencies
                        .json()
                        .encode(new PreparationIdentity(
                                input,
                                before,
                                dependencies
                                        .json()
                                        .encode(invocation.environment())
                                        .sha256(),
                                dependencies
                                        .json()
                                        .encode(invocation.permission())
                                        .sha256(),
                                dependencies
                                        .json()
                                        .encode(observation.evidence().before())
                                        .sha256()))
                        .sha256(),
                destinations,
                new CommandNetworkGrant.Limits(512L * 1024 * 1024, 16, Duration.ofSeconds(30)),
                expiry);
        grants.open(grant);
        CommandProxyLease lease = null;
        try {
            lease = broker.open(
                    grant,
                    invocation.cancellation(),
                    () -> dependencies
                            .authority()
                            .requireUnchanged(invocation.turn().id(), invocation.permission()));
            invocation.cancellation().throwIfCancelled();
            return execute(files, invocation, input, observation, lease);
        } finally {
            try {
                if (lease != null) {
                    lease.close();
                }
            } finally {
                grants.close(grant.id(), lease == null ? 0 : lease.transferredBytes());
            }
        }
    }

    private CodingToolResult execute(
            WorkspaceFileAccess files,
            CodingInvocation invocation,
            CodingContracts.DependenciesPrepare input,
            CodingDependencyEvidence.Session observation,
            CommandProxyLease lease)
            throws Exception {
        Map<String, Optional<String>> before = observation.manifests();
        Path cache = resolver.cacheRoot(invocation);
        Path configuration =
                dependencies.database().dataRoot().resolve("coding/control").resolve(invocation.id());
        Files.createDirectories(configuration);
        String proxy = "http://127.0.0.1:" + lease.endpoint().getPort();
        Path settings = writeMavenSettings(configuration, lease.endpoint().getPort());
        List<String> argv = NativeDependencyPlan.argv(
                input, before, invocation.environment().spec().allowLifecycleScripts(), cache, settings, proxy);
        Map<String, String> environment =
                proxyEnvironment(proxy, lease.endpoint().getPort(), input.manager());
        int timeout = (int) Math.max(
                1,
                Math.min(
                        1800,
                        Duration.between(dependencies.clock().instant(), expiry(invocation))
                                .toSeconds()));
        var command = new CodingContracts.CommandRun(argv, input.workingDirectory(), timeout, 1024 * 1024);
        var plan = new NativeDependencyPlan(
                input.manager(), command, before, List.of(input.workingDirectory(), cache.toString()));
        operations.preparation(invocation.id(), dependencies.json().encode(plan));
        SandboxNetworkAccess network =
                SandboxNetworkAccess.proxyOnly(lease.id(), lease.endpoint(), controlDirectory, lease::close);
        CodingToolResult execution;
        try {
            execution = processes.run(invocation, command, network, environment, Optional.of(configuration));
        } catch (Exception failure) {
            try {
                evidence.finish(observation, files, invocation, input, Optional.empty());
            } catch (Exception captureFailure) {
                failure.addSuppressed(captureFailure);
            }
            throw failure;
        }
        var observed = evidence.finish(
                observation, files, invocation, input, Optional.of((CodingResults.CommandResult) execution.value()));
        List<ToolExecutionFact> facts = new ArrayList<>(execution.facts());
        // 摘要变化只是两次有界观察的事实；独立证据明确指出外部并发编辑仍可能发生。
        observed.observedChanges().stream()
                .limit(200)
                .forEach(change -> facts.add(new ToolExecutionFact(observedFact(change))));
        return new CodingToolResult(
                new CodingResults.PreparationResult(
                        input.manager(),
                        (CodingResults.CommandResult) execution.value(),
                        invocation.environment().spec().toolchains()),
                facts,
                execution.success());
    }

    static CorePayloads.FileChange observedFact(com.javaclaw.builtin.contracts.DependencyEvidence.Change change) {
        String operation = change.beforeSha256().isEmpty()
                ? "create"
                : change.afterSha256().isEmpty() ? "delete" : "update";
        return new CorePayloads.FileChange(
                Path.of(change.path()), operation, change.beforeSha256(), change.afterSha256());
    }

    private static NetworkPermission destinations(CodingInvocation invocation) {
        Set<String> hosts = invocation.environment().spec().repositoryHosts();
        var permission = invocation.permission().network();
        if (hosts.isEmpty()
                || !permission.allowsPort(443)
                || hosts.stream().anyMatch(host -> !permission.allowsHost(host))) {
            throw new SecurityException("依赖准备仓库缺少当前 Turn 的精确 HTTPS 授权");
        }
        return new NetworkPermission(hosts, Set.of(443), true);
    }

    private Instant expiry(CodingInvocation invocation) {
        Instant now = dependencies.clock().instant();
        Instant maximum = now.plus(invocation.permission().processes().maxRunTime());
        Instant turnEnd =
                invocation.turn().createdAt().plus(invocation.turn().budget().wallTime());
        Instant policyEnd = now.plus(Duration.ofMinutes(30));
        Instant end = maximum.isBefore(turnEnd) ? maximum : turnEnd;
        end = end.isBefore(policyEnd) ? end : policyEnd;
        if (!now.isBefore(end)) {
            throw new IllegalStateException("TURN_DEADLINE_EXCEEDED: 准备阶段没有剩余墙钟预算");
        }
        return end;
    }

    private static Path writeMavenSettings(Path directory, int port) throws Exception {
        String xml = "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"><proxies>" + mavenProxy("http", port)
                + mavenProxy("https", port) + "</proxies></settings>";
        Path target = directory.resolve("maven-settings.xml");
        Files.writeString(
                target,
                xml,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        return target;
    }

    private static String mavenProxy(String protocol, int port) {
        return "<proxy><id>coding-" + protocol + "</id><active>true</active><protocol>" + protocol
                + "</protocol><host>127.0.0.1</host><port>" + port + "</port><nonProxyHosts></nonProxyHosts></proxy>";
    }

    private static Map<String, String> proxyEnvironment(
            String proxy, int port, CodingContracts.PackageManager manager) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        environment.put("HTTPS_PROXY", proxy);
        environment.put("HTTP_PROXY", proxy);
        environment.put("https_proxy", proxy);
        environment.put("http_proxy", proxy);
        environment.put("NO_PROXY", "");
        environment.put("no_proxy", "");
        environment.put("npm_config_proxy", proxy);
        environment.put("npm_config_https_proxy", proxy);
        environment.put("PIP_PROXY", proxy);
        environment.put("PIP_CONFIG_FILE", CodingToolchainCatalog.platform().equals("windows") ? "NUL" : "/dev/null");
        if (manager == CodingContracts.PackageManager.GRADLE) {
            environment.put(
                    "GRADLE_OPTS",
                    "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=" + port
                            + " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=" + port + " -Dhttp.nonProxyHosts=");
        }
        return Map.copyOf(environment);
    }

    void finish(TurnId turnId) throws Exception {
        scope.finish(turnId);
    }

    @Override
    public void close() throws Exception {
        Exception failure = CodingCleanup.close(null, scope, broker);
        if (failure != null) {
            throw failure;
        }
    }

    private record PreparationIdentity(
            CodingContracts.DependenciesPrepare input,
            Map<String, Optional<String>> manifests,
            String environmentDigest,
            String permissionDigest,
            String observedSourcesDigest) {}
}
