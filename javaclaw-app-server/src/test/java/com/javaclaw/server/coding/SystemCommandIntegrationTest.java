package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.builtin.contracts.CodingSystemContracts.CommandRun;
import com.javaclaw.builtin.contracts.CodingSystemContracts.ShellRun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实系统二进制与原生 Sandbox，不调用模型或下载工具链。 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class SystemCommandIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void 原生Shell管道重定向产生真实文件且保留命令事实和幂等回执() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var input = new ShellRun(
                    "printf 'alpha beta\\n' | cat > 'shell result.txt'; cat 'shell result.txt'", ".", 10, 4096);
            var response = shellOnly(fixture, input);
            assertTrue(response.success(), response.response().payload().json());
            var result = fixture.json.decode(response.response().payload(), CodingResults.CommandResult.class);
            assertEquals("alpha beta\n", result.output().stdout());
            assertEquals("alpha beta\n", Files.readString(fixture.root.resolve("shell result.txt")));
            assertEquals(input.command(), result.command().argv().getLast());
            assertFalse(response.facts().isEmpty());
            assertEquals(response, shellOnly(fixture, input));
        }
    }

    @Test
    void 原生直接argv不解释注入文本且根外文件不可读取() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            String literal = "$(touch injected); a b *";
            var response = fixture.invoke(
                    "system_command_run", new CommandRun("system.echo", List.of(literal), ".", 10, 4096), "argv");
            assertTrue(response.success(), response.response().payload().json());
            var result = fixture.json.decode(response.response().payload(), CodingResults.CommandResult.class);
            assertEquals(literal + "\n", result.output().stdout());
            assertFalse(Files.exists(fixture.root.resolve("injected")));
            Path secret = Files.writeString(temporary.resolve("outside-secret"), "must-not-leak");
            var denied = fixture.invoke(
                    "system_command_run",
                    new CommandRun("system.cat", List.of(secret.toString()), ".", 10, 4096),
                    "outside");
            var deniedResult = fixture.json.decode(denied.response().payload(), CodingResults.CommandResult.class);
            assertFalse(denied.success());
            assertFalse(deniedResult.output().stdout().contains("must-not-leak"));
        }
    }

    @Test
    void 原生Shell无限循环受超时治理并能在同Turn继续执行() throws Exception {
        try (var fixture = new CodingTestFixture(temporary)) {
            var response =
                    fixture.invoke("system_shell_run", new ShellRun("while :; do :; done", ".", 1, 4096), "timeout");
            var result = fixture.json.decode(response.response().payload(), CodingResults.CommandResult.class);
            assertEquals(CodingResults.ProcessState.TIMED_OUT, result.command().state());
            assertFalse(response.success());
            assertTrue(fixture.invoke(
                            "system_command_run",
                            new CommandRun("system.echo", List.of("after-timeout"), ".", 10, 4096),
                            "after")
                    .success());
        }
    }

    private com.javaclaw.server.extension.contract.GovernedExtensionResponse shellOnly(
            CodingTestFixture fixture, ShellRun input) throws Exception {
        var original = fixture.permission;
        // 本次调用只批准 Shell 逻辑入口；管道中的 cat 由同一个原生 Sandbox 进程树边界治理。
        var permission = new com.javaclaw.api.PermissionProfile(
                original.id(),
                original.version(),
                original.files(),
                original.network(),
                new com.javaclaw.api.ProcessPermission(
                        java.util.Set.of("system.sh"),
                        false,
                        original.processes().maxRunTime()),
                original.tools(),
                original.resources());
        try (var binding = fixture.platform.bindTool(
                fixture.request(fixture.turn, "system_shell_run", input, "shell-pipe"),
                permission,
                new com.javaclaw.api.CancellationSource())) {
            return binding.result(binding.invoke());
        }
    }
}
