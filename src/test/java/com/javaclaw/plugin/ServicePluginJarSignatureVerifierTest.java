package com.javaclaw.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ServicePluginJarSignatureVerifierTest {
    @TempDir Path temporary;

    @Test
    void rejectsUnsignedServicePluginContent() throws Exception {
        Path jar = temporary.resolve("unsigned.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/versions/21/example/Plugin.class"));
            output.write(new byte[] {0x01, 0x02});
            output.closeEntry();
        }

        assertThrows(IOException.class,
                () -> new ServicePluginJarSignatureVerifier().verify(jar));
    }

    @Test
    void rejectsSymbolicLinkEvenWhenTargetIsARegularJar() throws Exception {
        Path target = temporary.resolve("target.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(target))) { }
        Path link = temporary.resolve("link.jar");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | IOException denied) {
            return;
        }

        assertThrows(IOException.class,
                () -> new ServicePluginJarSignatureVerifier().verify(link));
    }
}
