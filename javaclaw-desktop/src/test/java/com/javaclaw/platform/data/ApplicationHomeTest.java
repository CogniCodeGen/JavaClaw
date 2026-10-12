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
            assertEquals(project.resolve("data-v5"), home.dataDirectory());
            assertEquals(project.resolve("plugins-v2"), home.pluginsDirectory());
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
                "javaclaw-desktop/target/../data-v5", "target-other/test-home"}) {
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

    @Test void createsIndependentFormatFiveDirectoriesWithoutTouchingLegacyFiles() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        Path old = Files.createDirectories(root.resolve("data"));
        Files.writeString(old.resolve(DataRoot.FORMAT_FILE), "unknown");
        Path database = Files.writeString(old.resolve("javaclaw.mv.db"), "legacy database");
        Path plugins = Files.createDirectories(root.resolve("plugins/example"));
        Path plugin = Files.writeString(plugins.resolve("example.jar"), "legacy plugin");
        Path reset = Files.writeString(root.resolve(".javaclaw-reset-in-progress"), "3-to-4");
        ApplicationHome home = ApplicationHome.at(root);
        try (var guard = ApplicationUpgradeGuard.acquire(home)) { home.prepare(guard); }
        assertEquals(root.toRealPath().resolve("data-v5"), home.dataDirectory());
        assertEquals(root.toRealPath().resolve("plugins-v2"), home.pluginsDirectory());
        assertEquals("5", Files.readString(home.dataDirectory().resolve(DataRoot.FORMAT_FILE)));
        assertEquals("legacy database", Files.readString(database));
        assertEquals("legacy plugin", Files.readString(plugin));
        assertEquals("unknown", Files.readString(old.resolve(DataRoot.FORMAT_FILE)));
        assertEquals("3-to-4", Files.readString(reset));
        assertTrue(Files.isDirectory(home.temporaryDirectory()));
        assertTrue(Files.isDirectory(home.playwrightBrowsersDirectory()));
        assertFalse(Files.exists(home.dataDirectory().resolve("javaclaw.mv.db")));
    }

    @Test void ignoresLegacyLinksAndFilesButRejectsUnsafeNewPaths() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        Path external = Files.createDirectories(temporary.resolve("external"));
        Files.createSymbolicLink(root.resolve("plugins"), external);
        Files.writeString(root.resolve("data"), "old path");
        var home = ApplicationHome.at(root).prepare();
        assertEquals("old path", Files.readString(root.resolve("data")));
        try (var contents = Files.list(external)) { assertTrue(contents.findAny().isEmpty()); }
        Files.delete(home.pluginsDirectory());
        Files.createSymbolicLink(home.pluginsDirectory(), external);
        assertThrows(IOException.class, home::prepare);
        assertThrows(IOException.class, () -> home.requireManaged(external.resolve("file")));
    }

    @Test void preservesExistingNewDataAndRejectsUnknownNewFormat() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("home"));
        var home = ApplicationHome.at(root).prepare();
        Path retained = Files.writeString(home.dataDirectory().resolve("retain.txt"), "retained");
        home.prepare();
        assertEquals("retained", Files.readString(retained));
        Files.writeString(home.dataDirectory().resolve(DataRoot.FORMAT_FILE), "unknown");
        assertThrows(IOException.class, home::prepare);
        assertEquals("retained", Files.readString(retained));
    }

    @Test void serializesHomeAndNewDataOwnership() throws Exception {
        var home = ApplicationHome.at(Files.createDirectories(temporary.resolve("home")));
        try (var first = ApplicationUpgradeGuard.acquire(home)) {
            assertNotNull(first);
            assertNull(ApplicationUpgradeGuard.acquire(home));
            home.prepare(first);
            try (var lease = first.takeDataLock()) { assertTrue(lease.lock().isValid()); }
        }
        try (var next = ApplicationUpgradeGuard.acquire(home)) { assertNotNull(next); }
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
