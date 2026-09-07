package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts.CommandRun;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

/** 真 H2 Turn 与显式的空启动目录夹具；仅检查解析结果，不执行这些假制品。 */
final class ManagedResolverFixture implements AutoCloseable {
    final TrackedToolchains toolchains = new TrackedToolchains();
    final CodingTestFixture server;
    final Path layouts;
    final List<List<ToolchainKind>> checked = new ArrayList<>();
    Environment environment =
            new Environment(1, CodingToolchainCatalog.bundled().defaultEnvironment());

    ManagedResolverFixture(Path temporary) throws Exception {
        layouts = Files.createDirectories(temporary.resolve("fixture layouts"));
        server = new CodingTestFixture(temporary, toolchains);
    }

    ManagedCommandResolver resolver(Clock clock) {
        return new ManagedCommandResolver(
                toolchains, server.database.dataRoot(), clock, (turn, kinds) -> checked.add(kinds));
    }

    CodingInvocation invocation(CommandRun input, PermissionProfile permission) {
        return new CodingInvocation(
                "resolver",
                server.request(server.turn, "command_run", input, "resolver"),
                server.turn,
                server.workspace.id(),
                permission,
                new CancellationSource(),
                environment);
    }

    ToolchainArtifact artifact(ToolchainKind kind, String prefix) {
        return toolchains.catalog().artifacts().stream()
                .filter(value -> value.reference().kind() == kind
                        && value.reference().version().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    Path register(ToolchainArtifact artifact) throws Exception {
        Path root = Files.createDirectories(
                layouts.resolve(artifact.reference().kind().name()));
        for (String relative : artifact.executablePaths().values()) {
            Path executable = root.resolve(relative);
            Files.createDirectories(executable.getParent());
            Files.write(executable, new byte[0]);
        }
        toolchains.delegate.register(artifact, root);
        var old = environment.spec();
        List<ToolchainRef> references = old.toolchains().stream()
                .map(value -> value.kind() == artifact.reference().kind() ? artifact.reference() : value)
                .toList();
        environment = new Environment(
                environment.revision(),
                new EnvironmentSpec(old.name(), references, old.repositoryHosts(), old.allowLifecycleScripts()));
        return root;
    }

    @Override
    public void close() throws Exception {
        server.close();
    }

    /** 对正式端口加引用计数，拒绝路径必须在获租约之前或关闭已获得的租约。 */
    static final class TrackedToolchains implements CodingToolchainPort {
        final FixtureToolchains delegate = new FixtureToolchains();
        int acquired;
        int released;

        @Override
        public CodingEnvironmentContracts.Catalog catalog() {
            return delegate.catalog();
        }

        @Override
        public CodingEnvironmentContracts.InstalledList installed(WorkspaceId workspaceId) {
            return delegate.installed(workspaceId);
        }

        @Override
        public CodingEnvironmentContracts.InstallAccepted install(ExtensionRequest request, CancellationToken token) {
            return delegate.install(request, token);
        }

        @Override
        public Lease acquire(WorkspaceId workspaceId, List<ToolchainRef> references) {
            Lease lease = delegate.acquire(workspaceId, references);
            acquired++;
            return new Lease() {
                private boolean closed;

                @Override
                public Map<ToolchainKind, InstalledArtifact> installations() {
                    return lease.installations();
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        released++;
                        lease.close();
                    }
                }
            };
        }

        @Override
        public void registerJobs(ExtensionJobRegistrar registrar) {
            delegate.registerJobs(registrar);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
