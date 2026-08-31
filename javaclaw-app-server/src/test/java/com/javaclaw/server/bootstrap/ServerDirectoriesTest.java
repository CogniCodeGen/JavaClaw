package com.javaclaw.server.bootstrap;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerDirectoriesTest {
    @TempDir
    Path temporary;

    @Test
    void keepsEveryDefaultDirectoryUnderTheProgramDirectory() {
        Path program = temporary.resolve("程序 program");
        ServerDirectories directories = ServerDirectories.resolve(new String[0], Map.of(), new Properties(), program);

        assertEquals(program.toAbsolutePath().normalize(), directories.programDirectory());
        assertEquals(program.resolve(".javaclaw/data-v4").toAbsolutePath().normalize(), directories.dataRoot());
        assertEquals(
                program.resolve(".javaclaw/config-v4").toAbsolutePath().normalize(), directories.configurationRoot());
        assertEquals(program.resolve(".javaclaw/cache-v4").toAbsolutePath().normalize(), directories.cacheRoot());
    }

    @Test
    void explicitRootsOverrideOnlyTheirOwnDefaults() {
        Path program = temporary.resolve("portable");
        Path data = temporary.resolve("explicit data");
        Path configuration = temporary.resolve("explicit config");
        Properties properties = new Properties();
        properties.setProperty("javaclaw.program.dir", program.toString());

        ServerDirectories directories = ServerDirectories.resolve(
                new String[] {"--data-dir", data.toString()},
                Map.of("JAVACLAW_CONFIG_DIR", configuration.toString()),
                properties,
                temporary.resolve("ignored"));

        assertEquals(data.toAbsolutePath().normalize(), directories.dataRoot());
        assertEquals(configuration.toAbsolutePath().normalize(), directories.configurationRoot());
        assertEquals(program.resolve(".javaclaw/cache-v4").toAbsolutePath().normalize(), directories.cacheRoot());
    }

    @Test
    void rejectsAnOptionWithoutAPath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerDirectories.resolve(new String[] {"--cache-dir"}, Map.of(), new Properties(), temporary));
    }
}
