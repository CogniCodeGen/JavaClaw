package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CodingTerminalRepository;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.turn.CodingExecutionAuthority;

/** 真实 H2/权限与可控制的进程时序；所有外部执行都替换为专门的生命周期夹具。 */
final class CodingLifecycleFixture implements AutoCloseable {
    final CodingTestFixture base;
    final CodingExecutionLocks locks = new CodingExecutionLocks();
    final ControlledToolchains toolchains = new ControlledToolchains();
    final CodingProcessManager processes;
    final CodingTerminalManager terminals;
    final CodingTerminalRepository records;
    private final CodingOperationRepository operations;

    CodingLifecycleFixture(Path directory, CodingProcessSandbox sandbox) throws Exception {
        base = new CodingTestFixture(directory);
        var attachments = new AttachmentService(base.database, base.json, base.clock);
        operations = new CodingOperationRepository(base.database, base.json, base.clock);
        records = new CodingTerminalRepository(base.database, attachments, base.json, base.clock);
        var worktrees = new ManagedWorktreeService(
                base.database, attachments, base.json, base.clock, new PlatformSandboxExecutor());
        var authority = new CodingExecutionAuthority(
                base.core,
                base.profiles,
                worktrees,
                new ExtensionCatalogRepository(base.database, base.json, base.clock),
                base.json);
        var resolver =
                new ManagedCommandResolver(toolchains, directory.resolve("data-v6"), base.clock, (turnId, kinds) -> {});
        processes = new CodingProcessManager(
                new CodingProcessManager.Services(
                        base.json,
                        operations,
                        attachments,
                        new CodingCommandOutputRepository(base.database, base.json)),
                resolver,
                locks,
                authority,
                sandbox);
        terminals = new CodingTerminalManager(
                new CodingTerminalManager.Services(base.json, records, operations),
                resolver,
                locks,
                authority,
                sandbox);
    }

    CodingInvocation invocation(String id, String operation, Object input) {
        var request = base.request(base.turn, operation, input, id);
        var reference = toolchains.catalog().artifacts().stream()
                .filter(artifact -> artifact.reference().kind() == CodingEnvironmentContracts.ToolchainKind.JDK)
                .findFirst()
                .orElseThrow()
                .reference();
        var environment = new CodingEnvironmentContracts.Environment(
                1, new CodingEnvironmentContracts.EnvironmentSpec("test", List.of(reference), Set.of(), true));
        var invocation = new CodingInvocation(
                id, request, base.turn, base.workspace.id(), base.permission, new CancellationSource(), environment);
        operations.prepare(new CodingOperationRepository.Intent(
                id, base.turn.id(), base.workspace.id(), id, operation, base.root, base.json.encode(request)));
        return invocation;
    }

    @Override
    public void close() throws Exception {
        Exception failure = CodingCleanup.close(null, terminals, processes, base);
        if (failure != null) {
            throw failure;
        }
    }

    static final class ControlledToolchains implements CodingToolchainPort {
        final AtomicInteger released = new AtomicInteger();
        volatile Runnable beforeAcquire = () -> {};
        volatile Runnable beforeRelease = () -> {};
        private final FixtureToolchains delegate = new FixtureToolchains();

        @Override
        public CodingEnvironmentContracts.Catalog catalog() {
            return delegate.catalog();
        }

        @Override
        public CodingEnvironmentContracts.InstalledList installed(WorkspaceId workspaceId) {
            return delegate.installed(workspaceId);
        }

        @Override
        public CodingEnvironmentContracts.InstallAccepted install(
                ExtensionRequest request, CancellationToken cancellation) {
            return delegate.install(request, cancellation);
        }

        @Override
        public Lease acquire(WorkspaceId workspaceId, List<CodingEnvironmentContracts.ToolchainRef> references) {
            beforeAcquire.run();
            Lease lease = delegate.acquire(workspaceId, references);
            return new Lease() {
                @Override
                public Map<CodingEnvironmentContracts.ToolchainKind, InstalledArtifact> installations() {
                    return lease.installations();
                }

                @Override
                public void close() {
                    try {
                        beforeRelease.run();
                    } finally {
                        released.incrementAndGet();
                        lease.close();
                    }
                }
            };
        }

        @Override
        public void registerJobs(ExtensionJobRegistrar registrar) {}

        @Override
        public void close() {}
    }
}
