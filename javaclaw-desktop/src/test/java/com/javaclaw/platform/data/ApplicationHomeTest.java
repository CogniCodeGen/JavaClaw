package com.javaclaw.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationHomeTest {
    @TempDir Path temporary;

    @Test void derivesHomeFromRuntimeOrDevelopmentSource() throws Exception {
        Path release = Files.createDirectories(temporary.resolve("release/runtime/lib"));
        assertEquals(temporary.resolve("release").toRealPath(), ApplicationHome.fromCodeSource(
                Files.writeString(release.resolve("desktop.jar"), "jar")).root());
        Path source = Files.createDirectories(temporary.resolve("source/src"));
        Files.writeString(source.getParent().resolve("pom.xml"), "<project/>");
        Path classes = Files.createDirectories(source.getParent().resolve("target/classes"));
        assertTrue(ApplicationHome.fromCodeSource(classes).development());
        assertEquals(source.getParent().toRealPath(), ApplicationHome.fromCodeSource(classes).root());
        assertThrows(IOException.class, () -> ApplicationHome.fromCodeSource(temporary));
    }

    @Test void aggregateAndDesktopCodeSourcesShareOneApplicationHome() throws Exception {
        Path project = createAggregateProject();
        for (String directory : new String[]{"target/classes", "javaclaw-desktop/target/classes",
                "javaclaw-desktop/target/test-classes", "javaclaw-desktop/src/main/java"}) {
            var home = ApplicationHome.fromCodeSource(Files.createDirectories(project.resolve(directory)));
            assertTrue(home.development());
            assertEquals(project, home.root());
            assertEquals(project.resolve("data"), home.dataDirectory());
            assertEquals(project.resolve("plugins"), home.pluginsDirectory());
        }
        Path jar = Files.writeString(project.resolve("javaclaw-desktop/target/desktop.jar"), "jar");
        assertEquals(project, ApplicationHome.fromCodeSource(jar).root());
    }

    @Test void permitsTestHomesOnlyUnderTheRootOrDesktopBuildDirectories() throws Exception {
        Path project = createAggregateProject();
        var home = ApplicationHome.fromCodeSource(project.resolve("javaclaw-desktop/src"));
        for (String directory : new String[]{"target/test-home", "javaclaw-desktop/target/test-home",
                "javaclaw-desktop/target/ui-test-home"}) {
            Path requested = project.resolve(directory);
            assertEquals(requested, home.withDevelopmentHome(requested).root());
        }
        assertEquals(project, home.withDevelopmentHome(project).root());
        for (String directory : new String[]{"plugin-sdk-java/target/test-home", "javaclaw-desktop/src/test-home",
                "javaclaw-desktop/target/../data", "target-other/test-home"}) {
            assertThrows(IOException.class, () -> home.withDevelopmentHome(project.resolve(directory)));
        }
        assertThrows(IOException.class, () -> home.withDevelopmentHome(temporary.resolve("outside")));
    }

    @Test void rejectsLinkedTestHomesAndDevelopmentOverridesForReleaseCode() throws Exception {
        Path project = createAggregateProject();
        var home = ApplicationHome.fromCodeSource(project.resolve("javaclaw-desktop/src"));
        Path build = Files.createDirectories(project.resolve("javaclaw-desktop/target"));
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.createSymbolicLink(build.resolve("linked-home"), outside);
        assertThrows(IOException.class, () -> home.withDevelopmentHome(build.resolve("linked-home/test-home")));

        Path runtime = Files.createDirectories(temporary.resolve("release/runtime"));
        var release = ApplicationHome.fromCodeSource(Files.writeString(runtime.resolve("desktop.jar"), "jar"));
        assertThrows(IOException.class, () -> release.withDevelopmentHome(project.resolve("target/test-home")));
    }

    @Test void resolvesTheActualCodeSourceIndependentlyOfTheWorkingDirectory() throws Exception {
        Path source = Path.of(ApplicationHome.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path expected = ApplicationHome.fromCodeSource(source).root();
        String previousHome = System.getProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY);
        String previousDirectory = System.getProperty("user.dir");
        try {
            System.clearProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY);
            for (Path directory : new Path[]{expected, expected.resolve("javaclaw-desktop"), temporary}) {
                System.setProperty("user.dir", directory.toString());
                assertEquals(expected, ApplicationHome.resolve().root());
            }
        } finally {
            restoreProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY, previousHome);
            restoreProperty("user.dir", previousDirectory);
        }
    }

    @Test void createsDefaultFormatFiveDirectoriesWithoutTouchingVersionedDirectories() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        Path old = Files.createDirectories(root.resolve("data-v5"));
        Files.writeString(old.resolve(DataRoot.FORMAT_FILE), "unknown");
        Path database = Files.writeString(old.resolve("javaclaw.mv.db"), "old database");
        Path plugins = Files.createDirectories(root.resolve("plugins-v2/example"));
        Path plugin = Files.writeString(plugins.resolve("example.jar"), "old plugin");
        Path reset = Files.writeString(root.resolve(".javaclaw-reset-in-progress"), "3-to-4");
        ApplicationHome home = ApplicationHome.at(root);
        try (var guard = ApplicationUpgradeGuard.acquire(home)) { home.prepare(guard); }
        assertEquals(root.toRealPath().resolve("data"), home.dataDirectory());
        assertEquals(root.toRealPath().resolve("plugins"), home.pluginsDirectory());
        assertEquals("5", Files.readString(home.dataDirectory().resolve(DataRoot.FORMAT_FILE)));
        assertEquals("old database", Files.readString(database));
        assertEquals("old plugin", Files.readString(plugin));
        assertEquals("unknown", Files.readString(old.resolve(DataRoot.FORMAT_FILE)));
        assertEquals("3-to-4", Files.readString(reset));
        assertTrue(Files.isDirectory(home.pluginsDirectory()));
        assertTrue(Files.isDirectory(home.cacheDirectory()));
        assertTrue(Files.isDirectory(home.temporaryDirectory()));
        assertTrue(Files.isDirectory(home.logsDirectory()));
        assertTrue(Files.isDirectory(home.playwrightBrowsersDirectory()));
        assertFalse(Files.exists(home.dataDirectory().resolve("javaclaw.mv.db")));
        assertFalse(Files.exists(home.pluginsDirectory().resolve("example")));
        assertFalse(Files.exists(old.resolve(ApplicationUpgradeGuard.DATA_LOCK)));
    }

    @Test void ignoresVersionedLinksAndFiles() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        Path external = Files.createDirectories(temporary.resolve("external"));
        Files.createSymbolicLink(root.resolve("plugins-v2"), external);
        Files.writeString(root.resolve("data-v5"), "old path");
        ApplicationHome.at(root).prepare();
        assertEquals("old path", Files.readString(root.resolve("data-v5")));
        assertTrue(Files.isSymbolicLink(root.resolve("plugins-v2")));
        try (var contents = Files.list(external)) { assertTrue(contents.findAny().isEmpty()); }
    }

    @Test void rejectsSymbolicLinksForDefaultDirectories() throws Exception {
        Path external = Files.createDirectories(temporary.resolve("external"));
        for (String directory : new String[]{"data", "plugins"}) {
            Path root = Files.createDirectories(temporary.resolve("linked-" + directory));
            Path link = Files.createSymbolicLink(root.resolve(directory), external);
            var home = ApplicationHome.at(root);
            assertThrows(IOException.class, home::prepare);
            assertThrows(IOException.class, () -> home.requireManaged(external.resolve("file")));
            assertTrue(Files.isSymbolicLink(link));
        }
        try (var contents = Files.list(external)) { assertTrue(contents.findAny().isEmpty()); }
    }

    @Test void rejectsFilePlaceholdersForDefaultDirectories() throws Exception {
        for (String directory : new String[]{"data", "plugins"}) {
            Path root = Files.createDirectories(temporary.resolve("file-" + directory));
            Path file = Files.writeString(root.resolve(directory), "retained");
            var home = ApplicationHome.at(root);
            assertThrows(IOException.class, home::prepare);
            assertEquals("retained", Files.readString(file));
        }
    }

    @Test void rejectsNonEmptyUnmarkedDataBeforeCreatingRuntimeDirectories() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        Path data = Files.createDirectories(root.resolve("data"));
        Path database = Files.writeString(data.resolve("javaclaw.mv.db"), "retained database");
        var home = ApplicationHome.at(root);
        try (var guard = ApplicationUpgradeGuard.acquire(home)) {
            assertNotNull(guard);
            assertThrows(IOException.class, () -> home.prepare(guard));
        }
        assertEquals("retained database", Files.readString(database));
        assertFalse(Files.exists(data.resolve(DataRoot.FORMAT_FILE)));
        assertFalse(Files.exists(home.pluginsDirectory()));
        assertFalse(Files.exists(home.cacheDirectory()));
        assertFalse(Files.exists(home.temporaryDirectory()));
        assertFalse(Files.exists(home.logsDirectory()));
    }

    @Test void preservesExistingFormatFiveDataAndRejectsUnknownFormat() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        var home = ApplicationHome.at(root).prepare();
        Path retained = Files.writeString(home.dataDirectory().resolve("retain.txt"), "retained");
        home.prepare();
        assertEquals("retained", Files.readString(retained));
        Files.writeString(home.dataDirectory().resolve(DataRoot.FORMAT_FILE), "unknown");
        assertThrows(IOException.class, home::prepare);
        assertEquals("retained", Files.readString(retained));
        assertEquals("unknown", Files.readString(home.dataDirectory().resolve(DataRoot.FORMAT_FILE)));
    }

    @Test void serializesHomeAndDefaultDataOwnershipAcrossLockTransfer() throws Exception {
        var home = ApplicationHome.at(Files.createDirectories(temporary.resolve("home")));
        ApplicationUpgradeGuard.DataLock lease;
        try (var first = ApplicationUpgradeGuard.acquire(home)) {
            assertNotNull(first);
            assertNull(ApplicationUpgradeGuard.acquire(home));
            home.prepare(first);
            lease = first.takeDataLock();
        }
        try (lease) {
            assertTrue(lease.lock().isValid());
            assertThrows(ApplicationUpgradeGuard.AlreadyRunningException.class,
                    () -> ApplicationUpgradeGuard.acquire(home));
        }
        try (var next = ApplicationUpgradeGuard.acquire(home)) { assertNotNull(next); }
        assertTrue(Files.isRegularFile(home.dataDirectory().resolve(ApplicationUpgradeGuard.DATA_LOCK)));
        assertFalse(Files.exists(home.root().resolve("data-v5")));
    }

    @Test void rejectsInvalidRootsAndExternalDevelopmentOverrides() throws Exception {
        Path file = Files.writeString(temporary.resolve("file"), "x");
        assertThrows(IOException.class, () -> ApplicationHome.at(file).prepare());
        String previous = System.getProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY);
        System.setProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY, temporary.toString());
        try { assertThrows(IllegalStateException.class, ApplicationHome::resolve); }
        finally {
            if (previous == null) System.clearProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY);
            else System.setProperty(ApplicationHome.DEVELOPMENT_HOME_PROPERTY, previous);
        }
    }

    private Path createAggregateProject() throws IOException {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Path desktop = Files.createDirectories(project.resolve("javaclaw-desktop/src"));
        Files.writeString(desktop.getParent().resolve("pom.xml"), "<project/>");
        return project.toRealPath();
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) System.clearProperty(name);
        else System.setProperty(name, previous);
    }
}
