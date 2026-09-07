package com.javaclaw.server.toolchain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectToolchainSelectorTest {
    private final CodingToolchainCatalog catalog = CodingToolchainCatalog.bundled();

    @Test
    void 缺省选择满足项目静态Java和Gradle版本但手工配置不被替换() {
        var files = Map.of(
                ".java-version",
                "21",
                "gradle/wrapper/gradle-wrapper.properties",
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip");
        var selected = select(files, 0);
        assertTrue(selected.inspected());
        assertTrue(version(selected, ToolchainKind.JDK).startsWith("21"));
        assertEquals("8.14.3", version(selected, ToolchainKind.GRADLE));
        selected.requireCompatible(List.of(ToolchainKind.JDK, ToolchainKind.GRADLE));
        var explicit = select(files, 1);
        assertEquals(
                catalog.defaultEnvironment().toolchains(),
                explicit.environment().spec().toolchains());
        assertThrows(
                IllegalStateException.class,
                () -> explicit.requireCompatible(List.of(ToolchainKind.JDK, ToolchainKind.GRADLE)));
    }

    @Test
    void Node包管理器与Python范围只限制对应执行种类() {
        var selected = select(
                Map.of("package.json", """
                {"engines":{"node":">=22 <23"},"packageManager":"pnpm@10.14.0"}
                """, "pyproject.toml", "[project]\nrequires-python = \">=3.12,<3.13\""), 0);
        selected.requireCompatible(List.of(ToolchainKind.NODE, ToolchainKind.PNPM, ToolchainKind.PYTHON));
        var incompatible = select(Map.of(".python-version", "2.7"), 0);
        incompatible.requireCompatible(List.of(ToolchainKind.NODE));
        assertThrows(IllegalStateException.class, () -> incompatible.requireCompatible(List.of(ToolchainKind.PYTHON)));
    }

    @Test
    void XML实体和动态版本不能获得兼容证据() {
        var selected = select(Map.of("pom.xml", """
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><properties><java.version>&xxe;</java.version></properties></project>
                """, ".nvmrc", "lts/*"), 0);
        assertTrue(selected.inspected());
        assertThrows(IllegalStateException.class, () -> selected.requireCompatible(List.of(ToolchainKind.JDK)));
        assertThrows(IllegalStateException.class, () -> selected.requireCompatible(List.of(ToolchainKind.NODE)));
    }

    @Test
    void 读取失败不阻断创建但实际命令缺少校验证据() {
        var selector = new ProjectToolchainSelector(catalog, (root, permission) -> {
            throw new SecurityException("读取被拒绝");
        });
        var selected = selector.select(
                Path.of("."),
                TurnContractFixtures.TOOL_CATALOG.permissionCeiling(),
                new Environment(0, catalog.defaultEnvironment()));
        assertFalse(selected.inspected());
        assertThrows(IllegalStateException.class, () -> selected.requireCompatible(List.of(ToolchainKind.NODE)));
    }

    @Test
    void 范围和不支持表达式有确定语义() {
        assertTrue(ToolchainVersionConstraint.matches("22.18.0", "^22.1 || 24.x"));
        assertFalse(ToolchainVersionConstraint.matches("25.0.0+36", ">=17 <25"));
        assertTrue(ToolchainVersionConstraint.matches("3.12.11", "~=3.12"));
        assertFalse(ToolchainVersionConstraint.matches("22.18.0", ">22"));
        assertTrue(ToolchainVersionConstraint.matches("22.18.0", "<=22"));
        assertFalse(ToolchainVersionConstraint.matches("3.12.11", "<=3.12", true));
        assertTrue(ToolchainVersionConstraint.matches("3.12.11", "!=3.12", true));
        assertThrows(IllegalArgumentException.class, () -> ToolchainVersionConstraint.matches("22.18.0", "latest"));
        assertThrows(
                IllegalArgumentException.class, () -> ToolchainVersionConstraint.matches("22.18.0", ">=0 || latest"));
    }

    private CodingEnvironmentSelection select(Map<String, String> content, long revision) {
        var selector = new ProjectToolchainSelector(catalog, (root, permission) -> {
            var snapshots = new HashMap<String, WorkspaceFileAccess.Snapshot>();
            for (String path : ProjectToolchainDeclarations.paths()) {
                byte[] bytes = content.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
                boolean exists = content.containsKey(path);
                String digest = exists
                        ? HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                        : "";
                snapshots.put(path, new WorkspaceFileAccess.Snapshot(path, exists, digest, bytes));
            }
            return snapshots;
        });
        return selector.select(
                Path.of("."),
                TurnContractFixtures.TOOL_CATALOG.permissionCeiling(),
                new Environment(revision, catalog.defaultEnvironment()));
    }

    private static String version(CodingEnvironmentSelection selection, ToolchainKind kind) {
        return selection.environment().spec().toolchains().stream()
                .filter(reference -> reference.kind() == kind)
                .findFirst()
                .orElseThrow()
                .version();
    }
}
