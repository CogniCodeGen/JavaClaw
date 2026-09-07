package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFilePagingTest {
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
    void pageHashesEntireFileAndVeryLargeOffsetAllocatesNoOffsetBuffer() throws Exception {
        Files.writeString(temporary.resolve("a"), "0123456789");
        ownedTree = new WorkspaceFileTree(temporary.toRealPath());
        var tree = ownedTree;
        var page = WorkspaceFilePaging.read(tree, "a", 4, 3, 10);
        assertEquals("456", new String(page.content(), StandardCharsets.UTF_8));
        assertEquals(10, page.sizeBytes());
        assertEquals(WorkspaceFileAccess.hash("0123456789".getBytes(StandardCharsets.UTF_8)), page.sha256());
        assertEquals(
                0, WorkspaceFilePaging.read(tree, "a", Long.MAX_VALUE, 3, 10).content().length);
        assertThrows(IOException.class, () -> WorkspaceFilePaging.read(tree, "a", 0, 3, 9));
    }

    @Test
    void directoryCursorIsExclusiveAndLastPageHasNoCursor() throws Exception {
        Files.writeString(temporary.resolve("b"), "");
        Files.writeString(temporary.resolve("a"), "");
        ownedTree = new WorkspaceFileTree(temporary.toRealPath());
        var tree = ownedTree;
        var first = WorkspaceFilePaging.list(tree, ".", "", 1);
        assertEquals(
                List.of("a"),
                first.entries().stream().map(WorkspaceFileAccess.Entry::path).toList());
        assertEquals("a", first.nextName().orElseThrow());
        var last = WorkspaceFilePaging.list(tree, ".", first.nextName().orElseThrow(), 1);
        assertEquals("b", last.entries().getFirst().path());
        assertTrue(last.nextName().isEmpty());
    }

    @Test
    void searchAppliesGlobAndCaseWithoutTreatingQueryAsRegularExpression() throws Exception {
        Files.writeString(temporary.resolve("a.java"), "HELLO [world]");
        Files.writeString(temporary.resolve("b.txt"), "hello [world]");
        ownedTree = new WorkspaceFileTree(temporary.toRealPath());
        var tree = ownedTree;
        assertEquals(1, tree.search(".", "hello [", "*.java", false, 10, 1000).size());
        assertTrue(tree.search(".", "hello [", "*.java", true, 10, 1000).isEmpty());
    }
}
