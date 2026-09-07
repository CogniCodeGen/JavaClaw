package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

/** 执行测试显式使用当前测试 JDK；不把测试机 JDK 宣称为已下载的托管制品。 */
final class FixtureToolchains implements CodingToolchainPort {
    private final Map<ToolchainKind, InstalledArtifact> extra = new java.util.EnumMap<>(ToolchainKind.class);

    void register(CodingEnvironmentContracts.ToolchainArtifact artifact, Path root) {
        extra.put(artifact.reference().kind(), new InstalledArtifact(artifact, root));
    }

    @Override
    public CodingEnvironmentContracts.Catalog catalog() {
        return CodingToolchainCatalog.bundled().list();
    }

    @Override
    public CodingEnvironmentContracts.InstalledList installed(WorkspaceId workspaceId) {
        return new CodingEnvironmentContracts.InstalledList(List.of());
    }

    @Override
    public CodingEnvironmentContracts.InstallAccepted install(
            ExtensionRequest request, CancellationToken cancellation) {
        throw new UnsupportedOperationException("测试不安装工具链");
    }

    @Override
    public Lease acquire(WorkspaceId workspaceId, List<CodingEnvironmentContracts.ToolchainRef> references) {
        if (references.stream()
                .anyMatch(reference -> reference.kind() != ToolchainKind.JDK && !extra.containsKey(reference.kind()))) {
            throw new IllegalStateException("TOOLCHAIN_MISSING: 测试未安装此工具链");
        }
        var published = catalog().artifacts().stream()
                .filter(artifact -> artifact.reference().kind() == ToolchainKind.JDK)
                .findFirst()
                .orElseThrow();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var fixture = new CodingEnvironmentContracts.ToolchainArtifact(
                published.reference(),
                published.platform(),
                published.architecture(),
                published.downloadUri(),
                published.archiveFormat(),
                Map.of("java", windows ? "bin/java.exe" : "bin/java", "javac", windows ? "bin/javac.exe" : "bin/javac"),
                published.downloadBytes(),
                published.license());
        return new Lease() {
            @Override
            public Map<ToolchainKind, InstalledArtifact> installations() {
                var result = new java.util.EnumMap<ToolchainKind, InstalledArtifact>(ToolchainKind.class);
                for (var reference : references) {
                    result.put(
                            reference.kind(),
                            reference.kind() == ToolchainKind.JDK && !extra.containsKey(ToolchainKind.JDK)
                                    ? new InstalledArtifact(fixture, Path.of(System.getProperty("java.home")))
                                    : extra.get(reference.kind()));
                }
                return Map.copyOf(result);
            }

            @Override
            public void close() {}
        };
    }

    @Override
    public void registerJobs(ExtensionJobRegistrar registrar) {}

    @Override
    public void close() {}
}
