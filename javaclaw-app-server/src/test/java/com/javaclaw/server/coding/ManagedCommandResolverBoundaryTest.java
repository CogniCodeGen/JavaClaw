package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.builtin.contracts.CodingContracts.CommandRun;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真 H2 下核验固定命令构造与资源生命周期；空启动组件不用于操作系统执行验收。 */
class ManagedCommandResolverBoundaryTest {
    @TempDir
    Path temporary;

    @Test
    void Node与两种包管理器固定使用各自CLI且参数保持单个字面量() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var node = fixture.artifact(ToolchainKind.NODE, "22");
            Path nodeRoot = fixture.register(node);
            Path npmRoot = fixture.register(fixture.artifact(ToolchainKind.NPM, "10"));
            Path pnpmRoot = fixture.register(fixture.artifact(ToolchainKind.PNPM, "10"));
            var resolver = fixture.resolver(fixture.server.clock);
            for (String name : List.of("node", "npm", "pnpm")) {
                var input = new CommandRun(List.of(name, "run", "$(touch escaped); *"), ".", 5, 4096);
                try (var result = resolver.resolve(
                        fixture.invocation(input, fixture.server.permission), input, SandboxMode.BATCH)) {
                    var argv = result.command().argv();
                    assertEquals(
                            nodeRoot.resolve(node.executablePaths().get("node")).toString(), argv.getFirst());
                    assertEquals(List.of("run", "$(touch escaped); *"), argv.subList(argv.size() - 2, argv.size()));
                    assertEquals(
                            Set.of(Path.of(argv.getFirst()).getFileName().toString()),
                            result.permission().processes().executables());
                    assertTrue(result.permission().network().hosts().isEmpty());
                    assertEquals(4096, result.permission().resources().outputBytes());
                    assertFalse(result.command().environment().containsKey("JAVA_HOME"));
                    if (!name.equals("node")) {
                        Path managerRoot = name.equals("npm") ? npmRoot : pnpmRoot;
                        assertTrue(Path.of(argv.get(1)).startsWith(managerRoot));
                        assertTrue(argv.get(1).endsWith(".js") || argv.get(1).endsWith(".cjs"));
                    }
                }
            }
            assertEquals(3, fixture.toolchains.released);
            assertEquals(List.of(ToolchainKind.NODE, ToolchainKind.PNPM), fixture.checked.getLast());
        }
    }

    @Test
    void Python别名和pip先由固定解释器选择受限venv且不执行pip壳入口() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var python = fixture.artifact(ToolchainKind.PYTHON, "3.12");
            Path root = fixture.register(python);
            fixture.register(fixture.artifact(ToolchainKind.PIP, "24"));
            var permission = permission(fixture.server.permission, Set.of("python", "python3", "pip"), false);
            for (String name : List.of("python", "python3", "pip")) {
                var input = new CommandRun(List.of(name, "--version"), ".", 5, 4096);
                try (var result = fixture.resolver(fixture.server.clock)
                        .resolve(fixture.invocation(input, permission), input, SandboxMode.BATCH)) {
                    var argv = result.command().argv();
                    assertEquals(
                            root.resolve(python.executablePaths().get("python")).toString(), argv.getFirst());
                    assertEquals("-c", argv.get(1));
                    assertEquals(result.cacheRoot().resolve("venv").toString(), argv.get(3));
                    assertEquals("--version", argv.getLast());
                    assertEquals("1", result.command().environment().get("PYTHONNOUSERSITE"));
                    if (name.equals("pip")) {
                        assertEquals(List.of("-m", "pip", "--version"), argv.subList(4, argv.size()));
                    } else {
                        assertEquals(5, argv.size());
                    }
                }
            }
            assertEquals(3, fixture.toolchains.released);
            assertEquals(List.of(ToolchainKind.PYTHON, ToolchainKind.PIP), fixture.checked.getLast());
        }
    }

    @Test
    void 两个已审阅Gradle布局按唯一启动组件构造Java参数且不使用脚本() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            for (String version : List.of("9.1", "8.14")) {
                var artifact = fixture.artifact(ToolchainKind.GRADLE, version);
                Path root = fixture.register(artifact);
                Path lib = Files.createDirectories(
                        root.resolve(artifact.executablePaths().get("gradle"))
                                .getParent()
                                .getParent()
                                .resolve("lib"));
                String prefix = version.equals("9.1") ? "gradle-gradle-cli-main-" : "gradle-launcher-";
                Path main = Files.write(lib.resolve(prefix + "fixture.jar"), new byte[0]);
                Files.write(lib.resolve(prefix + "fixture.jar.sha256"), new byte[0]);
                Files.write(lib.resolve("unrelated.jar"), new byte[0]);
                var input = new CommandRun(List.of("gradle", "--version"), ".", 5, 4096);
                try (var result = fixture.resolver(fixture.server.clock)
                        .resolve(fixture.invocation(input, fixture.server.permission), input, SandboxMode.BATCH)) {
                    var argv = result.command().argv();
                    assertEquals(main.toString(), argv.get(argv.indexOf("-classpath") + 1));
                    assertEquals(
                            List.of("org.gradle.launcher.GradleMain", "--no-daemon", "--version"),
                            argv.subList(argv.size() - 3, argv.size()));
                    assertTrue(argv.contains("-Duser.home=" + result.cacheRoot()));
                    assertEquals(
                            Path.of(System.getProperty("java.home")).toString(),
                            result.command().environment().get("JAVA_HOME"));
                }
            }
            assertEquals(2, fixture.toolchains.released);
        }
    }

    @Test
    void 启动组件缺失或歧义必须失败并释放已取得的工具链租约() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var artifact = fixture.artifact(ToolchainKind.GRADLE, "9.1");
            Path root = fixture.register(artifact);
            Path lib = Files.createDirectories(
                    root.resolve(artifact.executablePaths().get("gradle"))
                            .getParent()
                            .getParent()
                            .resolve("lib"));
            var input = new CommandRun(List.of("gradle", "build"), ".", 5, 4096);
            var invocation = fixture.invocation(input, fixture.server.permission);
            var resolver = fixture.resolver(fixture.server.clock);
            assertThrows(IllegalStateException.class, () -> resolver.resolve(invocation, input, SandboxMode.BATCH));
            Files.write(lib.resolve("gradle-gradle-cli-main-first.jar"), new byte[0]);
            Files.write(lib.resolve("gradle-gradle-cli-main-second.jar"), new byte[0]);
            assertThrows(IllegalStateException.class, () -> resolver.resolve(invocation, input, SandboxMode.BATCH));
            assertEquals(2, fixture.toolchains.acquired);
            assertEquals(2, fixture.toolchains.released);
        }
    }

    @Test
    void 制品缺少逻辑入口时不搜索宿主PATH并释放租约() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var original = fixture.artifact(ToolchainKind.JDK, "25");
            fixture.register(new ToolchainArtifact(
                    original.reference(),
                    original.platform(),
                    original.architecture(),
                    original.downloadUri(),
                    original.archiveFormat(),
                    Map.of("javac", "bin/javac"),
                    original.downloadBytes(),
                    original.license()));
            var input = new CommandRun(List.of("java", "--version"), ".", 5, 4096);
            var failure = assertThrows(
                    IllegalStateException.class,
                    () -> fixture.resolver(fixture.server.clock)
                            .resolve(fixture.invocation(input, fixture.server.permission), input, SandboxMode.BATCH));
            assertTrue(failure.getMessage().contains("未声明固定入口: java"));
            assertEquals(1, fixture.toolchains.released);
        }
    }

    @Test
    void 未授权逻辑入口和PTY均在版本检查与获租约之前拒绝() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var java = new CommandRun(List.of("java", "--version"), ".", 5, 4096);
            var resolver = fixture.resolver(fixture.server.clock);
            var onlyNode = permission(fixture.server.permission, Set.of("node"), false);
            assertThrows(
                    SecurityException.class,
                    () -> resolver.resolve(fixture.invocation(java, onlyNode), java, SandboxMode.BATCH));
            var noPty = permission(fixture.server.permission, Set.of("java"), false);
            assertThrows(
                    SecurityException.class,
                    () -> resolver.resolve(fixture.invocation(java, noPty), java, SandboxMode.PTY));
            var git = new CommandRun(List.of("git", "status"), ".", 5, 4096);
            var namedGit = permission(fixture.server.permission, Set.of("git"), false);
            var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> resolver.resolve(fixture.invocation(git, namedGit), git, SandboxMode.BATCH));
            assertTrue(failure.getMessage().startsWith("UNREGISTERED_EXECUTABLE:"));
            assertTrue(fixture.checked.isEmpty());
            assertEquals(0, fixture.toolchains.acquired);
        }
    }

    @Test
    void 冻结配置缺少伴随工具或声明冲突时不得开始安装租约() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var old = fixture.environment.spec();
            fixture.environment = new Environment(
                    2,
                    new EnvironmentSpec(
                            old.name(),
                            old.toolchains().stream()
                                    .filter(value -> value.kind() != ToolchainKind.NPM)
                                    .toList(),
                            old.repositoryHosts(),
                            false));
            var input = new CommandRun(List.of("npm", "test"), ".", 5, 4096);
            var invocation = fixture.invocation(input, fixture.server.permission);
            var missing = assertThrows(
                    IllegalStateException.class,
                    () -> fixture.resolver(fixture.server.clock).resolve(invocation, input, SandboxMode.BATCH));
            assertEquals("TOOLCHAIN_MISSING: NPM", missing.getMessage());
            assertEquals(List.of(List.of(ToolchainKind.NODE, ToolchainKind.NPM)), fixture.checked);
            var conflicted = new ManagedCommandResolver(
                    fixture.toolchains, fixture.server.database.dataRoot(), fixture.server.clock, (turn, kinds) -> {
                        throw new IllegalStateException("declaration conflict");
                    });
            assertThrows(IllegalStateException.class, () -> conflicted.resolve(invocation, input, SandboxMode.BATCH));
            assertEquals(0, fixture.toolchains.acquired);
        }
    }

    @Test
    void Turn剩余墙钟和进程权限共同限制时间且耗尽时释放租约() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var deadline = fixture.server
                    .turn
                    .createdAt()
                    .plus(fixture.server.turn.budget().wallTime());
            var input = new CommandRun(List.of("java", "--version"), ".", 90, 4096);
            var invocation = fixture.invocation(input, fixture.server.permission);
            for (var instant : List.of(deadline, deadline.plusNanos(1))) {
                var resolver = fixture.resolver(Clock.fixed(instant, ZoneOffset.UTC));
                var failure = assertThrows(
                        IllegalStateException.class, () -> resolver.resolve(invocation, input, SandboxMode.BATCH));
                assertTrue(failure.getMessage().startsWith("TURN_DEADLINE_EXCEEDED:"));
            }
            try (var result = fixture.resolver(Clock.fixed(deadline.minusMillis(1500), ZoneOffset.UTC))
                    .resolve(invocation, input, SandboxMode.BATCH)) {
                assertEquals(Duration.ofMillis(1500), result.command().timeout());
            }
            try (var result = fixture.resolver(Clock.fixed(deadline.minusSeconds(120), ZoneOffset.UTC))
                    .resolve(invocation, input, SandboxMode.BATCH)) {
                assertEquals(
                        fixture.server.permission.processes().maxRunTime(),
                        result.command().timeout());
            }
            assertEquals(4, fixture.toolchains.acquired);
            assertEquals(4, fixture.toolchains.released);
        }
    }

    private static PermissionProfile permission(PermissionProfile original, Set<String> commands, boolean pty) {
        return new PermissionProfile(
                original.id(),
                original.version(),
                original.files(),
                original.network(),
                new ProcessPermission(commands, pty, original.processes().maxRunTime()),
                original.tools(),
                original.resources());
    }
}
