package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Edit;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileTreeTest {
    @TempDir
    Path temporary;

    private WorkspaceFileTree ownedTree;

    @AfterEach
    void closeOwnedDirectory() throws Exception {
        if (ownedTree != null) {
            ownedTree.close();
        }
    }

    @Test
    void rejectsPathEscapesProtectedMetadataAndWindowsAliases() throws Exception {
        WorkspaceFileTree tree = tree();
        for (String path : List.of(
                "../secret",
                "/secret",
                "a/../b",
                "a\\b",
                ".git/config",
                "A/.GIT/config",
                "a:x",
                "a/",
                "NUL.txt",
                "x. ")) {
            assertThrows(IllegalArgumentException.class, () -> WorkspaceFileProtocol.requireRelative(path, false));
        }
        assertEquals(temporary.toRealPath(), tree.access().path());
    }

    @Test
    void snapshotCopiesBytesAndRejectsOversizedFile() throws Exception {
        Files.writeString(temporary.resolve("a.txt"), "hello");
        var snapshot = tree().snapshot("a.txt", 5);
        snapshot.content()[0] = 0;
        assertEquals("hello", new String(snapshot.content(), StandardCharsets.UTF_8));
        assertEquals(WorkspaceFileAccess.hash("hello".getBytes(StandardCharsets.UTF_8)), snapshot.sha256());
        assertThrows(IOException.class, () -> tree().snapshot("a.txt", 4));
        assertFalse(tree().snapshot("missing", 1).exists());
        assertFalse(tree().snapshot(".mvn/maven.config", 1).exists());
        assertFalse(tree().snapshot("gradle/libs.versions.toml", 1).exists());
    }

    @Test
    void refusesSymbolicLinkReadsAndDoesNotListLinks() throws Exception {
        Path source = Files.writeString(temporary.resolve("source"), "private");
        try {
            Files.createSymbolicLink(temporary.resolve("alias"), source);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "file system cannot create test symbolic links");
        }
        assertThrows(IOException.class, () -> tree().snapshot("alias", 100));
        assertEquals(
                List.of("source"),
                tree().list("", 10).stream()
                        .map(WorkspaceFileAccess.Entry::path)
                        .toList());
    }

    @Test
    void preflightConflictLeavesEveryFileUntouched() throws Exception {
        Files.writeString(temporary.resolve("a"), "old-a");
        Files.writeString(temporary.resolve("b"), "old-b");
        WorkspacePatchWriter writer = new WorkspacePatchWriter(tree());
        var prepared = writer.prepare(List.of(update("a", "old-a", "new-a"), update("b", "old-b", "new-b")), 1000);
        Files.writeString(temporary.resolve("b"), "user edit");
        assertEquals(Status.ROLLED_BACK, writer.apply(prepared).status());
        assertEquals("old-a", Files.readString(temporary.resolve("a")));
        assertEquals("user edit", Files.readString(temporary.resolve("b")));
    }

    @Test
    void createsUpdatesDeletesAndConditionallyRestores() throws Exception {
        Files.writeString(temporary.resolve("old"), "before");
        WorkspacePatchWriter writer = new WorkspacePatchWriter(tree());
        var prepared = writer.prepare(
                List.of(
                        update("old", "before", "after"),
                        new Edit("new/f.txt", Optional.empty(), Optional.of("new".getBytes(StandardCharsets.UTF_8)))),
                1000);
        assertEquals(Status.APPLIED, writer.apply(prepared).status());
        assertEquals("after", Files.readString(temporary.resolve("old")));
        assertEquals("new", Files.readString(temporary.resolve("new/f.txt")));
        var reverse = new WorkspaceFileAccess.PreparedPatch(prepared.changes().stream()
                .map(change -> new WorkspaceFileAccess.Change(change.after(), change.before()))
                .toList());
        assertEquals(Status.APPLIED, writer.apply(reverse).status());
        assertEquals("before", Files.readString(temporary.resolve("old")));
        assertFalse(Files.exists(temporary.resolve("new/f.txt")));
    }

    @Test
    void failedLaterMutationRollsBackEarlierMutation() throws Exception {
        Files.writeString(temporary.resolve("a"), "before");
        Files.writeString(temporary.resolve("parent"), "not directory");
        WorkspacePatchWriter writer = new WorkspacePatchWriter(tree());
        var before = tree().snapshot("a", 100);
        var changed =
                new WorkspaceFileAccess.Snapshot("a", true, WorkspaceFileAccess.hash(new byte[] {1}), new byte[] {1});
        var absent = new WorkspaceFileAccess.Snapshot("parent/child", false, "", new byte[0]);
        var created = new WorkspaceFileAccess.Snapshot(
                "parent/child", true, WorkspaceFileAccess.hash(new byte[] {2}), new byte[] {2});
        var prepared = new WorkspaceFileAccess.PreparedPatch(List.of(
                new WorkspaceFileAccess.Change(before, changed), new WorkspaceFileAccess.Change(absent, created)));
        assertEquals(Status.ROLLED_BACK, writer.apply(prepared).status());
        assertEquals("before", Files.readString(temporary.resolve("a")));
    }

    @Test
    void boundedLiteralSearchSkipsGitAndBinaryFiles() throws Exception {
        Files.writeString(temporary.resolve("a.txt"), "first\nneedle here\nlast");
        Files.createDirectory(temporary.resolve(".git"));
        Files.writeString(temporary.resolve(".git/config"), "needle");
        Files.write(temporary.resolve("binary"), new byte[] {0, 1, 2});
        var matches = tree().search("", "needle", 10, 1000);
        assertEquals(1, matches.size());
        assertEquals(2, matches.getFirst().line());
        assertTrue(matches.getFirst().text().contains("needle"));
        assertThrows(IOException.class, () -> tree().search("", "needle", 10, 3));
    }

    @Test
    void searchPageReportsActualScannedBytesAndPartialFileTruncation() throws Exception {
        Files.writeString(temporary.resolve("a.txt"), "Needle\nremaining");
        Files.writeString(temporary.resolve("ignored.md"), "Needle");
        var partial = tree().searchPage("", "needle", "*.txt", false, 10, 7);
        assertEquals(7, partial.scannedBytes());
        assertTrue(partial.truncated());
        assertEquals(1, partial.matches().size());
        var complete = tree().searchPage("", "needle", "*.txt", true, 10, 100);
        assertEquals(16, complete.scannedBytes());
        assertFalse(complete.truncated());
        assertTrue(complete.matches().isEmpty());
    }

    private WorkspaceFileTree tree() throws IOException {
        if (ownedTree == null) {
            ownedTree = new WorkspaceFileTree(temporary.toRealPath());
        }
        return ownedTree;
    }

    private static Edit update(String path, String before, String after) {
        return new Edit(
                path,
                Optional.of(WorkspaceFileAccess.hash(before.getBytes(StandardCharsets.UTF_8))),
                Optional.of(after.getBytes(StandardCharsets.UTF_8)));
    }
}
