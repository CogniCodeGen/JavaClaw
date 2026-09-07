package com.javaclaw.server.coding;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.TurnId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCommandDirectoriesTest {
    @TempDir
    Path temporary;

    @Test
    void putsTemporaryFilesOutsideTheProjectWritableCache() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        Path cache = data.resolve("coding/caches/workspace/platform/hash");
        var directories = new ManagedCommandDirectories(data);
        TurnId turn = TurnId.random();
        var prepared = directories.prepare(cache, turn);
        assertTrue(Files.isDirectory(prepared.cache()));
        assertTrue(Files.isDirectory(prepared.temporary()));
        assertFalse(prepared.temporary().startsWith(prepared.cache()));
        assertEquals(prepared, directories.prepare(cache, turn));
        assertFalse(Files.exists(cache.resolve("tmp")));
        assertThrows(SecurityException.class, () -> directories.prepare(temporary.resolve("other"), turn));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void ignoresPoisonedCacheChildrenAndNeverCreatesOutsideDirectories() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data-v6"));
        Path cache = data.resolve("coding/caches/workspace/platform/hash");
        var directories = new ManagedCommandDirectories(data);
        directories.prepare(cache, TurnId.random());
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(cache.resolve("tmp"), outside);
        TurnId turn = TurnId.random();
        var prepared = directories.prepare(cache, turn);
        assertFalse(Files.exists(outside.resolve(turn.toString())));
        assertTrue(Files.isDirectory(prepared.temporary()));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void rejectsSymlinksAtAnyManagedAncestorOrFinalCacheRoot() throws Exception {
        for (String poisoned : new String[] {"coding", "coding/caches", "coding/caches/workspace/platform/hash"}) {
            Path data = Files.createTempDirectory(temporary, "data-");
            Path outside = Files.createTempDirectory(temporary, "outside-");
            Path link = data.resolve(poisoned);
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, outside);
            var directories = new ManagedCommandDirectories(data);
            assertThrows(
                    SecurityException.class,
                    () -> directories.prepare(data.resolve("coding/caches/workspace/platform/hash"), TurnId.random()));
            try (var files = Files.list(outside)) {
                assertEquals(0, files.count());
            }
        }
    }
}
