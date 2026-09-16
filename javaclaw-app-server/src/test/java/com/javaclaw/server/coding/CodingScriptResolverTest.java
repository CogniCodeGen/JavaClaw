package com.javaclaw.server.coding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.builtin.contracts.CodingContracts.CommandRun;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.builtin.contracts.CodingScriptContracts.ScriptRun;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingScriptResolverTest {
    @TempDir
    Path temporary;

    @Test
    void JShell权限映射到冻结JDK并将源码仅放入独立有界stdin() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            Path installed = fixture.register(fixture.artifact(ToolchainKind.JDK, "25"));
            PermissionProfile permission = scriptPermission(fixture.server.permission);
            var input = new ScriptRun("System.out.println(\"中文😀\");", ".", 30, 1);
            var invocation = fixture.invocation(new CommandRun(List.of("java", "--version"), ".", 30, 1), permission);
            try (var resolved = fixture.resolver(fixture.server.clock).resolveScript(invocation, input)) {
                assertTrue(Path.of(resolved.command().argv().getFirst()).startsWith(installed));
                assertTrue(resolved.command()
                        .argv()
                        .containsAll(List.of("--add-modules", "jdk.jshell", "--source", "21")));
                assertFalse(resolved.command().argv().contains(input.source()));
                assertArrayEquals(
                        input.source().getBytes(StandardCharsets.UTF_8),
                        resolved.command().standardInput());
                assertEquals(1, resolved.permission().resources().outputBytes());
                assertEquals(65_536, resolved.access().standardInputBytes());
                assertEquals(
                        Set.of(Path.of(resolved.command().argv().getFirst())
                                .getFileName()
                                .toString()),
                        resolved.permission().processes().executables());
                Path helper = Path.of(resolved.command().argv().getLast());
                assertTrue(resolved.access().readRoots().contains(helper.getParent()));
                assertFalse(resolved.access().readRoots().contains(fixture.server.database.dataRoot()));
                assertTrue(resolved.evidence().orElseThrow().json().contains("sourceSha256"));
            }
            assertEquals(List.of(List.of(ToolchainKind.JDK)), fixture.checked);
            assertEquals(1, fixture.toolchains.acquired);
            assertEquals(1, fixture.toolchains.released);
        }
    }

    @Test
    void 未授权JShell在工具链兼容检查和获租之前拒绝() throws Exception {
        try (var fixture = new ManagedResolverFixture(temporary)) {
            var invocation = fixture.invocation(
                    new CommandRun(List.of("java", "--version"), ".", 30, 64),
                    processPermission(fixture.server.permission, Set.of("java")));
            assertThrows(
                    SecurityException.class,
                    () -> fixture.resolver(fixture.server.clock)
                            .resolveScript(invocation, new ScriptRun("1 + 1", ".", 30, 64)));
            assertTrue(fixture.checked.isEmpty());
            assertEquals(0, fixture.toolchains.acquired);
        }
    }

    private static PermissionProfile scriptPermission(PermissionProfile original) {
        return processPermission(original, Set.of("jshell"));
    }

    private static PermissionProfile processPermission(PermissionProfile original, Set<String> executables) {
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
