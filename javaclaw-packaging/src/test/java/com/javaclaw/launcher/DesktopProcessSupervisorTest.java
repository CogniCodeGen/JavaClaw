package com.javaclaw.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopProcessSupervisorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void 重复打开只保留一个Desktop且退出只回调状态() throws Exception {
        RuntimeLayout layout = layout();
        FakeChild child = new FakeChild();
        List<List<String>> commands = new ArrayList<>();
        DesktopProcessSupervisor supervisor = new DesktopProcessSupervisor(
                layout, "-Djavaclaw.server.socket=/tmp/test.sock", new String[] {"--workspace", "demo"}, command -> {
                    commands.add(command);
                    return child;
                });
        CountDownLatch exited = new CountDownLatch(1);

        supervisor.open(exited::countDown);
        supervisor.open(() -> {});

        assertTrue(supervisor.running());
        assertEquals(1, commands.size());
        assertTrue(commands.getFirst().contains("com.javaclaw.desktop.shell.JavaClawDesktop"));
        child.exit();
        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertFalse(supervisor.running());
    }

    @Test
    void 真实子进程退出后释放窗口占用并回调() throws Exception {
        Assumptions.assumeFalse(RuntimeLayout.isWindows());
        RuntimeLayout layout = layout();
        Files.writeString(layout.javaExecutable(), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(
                layout.javaExecutable(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        DesktopProcessSupervisor supervisor =
                new DesktopProcessSupervisor(layout, "-Djavaclaw.server.socket=/tmp/test.sock", new String[0]);
        CountDownLatch exited = new CountDownLatch(1);

        supervisor.open(exited::countDown);

        assertTrue(exited.await(1, TimeUnit.SECONDS));
        assertFalse(supervisor.running());
    }

    private RuntimeLayout layout() throws Exception {
        Path root = temporaryDirectory.resolve("distribution");
        Path runtime = root.resolve("runtime/bin/java");
        Path library = root.resolve("lib");
        Files.createDirectories(runtime.getParent());
        Files.createDirectories(library);
        Files.writeString(runtime, "java");
        return new RuntimeLayout(root, runtime, library, Optional.empty());
    }

    private static final class FakeChild implements DesktopProcessSupervisor.ChildProcess {
        private final CountDownLatch exit = new CountDownLatch(1);
        private volatile boolean alive = true;

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void awaitExit() throws InterruptedException {
            exit.await();
        }

        private void exit() {
            alive = false;
            exit.countDown();
        }
    }
}
