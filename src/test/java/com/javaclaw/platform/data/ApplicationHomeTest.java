package com.javaclaw.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationHomeTest {

    @TempDir Path temporary;

    @Test
    void derivesHomeFromRuntimeOrDevelopmentSourceRatherThanWorkingDirectory() throws Exception {
        Path release = Files.createDirectories(temporary.resolve("release/runtime/lib"));
        Path jar = Files.writeString(release.resolve("javaclaw.jar"), "jar");
        assertEquals(temporary.resolve("release").toRealPath(),
                ApplicationHome.fromCodeSource(jar).root());

        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        assertEquals(project.toRealPath(), ApplicationHome.fromCodeSource(classes).root());

        Path unrelated = Files.createDirectories(temporary.resolve("unrelated"));
        assertThrows(IOException.class, () -> ApplicationHome.fromCodeSource(unrelated));
    }

    @Test
    void freshFormatFourHomeDoesNotImportFormerWorkingDirectory() throws Exception {
        Path legacy = Files.createDirectories(temporary.resolve("legacy"));
        Path oldData = Files.createDirectories(legacy.resolve("data"));
        Files.writeString(oldData.resolve(DataRoot.FORMAT_FILE), "3");
        Files.writeString(oldData.resolve("javaclaw.mv.db"), "legacy-db");
        Path oldPlugin = Files.createDirectories(legacy.resolve("plugins/example"));
        Files.writeString(oldPlugin.resolve("example.jar"), "legacy-plugin");
        Path newRoot = Files.createDirectories(temporary.resolve("portable"));
        String previous = System.getProperty(ApplicationHome.MIGRATION_SOURCE_PROPERTY);
        System.setProperty(ApplicationHome.MIGRATION_SOURCE_PROPERTY, legacy.toString());
        try {
            ApplicationHome home = ApplicationHome.at(newRoot).prepare();
            assertEquals("4", Files.readString(home.dataDirectory().resolve(DataRoot.FORMAT_FILE)));
            assertFalse(Files.exists(home.dataDirectory().resolve("javaclaw.mv.db")));
            assertFalse(Files.exists(home.pluginsDirectory().resolve("example/example.jar")));
            assertEquals("legacy-db", Files.readString(oldData.resolve("javaclaw.mv.db")));
            assertEquals("legacy-plugin", Files.readString(oldPlugin.resolve("example.jar")));
            assertTrue(Files.isDirectory(home.temporaryDirectory()));
            assertTrue(Files.isDirectory(home.playwrightBrowsersDirectory()));
            assertFalse(Files.exists(newRoot.resolve(".javaclaw-reset-in-progress")));
        } finally {
            restore(ApplicationHome.MIGRATION_SOURCE_PROPERTY, previous);
        }
    }

    @Test
    void firstUpgradeClearsOnlyOwnedDataAndPlugins() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("upgrade"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path plugins = Files.createDirectories(root.resolve("plugins/example"));
        Path runtime = Files.createDirectories(root.resolve("runtime"));
        Files.writeString(data.resolve(DataRoot.FORMAT_FILE), "3");
        Files.writeString(data.resolve("javaclaw.mv.db"), "old data");
        Files.writeString(plugins.resolve("example.jar"), "old plugin");
        Files.writeString(runtime.resolve("app.jar"), "application");
        ApplicationHome home = ApplicationHome.at(root);
        try (ApplicationUpgradeGuard guard = ApplicationUpgradeGuard.acquire(home)) {
            home.prepare(guard);
        }
        assertEquals("4", Files.readString(data.resolve(DataRoot.FORMAT_FILE)));
        assertFalse(Files.exists(data.resolve("javaclaw.mv.db")));
        assertFalse(Files.exists(plugins.resolve("example.jar")));
        assertEquals("application", Files.readString(runtime.resolve("app.jar")));
    }

    @Test
    void interruptedUpgradeResumesAndLegacyInstanceLockBlocksReset() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("interrupted"));
        Path data = Files.createDirectories(root.resolve("data"));
        Files.writeString(data.resolve(DataRoot.FORMAT_FILE), "3");
        Files.writeString(data.resolve("old.txt"), "old");
        ApplicationHome home = ApplicationHome.at(root);
        try (var channel = java.nio.channels.FileChannel.open(
                    data.resolve(ApplicationUpgradeGuard.DATA_LOCK),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertThrows(ApplicationUpgradeGuard.AlreadyRunningException.class,
                    () -> ApplicationUpgradeGuard.acquire(home));
            assertEquals("3", Files.readString(data.resolve(DataRoot.FORMAT_FILE)));
        }
        Files.writeString(root.resolve(".javaclaw-reset-in-progress"), "3-to-4");
        Files.writeString(data.resolve(DataRoot.FORMAT_FILE), "4");
        try (ApplicationUpgradeGuard guard = ApplicationUpgradeGuard.acquire(home)) {
            home.prepare(guard);
        }
        assertFalse(Files.exists(data.resolve("old.txt")));
        assertFalse(Files.exists(root.resolve(".javaclaw-reset-in-progress")));
    }

    @Test
    void concurrentUpgradeCannotPassStableHomeLock() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("concurrent-upgrade"));
        Path data = Files.createDirectories(root.resolve("data"));
        Files.writeString(data.resolve(DataRoot.FORMAT_FILE), "3");
        Path oldData = Files.writeString(data.resolve("old.txt"), "preserve until locked");
        ApplicationHome home = ApplicationHome.at(root);

        try (ApplicationUpgradeGuard first = ApplicationUpgradeGuard.acquire(home)) {
            assertNull(ApplicationUpgradeGuard.acquire(home));
            assertEquals("preserve until locked", Files.readString(oldData));
        }
    }

    @Test
    void interruptedUpgradeWithUnknownFormatPreservesRemainingFiles() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("unknown-interrupted"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path marker = Files.writeString(data.resolve(DataRoot.FORMAT_FILE), "unknown");
        Path oldData = Files.writeString(data.resolve("old.txt"), "preserve data");
        Path reset = Files.writeString(root.resolve(".javaclaw-reset-in-progress"), "3-to-4");
        ApplicationHome home = ApplicationHome.at(root);

        try (ApplicationUpgradeGuard guard = ApplicationUpgradeGuard.acquire(home)) {
            assertThrows(IOException.class, () -> home.prepare(guard));
        }

        assertEquals("unknown", Files.readString(marker));
        assertEquals("preserve data", Files.readString(oldData));
        assertEquals("3-to-4", Files.readString(reset));
    }

    @Test
    void unknownFormatAndUnsafePluginPathRefuseToDeleteOldData() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("unsafe-upgrade"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path preserved = Files.writeString(data.resolve("keep.txt"), "keep");
        Path marker = Files.writeString(data.resolve(DataRoot.FORMAT_FILE), "unknown");
        ApplicationHome home = ApplicationHome.at(root);
        try (ApplicationUpgradeGuard guard = ApplicationUpgradeGuard.acquire(home)) {
            assertThrows(IOException.class, () -> home.prepare(guard));
        }
        assertEquals("keep", Files.readString(preserved));

        Files.writeString(marker, "3");
        Path external = Files.createDirectories(temporary.resolve("external-plugin-data"));
        Path externalPlugin = Files.writeString(external.resolve("preserved.jar"), "plugin");
        Files.createSymbolicLink(root.resolve("plugins"), external);
        try (ApplicationUpgradeGuard guard = ApplicationUpgradeGuard.acquire(home)) {
            assertThrows(IOException.class, () -> home.prepare(guard));
        }
        assertEquals("3", Files.readString(marker));
        assertEquals("keep", Files.readString(preserved));
        assertEquals("plugin", Files.readString(externalPlugin));
    }

    @Test
    void upgradeRejectsRegularFileAtPluginDirectoryBeforeDeletingData() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("file-instead-of-plugins"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path marker = Files.writeString(data.resolve(DataRoot.FORMAT_FILE), "3");
        Path oldData = Files.writeString(data.resolve("old.txt"), "preserve data");
        Path plugins = Files.writeString(root.resolve("plugins"), "preserve plugin path");
        ApplicationHome home = ApplicationHome.at(root);

        try (ApplicationUpgradeGuard guard = ApplicationUpgradeGuard.acquire(home)) {
            assertThrows(IOException.class, () -> home.prepare(guard));
        }

        assertEquals("3", Files.readString(marker));
        assertEquals("preserve data", Files.readString(oldData));
        assertEquals("preserve plugin path", Files.readString(plugins));
        assertFalse(Files.exists(root.resolve(".javaclaw-reset-in-progress")));
    }

    @Test
    void upgradeRejectsRegularFileAtDataDirectoryBeforeTakingLocks() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("file-instead-of-data"));
        Path data = Files.writeString(root.resolve("data"), "preserve data path");
        ApplicationHome home = ApplicationHome.at(root);

        assertThrows(IOException.class, () -> ApplicationUpgradeGuard.acquire(home));
        assertEquals("preserve data path", Files.readString(data));
    }

    @Test
    void neverOverwritesExistingDestinationOrFollowsManagedSymlink() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        Path data = Files.createDirectories(root.resolve("data"));
        Files.writeString(data.resolve(DataRoot.FORMAT_FILE), DataRoot.FORMAT_VERSION);
        Files.writeString(data.resolve("existing.txt"), "keep");
        Path legacy = Files.createDirectories(temporary.resolve("legacy"));
        Path oldData = Files.createDirectories(legacy.resolve("data"));
        Files.writeString(oldData.resolve(DataRoot.FORMAT_FILE), DataRoot.FORMAT_VERSION);
        Files.writeString(oldData.resolve("existing.txt"), "old");
        String previous = System.getProperty(ApplicationHome.MIGRATION_SOURCE_PROPERTY);
        System.setProperty(ApplicationHome.MIGRATION_SOURCE_PROPERTY, legacy.toString());
        try {
            ApplicationHome.at(root).prepare();
            assertEquals("keep", Files.readString(data.resolve("existing.txt")));
        } finally {
            restore(ApplicationHome.MIGRATION_SOURCE_PROPERTY, previous);
        }

        Path external = Files.createDirectories(temporary.resolve("external"));
        Files.delete(root.resolve("plugins"));
        Files.createSymbolicLink(root.resolve("plugins"), external);
        assertThrows(IOException.class, () -> ApplicationHome.at(root).prepare());
        assertFalse(Files.exists(external.resolve("example")));
    }

    @Test
    void invalidRootFailsInsteadOfUsingSystemDirectory() throws Exception {
        Path file = Files.writeString(temporary.resolve("not-a-directory"), "x");
        assertThrows(IOException.class, () -> ApplicationHome.at(file).prepare());
    }

    @Test
    void developmentOverrideCannotMoveApplicationHomeOutsideSourceTree() {
        String previous = System.getProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY);
        System.setProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY,
                temporary.resolve("external-home").toString());
        try {
            assertThrows(IllegalStateException.class, ApplicationHome::resolve);
        } finally {
            restore(ApplicationHome.DEVELOPMENT_HOME_PROPERTY, previous);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
