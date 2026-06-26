package com.javaclaw.plugin.capability;

import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.capability.StorageAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * STORAGE 能力实现 —— 托管键值存储，落在宿主分配的 {@code {dataRoot}/plugins/{id}/storage.properties}，
 * 按工作区隔离。插件无须关心路径，写入即持久化。
 *
 * @author JavaClaw
 */
public final class StorageAccessImpl implements StorageAccess {

    private static final Logger log = LoggerFactory.getLogger(StorageAccessImpl.class);

    private final String pluginId;
    private final Path file;
    private final Properties props = new Properties();

    public StorageAccessImpl(String pluginId, Path dataRoot) {
        this.pluginId = pluginId;
        this.file = dataRoot.resolve("plugins").resolve(pluginId).resolve("storage.properties");
        load();
    }

    @Override
    public synchronized String get(String key) {
        CapabilityGuard.require(Capability.STORAGE);
        return props.getProperty(key, "");
    }

    @Override
    public synchronized void put(String key, String value) {
        CapabilityGuard.require(Capability.STORAGE);
        props.setProperty(key, value == null ? "" : value);
        save();
    }

    @Override
    public synchronized void remove(String key) {
        CapabilityGuard.require(Capability.STORAGE);
        props.remove(key);
        save();
    }

    @Override
    public synchronized Set<String> keys() {
        CapabilityGuard.require(Capability.STORAGE);
        return new LinkedHashSet<>(props.stringPropertyNames());
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("插件[{}]存储加载失败：{}", pluginId, e.toString());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "JavaClaw 插件存储：" + pluginId);
            }
        } catch (IOException e) {
            log.error("插件[{}]存储保存失败：{}", pluginId, e.toString());
        }
    }
}
