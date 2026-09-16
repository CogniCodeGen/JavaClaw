package com.javaclaw.nativehost.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 监护入口使用本地无网络夹具；这些断言不替代浏览器原生隔离或任意后代回收证明。 */
class SandboxWorkerMonitorEntryTest {
    @Test
    void 参数或控制目录无效时入口拒绝且不启动目标() {
        assertEquals(72, SandboxWorkerMonitorMain.run(new String[0]));
        assertEquals(72, SandboxWorkerMonitorMain.run(new String[] {
            "/nonexistent-javaclaw-control", "1000", "1024", "1", "true", "false", "--", "unreachable"
        }));
    }

    @Test
    void 后端启动失败只写固定类别不泄漏目标路径() throws Exception {
        try (var control = SandboxWorkerControl.create()) {
            int result = SandboxWorkerMonitorMain.run(arguments(control, "/nonexistent-javaclaw-worker", "secret-arg"));
            assertEquals(72, result);
            assertEquals("FAILED:NATIVE_WORKER_STARTUP", control.status());
            assertFalse(control.status().contains("secret-arg"));
        }
    }

    @Test
    void 宿主显式关闭回收已观察进程后才签发CLOSED() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sleep")));
        try (var control = SandboxWorkerControl.create()) {
            var completion = CompletableFuture.supplyAsync(
                    () -> SandboxWorkerMonitorMain.run(arguments(control, "/bin/sleep", "60")));
            long pid;
            try {
                pid = awaitReady(control);
                assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            } finally {
                control.stop();
            }
            assertEquals(0, completion.get(5, TimeUnit.SECONDS));
            assertEquals("CLOSED", control.status());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void 后端自行退出不能伪造正常关闭成功() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/true")));
        try (var control = SandboxWorkerControl.create()) {
            assertEquals(72, SandboxWorkerMonitorMain.run(arguments(control, "/usr/bin/true", "")));
            assertEquals("FAILED:NATIVE_WORKER_STARTUP", control.status());
        }
    }

    private static long awaitReady(SandboxWorkerControl control) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            String status = control.status();
            if (status.startsWith("READY:")) {
                return Long.parseLong(status.substring(6));
            }
            Thread.sleep(10);
        }
        throw new AssertionError("监护未完成启动握手");
    }

    private static String[] arguments(SandboxWorkerControl control, String executable, String argument) {
        return new String[] {
            control.directory().toString(), "15000", "268435456", "4", "true", "false", "--", executable, argument
        };
    }
}
