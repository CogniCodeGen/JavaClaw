package com.javaclaw.nativehost.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.SandboxMode;
import com.javaclaw.nativehost.ffm.MacBootstrapNamespace;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxedWorkerLeaseTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(512L * 1024 * 1024, 4096, 8, 64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 闲置到期同时回收原生Sandbox根和后代() throws Exception {
        try (SandboxedWorkerLease lease = resident("sleep 60 & echo $!; wait", Duration.ofSeconds(1))) {
            ProcessHandle descendant = descendant(lease);
            assertTrue(lease.process().waitFor(6, TimeUnit.SECONDS));
            assertFalse(descendant.isAlive());
            assertThrows(IllegalStateException.class, lease::touch);
        }
    }

    @Test
    void 只有宿主续期会延后闲置终止且关闭保持幂等() throws Exception {
        SandboxedWorkerLease lease = resident("sleep 60 & echo $!; wait", Duration.ofSeconds(1));
        ProcessHandle descendant = descendant(lease);
        try {
            Thread.sleep(650);
            lease.touch();
            Thread.sleep(650);
            assertTrue(lease.process().isAlive());
            lease.close();
            lease.close();
            assertFalse(lease.process().isAlive());
            assertFalse(descendant.isAlive());
        } finally {
            lease.close();
        }
    }

    @Test
    void Process强制关闭也走租约整树回收而不直接杀监护() throws Exception {
        try (SandboxedWorkerLease lease = resident("sleep 60 & echo $!; wait", Duration.ofMinutes(15))) {
            ProcessHandle descendant = descendant(lease);
            Process exposed = lease.process();
            assertEquals(exposed, exposed.destroyForcibly());
            assertTrue(exposed.waitFor(1, TimeUnit.SECONDS));
            assertFalse(descendant.isAlive());
            assertEquals(exposed, exposed.onExit().get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void 根在首次观察窗口内退出不能签发整树回收成功回执() throws Exception {
        Path childPid = temporaryDirectory.resolve("short-lived-root-child.pid");
        SandboxedWorkerLease lease = null;
        try {
            try {
                lease = resident("sleep 30 & echo $! > short-lived-root-child.pid; exit 0", Duration.ofSeconds(10));
            } catch (IOException startupRefused) {
                assertTrue(startupRefused.getMessage().contains("NATIVE_WORKER_STARTUP")
                        || startupRefused.getMessage().contains("monitor exited"));
            }
            if (lease != null) {
                SandboxedWorkerLease running = lease;
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> running.process().onExit().get(6, TimeUnit.SECONDS));
            }
            assertTrue(Files.isRegularFile(childPid));
        } finally {
            if (lease != null) {
                try {
                    lease.close();
                } catch (java.util.concurrent.CompletionException expectedFailure) {
                    // 本场景必须保留失败回执，测试不把二次关闭异常改成成功。
                }
            }
            if (Files.isRegularFile(childPid)) {
                long pid = Long.parseLong(Files.readString(childPid).strip());
                Optional<ProcessHandle> child = ProcessHandle.of(pid);
                if (child.isPresent() && child.orElseThrow().isAlive()) {
                    // 测试夹具可能故意制造未观察孤儿；以已记录的身份有界回收，不留后台进程。
                    child.orElseThrow().destroyForcibly();
                    child.orElseThrow().onExit().get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void 监护私有控制目录不能被目标读取或替换() throws Exception {
        try (SandboxWorkerControl control = SandboxWorkerControl.create()) {
            assertTrue(control.state().startsWith("RUN:"));
            control.touch();
            assertEquals("RUN:2", control.state());
            control.stop();
            assertEquals("CLOSE", control.state());
            control.status("READY:123");
            assertEquals("READY:123", control.status());
            Files.writeString(control.directory().resolve("control"), "x".repeat(513));
            assertThrows(IOException.class, control::state);
        }
    }

    @Test
    void 交互启动拒绝无独占Scratch或超过十五分钟的闲置期限() throws Exception {
        SandboxedWorkerLauncher launcher = new SandboxedWorkerLauncher();
        SandboxedWorkerCommand command = command();
        assertThrows(NullPointerException.class, () -> launcher.startInteractive(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> launcher.startInteractive(command, null));
        assertThrows(IllegalArgumentException.class, () -> launcher.startInteractive(command, Duration.ofMillis(999)));
        assertThrows(IllegalArgumentException.class, () -> launcher.startInteractive(command, Duration.ofMinutes(16)));
        assertThrows(SecurityException.class, () -> launcher.startInteractive(command, Duration.ofMinutes(15)));
    }

    @Test
    void 未证明任意后代回收的平台不能通过交互发布门禁() {
        IOException failure =
                assertThrows(IOException.class, SandboxedWorkerLauncher::verifyInteractiveProcessContainment);
        assertTrue(failure.getMessage().startsWith("INTERACTIVE_TREE_CONTAINMENT_UNVERIFIED:"));
    }

    @Test
    void Mac无法创建私有Bootstrap时拒绝启动目标并返回原生原因() throws Exception {
        Assumptions.assumeTrue(isMac());
        IOException nativeFailure = null;
        try (MacBootstrapNamespace ignored = MacBootstrapNamespace.open()) {
            // 支持 namespace 的 Runner 由可见 Browser smoke 检查成功路径；此处专门验证原生不可用的拒绝路径。
        } catch (IOException unavailable) {
            nativeFailure = unavailable;
        }
        if (nativeFailure != null) {
            SandboxedWorkerCommand command = command().withPrivateScratch(temporaryDirectory.toRealPath());
            IOException failure = assertThrows(
                    IOException.class,
                    () -> new SandboxedWorkerLauncher().startInteractive(command, Duration.ofMinutes(15)));
            assertEquals(nativeFailure.getMessage(), failure.getMessage());
            assertTrue(failure.getMessage().startsWith("MAC_BOOTSTRAP_UNAVAILABLE:"));
        }
    }

    private SandboxedWorkerCommand command() throws Exception {
        Path executable = Path.of("/bin/cat").toRealPath();
        return new SandboxedWorkerCommand(
                "lease-contract",
                List.of(executable.toString()),
                temporaryDirectory.toRealPath(),
                Map.of(),
                List.of(executable.getParent()),
                List.of(temporaryDirectory.toRealPath()),
                List.of(executable),
                Duration.ofMinutes(10),
                LIMITS,
                Optional.empty());
    }

    private SandboxedWorkerLease resident(String script, Duration idle) throws Exception {
        Assumptions.assumeTrue(isMac());
        Path shell = Path.of("/bin/sh").toRealPath();
        Path work = temporaryDirectory.toRealPath();
        ValidatedSandboxCommand command = new ValidatedSandboxCommand(
                "resident-native-fixture",
                List.of(shell.toString(), "-c", script),
                shell,
                work,
                Map.of(),
                new byte[0],
                SandboxMode.BATCH,
                Duration.ofMinutes(1),
                LIMITS,
                List.of(Path.of("/bin"), Path.of("/usr/bin")),
                List.of(work),
                List.of(Path.of("/bin"), Path.of("/usr/bin")),
                true,
                Optional.empty(),
                SandboxNetworkAccess.offline());
        // 原生隔离 shell 仅是监护夹具，不申请 GUI 能力，也不替代可见 Chromium 门禁。
        return SandboxResidentWorkerLauncher.start(
                command, idle, new MacSandboxCommandBuilder().build(command), false, true);
    }

    private static ProcessHandle descendant(SandboxedWorkerLease lease) throws Exception {
        BufferedReader output =
                new BufferedReader(new InputStreamReader(lease.process().getInputStream(), StandardCharsets.US_ASCII));
        String value = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return output.readLine();
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                })
                .get(5, TimeUnit.SECONDS);
        assertTrue(value != null && value.matches("[1-9][0-9]*"));
        return ProcessHandle.of(Long.parseLong(value)).orElseThrow();
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
