package com.javaclaw.client.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CoreRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClawCliTest {
    @TempDir
    Path directory;

    @Test
    @Timeout(15)
    void 真实stdio进程保持到终态且stdout仅包含最终结果() throws Exception {
        Path marker = directory.resolve("lifecycle.txt");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> arguments = new ArrayList<>(List.of(
                "turn-start",
                CliTestPeer.THREAD.toString(),
                "测试",
                "--non-interactive",
                "--",
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                CliStdioTestServer.class.getName(),
                marker.toString()));
        int exit = JavaClawCli.run(
                arguments.toArray(String[]::new),
                new CliTerminal(false, Reader.nullReader()),
                new PrintStream(output),
                new PrintStream(errors));
        assertEquals(0, exit, errors.toString());
        assertEquals("completed:closed", Files.readString(marker));
        String stdout = output.toString().strip();
        var result = CliTestPeer.JSON.decode(CliTestPeer.JSON.parse(stdout), CoreRpcContracts.TurnStartResult.class);
        assertEquals(TurnStatus.COMPLETED, result.turn().status());
        assertEquals(1, stdout.lines().count());
        assertTrue(errors.toString().contains("任务完成"));
        assertFalse(stdout.contains("任务完成"));
    }

    @Test
    void 非交互标志仅控制终端且非法参数启动进程前退出二() {
        var request =
                CliTurnRequest.parse(List.of("turn-start", CliTestPeer.THREAD.toString(), "测试", "--non-interactive"));
        assertTrue(request.nonInteractive());
        assertTrue(request.payload().execution().role().isEmpty());
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = JavaClawCli.run(
                new String[] {"turn-start", "bad-id", "测试", "--", "不存在的服务端"},
                new CliTerminal(false, Reader.nullReader()),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors));
        assertEquals(2, code);
        assertTrue(errors.toString().contains("参数错误"));
    }
}
