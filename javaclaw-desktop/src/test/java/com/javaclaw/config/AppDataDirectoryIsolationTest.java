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
        Path expected = tempDirectory.resolve("data").toAbsolutePath().normalize();
        try (var root = ApplicationContexts.createRoot(new DataRoot(expected))) {
            assertEquals(expected, root.getBean(DataRoot.class).path());
            assertEquals(expected, root.getBean(WorkspaceManager.class).getGlobalDataPath());
            Path databaseFile = root.getBean(
                    com.javaclaw.platform.data.H2DataSource.class).databaseFile();
            assertEquals(expected.resolve("javaclaw.mv.db"), databaseFile);
            assertTrue(databaseFile.startsWith(expected));
        }
    }
}
