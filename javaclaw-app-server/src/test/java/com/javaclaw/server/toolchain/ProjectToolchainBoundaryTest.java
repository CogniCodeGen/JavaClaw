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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用实际项目声明格式核验冲突和静态边界，不执行版本管理器或构建脚本。 */
class ProjectToolchainBoundaryTest {
    private final CodingToolchainCatalog catalog = CodingToolchainCatalog.bundled();

    @Test
    void 工具版本文件与专用声明相交且不使用多版本或宿主回退() {
        var selected = select(
                Map.of(".tool-versions", "# pinned project\njava 21\nnodejs 22.18.0\npython 3.12.11\nruby system\n"),
                0);
        selected.requireCompatible(List.of(ToolchainKind.JDK, ToolchainKind.NODE, ToolchainKind.PYTHON));
        assertTrue(version(selected, ToolchainKind.JDK).startsWith("21"));
        assertTrue(selected.declarations().containsKey(".tool-versions"));
        var conflict = select(Map.of(".tool-versions", "java 25\n", ".java-version", "21"), 0);
        assertThrows(IllegalStateException.class, () -> conflict.requireCompatible(List.of(ToolchainKind.JDK)));
        for (String text : List.of("java system", "java path:/host/jdk", "java 21 25", "java 21\njava 21")) {
            var denied = select(Map.of(".tool-versions", text), 0);
            assertThrows(IllegalStateException.class, () -> denied.requireCompatible(List.of(ToolchainKind.JDK)), text);
            denied.requireCompatible(List.of(ToolchainKind.NODE));
        }
    }

    @Test
    void 包管理器必须使用完整版本且不隐式禁止其他已注册工具() {
        var selected = select(
                Map.of("package.json", "{\"engines\":{\"node\":\">=22 <23\"},\"packageManager\":\"pnpm@10.14.0\"}"), 0);
        selected.requireCompatible(List.of(ToolchainKind.NODE, ToolchainKind.PNPM));
        selected.requireCompatible(List.of(ToolchainKind.NPM));
        for (String text : List.of(
                "{\"engines\":\"node >=22\"}",
                "{\"engines\":null}",
                "{\"packageManager\":\"pnpm@10\"}",
                "{\"packageManager\":\"pnpm@https://example.org/pkg.tgz\"}")) {
            var denied = select(Map.of("package.json", text), 0);
            assertThrows(IllegalStateException.class, () -> denied.requireCompatible(List.of(ToolchainKind.PNPM)));
        }
    }

    @Test
    void Python标准字段与Poetry字段矛盾不能只采用其中一个() {
        String text = "[project]\nrequires-python = \">=3.12,<3.13\"\n[tool.poetry.dependencies]\npython = \"<3.12\"\n";
        var selected = select(Map.of("pyproject.toml", text), 0);
        assertThrows(IllegalStateException.class, () -> selected.requireCompatible(List.of(ToolchainKind.PYTHON)));
        selected.requireCompatible(List.of(ToolchainKind.NODE));
        for (String invalid : List.of("^3.12", "~=3", ">=3.*")) {
            var denied = select(Map.of("pyproject.toml", "[project]\nrequires-python = \"" + invalid + "\""), 0);
            assertThrows(
                    IllegalStateException.class,
                    () -> denied.requireCompatible(List.of(ToolchainKind.PYTHON)),
                    invalid);
        }
    }

    @Test
    void Gradle编译目标不错误绑定精确JDK且注释或字符串不构成版本声明() {
        String source = "java {\n sourceCompatibility = JavaVersion.VERSION_1_8\n targetCompatibility = \"1.8\"\n}\n"
                + "// jvmToolchain(8)\nprintln(\"sourceCompatibility = 7\")\n";
        var selected = select(Map.of("build.gradle", source), 0);
        selected.requireCompatible(List.of(ToolchainKind.JDK, ToolchainKind.GRADLE));
        assertTrue(version(selected, ToolchainKind.JDK).startsWith("25"));
        var toolchain = select(
                Map.of("build.gradle.kts", "java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }"),
                0);
        toolchain.requireCompatible(List.of(ToolchainKind.JDK));
        assertTrue(version(toolchain, ToolchainKind.JDK).startsWith("21"));
    }

    @Test
    void Gradle静态声明不能掩盖另一项需要执行求值的声明() {
        for (String source : List.of(
                "kotlin { jvmToolchain(21) }\ntargetCompatibility = requestedVersion\n",
                "java { toolchain { languageVersion = JavaLanguageVersion.of(21 + offset) } }",
                "sourceCompatibility = 17 + versionOffset")) {
            var selected = select(Map.of("build.gradle.kts", source), 0);
            assertThrows(
                    IllegalStateException.class, () -> selected.requireCompatible(List.of(ToolchainKind.JDK)), source);
            selected.requireCompatible(List.of(ToolchainKind.NODE));
        }
    }

    @Test
    void 显式JDK固定版本与Gradle运行要求冲突时保持原精确配置() {
        var selected = select(
                Map.of(
                        "gradle/wrapper/gradle-wrapper.properties",
                        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip"),
                1);
        assertEquals(catalog.defaultEnvironment(), selected.environment().spec());
        assertThrows(IllegalStateException.class, () -> selected.requireCompatible(List.of(ToolchainKind.GRADLE)));
        var defaults = select(
                Map.of(
                        "gradle/wrapper/gradle-wrapper.properties",
                        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip"),
                0);
        defaults.requireCompatible(List.of(ToolchainKind.JDK, ToolchainKind.GRADLE));
        assertTrue(version(defaults, ToolchainKind.JDK).startsWith("21"));
    }

    @Test
    void Maven编译插件的release配置参与JDK约束且属性循环不能回退() {
        String plugin = "<project><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "<configuration><release>26</release></configuration></plugin></plugins></build></project>";
        var selected = select(Map.of("pom.xml", plugin), 0);
        assertThrows(IllegalStateException.class, () -> selected.requireCompatible(List.of(ToolchainKind.JDK)));
        var cyclic = select(
                Map.of(
                        "pom.xml",
                        "<project><properties><maven.compiler.release>${version.a}</maven.compiler.release>"
                                + "<version.a>${version.b}</version.a><version.b>${version.a}</version.b></properties></project>"),
                0);
        assertThrows(IllegalStateException.class, () -> cyclic.requireCompatible(List.of(ToolchainKind.JDK)));
    }

    @Test
    void 真实Node范围和专用版本声明共同约束且不隐藏冲突() {
        String manifest =
                "{\"engines\":{\"node\":\"^20 || ~22.18\",\"npm\":\"^10.9\"},\"packageManager\":\"pnpm@10.14.0\"}";
        var compatible = select(Map.of("package.json", manifest, ".nvmrc", "v22.18.0"), 0);
        compatible.requireCompatible(List.of(ToolchainKind.NODE, ToolchainKind.NPM, ToolchainKind.PNPM));
        var conflict = select(Map.of("package.json", manifest, ".node-version", "24"), 0);
        assertThrows(IllegalStateException.class, () -> conflict.requireCompatible(List.of(ToolchainKind.NODE)));
        assertTrue(conflict.inspected());
        assertEquals(2, conflict.declarations().size());
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
