package com.javaclaw.nativehost.sandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxCommand;
import com.javaclaw.api.SandboxFrame;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.api.SandboxResult;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.nativehost.coding.JShellScriptSource;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JShellNativeSandboxTest {
    @TempDir
    Path temporary;

    @Test
    void JShell在真实沙箱执行且大于输出预算的源码不抬高输出上限() throws Exception {
        SandboxResult result = execute("System.out.println(\"ok\");\n//" + "x".repeat(4096), 32, 20);
        assertEquals(0, result.exitCode(), new String(result.standardError(), StandardCharsets.UTF_8));
        assertTrue(new String(result.standardOutput(), StandardCharsets.UTF_8).contains("ok"));
        assertTrue(result.standardOutput().length + result.standardError().length <= 32);
        assertFalse(result.timedOut());
    }

    @Test
    void 片段读执行根外文件失败并由父平台终止无限循环() throws Exception {
        Path forbidden = Files.writeString(temporary.resolve("outside.txt"), "outside-secret");
        String escaped = forbidden.toRealPath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        SandboxResult denied =
                execute("java.nio.file.Files.readString(java.nio.file.Path.of(\"" + escaped + "\"));", 4096, 20);
        assertEquals(3, denied.exitCode());
        assertFalse(new String(denied.standardOutput(), StandardCharsets.UTF_8).contains("outside-secret"));
        SandboxResult timeout = execute("while (true) {}", 64, 5);
        assertTrue(timeout.timedOut());
    }

    @Test
    void 输出确认进入片段后取消可终止不协作的Java循环() throws Exception {
        var cancellation = new CancellationSource();
        SandboxResult result = execute(
                "System.out.println(\"running\"); while (true) {}",
                1024,
                30,
                cancellation,
                frame -> cancellation.cancel("observed script output"));
        assertTrue(new String(result.standardOutput(), StandardCharsets.UTF_8).contains("running"));
        assertTrue(result.cancelled());
        assertFalse(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    void 主动退出保留真实退出码而不宣称全部片段完成() throws Exception {
        SandboxResult result =
                execute("System.out.println(\"before\"); System.exit(7); System.out.println(\"after\");", 1024, 20);
        assertEquals(7, result.exitCode());
        assertTrue(new String(result.standardOutput(), StandardCharsets.UTF_8).contains("before"));
        assertFalse(new String(result.standardOutput(), StandardCharsets.UTF_8).contains("after"));
        assertFalse(result.timedOut());
        assertFalse(result.cancelled());
    }

    private SandboxResult execute(String source, int outputBytes, int seconds) throws Exception {
        return execute(source, outputBytes, seconds, new CancellationSource(), frame -> {});
    }

    private SandboxResult execute(
            String source,
            int outputBytes,
            int seconds,
            CancellationSource cancellation,
            Consumer<SandboxFrame> observer)
            throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("workspace")).toRealPath();
        Path sourceFile = JShellScriptSource.materialize(Files.createDirectories(temporary.resolve("data-v6")))
                .path();
        Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = runtime.resolve("bin").resolve(name).toRealPath();
        var permission = new PermissionProfile(
                "script-native",
                1,
                new FilePermission(List.of(workspace), List.of(workspace), true, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(java.getFileName().toString()), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of(), ToolRisk.PROCESS, ApprovalRequirement.EVERY_CALL),
                new ResourceLimits(768L * 1024 * 1024, outputBytes, 8, 256));
        var command = new SandboxCommand(
                "script-native",
                List.of(
                        java.toString(),
                        "-XX:-UsePerfData",
                        "-XX:+DisableAttachMechanism",
                        "-XX:ActiveProcessorCount=2",
                        "-Duser.home=" + workspace,
                        "-Djava.io.tmpdir=" + workspace,
                        "--add-modules",
                        "jdk.jshell",
                        "--source",
                        "21",
                        sourceFile.toString()),
                workspace,
                Map.of("HOME", workspace.toString(), "TMPDIR", workspace.toString()),
                source.getBytes(StandardCharsets.UTF_8),
                SandboxMode.BATCH,
                Duration.ofSeconds(seconds));
        return new PlatformSandboxExecutor()
                .execute(
                        command,
                        permission,
                        cancellation,
                        new SandboxRuntimeAccess(
                                List.of(runtime, sourceFile.getParent()), List.of(), List.of(runtime), 65_536),
                        SandboxNetworkAccess.offline(),
                        observer);
    }
}
