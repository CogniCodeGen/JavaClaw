package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.server.persistence.CodingEnvironmentRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.PersistenceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原生 Worker 读取与真实 H2 启动证据验收；伪造的启动目录只检查 argv，不宣称执行了 Maven 发行包。 */
class MavenLaunchNativeTest {
    @TempDir
    Path temporary;

    @Test
    void 原生读取多模块配置应用于实际argv并与原准备计划一起在启动前冻结() throws Exception {
        var toolchains = new FixtureToolchains();
        try (var fixture = new CodingTestFixture(temporary, toolchains)) {
            registerMavenLayout(toolchains);
            Files.createDirectories(fixture.root.resolve(".mvn"));
            Files.createDirectories(fixture.root.resolve("module"));
            Path configuration = fixture.root.resolve(".mvn/jvm.config");
            Files.writeString(configuration, "-Xmx256m\n-Dproject.marker=original\n");
            var input = new CodingContracts.CommandRun(List.of("mvn", "--version"), "module", 30, 65536);
            var invocation = invocation(fixture, input);
            var operations = new CodingOperationRepository(fixture.database, fixture.json, fixture.clock);
            operations.prepare(new CodingOperationRepository.Intent(
                    invocation.id(),
                    fixture.turn.id(),
                    fixture.workspace.id(),
                    invocation.request().callId(),
                    "command_run",
                    fixture.root,
                    invocation.request().arguments()));
            var previous = fixture.json.encode(Map.of("manager", "MAVEN", "manifest", "original"));
            operations.preparation(invocation.id(), previous);
            var resolver = new ManagedCommandResolver(
                    toolchains, fixture.database.dataRoot(), fixture.clock, (turn, kinds) -> {});
            try (var resolved = resolver.resolve(invocation, input, SandboxMode.BATCH)) {
                List<String> argv = resolved.command().argv();
                assertTrue(argv.contains("-Xmx256m"));
                assertTrue(argv.contains("-Dproject.marker=original"));
                assertTrue(argv.contains("-Dmaven.multiModuleProjectDirectory=" + fixture.root));
                assertTrue(
                        argv.indexOf("-Xmx256m") < argv.indexOf("org.codehaus.plexus.classworlds.launcher.Launcher"));
                assertFalse(resolved.command().environment().containsKey("MAVEN_OPTS"));
                assertFalse(resolved.command().environment().containsKey("JAVA_TOOL_OPTIONS"));
                CodingCommandEvidence.store(
                        operations, fixture.json, invocation, resolved, resolved.command(), "OFFLINE");
                operations.start(invocation.id());
                Files.writeString(configuration, "-Dproject.marker=changed");
                var saved = fixture.json.decode(
                        operations
                                .find(fixture.workspace.id(), invocation.id())
                                .orElseThrow()
                                .preparation()
                                .orElseThrow(),
                        CodingCommandEvidence.class);
                assertEquals(previous, saved.precedingPreparation().orElseThrow());
                assertEquals(resolved.maven().orElseThrow(), saved.maven().orElseThrow());
                assertEquals("module", saved.relativeCwd());
                assertEquals(argv, saved.argv());
                assertThrows(
                        PersistenceException.class,
                        () -> CodingCommandEvidence.store(
                                operations, fixture.json, invocation, resolved, resolved.command(), "OFFLINE"));
            }
        }
    }

    @Test
    void 原生读取拒绝超额配置且缓存属性冲突不会被静默覆盖() throws Exception {
        var toolchains = new FixtureToolchains();
        try (var fixture = new CodingTestFixture(temporary, toolchains)) {
            registerMavenLayout(toolchains);
            Files.createDirectories(fixture.root.resolve(".mvn"));
            Path configuration = fixture.root.resolve(".mvn/jvm.config");
            Files.writeString(configuration, "-Duser.home=/project-selected");
            var input = new CodingContracts.CommandRun(List.of("mvn", "--version"), ".", 30, 65536);
            var invocation = invocation(fixture, input);
            var resolver = new ManagedCommandResolver(
                    toolchains, fixture.database.dataRoot(), fixture.clock, (turn, kinds) -> {});
            var conflict = assertThrows(
                    IllegalArgumentException.class, () -> resolver.resolve(invocation, input, SandboxMode.BATCH));
            assertTrue(conflict.getMessage().contains("MAVEN_JVM_PROPERTY_CONFLICT: user.home"));
            Files.writeString(configuration, " ".repeat(64 * 1024 + 1));
            assertThrows(Exception.class, () -> MavenProjectLaunch.resolve(invocation, fixture.root, input.argv()));
        }
    }

    @Test
    void 受控代理实际进入Java参数且先拒绝项目设置的旁路值() {
        var command = new SandboxCommand(
                "proxy",
                List.of(temporary.resolve("java").toString(), "Main"),
                temporary,
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(10));
        var additional = Map.of("HTTPS_PROXY", "http://127.0.0.1:3137");
        var resolved = CodingProcessManager.command(command, additional);
        assertTrue(resolved.argv().contains("-Dhttps.proxyHost=127.0.0.1"));
        assertTrue(resolved.argv().contains("-Dhttp.proxyPort=3137"));
        assertTrue(resolved.argv().contains("-Dhttp.nonProxyHosts="));
        var conflict = new SandboxCommand(
                "conflict",
                List.of(temporary.resolve("java").toString(), "-Dhttps.proxyHost=elsewhere", "Main"),
                temporary,
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> CodingProcessManager.command(conflict, additional));
    }

    private void registerMavenLayout(FixtureToolchains toolchains) throws Exception {
        var artifact = toolchains.catalog().artifacts().stream()
                .filter(value -> value.reference().kind() == ToolchainKind.MAVEN)
                .findFirst()
                .orElseThrow();
        Path installed = Files.createDirectories(temporary.resolve("test-layout"));
        Path home = installed
                .resolve(artifact.executablePaths().get("mvn"))
                .getParent()
                .getParent();
        Files.createDirectories(home.resolve("boot"));
        Files.write(home.resolve("boot/plexus-classworlds-fixture.jar"), new byte[0]);
        toolchains.register(artifact, installed);
    }

    private static CodingInvocation invocation(CodingTestFixture fixture, CodingContracts.CommandRun input) {
        var environment = new CodingEnvironmentRepository(fixture.database, fixture.json, fixture.clock)
                .frozen(fixture.turn.id());
        return new CodingInvocation(
                "maven-launch",
                fixture.request(fixture.turn, "command_run", input, "maven-launch"),
                fixture.turn,
                fixture.workspace.id(),
                fixture.permission,
                new CancellationSource(),
                environment);
    }
}
