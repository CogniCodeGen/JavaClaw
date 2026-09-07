package com.javaclaw.nativehost;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.nativehost.transport.WindowsPipeName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LocalRuntimeDirectoriesTest {
    private static final List<String> PROPERTIES = List.of("javaclaw.program.dir", "javaclaw.data.root", "user.dir");
    private final Map<String, String> original = new HashMap<>();

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void captureProperties() {
        PROPERTIES.forEach(name -> original.put(name, System.getProperty(name)));
        System.clearProperty("javaclaw.program.dir");
        System.clearProperty("javaclaw.data.root");
    }

    @AfterEach
    void restoreProperties() {
        original.forEach((name, value) -> {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
        });
    }

    @Test
    void defaultUsesProgramDirectoryAndDevelopmentWorkingDirectoryWithoutIo() {
        System.setProperty("user.dir", temporaryDirectory.toString());
        assertEquals(temporaryDirectory.resolve("data-v6"), LocalRuntimeDirectories.dataDirectory());
        Path program = temporaryDirectory.resolve("installation");
        System.setProperty("javaclaw.program.dir", "  " + program + "  ");

        assertEquals(program, LocalRuntimeDirectories.programDirectory());
        assertEquals(program.resolve("data-v6"), LocalRuntimeDirectories.dataDirectory());
        assertFalse(Files.exists(program));
    }

    @Test
    void explicitDataRootOverridesProgramAndSeparatesWindowsPipeIdentity() {
        Path explicit = temporaryDirectory.resolve("custom/data-v6");
        System.setProperty("javaclaw.data.root", explicit.toString());
        WindowsPipeName first = WindowsPipeName.currentUserDefault();

        assertEquals(explicit, LocalRuntimeDirectories.dataDirectory(temporaryDirectory.resolve("installation")));
        System.setProperty(
                "javaclaw.data.root",
                temporaryDirectory.resolve("other/data-v6").toString());
        assertNotEquals(first, WindowsPipeName.currentUserDefault());
        assertFalse(first.value().contains(explicit.toString()));
        assertFalse(Files.exists(explicit));
    }
}
