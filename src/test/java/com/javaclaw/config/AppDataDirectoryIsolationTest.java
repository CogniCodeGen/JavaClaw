package com.javaclaw.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppDataDirectoryIsolationTest {

    @Test
    void allGlobalPathsUseConfiguredTestDataDirectory() {
        String configured = System.getProperty(AppDatabase.DATA_DIR_PROPERTY);
        assertTrue(configured != null && !configured.isBlank(),
                "Surefire 必须显式配置测试数据目录");

        Path expected = Path.of(configured).toAbsolutePath().normalize();
        Path productionDefault = Path.of(System.getProperty("user.dir"), "data")
                .toAbsolutePath().normalize();

        assertEquals(expected, AppDatabase.dataDirectory());
        assertEquals(expected, WorkspaceManager.getInstance().getGlobalDataPath());
        assertEquals(expected.resolve("javaclaw.mv.db"), AppDatabase.databaseFilePath());
        assertEquals(expected.resolve("credential.key"), CredentialEncryptor.legacyKeyFilePath());
        assertNotEquals(productionDefault.resolve("credential.key"),
                CredentialEncryptor.legacyKeyFilePath());
    }
}
