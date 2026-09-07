package com.javaclaw.nativehost.coding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Change;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Edit;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.PreparedPatch;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Status;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实际二进制帧进入固定 Worker 分派并操作原生目录能力；不以进程替身绕过 codec 或文件行为。 */
class WorkspaceFileWorkerContractTest {
    @TempDir
    Path temporary;

    @Test
    void binaryReadAndBytePagesRetainFullDigestAndDoNotAllocateTheOffset() throws Exception {
        byte[] original = "中文文件\nneedle".getBytes(StandardCharsets.UTF_8);
        Files.write(temporary.resolve("a.txt"), original);
        try (var result = request("read", output -> {
            output.writeUTF("a.txt");
            output.writeInt(1000);
        })) {
            var snapshot = WorkspaceFileProtocol.readSnapshot(result);
            assertEquals("a.txt", snapshot.path());
            assertArrayEquals(original, snapshot.content());
            assertEquals(-1, result.read());
        }
        for (long offset : List.of(3L, 1_000_000_000L)) {
            try (var result = request("read-page", output -> {
                output.writeUTF("a.txt");
                output.writeLong(offset);
                output.writeInt(6);
                output.writeInt(1000);
            })) {
                assertEquals("a.txt", result.readUTF());
                assertEquals(original.length, result.readLong());
                assertEquals(WorkspaceFileAccess.hash(original), result.readUTF());
                assertEquals(offset, result.readLong());
                byte[] page = WorkspaceFileProtocol.readBytes(result);
                assertArrayEquals(offset == 3 ? Arrays.copyOfRange(original, 3, 9) : new byte[0], page);
                assertEquals(-1, result.read());
            }
        }
    }

    @Test
    void binaryDirectoryPagesUseStableNamesAndReportTheFinalPage() throws Exception {
        for (String name : List.of("c", "a", "b")) {
            Files.writeString(temporary.resolve(name), name);
        }
        try (var result = request("list", output -> {
            output.writeUTF("");
            output.writeInt(10);
        })) {
            assertEquals(
                    List.of("a", "b", "c"),
                    WorkspaceFileProtocol.readEntries(result).stream()
                            .map(WorkspaceFileAccess.Entry::path)
                            .toList());
            assertEquals(-1, result.read());
        }
        for (String after : List.of("a", "b")) {
            try (var result = request("list-page", output -> {
                output.writeUTF("");
                output.writeUTF(after);
                output.writeInt(1);
            })) {
                String expected = after.equals("a") ? "b" : "c";
                assertEquals(
                        expected,
                        WorkspaceFileProtocol.readEntries(result).getFirst().path());
                assertEquals(after.equals("a"), result.readBoolean());
                if (after.equals("a")) {
                    assertEquals(expected, result.readUTF());
                }
                assertEquals(-1, result.read());
            }
        }
    }

    @Test
    void binarySearchAndInventoryReportActualBudgetsAndExcludedDirectories() throws Exception {
        Files.writeString(temporary.resolve("a.txt"), "needle");
        Files.createDirectory(temporary.resolve("skip"));
        Files.writeString(temporary.resolve("skip/other.bin"), "not scanned");
        try (var result = request("search", output -> {
            output.writeUTF("");
            output.writeUTF("NEEDLE");
            output.writeUTF("*.txt");
            output.writeBoolean(false);
            output.writeInt(10);
            output.writeInt(1000);
        })) {
            var matches = WorkspaceFileProtocol.readMatches(result);
            assertEquals("a.txt", matches.getFirst().path());
            assertEquals("needle", matches.getFirst().text());
            assertFalse(result.readBoolean());
            assertEquals(6, result.readLong());
            assertEquals(-1, result.read());
        }
        try (var result = request("inventory", output -> {
            output.writeUTF("");
            output.writeInt(10);
            output.writeInt(1000);
            WorkspaceFileProtocol.writeStrings(output, List.of("skip"));
        })) {
            var inventory = WorkspaceFileProtocol.readInventory(result);
            assertEquals(1, inventory.entries().size());
            assertEquals(6, inventory.scannedBytes());
            assertEquals(List.of("skip"), inventory.excludedDirectories());
            assertFalse(inventory.truncated());
            assertEquals(-1, result.read());
        }
    }

    @Test
    void binaryPrepareApplyAndReversePreserveRecoveryPathsAndActualContents() throws Exception {
        Files.writeString(temporary.resolve("value"), "before");
        var edit = new Edit(
                "value",
                Optional.of(WorkspaceFileAccess.hash("before".getBytes(StandardCharsets.UTF_8))),
                Optional.of("after".getBytes(StandardCharsets.UTF_8)));
        PreparedPatch patch;
        try (var result = request("prepare", output -> {
            WorkspaceFileProtocol.writeEdits(output, List.of(edit));
            output.writeInt(1000);
        })) {
            patch = WorkspaceFileProtocol.readPatch(result);
            assertEquals(
                    "before", new String(patch.changes().getFirst().before().content(), StandardCharsets.UTF_8));
            assertEquals(-1, result.read());
        }
        apply(patch, "after");
        var reverse = new PreparedPatch(patch.changes().stream()
                .map(change -> new Change(change.after(), change.before()))
                .toList());
        apply(reverse, "before");
    }

