package com.javaclaw.server.extension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory installed catalog. Uninstall never deletes a bundle from disk. */
public final class PluginCatalog {
    private final PluginBundleLoader loader;
    private final ConcurrentHashMap<String, LoadedPlugin> installed = new ConcurrentHashMap<>();

    /** 绑定包验证器，建立可重建的内存安装目录。 */
    public PluginCatalog(PluginBundleLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    PluginBundleLoader loader() {
        return loader;
    }

    /**
     * 验证并登记签名插件，重复 id 拒绝；不启动贡献进程。
     *
     * @throws java.io.IOException 包验证失败
     */
    public LoadedPlugin register(Path bundleDirectory) throws IOException {
        return register(bundleDirectory, false);
    }

    /**
     * 验证并登记插件；allowUnsigned 必须来自用户显式来源确认，不能由签名结果推导权限。
     *
     * @throws java.io.IOException 包或签名不符合约束
     */
    public LoadedPlugin register(Path bundleDirectory, boolean allowUnsigned) throws IOException {
        LoadedPlugin loaded = loader.load(bundleDirectory, allowUnsigned);
        LoadedPlugin previous = installed.putIfAbsent(loaded.manifest().id(), loaded);
        if (previous != null) {
            throw new IllegalStateException(
                    "plugin is already registered: " + loaded.manifest().id());
        }
        return loaded;
    }

    /** 读取当前登记插件；不存在返回 Optional.empty。 */
    public Optional<LoadedPlugin> find(String pluginId) {
        return Optional.ofNullable(installed.get(pluginId));
    }

    /** 读取已登记插件；不存在时抛出 NoSuchElementException。 */
    public LoadedPlugin require(String pluginId) {
        return find(pluginId).orElseThrow(() -> new NoSuchElementException("plugin is not registered: " + pluginId));
    }

    /** 按插件 id 排序返回安装快照，稳定发现结果顺序。 */
    public List<LoadedPlugin> list() {
        return installed.values().stream()
                .sorted(Comparator.comparing(value -> value.manifest().id()))
                .toList();
    }

    /** 仅移除内存登记并返回是否存在；不停止进程或删除磁盘目录，这些动作由上层协调。 */
    public boolean unregister(String pluginId) {
        return installed.remove(pluginId) != null;
    }
}
