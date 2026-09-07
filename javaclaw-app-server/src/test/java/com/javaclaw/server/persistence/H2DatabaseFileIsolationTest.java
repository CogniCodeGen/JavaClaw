package com.javaclaw.server.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.ManagedRuntimeDirectory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2DatabaseFileIsolationTest {
    private static final List<String> DATABASE_FILES = List.of(
            "javaclaw.mv.db",
            "javaclaw.trace.db",
            "javaclaw.trace.db.old",
            "javaclaw.lock.db",
            "javaclaw.newFile",
            "javaclaw.tempFile",
            "javaclaw.mv.db.newFile",
            "javaclaw.mv.db.tempFile",
            "javaclaw.h2.db");

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyKnownDatabaseFileRejectsLinksBeforeJdbcAndLeavesFreshLegacyFixtureUntouched() throws Exception {
        // 仅创建本测试拥有的旧目录样本；不枚举、不打开用户任何实际历史数据库。
        Path legacy = Files.createDirectory(temporaryDirectory.resolve("data-v5"));
        Path marker = Files.writeString(legacy.resolve("marker.bin"), "legacy database must remain untouched");
        byte[] before = Files.readAllBytes(marker);
        FileTime modified = Files.getLastModifiedTime(marker);
        FileTime legacyModified = Files.getLastModifiedTime(legacy);
        for (int index = 0; index < DATABASE_FILES.size(); index++) {
            String filename = DATABASE_FILES.get(index);
            Path current = temporaryDirectory.resolve("case-" + index).resolve("data-v6");
            ManagedRuntimeDirectory.prepare(current);
            Files.createSymbolicLink(current.resolve(filename), marker);

            PersistenceException failure =
                    assertThrows(PersistenceException.class, () -> new H2Database(current).initialize());

            assertTrue(failure.getCause() instanceof SQLException);
            assertTrue(failure.getCause().getMessage().contains("拒绝符号链接"));
            assertEquals(Set.of(filename), filenames(current));
            assertEquals(Set.of("marker.bin"), filenames(legacy));
            assertArrayEquals(before, Files.readAllBytes(marker));
            assertEquals(modified, Files.getLastModifiedTime(marker));
            assertEquals(legacyModified, Files.getLastModifiedTime(legacy));
        }
    }

    @Test
    void eachConnectionRechecksDatabaseFilesAfterSuccessfulInitialization() throws Exception {
        Path current = temporaryDirectory.resolve("data-v6");
        H2Database database = new H2Database(current);
        database.initialize();
        Path marker = Files.writeString(temporaryDirectory.resolve("outside-marker"), "unchanged");
        Files.createSymbolicLink(current.resolve("javaclaw.trace.db"), marker);

        SQLException failure = assertThrows(SQLException.class, database::open);

        assertEquals("08001", failure.getSQLState());
        assertFalse(database.healthy());
        assertEquals("unchanged", Files.readString(marker));
    }

    @Test
    void nonRegularDatabaseFilesFailClosedWhileANewDatabaseStillInitializes() throws Exception {
        Path blocked = temporaryDirectory.resolve("blocked").resolve("data-v6");
        ManagedRuntimeDirectory.prepare(blocked);
        Files.createDirectory(blocked.resolve("javaclaw.mv.db"));
        assertThrows(PersistenceException.class, () -> new H2Database(blocked).initialize());
        Path fresh = temporaryDirectory.resolve("fresh").resolve("data-v6");
        H2Database valid = new H2Database(fresh);
        valid.initialize();
        assertTrue(valid.healthy());
    }

    private static Set<String> filenames(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toUnmodifiableSet());
        }
    }
}
