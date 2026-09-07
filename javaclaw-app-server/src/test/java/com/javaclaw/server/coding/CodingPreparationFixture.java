package com.javaclaw.server.coding;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.SandboxSession;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;
import com.javaclaw.nativehost.sandbox.SandboxRuntimeAccess;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingEnvironmentRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.DependencyEvidenceRepository;
import com.javaclaw.server.persistence.H2Transactions;

/** 真实 H2、原生文件 Worker 和本机 Broker；仅包管理器进程由确定性替身控制。 */
final class CodingPreparationFixture implements AutoCloseable {
    final CodingTestFixture base;
    final PreparationToolchains toolchains;
    final CodingOperationRepository operations;
    final CodingDependencyPreparer preparer;
    final PreparationSandbox sandbox = new PreparationSandbox();
    private final CodingProcessManager processes;

    CodingPreparationFixture(Path directory) throws Exception {
        base = new CodingTestFixture(directory, new FixtureToolchains(), Set.of("repo.example"));
        toolchains = new PreparationToolchains(directory.resolve("tool-layout"));
        var original = DependencyEvidenceTest.dependencies(base);
        var dependencies = new CodingPlatform.Dependencies(
                original.database(),
                original.core(),
                original.authority(),
                original.attachments(),
                toolchains,
                original.sandbox(),
                original.json(),
                original.clock());
        operations = new CodingOperationRepository(base.database, base.json, base.clock);
        var resolver = new ManagedCommandResolver(
                toolchains, base.database.dataRoot(), base.clock, base.core.codingEnvironments()::requireCompatible);
        processes = new CodingProcessManager(
                new CodingProcessManager.Services(
                        base.json,
                        operations,
                        dependencies.attachments(),
                        new CodingCommandOutputRepository(base.database, base.json)),
                resolver,
                new CodingExecutionLocks(),
                dependencies.authority(),
                sandbox);
        preparer = new CodingDependencyPreparer(dependencies, operations, processes, resolver);
    }

    CodingInvocation invocation(String id, CodingContracts.PackageManager manager, boolean scripts, Set<String> hosts) {
        var input = new CodingContracts.DependenciesPrepare(manager, ".", java.util.List.of());
        var request = base.request(base.turn, "dependencies_prepare", input, id);
        var frozen = new CodingEnvironmentRepository(base.database, base.json, base.clock).frozen(base.turn.id());
        var environment = new CodingEnvironmentContracts.Environment(
                frozen.revision(),
                new CodingEnvironmentContracts.EnvironmentSpec(
                        "准备测试", frozen.spec().toolchains(), hosts, scripts));
        var invocation = new CodingInvocation(
                id, request, base.turn, base.workspace.id(), base.permission, new CancellationSource(), environment);
        operations.prepare(new CodingOperationRepository.Intent(
                id,
                base.turn.id(),
                base.workspace.id(),
                id,
                "dependencies_prepare",
                base.root,
                base.json.encode(request)));
        return invocation;
    }

    DependencyEvidence evidence(CodingInvocation invocation) {
        return new DependencyEvidenceRepository(base.database, base.json).read(base.workspace.id(), invocation.id());
    }

    String grantState(String operationId) throws Exception {
        return new H2Transactions(base.database).execute(connection -> {
            try (var statement =
                    connection.prepareStatement("SELECT STATE FROM CORE.CODING_NETWORK_GRANT WHERE OPERATION_ID=?")) {
                statement.setString(1, operationId);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : "ABSENT";
                }
            }
        });
    }

    static void connect(InetSocketAddress endpoint) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, 1000);
        }
    }

    @Override
    public void close() throws Exception {
        Exception failure = CodingCleanup.close(null, preparer, processes, base);
        if (failure != null) {
            throw failure;
        }
    }

    static final class PreparationSandbox implements CodingProcessSandbox {
        int calls;
        int exitCode;
        SandboxCommand command;
        InetSocketAddress endpoint;
        Step step = ignored -> {};

        @Override
        public SandboxResult execute(
                SandboxCommand value,
                PermissionProfile permission,
                CancellationToken cancellation,
                SandboxRuntimeAccess access,
                SandboxNetworkAccess network)
                throws Exception {
            calls++;
            command = value;
            if (network.mode() != SandboxNetworkAccess.Mode.PROXY_ONLY) {
                throw new AssertionError("依赖准备必须通过真实 Broker 的精确端点");
            }
            endpoint = network.proxyEndpoint().orElseThrow();
            connect(endpoint);
            step.execute(value);
            return new SandboxResult(
                    exitCode,
                    "controlled-output".getBytes(StandardCharsets.UTF_8),
                    new byte[0],
                    false,
                    false,
                    Duration.ofMillis(12));
        }

        @Override
        public SandboxSession open(
                SandboxCommand command,
                PermissionProfile permission,
                CancellationToken cancellation,
                SandboxRuntimeAccess access,
                SandboxNetworkAccess network) {
            throw new UnsupportedOperationException("依赖准备不使用 PTY");
        }
    }

    @FunctionalInterface
    interface Step {
        void execute(SandboxCommand command) throws Exception;
    }
}
