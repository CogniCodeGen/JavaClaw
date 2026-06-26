package com.javaclaw.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.plugin.api.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 插件状态持久化（工作区维度）—— 记录每个插件的"是否启用"与"已授权能力"，落在
 * {@code {dataRoot}/plugins.json}。供下次启动自动恢复已启用插件、跳过已授权能力的重复确认。
 *
 * <p>插件本体 jar 是全局的（{@code {user.dir}/plugins/}），但启用态与授权按工作区隔离——
 * 与 {@code skill-usage.json}/{@code sdd-tasks.json} 等一致。</p>
 *
 * @author JavaClaw
 */
final class PluginStore {

    private static final Logger log = LoggerFactory.getLogger(PluginStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单插件的持久化条目（公开字段，便于 Jackson 读写） */
    static final class Persist {
        public boolean enabled;
        public List<String> granted = new ArrayList<>();
        /** 插件自有配置（secret 项以密文存储，由 PluginManager 加解密） */
        public Map<String, String> config = new LinkedHashMap<>();
    }

    private Path file;
    private final Map<String, Persist> entries = new LinkedHashMap<>();

    /** （重新）绑定到指定工作区数据根并加载。 */
    void bind(Path dataRoot) {
        this.file = dataRoot.resolve("plugins.json");
        entries.clear();
        load();
    }

    boolean isEnabled(String id) {
        Persist p = entries.get(id);
        return p != null && p.enabled;
    }

    /** 读取已授权能力集合（未知能力名跳过）。 */
    Set<Capability> granted(String id) {
        Persist p = entries.get(id);
        Set<Capability> result = new LinkedHashSet<>();
        if (p != null) {
            for (String name : p.granted) {
                try {
                    result.add(Capability.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // 旧版本遗留的未知能力名，忽略
                }
            }
        }
        return result;
    }

    /** 记录启用态与授权能力并持久化。 */
    void update(String id, boolean enabled, Set<Capability> granted) {
        Persist p = entries.computeIfAbsent(id, k -> new Persist());
        p.enabled = enabled;
        p.granted = granted.stream().map(Enum::name).toList();
        save();
    }

    /** 仅更新启用态（保留已授权能力）。 */
    void setEnabled(String id, boolean enabled) {
        Persist p = entries.computeIfAbsent(id, k -> new Persist());
        p.enabled = enabled;
        save();
    }

    /** 读取插件配置（原样返回，secret 项为密文，由 PluginManager 解密）。 */
    Map<String, String> config(String id) {
        Persist p = entries.get(id);
        return p == null ? new LinkedHashMap<>() : new LinkedHashMap<>(p.config);
    }

    /** 写入插件配置（secret 项应已由 PluginManager 加密）并持久化。 */
    void setConfig(String id, Map<String, String> config) {
        Persist p = entries.computeIfAbsent(id, k -> new Persist());
        p.config = new LinkedHashMap<>(config);
        save();
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            Map<String, Persist> loaded = MAPPER.readValue(
                    Files.readAllBytes(file), new TypeReference<LinkedHashMap<String, Persist>>() {
                    });
            entries.putAll(loaded);
            log.info("插件状态已加载：{} 条（{}）", entries.size(), file);
        } catch (IOException e) {
            log.warn("加载插件状态失败，使用空状态：{}", e.toString());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        } catch (IOException e) {
            log.error("保存插件状态失败：{}", e.toString());
        }
    }
}
