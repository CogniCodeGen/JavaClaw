package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.builtin.contracts.CodingSystemContracts;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Registration;
import com.javaclaw.builtin.contracts.CodingSystemContracts.RegistryUpdate;
import com.javaclaw.server.persistence.CodingEnvironmentRepository;
import com.javaclaw.server.system.SystemCommandCatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemCommandResolverTest {
    @TempDir
    Path temporary;

    @Test
    void 登记不授权进程且字面参数不会进入Shell或宿主PATH() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            Path program = program();
            AgentTurn turn = register(fixture, program, List.of());
            var input =
                    new CodingSystemContracts.CommandRun("custom", List.of("$(touch bad); *", " a b "), ".", 5, 4096);
            var resolver = resolver(fixture);
            assertThrows(
                    SecurityException.class,
                    () -> resolver.resolveCommand(invocation(fixture, turn, input, fixture.permission), input));
            PermissionProfile allowed = permission(fixture.permission, Set.of("custom"));
            try (var result = resolver.resolveCommand(invocation(fixture, turn, input, allowed), input)) {
                assertEquals(
                        List.of(program.toRealPath().toString(), "$(touch bad); *", " a b "),
                        result.command().argv());
                assertFalse(result.command()
                        .environment()
                        .get("PATH")
                        .contains(program.getParent().toString()));
                assertFalse(result.access().readRoots().contains(program.getParent()));
                assertTrue(result.access().readRoots().contains(program.toRealPath()));
                assertEquals(
                        Set.of(program.getFileName().toString()),
                        result.permission().processes().executables());
                assertTrue(result.permission().network().hosts().isEmpty());
                assertEquals("GBK", result.outputEncoding());
                assertTrue(result.evidence().isPresent());
            }
        }
    }

    @Test
    void 依赖读取需既有授权且替换冻结程序后不能执行() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            Path program = program();
            Path outside = Files.createDirectories(temporary.resolve("private-library"));
            AgentTurn turn = register(fixture, program, List.of(outside.toString()));
            var input = new CodingSystemContracts.CommandRun("custom", List.of("value"), ".", 5, 4096);
            PermissionProfile allowed = permission(fixture.permission, Set.of("custom"));
            var invocation = invocation(fixture, turn, input, allowed);
            var resolver = resolver(fixture);
            assertThrows(SecurityException.class, () -> resolver.resolveCommand(invocation, input));
            assertFalse(resolver.list(invocation).executables().getFirst().available());
            Files.writeString(program, "replacement");
            var failure = assertThrows(SecurityException.class, () -> resolver.resolveCommand(invocation, input));
            assertTrue(failure.getMessage().contains("SYSTEM_EXECUTABLE_CHANGED"));
        }
    }

    @Test
    void 显式Shell保留原命令并固定非登录入口及离线权限() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            boolean windows = SystemCommandCatalog.platform().equals("windows");
            var input = new CodingSystemContracts.ShellRun("printf '%s' \" a b \"", ".", 5, 4096);
            var allowed = permission(fixture.permission, Set.of(windows ? "system.cmd" : "system.sh"));
            var invocation = invocation(fixture, fixture.turn, input, allowed);
            try (var result = resolver(fixture).resolveShell(invocation, input)) {
                assertEquals(input.command(), result.command().argv().getLast());
                assertTrue(result.permission().network().hosts().isEmpty());
                assertFalse(result.command().environment().containsKey("JAVA_HOME"));
                if (!windows) {
                    Path cat = Path.of("/bin/cat").toRealPath();
                    assertTrue(result.access().executableRoots().contains(cat));
                }
                assertEquals(
                        windows ? List.of("/d", "/s", "/c") : List.of("-c"),
                        result.command()
                                .argv()
                                .subList(1, result.command().argv().size() - 1));
            }
        }
    }

    private Path program() throws Exception {
        Path program = temporary.resolve("custom-bin/real-program");
        Files.createDirectories(program.getParent());
        Files.writeString(program, "initial");
        assertTrue(program.toFile().setExecutable(true));
        return program;
    }

    private AgentTurn register(CodingTestFixture fixture, Path program, List<String> roots) {
        RegistryUpdate request =
                new RegistryUpdate(List.of(new Registration("custom", program.toString(), roots, "GBK")));
        fixture.core
                .systemCommands()
                .update(
                        fixture.workspace.id(),
                        fixture.identity("system/registry/update", "system-registration", request),
                        request);
        return fixture.createTurn("with-system-registry");
    }

    private SystemCommandResolver resolver(CodingTestFixture fixture) {
        return new SystemCommandResolver(
                fixture.core.systemCommands(), fixture.database.dataRoot(), fixture.json, fixture.clock);
    }

    private CodingInvocation invocation(
            CodingTestFixture fixture, AgentTurn turn, Object request, PermissionProfile permission) {
        return new CodingInvocation(
                "system-test",
                fixture.request(
                        turn,
                        request instanceof CodingSystemContracts.ShellRun ? "system_shell_run" : "system_command_run",
                        request,
                        "system-test"),
                turn,
                fixture.workspace.id(),
                permission,
                new CancellationSource(),
                new CodingEnvironmentRepository(fixture.database, fixture.json, fixture.clock).frozen(turn.id()));
    }

    private PermissionProfile permission(PermissionProfile original, Set<String> executables) {
        return new PermissionProfile(
                original.id(),
                original.version(),
                original.files(),
                original.network(),
                new ProcessPermission(executables, false, original.processes().maxRunTime()),
                original.tools(),
                original.resources());
    }
}
