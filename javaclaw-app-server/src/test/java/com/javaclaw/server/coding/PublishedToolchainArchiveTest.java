package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Catalog;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发行归档的离线验收；输入由显式校验流程准备，不在默认测试下载外部制品。 */
@EnabledIfSystemProperty(named = "javaclaw.toolchainArchives", matches = ".+")
class PublishedToolchainArchiveTest {
    @TempDir
    Path temporary;

    @Test
    void WindowsPython归档在任意宿主解包后同时满足Python和Pip真实入口() throws Exception {
        Path archive = Path.of(System.getProperty("javaclaw.toolchainArchives")).resolve("python-windows.tar.gz");
        var json = new CanonicalJson();
        Catalog source;
        try (var input = CodingToolchainCatalog.class.getResourceAsStream("/coding/toolchains-v1.json")) {
            source = json.decode(
                    new CanonicalPayload(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)),
                    Catalog.class);
        }
        var catalog = new CodingToolchainCatalog(source.artifacts(), "windows", "x64");
        var installer = new ToolchainInstaller(
                temporary.resolve("data-v6"), (artifact, target, cancellation) -> Files.copy(archive, target), json);
        Path installed = null;
        for (ToolchainKind kind : List.of(ToolchainKind.PYTHON, ToolchainKind.PIP)) {
            var artifact = catalog.list().artifacts().stream()
                    .filter(value -> value.reference().kind() == kind)
                    .findFirst()
                    .orElseThrow();
            Path root = installer.install(artifact, new CancellationSource());
            if (installed != null) {
                assertEquals(installed, root);
            }
            installed = root;
            assertEquals(root, installer.verify(artifact, new CancellationSource()));
            for (String entry : artifact.executablePaths().values()) {
                assertTrue(Files.isRegularFile(root.resolve(entry)));
            }
            assertTrue(Files.isRegularFile(root.resolve("python/python.exe")));
        }
    }

    @Test
    void 真实归档通过流式解包完整性证据和目录入口校验() throws Exception {
        var files = Map.of(
                ToolchainKind.MAVEN,
                "maven.tar.gz",
                ToolchainKind.PNPM,
                "pnpm.tgz",
                ToolchainKind.NODE,
                "node-mac.tar.gz",
                ToolchainKind.PYTHON,
                "python-mac.tar.gz");
        Path source = Path.of(System.getProperty("javaclaw.toolchainArchives"));
        var catalog = CodingToolchainCatalog.bundled();
        Path dataRoot = temporary.resolve("data-v6");
        var installer = new ToolchainInstaller(
                dataRoot,
                (artifact, target, cancellation) ->
                        Files.copy(source.resolve(files.get(artifact.reference().kind())), target),
                new CanonicalJson());
        for (ToolchainKind kind :
                List.of(ToolchainKind.MAVEN, ToolchainKind.PNPM, ToolchainKind.NODE, ToolchainKind.PYTHON)) {
            var artifact = catalog.list().artifacts().stream()
                    .filter(item -> item.reference().kind() == kind)
                    .findFirst()
                    .orElseThrow();
            Path root = installer.install(artifact, new CancellationSource());
            assertEquals(root, installer.verify(artifact, new CancellationSource()));
            for (String entry : artifact.executablePaths().values()) {
                assertTrue(Files.isRegularFile(root.resolve(entry)));
            }
        }
    }
}
