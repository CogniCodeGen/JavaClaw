package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDirectoryScannerPathTest {
    @TempDir Path temporary;

    @Test
    void refusesPluginRootSwappedToExternalSymlink() throws Exception {
        Path external = Files.createDirectories(temporary.resolve("external/hello"));
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(external.resolve("hello.jar")))) {
            jar.putNextEntry(new JarEntry("plugin.json"));
            Files.copy(Path.of("sample-plugins/hello/plugin.json"), jar);
            jar.closeEntry();
        }
        PluginDescriptorLoader loader = new PluginDescriptorLoader(new ObjectMapper());
        assertEquals(1, new PluginDirectoryScanner(external.getParent(), loader).scan().size());

        Path linkedRoot = temporary.resolve("plugins");
        Files.createSymbolicLink(linkedRoot, external.getParent());
        assertTrue(new PluginDirectoryScanner(linkedRoot, loader).scan().isEmpty());
    }
}
