package com.javaclaw.nativehost.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxedWorkerScratchTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(256L * 1024 * 1024, 4096, 4, 128);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 普通Worker保持禁删且只有显式声明能绑定临时根() throws Exception {
        Path scratch = directory("scratch");
        SandboxedWorkerCommand ordinary = command(scratch, List.of(scratch), List.of());

        SandboxedWorkerCommand disposable = ordinary.withPrivateScratch(scratch);

        assertTrue(ordinary.privateScratch().isEmpty());
        assertEquals(scratch, disposable.privateScratch().orElseThrow().root());
        assertEquals(ordinary.argv(), disposable.argv());
        assertEquals(ordinary.environment(), disposable.environment());
        assertEquals(ordinary.readRoots(), disposable.readRoots());
        assertEquals(ordinary.writeRoots(), disposable.writeRoots());
        assertEquals(ordinary.executableRoots(), disposable.executableRoots());
    }

    @Test
    void 临时根必须等于唯一写入根且不得覆盖只读或执行根() throws Exception {
        Path scratch = directory("scratch");
        Path sibling = directory("sibling");
        Path child = Files.createDirectory(scratch.resolve("read-only"));

        assertThrows(
                SecurityException.class,
                () -> command(scratch, List.of(scratch, sibling), List.of()).withPrivateScratch(scratch));
        assertThrows(
                SecurityException.class,
                () -> command(scratch, List.of(scratch), List.of()).withPrivateScratch(sibling));
        assertThrows(
                SecurityException.class,
                () -> command(sibling, List.of(scratch), List.of()).withPrivateScratch(scratch));
        for (Path read : List.of(scratch, scratch.getParent(), child)) {
            assertThrows(
                    SecurityException.class,
                    () -> command(scratch, List.of(scratch), List.of(read)).withPrivateScratch(scratch));
        }
        Path executable = executable();
        SandboxedWorkerCommand overlappingExecutable = new SandboxedWorkerCommand(
                "explicit-scratch",
                List.of(executable.toString()),
                scratch,
                Map.of(),
                List.of(executable.getParent()),
                List.of(scratch),
                List.of(executable, scratch),
                Duration.ofSeconds(10),
                LIMITS,
                Optional.empty());
        assertThrows(SecurityException.class, () -> overlappingExecutable.withPrivateScratch(scratch));
    }

    @Test
    void 文件缺失路径非规范路径及符号链接不能声明为临时根() throws Exception {
        Path scratch = directory("scratch");
        SandboxedWorkerCommand command = command(scratch, List.of(scratch), List.of());
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "fixture");

        assertThrows(SecurityException.class, () -> command.withPrivateScratch(file));
        assertThrows(IOException.class, () -> command.withPrivateScratch(scratch.resolve("missing")));
        assertThrows(SecurityException.class, () -> command.withPrivateScratch(Path.of("relative")));
        assertThrows(SecurityException.class, () -> command.withPrivateScratch(scratch.resolve(".")));
        Path alias = Files.createSymbolicLink(temporaryDirectory.resolve("alias"), scratch);
        assertThrows(SecurityException.class, () -> command.withPrivateScratch(alias));
        Path nested = Files.createDirectory(scratch.resolve("nested"));
        assertThrows(SecurityException.class, () -> command.withPrivateScratch(alias.resolve(nested.getFileName())));
    }

    @Test
    void 其他用户可改写的临时根不得声明或继续使用() throws Exception {
        Assumptions.assumeTrue(
                temporaryDirectory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path scratch = directory("scratch");
        SandboxedWorkerCommand ordinary = command(scratch, List.of(scratch), List.of());
        SandboxedWorkerCommand declared = ordinary.withPrivateScratch(scratch);
        try {
            Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwxrwxrwx"));
            assertThrows(SecurityException.class, () -> ordinary.withPrivateScratch(scratch));
            assertThrows(SecurityException.class, () -> new SandboxedWorkerLauncher().start(declared));
        } finally {
            Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void 启动前拒绝被普通目录替换或链接替换的临时根() throws Exception {
        Path scratch = directory("scratch");
        SandboxedWorkerCommand command =
                command(scratch, List.of(scratch), List.of()).withPrivateScratch(scratch);
        Files.move(scratch, scratch.resolveSibling("original"));
        Files.createDirectory(scratch);
        assertThrows(SecurityException.class, () -> new SandboxedWorkerLauncher().start(command));

        Files.delete(scratch);
        Files.createSymbolicLink(scratch, directory("replacement"));
        assertThrows(SecurityException.class, () -> new SandboxedWorkerLauncher().start(command));
    }

    @Test
    void 只读根改为指向临时目录的别名后不得启动() throws Exception {
        Path scratch = directory("scratch");
        Path read = directory("read");
        SandboxedWorkerCommand command =
                command(scratch, List.of(scratch), List.of(read)).withPrivateScratch(scratch);
        Files.delete(read);
        Files.createSymbolicLink(read, scratch);

        assertThrows(SecurityException.class, () -> new SandboxedWorkerLauncher().start(command));
    }

    @Test
    void 目标可执行文件不能通过漏报执行根藏在临时目录内() throws Exception {
        Path scratch = directory("scratch");
        Path target = Files.writeString(scratch.resolve("target"), "fixture");
        assertTrue(target.toFile().setExecutable(true, true) || Files.isExecutable(target));
        Path allowed = executable().getParent();
        SandboxedWorkerCommand command = new SandboxedWorkerCommand(
                        "explicit-scratch",
                        List.of(target.toString()),
                        scratch,
                        Map.of(),
                        List.of(allowed),
                        List.of(scratch),
                        List.of(allowed),
                        Duration.ofSeconds(10),
                        LIMITS,
                        Optional.empty())
                .withPrivateScratch(scratch);

        assertThrows(SecurityException.class, () -> new SandboxedWorkerLauncher().start(command));
    }

    @Test
    void macOS普通Worker仍不能删除自身写入根内的文件() throws Exception {
        Assumptions.assumeTrue(isMacOs());
        Path scratch = directory("scratch");
        Path child = Files.writeString(scratch.resolve("keep"), "preserved");
        Path remove = Path.of("/bin/rm").toRealPath();
        SandboxedWorkerCommand command = new SandboxedWorkerCommand(
                "browser-worker",
                List.of(remove.toString(), child.toString()),
                scratch,
                Map.of(),
                List.of(remove.getParent()),
                List.of(scratch),
                List.of(remove),
                Duration.ofSeconds(10),
                LIMITS,
                Optional.empty());

        assertTrue(exit(command) != 0);
        assertEquals("preserved", Files.readString(child));
    }

    @Test
    void macOS私有临时子项可清理但根只读文件及符号链接外目标保持不变() throws Exception {
        Assumptions.assumeTrue(isMacOs());
        Path scratch = directory("scratch");
        Path read = directory("read");
        Path sentinel = Files.writeString(read.resolve("sentinel"), "preserved");
        Files.createSymbolicLink(scratch.resolve("escape"), read);
        Path shell = Path.of("/bin/sh").toRealPath();
        String script = "set -eu\n"
                + "test -z \"${HOME+x}\"\n"
                + "printf child > \"$1/child\"\n"
                + "rm \"$1/child\"\n"
                + "if rm \"$1/escape/sentinel\"; then exit 31; fi\n"
                + "if rm \"$2\"; then exit 32; fi\n"
                + "if rm -rf \"$1\"; then exit 33; fi\n"
                + "test -d \"$1\"\n";
        SandboxedWorkerCommand command = new SandboxedWorkerCommand(
                        "private-scratch",
                        List.of(
                                shell.toString(),
                                "-c",
                                script,
                                "scratch-probe",
                                scratch.toString(),
                                sentinel.toString()),
                        scratch,
                        Map.of("PATH", "/bin:/usr/bin"),
                        List.of(Path.of("/bin"), Path.of("/usr/bin"), read),
                        List.of(scratch),
                        List.of(Path.of("/bin"), Path.of("/usr/bin")),
                        Duration.ofSeconds(10),
                        LIMITS,
                        Optional.empty())
                .withPrivateScratch(scratch);

        assertEquals(0, exit(command));
        assertTrue(Files.isDirectory(scratch));
        assertFalse(Files.exists(scratch.resolve("child")));
        assertEquals("preserved", Files.readString(sentinel));
    }

    private SandboxedWorkerCommand command(Path working, List<Path> writes, List<Path> additionalReads)
            throws IOException {
        Path executable = executable();
        var reads = new java.util.ArrayList<Path>(List.of(executable.getParent()));
        reads.addAll(additionalReads);
        return new SandboxedWorkerCommand(
                "explicit-scratch",
                List.of(executable.toString()),
                working,
                Map.of(),
                reads,
                writes,
                List.of(executable),
                Duration.ofSeconds(10),
                LIMITS,
                Optional.empty());
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve(name)).toRealPath();
    }

    private static Path executable() throws IOException {
        String suffix = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("windows")
                ? ".exe"
                : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + suffix).toRealPath();
    }

    private static int exit(SandboxedWorkerCommand command) throws Exception {
        Process process = new SandboxedWorkerLauncher().start(command);
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("mac");
    }
}
