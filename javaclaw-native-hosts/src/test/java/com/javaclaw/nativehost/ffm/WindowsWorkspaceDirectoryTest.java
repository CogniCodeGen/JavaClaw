package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsWorkspaceDirectoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void realNativeChannelRenamesWithoutReplacementCopiesAclAndDeletesByHandle() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root")).toRealPath();
        byte[] expected = new byte[100_123];
        java.util.Arrays.fill(expected, (byte) 0x8f);
        try (WindowsWorkspaceDirectory directory = WindowsWorkspaceDirectory.open(root)) {
            write(directory, "source.bin", expected);
            write(directory, "present.bin", new byte[] {9});
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> directory.moveNoReplace("source.bin", directory, "present.bin"));
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(root.resolve("present.bin")));
            directory.copyAccess("source.bin", directory, "present.bin");
            assertEquals(
                    Files.getFileAttributeView(root.resolve("source.bin"), AclFileAttributeView.class)
                            .getAcl(),
                    Files.getFileAttributeView(root.resolve("present.bin"), AclFileAttributeView.class)
                            .getAcl());
            directory.createDirectory("child");
            try (WindowsWorkspaceDirectory child = directory.directory("child")) {
                directory.moveNoReplace("source.bin", child, "moved.bin");
                assertEquals(expected.length, child.attributes("moved.bin").size());
                assertArrayEquals(expected, read(child, "moved.bin", expected.length));
                assertEquals(List.of("moved.bin"), child.names(5));
                assertThrows(IOException.class, () -> directory.deleteDirectory("child"));
                child.deleteFile("moved.bin");
            }
            directory.deleteDirectory("child");
            directory.deleteFile("present.bin");
            assertTrue(directory.names(5).isEmpty());
        }
    }

    @Test
    void directoryCapabilityBlocksRenameAndCannotFollowInsertedJunction() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root")).toRealPath();
        Path outside =
                Files.createDirectory(temporaryDirectory.resolve("outside")).toRealPath();
        Files.writeString(outside.resolve("secret.txt"), "outside");
        junction(root.resolve("escape"), outside);
        try (WindowsWorkspaceDirectory directory = WindowsWorkspaceDirectory.open(root)) {
            assertThrows(IOException.class, () -> Files.move(root, temporaryDirectory.resolve("renamed")));
            assertThrows(IOException.class, () -> directory.directory("escape"));
            assertThrows(IOException.class, () -> directory.attributes("escape"));
            assertThrows(IOException.class, () -> directory.openFile("escape", Set.of(StandardOpenOption.READ)));
            assertThrows(IOException.class, () -> WindowsWorkspaceDirectory.open(root.resolve("escape")));
            assertEquals("outside", Files.readString(outside.resolve("secret.txt")));
        } finally {
            Files.delete(root.resolve("escape"));
        }
        Files.move(root, temporaryDirectory.resolve("renamed"));
        assertFalse(Files.exists(root));
    }

    @Test
    void independentChildRemainsOwnedAfterParentClosesAndTruncationIsDurable() throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root")).toRealPath();
        WindowsWorkspaceDirectory parent = WindowsWorkspaceDirectory.open(root);
        try (WindowsWorkspaceDirectory child = parent.directory("")) {
            parent.close();
            parent.close();
            write(child, "file", new byte[] {1, 2, 3, 4});
            try (SeekableByteChannel file = child.openFile("file", Set.of(StandardOpenOption.WRITE))) {
                file.position(3);
                file.truncate(2);
                assertEquals(2, file.position());
                assertEquals(2, file.size());
                child.force(file);
            }
            assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(root.resolve("file")));
        } finally {
            parent.close();
        }
    }

    private static byte[] read(WindowsWorkspaceDirectory directory, String name, int length) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = directory.openFile(name, Set.of(StandardOpenOption.READ))) {
            while (bytes.hasRemaining()) {
                assertTrue(channel.read(bytes) > 0);
            }
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            channel.position(17);
            assertEquals(17, channel.position());
        }
        return bytes.array();
    }

    private static void write(WindowsWorkspaceDirectory directory, String name, byte[] bytes) throws IOException {
        try (SeekableByteChannel channel =
                directory.openFile(name, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            ByteBuffer input = ByteBuffer.wrap(bytes);
            while (input.hasRemaining()) {
                channel.write(input);
            }
            directory.force(channel);
        }
    }

    private static void junction(Path link, Path target) throws Exception {
        Path command = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe");
        Process process = new ProcessBuilder(
                        command.toString(), "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "junction fixture must finish");
            assertEquals(
                    0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } finally {
            process.destroyForcibly();
        }
    }
}
