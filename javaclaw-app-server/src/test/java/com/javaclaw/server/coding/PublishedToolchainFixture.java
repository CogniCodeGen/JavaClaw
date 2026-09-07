package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;

/** 五 Runner 共用真实发行归档；传输夹具不替代生产摘要校验、解包和原生执行。 */
final class PublishedToolchainFixture {
    private PublishedToolchainFixture() {}

    static void install(CodingTestFixture fixture, FixtureToolchains toolchains) throws Exception {
        install(fixture, toolchains, java.util.EnumSet.allOf(ToolchainKind.class));
    }

    /** 只安装本场景需要的官方制品，仍经生产摘要校验和解包，禁止落回宿主 PATH。 */
    static void install(CodingTestFixture fixture, FixtureToolchains toolchains, Set<ToolchainKind> required)
            throws Exception {
        var installer = new ToolchainInstaller(
                fixture.database.dataRoot(),
                (artifact, target, cancellation) -> Files.copy(archive(artifact), target),
                fixture.json);
        Map<ToolchainKind, ToolchainArtifact> selected = new EnumMap<>(ToolchainKind.class);
        for (var artifact : toolchains.catalog().artifacts()) {
            if (required.contains(artifact.reference().kind())) {
                selected.putIfAbsent(artifact.reference().kind(), artifact);
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(required, selected.keySet(), "平台缺少所需官方工具链");
        for (var artifact : selected.values()) {
            Path root = installer.install(artifact, new CancellationSource());
            toolchains.register(artifact, root);
        }
    }

    private static Path archive(ToolchainArtifact artifact) {
        Path directory = Path.of(System.getProperty("javaclaw.toolchainArchives"));
        return directory.resolve(artifact.reference().artifactSha256() + ".archive");
    }
}
