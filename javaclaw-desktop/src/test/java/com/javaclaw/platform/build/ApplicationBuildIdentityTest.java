package com.javaclaw.platform.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationBuildIdentityTest {

    @TempDir
    Path tempDirectory;

    @Test
    void directoryFingerprintIsStableAcrossCreationOrderAndLocation() throws Exception {
        Path first = tempDirectory.resolve("first");
        Path second = tempDirectory.resolve("second");
        write(first.resolve("fxml/view.fxml"), "<view/>");
        write(first.resolve("classes/Controller.class"), "controller");
        write(second.resolve("classes/Controller.class"), "controller");
        write(second.resolve("fxml/view.fxml"), "<view/>");

        ApplicationBuildIdentity left = ApplicationBuildIdentity.fromPath(first);
        ApplicationBuildIdentity right = ApplicationBuildIdentity.fromPath(second);

        assertTrue(left.available());
        assertEquals(left.fingerprint(), right.fingerprint());
        assertFalse(left.hasChanged());
    }

    @Test
    void classFxmlAndCssChangesInvalidateRunningIdentity() throws Exception {
        for (String relative : new String[]{"Controller.class", "view.fxml", "theme.css"}) {
            Path root = tempDirectory.resolve(relative.replace('.', '-'));
            Path changed = root.resolve(relative);
            write(changed, "before");
            ApplicationBuildIdentity running = ApplicationBuildIdentity.fromPath(root);

            Files.writeString(changed, "after");

            assertTrue(running.hasChanged(), relative + " 变化必须要求重启");
            assertNotEquals(running.fingerprint(),
                    ApplicationBuildIdentity.fromPath(root).fingerprint());
        }
    }

    @Test
    void packagedArtifactFingerprintTracksJarContent() throws Exception {
        Path jar = tempDirectory.resolve("javaclaw.jar");
        Files.writeString(jar, "jar-a");
        ApplicationBuildIdentity running = ApplicationBuildIdentity.fromPath(jar);
        assertFalse(running.hasChanged());

        Files.writeString(jar, "jar-b");

        assertTrue(running.hasChanged());
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
