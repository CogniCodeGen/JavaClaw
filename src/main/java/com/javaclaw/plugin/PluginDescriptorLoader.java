package com.javaclaw.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 描述符解析器 —— 从插件 jar 根目录读取并校验 {@code plugin.json}，产出 {@link PluginDescriptor}。
 *
 * <p>在建立插件类加载器<b>之前</b>调用：直接以 {@link JarFile} 打开 jar 读取条目，不加载任何插件类。</p>
 *
 * @author JavaClaw
 */
final class PluginDescriptorLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginDescriptorLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTOR_ENTRY = "plugin.json";

    private PluginDescriptorLoader() {
    }

    /**
     * 读取并校验插件 jar 中的描述符。
     *
     * @param jarPath 插件 jar 路径
     * @return 解析后的描述符
     * @throws IOException jar 无法打开、缺少 plugin.json、或必填字段缺失/非法
     */
    static PluginDescriptor load(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(DESCRIPTOR_ENTRY);
            if (entry == null) {
                throw new IOException("插件 jar 缺少 " + DESCRIPTOR_ENTRY + "：" + jarPath.getFileName());
            }
            try (InputStream in = jar.getInputStream(entry)) {
                JsonNode root = MAPPER.readTree(in);
                return parse(root, jarPath);
            }
        }
    }

    private static PluginDescriptor parse(JsonNode root, Path jarPath) throws IOException {
        String id = requireText(root, "id", jarPath);
        String mainClass = requireText(root, "main", jarPath);
        String name = optText(root, "name", id);
        String version = optText(root, "version", "0.0.0");
        String apiVersion = optText(root, "apiVersion", PluginDescriptor.HOST_API_VERSION);
        String description = optText(root, "description", "");

        Set<Capability> capabilities = parseCapabilities(root.get("capabilities"), id);
        List<PluginDescriptor.ConfigField> config = parseConfig(root.get("config"));

        PluginDescriptor descriptor = new PluginDescriptor(
                id, name, version, apiVersion, mainClass, description, capabilities, config);
        log.info("已解析插件描述符：id={}, name={}, version={}, 能力={}",
                id, name, version, capabilities);
        return descriptor;
    }

    /** 解析能力数组：大小写不敏感映射到枚举，未知能力告警并跳过。 */
    private static Set<Capability> parseCapabilities(JsonNode node, String pluginId) {
        Set<Capability> result = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String raw = item.asText("").strip();
                if (raw.isEmpty()) {
                    continue;
                }
                try {
                    result.add(Capability.valueOf(raw.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    log.warn("插件[{}]声明了未知能力「{}」，已忽略", pluginId, raw);
                }
            }
        }
        return result;
    }

    private static List<PluginDescriptor.ConfigField> parseConfig(JsonNode node) {
        List<PluginDescriptor.ConfigField> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String key = item.path("key").asText("").strip();
                if (key.isEmpty()) {
                    continue;
                }
                String label = item.path("label").asText(key);
                boolean secret = item.path("secret").asBoolean(false);
                result.add(new PluginDescriptor.ConfigField(key, label, secret));
            }
        }
        return result;
    }

    private static String requireText(JsonNode root, String field, Path jarPath) throws IOException {
        JsonNode n = root.get(field);
        if (n == null || n.asText("").strip().isEmpty()) {
            throw new IOException("插件 " + jarPath.getFileName() + " 的 plugin.json 缺少必填字段「" + field + "」");
        }
        return n.asText().strip();
    }

    private static String optText(JsonNode root, String field, String defaultValue) {
        JsonNode n = root.get(field);
        return (n == null || n.asText("").strip().isEmpty()) ? defaultValue : n.asText().strip();
    }
}
