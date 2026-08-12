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
        String previous = System.getProperty(DataRoot.DATA_DIR_PROPERTY);
        Path expected = tempDirectory.resolve("data").toAbsolutePath().normalize();
        System.setProperty(DataRoot.DATA_DIR_PROPERTY, expected.toString());
        try {
            try (var root = ApplicationContexts.createRoot(DataRoot.resolve())) {
                assertEquals(expected, root.getBean(DataRoot.class).path());
                assertEquals(expected, root.getBean(WorkspaceManager.class).getGlobalDataPath());
                Path databaseFile = root.getBean(
                        com.javaclaw.platform.data.H2DataSource.class).databaseFile();
                assertEquals(expected.resolve("javaclaw.mv.db"), databaseFile);
                assertTrue(databaseFile.startsWith(expected));
            }
        } finally {
            restoreDataDirectory(previous);
        }
    }

    private static void restoreDataDirectory(String previous) {
        if (previous == null) {
            System.clearProperty(DataRoot.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(DataRoot.DATA_DIR_PROPERTY, previous);
        }
    }
}
