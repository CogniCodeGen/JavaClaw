package com.javaclaw.nativehost.ffm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsAclLeaseTest {
    @TempDir
    Path temporary;

    @Test
    void anotherProcessWaitsUntilRestorationCompletesBeforeTakingSharedRootLease() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence")).toRealPath();
        Path root = temporary.resolve("lease");
        WindowsAclLease lease = WindowsAclLease.acquireIn(root, evidence);
        Process contender = probe(root, evidence, "complete");
        try {
            assertEquals("READY", line(contender));
            assertFalse(contender.waitFor(200, TimeUnit.MILLISECONDS));
            lease.complete(true);
            assertTrue(contender.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, contender.exitValue());
        } finally {
            lease.complete(true);
            contender.destroyForcibly();
        }
        assertFalse(Files.exists(root.resolve("pending-v1")));
    }

    @Test
    void deadHelperReleasesKernelLockButItsPendingMarkerPreventsSilentAclBaselineAdoption() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence")).toRealPath();
        Path root = temporary.resolve("lease");
        Process helper = probe(root, evidence, "crash");
        try {
            assertEquals("READY", line(helper));
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
            assertEquals(81, helper.exitValue());
        } finally {
            helper.destroyForcibly();
        }
        var failure = assertThrows(
                WindowsSandbox.AclRestorationException.class, () -> WindowsAclLease.acquireIn(root, evidence));
        assertEquals(evidence, failure.evidenceDirectory().orElseThrow());
        assertTrue(Files.exists(root.resolve("pending-v1")));
    }

    private static Process probe(Path root, Path evidence, String action) throws Exception {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java.exe")
                                .toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        Probe.class.getName(),
                        root.toString(),
                        evidence.toString(),
                        action)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static String line(Process process) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
                    try {
                        return new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                })
                .get(10, TimeUnit.SECONDS);
    }

    /** 独立 JVM 在测试私有目录持有真实内核锁；crash 分支仅退出此临时子进程。 */
    public static final class Probe {
        private Probe() {}

        /** 按固定测试参数正常释放或模拟 helper 在 ACL 生效区间内退出。 */
        public static void main(String[] arguments) throws Exception {
            System.out.println("READY");
            System.out.flush();
            WindowsAclLease lease = WindowsAclLease.acquireIn(Path.of(arguments[0]), Path.of(arguments[1]));
            if (arguments[2].equals("crash")) {
                Runtime.getRuntime().halt(81);
            }
            lease.complete(true);
        }
    }
}
