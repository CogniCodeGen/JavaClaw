package com.javaclaw.nativehost.ffm;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ResourceLimits;
import com.javaclaw.nativehost.network.SandboxNetworkAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsAclManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void originalAclIsRestoredOnSameObjectBeforeRenameHandlesAreReleased() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root")).toRealPath();
        List<AclEntry> before = acl(root);
        try (WindowsAclEvidence evidence = evidence(control());
                WindowsAppContainerScope scope = WindowsAppContainerScope.open(request(root, List.of(root)))) {
            assertNotEquals(before, acl(root));
            assertThrows(IOException.class, () -> Files.move(root, temporaryDirectory.resolve("renamed")));
            Files.writeString(root.resolve("content.txt"), "retained");
        }
        assertEquals(before, acl(root));
        assertEquals("retained", Files.readString(root.resolve("content.txt")));
        Files.move(root, temporaryDirectory.resolve("renamed"));
    }

    @Test
    void laterGrantFailureRestoresEarlierAclAndReleasesAllAncestorPins() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root")).toRealPath();
        List<AclEntry> before = acl(root);
        var request = request(root, List.of(root, temporaryDirectory.resolve("missing")));
        try (WindowsAclEvidence evidence = evidence(control())) {
            assertThrows(IOException.class, () -> WindowsAppContainerScope.open(request));
        }
        assertEquals(before, acl(root));
        Files.move(root, temporaryDirectory.resolve("renamed"));
        Files.move(temporaryDirectory.resolve("renamed"), root);
    }

    @Test
    void baselineIsDurableBeforeGrantAndOutlivesThreadScopeUntilAsynchronousRestore() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root")).toRealPath();
        Path control = control();
        try (WindowsAclEvidence evidence = evidence(control);
                WindowsAppContainerScope scope = WindowsAppContainerScope.open(request(root, List.of(root)))) {
            Path baseline;
            try (var files = Files.list(control)) {
                baseline = files.filter(path -> path.toString().endsWith(".baseline"))
                        .findFirst()
                        .orElseThrow();
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(baseline))) {
                assertEquals(0x4a434142, input.readInt());
                assertEquals(1, input.readInt());
                assertEquals(root.toString(), readText(input));
                input.readInt();
                assertTrue(input.readLong() != 0);
                input.readBoolean();
                assertTrue(readText(input).contains("D:"));
                assertEquals(-1, input.read());
            }
            evidence.close();
            assertThrows(IOException.class, () -> Files.move(control, temporaryDirectory.resolve("moved-control")));
            CompletableFuture.runAsync(() -> {
                        try {
                            scope.close();
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    })
                    .get(10, TimeUnit.SECONDS);
            try (var files = Files.list(control)) {
                assertEquals(0, files.count());
            }
        }
        Files.move(control, temporaryDirectory.resolve("moved-control"));
    }

    @Test
    void failedRestoreEvidenceRetainsOriginalIdentityAndNeverOffersPathBasedRestore() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "unchanged")
                .toRealPath();
        Path control = control();
        try (WindowsAclEvidence evidence = evidence(control);
                WindowsFileHandle handle =
                        WindowsFileHandle.openPath(file, WindowsFileHandle.ATTRIBUTES, WindowsFileHandle.FILE)) {
            var ticket = WindowsAclEvidence.capture(handle, file, "D:(A;;FR;;;WD)", true);
            ticket.finish(new IOException("simulated restoration failure"));
        }
        try (var files = Files.list(control)) {
            assertEquals(2, files.count());
        }
        assertEquals("unchanged", Files.readString(file));
        Files.move(control, temporaryDirectory.resolve("retained-control"));
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        assertTrue(length >= 0 && length <= 1024 * 1024);
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private WindowsAclEvidence evidence(Path control) throws IOException {
        // 失败 marker 故意保留在测试私有目录，不能污染真实用户跨进程租约。
        return WindowsAclEvidence.bind(
                control, WindowsAclLease.acquireIn(temporaryDirectory.resolve("lease"), control));
    }

    private Path control() throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve("control")).toRealPath();
    }

    private static List<AclEntry> acl(Path path) throws IOException {
        return Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
    }

    private static WindowsSandboxPaths.Prepared request(Path root, List<Path> writeRoots) {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return new WindowsSandboxPaths.Prepared(
                List.of(executable.toString(), "-version"),
                executable,
                root,
                Map.of(),
                List.of(root),
                writeRoots,
                true,
                Duration.ofSeconds(5),
                new ResourceLimits(256L * 1024 * 1024, 1024, 1, 128),
                SandboxNetworkAccess.offline());
    }
}
