package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginDescriptorLoaderTest {

    private static final PluginDescriptorLoader LOADER =
            new PluginDescriptorLoader(new ObjectMapper());

    @Test
    void 接受安全插件id(@TempDir Path dir) throws Exception {
        Path jar = pluginJar(dir, "demo-plugin-2");
        assertEquals("demo-plugin-2", LOADER.load(jar).id());
    }

    @Test
    void 拒绝可导致目录穿越的插件id(@TempDir Path dir) throws Exception {
        Path jar = pluginJar(dir, "../../outside");
        assertThrows(IOException.class, () -> LOADER.load(jar));
    }

    @Test
    void 拒绝非规范大小写和首尾短横线(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> LOADER.load(pluginJar(dir, "Demo")));
        assertThrows(IOException.class, () -> LOADER.load(pluginJar(dir, "-demo")));
        assertThrows(IOException.class, () -> LOADER.load(pluginJar(dir, "demo-")));
    }

    @Test
    void 缺失或空白apiVersion直接拒绝(@TempDir Path dir) throws Exception {
        assertThrows(IOException.class, () -> LOADER.load(
                pluginJar(dir, "legacy-missing", null)));
        assertThrows(IOException.class, () -> LOADER.load(
                pluginJar(dir, "legacy-blank", "")));
    }

    @Test
    void 显式三点零插件保持兼容(@TempDir Path dir) throws Exception {
        var descriptor = LOADER.load(
                pluginJar(dir, "current-plugin", "3.0"));

        assertEquals("3.0", descriptor.apiVersion());
        org.junit.jupiter.api.Assertions.assertTrue(
                PluginManager.isApiCompatible(descriptor.apiVersion()));
    }

    @Test
    void 仓库示例插件描述符始终兼容当前宿主(@TempDir Path dir) throws Exception {
        for (String sample : List.of("hello", "feishu")) {
            Path descriptor = Path.of("sample-plugins", sample, "plugin.json");
            Path jar = Files.createTempFile(dir, sample + "-", ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new JarEntry("plugin.json"));
                Files.copy(descriptor, out);
                out.closeEntry();
            }
            var loaded = LOADER.load(jar);
            org.junit.jupiter.api.Assertions.assertTrue(
                    PluginManager.isApiCompatible(loaded.apiVersion()),
                    sample + " 示例插件 API " + loaded.apiVersion()
                            + " 与宿主 " + com.javaclaw.plugin.api.PluginDescriptor.HOST_API_VERSION
                            + " 不兼容");
        }
    }

    private static Path pluginJar(Path dir, String id) throws Exception {
        return pluginJar(dir, id, "3.0");
    }

    private static Path pluginJar(Path dir, String id, String apiVersion) throws Exception {
        Path jar = Files.createTempFile(dir, "plugin-", ".jar");
        String apiVersionField = apiVersion == null
                ? ""
                : ",\"apiVersion\":" + jsonString(apiVersion);
        String json = """
                {"id":%s,"main":"example.Plugin"%s}
                """.formatted(jsonString(id), apiVersionField);
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
