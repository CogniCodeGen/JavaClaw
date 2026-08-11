package com.javaclaw.application.plugin;

import com.javaclaw.application.error.ConflictException;
import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.RejectedException;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** 插件目录、生命周期和配置用例；不持有页面状态。 */
public final class PluginManagementUseCase
        implements PluginManagementApplicationService {

    private final PluginManagementPort plugins;

    public PluginManagementUseCase(PluginManagementPort plugins) {
        this.plugins = Objects.requireNonNull(plugins, "plugins");
    }

    @Override
    public Catalog snapshot() {
        return new Catalog(plugins.list());
    }

    @Override
    public Catalog refresh() {
        plugins.refresh();
        return snapshot();
    }

    @Override
    public Catalog setEnabled(String pluginId, boolean enabled) {
        Plugin before = snapshot().require(pluginId);
        if (before.active() == enabled) return snapshot();
        plugins.setEnabled(pluginId, enabled);
        Catalog result = snapshot();
        Plugin after = result.require(pluginId);
        if (enabled && !after.active()) {
            String reason = after.error().isBlank()
                    ? "插件未获授权或被宿主策略拒绝" : after.error();
            throw new RejectedException("插件启用失败：" + reason);
        }
        if (!enabled && after.active()) {
            throw new ConflictException("插件停用后仍处于运行状态：" + pluginId);
        }
        return result;
    }

    @Override
    public InstallResult install(Path jar) {
        Objects.requireNonNull(jar, "jar");
        String id = plugins.install(jar.toAbsolutePath().normalize());
        if (id == null || id.isBlank()) {
            throw new RejectedException(
                    "无法安装该插件：descriptor 非法，或插件 API 与宿主不兼容。"
                            + "当前宿主要求 Plugin API 3.x；旧插件请重新编译后再安装。");
        }
        Catalog catalog = snapshot();
        catalog.require(id);
        return new InstallResult(id, catalog);
    }

    @Override
    public Catalog uninstall(String pluginId) {
        snapshot().require(pluginId);
        if (!plugins.uninstall(pluginId)) {
            throw new ConflictException("插件卸载失败：" + pluginId);
        }
        return snapshot();
    }

    @Override
    public Details details(String pluginId) {
        Plugin plugin = snapshot().require(pluginId);
        return new Details(plugin, plugins.config(pluginId));
    }

    @Override
    public void saveConfig(String pluginId, Map<String, String> values) {
        Plugin plugin = snapshot().require(pluginId);
        if (plugin.config().isEmpty()) {
            throw new ConflictException("插件没有可保存的配置：" + pluginId);
        }
        plugins.saveConfig(pluginId, Map.copyOf(values == null ? Map.of() : values));
    }

    @Override
    public Path pluginsDirectory() {
        return plugins.pluginsDirectory().toAbsolutePath().normalize();
    }

    @Override
    public AutoCloseable onCatalogChanged(Runnable listener) {
        return plugins.onChanged(Objects.requireNonNull(listener, "listener"));
    }
}