    @Test
    void malformedFramesReturnBoundedFailureBeforeFileMutation() throws Exception {
        assertFailure(frame("prepare", output -> output.writeInt(201)), "entry count");
        assertFailure(frame("apply", output -> output.writeInt(-1)), "entry count");
        assertFailure(frame("unknown", output -> {}), "unknown Workspace");
        assertFailure(
                frame("read", output -> {
                    output.writeUTF("../outside");
                    output.writeInt(100);
                }),
                "forbidden segment");
        assertFailure(
                frame("inventory", output -> {
                    output.writeUTF("");
                    output.writeInt(10);
                    output.writeInt(100);
                    WorkspaceFileProtocol.writeStrings(output, List.of("x".repeat(4097)));
                }),
                "protocol limit");
        try (var response = new DataInputStream(new ByteArrayInputStream(
                WorkspaceFileWorker.process(new String[0], new ByteArrayInputStream(new byte[0]))))) {
            assertFalse(response.readBoolean());
            assertTrue(response.readUTF().contains("frozen absolute root"));
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(new byte[] {0, 0, 0, 2, 1}))) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IOException.class, () -> WorkspaceFileProtocol.readBytes(input));
        }
        try (var entries = Files.list(temporary)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void accumulatedPatchSnapshotsAreRejectedEvenWhenEveryIndividualFrameFits() throws Exception {
        int size = WorkspaceFileAccess.MAX_BYTES / 2 + 1;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream zeros = zeroes(size)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = zeros.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        byte[] header = frame("apply", output -> output.writeInt(1));
        byte[] snapshot = snapshotHeader(size, sha);
        try (var oversized = new SequenceInputStream(Collections.enumeration(List.of(
                        new ByteArrayInputStream(header),
                        new ByteArrayInputStream(snapshot),
                        zeroes(size),
                        new ByteArrayInputStream(snapshot),
                        zeroes(size))));
                var result = response(oversized)) {
            assertFalse(result.readBoolean());
            assertTrue(result.readUTF().contains("cumulative byte limit"));
        }
    }

    @Test
    void 元数据仅返回文件目录和缺失且链接不伪装为不存在() throws Exception {
        Files.writeString(temporary.resolve("value"), "content");
        Files.createDirectory(temporary.resolve("folder"));
        for (String root : List.of("", ".")) {
            try (var response = request("stat", output -> output.writeUTF(root))) {
                assertEquals(
                        List.of(new WorkspaceFileAccess.Entry(".", true, 0)),
                        WorkspaceFileProtocol.readEntries(response));
            }
        }
        try (var response = request("stat", output -> output.writeUTF("value"))) {
            assertEquals(
                    List.of(new WorkspaceFileAccess.Entry("value", false, 7)),
                    WorkspaceFileProtocol.readEntries(response));
        }
        try (var response = request("stat", output -> output.writeUTF("folder"))) {
            assertEquals(
                    List.of(new WorkspaceFileAccess.Entry("folder", true, 0)),
                    WorkspaceFileProtocol.readEntries(response));
        }
        for (String missing : List.of("absent", "absent/child")) {
            try (var response = request("stat", output -> output.writeUTF(missing))) {
                assertTrue(WorkspaceFileProtocol.readEntries(response).isEmpty());
            }
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(temporary.resolve("link"), temporary.resolve("value"));
            try (var response = response(new ByteArrayInputStream(frame("stat", output -> output.writeUTF("link"))))) {
                assertFalse(response.readBoolean());
            }
        }
        assertFailure(frame("stat", output -> output.writeUTF("../outside")), "forbidden segment");
    }

    private void apply(PreparedPatch patch, String expected) throws Exception {
        try (var result = request("apply", output -> WorkspaceFileProtocol.writePatch(output, patch))) {
            assertEquals(Status.APPLIED.name(), result.readUTF());
            assertTrue(result.readUTF().contains("inode"));
            var paths = WorkspaceFileProtocol.readStrings(result, 200);
            assertEquals(1, paths.size());
            assertTrue(Files.isDirectory(temporary.resolve(paths.getFirst())));
            assertEquals(-1, result.read());
        }
        assertEquals(expected, Files.readString(temporary.resolve("value")));
    }

    private DataInputStream request(String operation, Encoder encoder) throws Exception {
        DataInputStream response = response(new ByteArrayInputStream(frame(operation, encoder)));
        assertTrue(response.readBoolean(), () -> {
            try {
                return response.readUTF();
            } catch (IOException failure) {
                return failure.toString();
            }
        });
        return response;
    }

    private DataInputStream response(InputStream request) throws Exception {
        return new DataInputStream(new ByteArrayInputStream(
                WorkspaceFileWorker.process(new String[] {temporary.toRealPath().toString()}, request)));
    }

    private void assertFailure(byte[] frame, String expected) throws Exception {
        try (var result = response(new ByteArrayInputStream(frame))) {
            assertFalse(result.readBoolean());
            String detail = result.readUTF();
            assertTrue(detail.contains(expected), detail);
            assertEquals(-1, result.read());
        }
    }

    private static byte[] frame(String operation, Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF(operation);
            encoder.write(output);
        }
        return bytes.toByteArray();
    }

    private static byte[] snapshotHeader(int size, String sha) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF("value");
            output.writeBoolean(true);
            output.writeUTF(sha);
            output.writeInt(size);
        }
        return bytes.toByteArray();
    }

    private static InputStream zeroes(int length) {
        return new InputStream() {
            private int remaining = length;

            @Override
            public int read() {
                if (remaining == 0) {
                    return -1;
                }
                remaining--;
                return 0;
            }

            @Override
            public int read(byte[] bytes, int offset, int maximum) {
                if (remaining == 0) {
                    return -1;
                }
                int count = Math.min(maximum, remaining);
                Arrays.fill(bytes, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        };
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }
}
