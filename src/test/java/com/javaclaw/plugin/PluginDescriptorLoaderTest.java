package com.javaclaw.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginDescriptorLoaderTest {

    @Test
    void 接受安全插件id(@TempDir Path dir) throws Exception {
        Path jar = pluginJar(dir, "demo-plugin-2");
        assertEquals("demo-plugin-2", PluginDescriptorLoader.load(jar).id());
    }

    @Test
    void 拒绝可导致目录穿越的插件id(@TempDir Path dir) throws Exception {
        Path jar = pluginJar(dir, "../../outside");
        assertThrows(IOException.class, () -> PluginDescriptorLoader.load(jar));
    }

    @Test
    void 拒绝非规范大小写和首尾短横线(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> PluginDescriptorLoader.load(pluginJar(dir, "Demo")));
        assertThrows(IOException.class, () -> PluginDescriptorLoader.load(pluginJar(dir, "-demo")));
        assertThrows(IOException.class, () -> PluginDescriptorLoader.load(pluginJar(dir, "demo-")));
    }

    private static Path pluginJar(Path dir, String id) throws Exception {
        Path jar = Files.createTempFile(dir, "plugin-", ".jar");
        String json = """
                {"id":%s,"main":"example.Plugin","apiVersion":"1.0"}
                """.formatted(jsonString(id));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("plugin.json"));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
