package com.javaclaw.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDataDirectoryIsolationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void allGlobalPathsUseConfiguredTestDataDirectory() {
        String previous = System.getProperty(AppDatabase.DATA_DIR_PROPERTY);
        Path expected = tempDirectory.resolve("data-v3").toAbsolutePath().normalize();
        System.setProperty(AppDatabase.DATA_DIR_PROPERTY, expected.toString());
        try {
            try (var root = ApplicationContexts.createRoot(DataRoot.resolve())) {
                assertEquals(expected, AppDatabase.dataDirectory());
                assertEquals(expected, root.getBean(WorkspaceManager.class).getGlobalDataPath());
                assertEquals(expected.resolve("javaclaw.mv.db"), AppDatabase.databaseFilePath());
                assertTrue(AppDatabase.databaseFilePath().startsWith(expected));
            }
        } finally {
            restoreDataDirectory(previous);
        }
    }

    private static void restoreDataDirectory(String previous) {
        if (previous == null) {
            System.clearProperty(AppDatabase.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(AppDatabase.DATA_DIR_PROPERTY, previous);
        }
    }
}
