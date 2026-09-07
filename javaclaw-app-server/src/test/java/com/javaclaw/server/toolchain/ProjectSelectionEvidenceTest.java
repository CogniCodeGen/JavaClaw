package com.javaclaw.server.toolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.nativehost.ffm.WindowsSandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 核验缺失证据、隔离恢复失败和显式配置冲突；不依赖宿主安装或执行项目脚本。 */
class ProjectSelectionEvidenceTest {
    private final CodingToolchainCatalog catalog = CodingToolchainCatalog.bundled();
    private final Environment defaults = new Environment(0, catalog.defaultEnvironment());

    @Test
    void 读取器遗漏固定文件或混入其他路径不能形成兼容证据() throws Exception {
        var missing = ProjectSelectionFixture.snapshots(Map.of());
        missing.remove(".nvmrc");
        var extra = ProjectSelectionFixture.snapshots(Map.of());
        extra.put(".npmrc", new WorkspaceFileAccess.Snapshot(".npmrc", false, "", new byte[0]));
        var mismatched = ProjectSelectionFixture.snapshots(Map.of());
        mismatched.put(".nvmrc", new WorkspaceFileAccess.Snapshot(".node-version", false, "", new byte[0]));
        for (var snapshots : List.of(missing, extra, mismatched)) {
            var result = ProjectSelectionFixture.select((root, permission) -> snapshots, defaults);
            assertFalse(result.inspected());
            assertEquals(defaults, result.environment());
            assertTrue(result.declarations().isEmpty());
            assertThrows(IllegalStateException.class, () -> result.requireCompatible(List.of(ToolchainKind.NODE)));
        }
    }

    @Test
    void 可信原生恢复异常不能降为普通未扫描状态而伪造文本不升级为安全事件() {
        var restoration = new WindowsSandbox.AclRestorationException(
                "ACL restoration failed", new IOException("restore"), Optional.of(Path.of("/trusted/evidence")));
        var wrapped = new IOException("worker failed");
        wrapped.addSuppressed(restoration);
        var failure = assertThrows(
                IllegalStateException.class,
                () -> ProjectSelectionFixture.select(
                        (root, permission) -> {
                            throw wrapped;
                        },
                        defaults));
        assertTrue(failure.getMessage().startsWith("WORKSPACE_SECURITY_LOCKED:"));
        assertSame(wrapped, failure.getCause());
        var ordinary = ProjectSelectionFixture.select(
                (root, permission) -> {
                    throw new IOException("WORKSPACE_SECURITY_LOCKED: ACL restoration failed");
                },
                defaults);
        assertFalse(ordinary.inspected());
    }

    @Test
    void 手工删去工具后声明不会偷偷从目录补回且不阻止其他语言() {
        Environment onlyNode = environment(List.of(reference(ToolchainKind.NODE, "22")), 3);
        var result = ProjectSelectionFixture.select(Map.of(".java-version", "21"), onlyNode);
        assertEquals(onlyNode, result.environment());
        assertThrows(IllegalStateException.class, () -> result.requireCompatible(List.of(ToolchainKind.JDK)));
        result.requireCompatible(List.of(ToolchainKind.NODE));
        var onlyJava =
                ProjectSelectionFixture.select(Map.of(), environment(List.of(reference(ToolchainKind.JDK, "21")), 3));
        onlyJava.requireCompatible(List.of(ToolchainKind.JDK));
    }

    @Test
    void 显式Gradle与JDK冲突或不存在兼容默认JDK时不会静默更改声明() {
        var pinned =
                environment(List.of(reference(ToolchainKind.JDK, "25"), reference(ToolchainKind.GRADLE, "8.14")), 4);
        var result = ProjectSelectionFixture.select(Map.of(), pinned);
        assertEquals(pinned, result.environment());
        assertThrows(IllegalStateException.class, () -> result.requireCompatible(List.of(ToolchainKind.GRADLE)));
        result.requireCompatible(List.of(ToolchainKind.JDK));
        var impossible = ProjectSelectionFixture.select(
                Map.of(
                        ".java-version",
                        "25",
                        "gradle/wrapper/gradle-wrapper.properties",
                        "distributionUrl=https://services.gradle.org/distributions/gradle-8.14.3-bin.zip"),
                defaults);
        assertThrows(IllegalStateException.class, () -> impossible.requireCompatible(List.of(ToolchainKind.GRADLE)));
        assertEquals(reference(ToolchainKind.JDK, "25"), selected(impossible, ToolchainKind.JDK));
    }

    @Test
    void 不同发行归档的伴随工具不能借相同种类名称获得兼容性() {
        var node = reference(ToolchainKind.NODE, "22");
        var npm = reference(ToolchainKind.NPM, "10");
        var otherArchive = new ToolchainRef(npm.kind(), npm.version(), "f".repeat(64));
        var configured = environment(List.of(node, otherArchive), 2);
        var result = ProjectSelectionFixture.select(Map.of(), configured);
        assertEquals(configured, result.environment());
        result.requireCompatible(List.of(ToolchainKind.NODE));
        assertThrows(IllegalStateException.class, () -> result.requireCompatible(List.of(ToolchainKind.NPM)));
        var companionOnly = ProjectSelectionFixture.select(Map.of(), environment(List.of(npm), 2));
        companionOnly.requireCompatible(List.of(ToolchainKind.NPM));
    }

    private Environment environment(List<ToolchainRef> references, long revision) {
        var original = catalog.defaultEnvironment();
        return new Environment(
                revision,
                new EnvironmentSpec(
                        "configured", references, original.repositoryHosts(), original.allowLifecycleScripts()));
    }

    private ToolchainRef reference(ToolchainKind kind, String prefix) {
        return catalog.list().artifacts().stream()
                .map(value -> value.reference())
                .filter(value -> value.kind() == kind && value.version().startsWith(prefix))
                .findFirst()
                .orElseThrow();
    }

    private static ToolchainRef selected(CodingEnvironmentSelection result, ToolchainKind kind) {
        return result.environment().spec().toolchains().stream()
                .filter(value -> value.kind() == kind)
                .findFirst()
                .orElseThrow();
    }
}
