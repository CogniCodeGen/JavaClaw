package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionRequest;

/** 借用已审核目录引用而不执行发行包，只记录真实解析器取得和释放的租约。 */
final class PreparationToolchains implements CodingToolchainPort {
    final AtomicInteger acquired = new AtomicInteger();
    final AtomicInteger released = new AtomicInteger();
    private final FixtureToolchains delegate = new FixtureToolchains();

    PreparationToolchains(Path root) throws Exception {
        for (ToolchainKind kind : List.of(ToolchainKind.NODE, ToolchainKind.NPM)) {
            var artifact = catalog().artifacts().stream()
                    .filter(value -> value.reference().kind() == kind)
                    .findFirst()
                    .orElseThrow();
            Path installation = Files.createDirectories(root.resolve(kind.name()));
            delegate.register(artifact, installation);
        }
    }

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
        Lease selected = delegate.acquire(workspaceId, references);
        acquired.incrementAndGet();
        return new Lease() {
            @Override
            public Map<ToolchainKind, InstalledArtifact> installations() {
                return selected.installations();
            }

            @Override
            public void close() {
                released.incrementAndGet();
                selected.close();
            }
        };
    }

    @Override
    public void registerJobs(ExtensionJobRegistrar registrar) {}

    @Override
    public void close() {}
}
