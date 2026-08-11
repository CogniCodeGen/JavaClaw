package com.javaclaw.infrastructure.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.ConfigField;
import com.javaclaw.application.plugin.PluginManagementApplicationService.NamedItem;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Permission;
import com.javaclaw.application.plugin.PluginManagementApplicationService.Plugin;
import com.javaclaw.application.plugin.PluginManagementApplicationService.State;
import com.javaclaw.application.plugin.PluginManagementPort;
import com.javaclaw.plugin.PluginInfo;
import com.javaclaw.plugin.PluginManager;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 把现有插件宿主适配为应用端口，并在边界处转换可变/内部 DTO。 */
public final class PluginManagerManagementAdapter implements PluginManagementPort {

    private final PluginManager manager;

    public PluginManagerManagementAdapter(PluginManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public List<Plugin> list() {
        return manager.list().stream().map(this::map).toList();
    }

    @Override
    public void refresh() { manager.refresh(); }

    @Override
    public void setEnabled(String pluginId, boolean enabled) {
        if (enabled) manager.enable(pluginId);
        else manager.disable(pluginId);
    }

    @Override
    public String install(Path jar) { return manager.installFromFile(jar); }

    @Override
    public boolean uninstall(String pluginId) { return manager.uninstall(pluginId); }

    @Override
    public Map<String, String> config(String pluginId) {
        return Map.copyOf(manager.getConfig(pluginId));
    }

    @Override
    public void saveConfig(String pluginId, Map<String, String> values) {
        manager.setConfig(pluginId, values);
    }

    @Override
    public Path pluginsDirectory() { return manager.pluginsDir(); }

    @Override
    public AutoCloseable onChanged(Runnable listener) {
        manager.setChangeListener(listener);
        return () -> manager.clearChangeListener(listener);
    }

    private Plugin map(PluginInfo info) {
        List<Permission> permissions = info.capabilities().stream()
                .sorted(Comparator.comparing(capability -> capability.displayName()))
                .map(capability -> new Permission(
                        capability.displayName(), info.granted().contains(capability)))
                .toList();
        List<ConfigField> fields = info.config().stream()
                .map(field -> new ConfigField(field.key(), field.label(), field.secret()))
                .toList();
        return new Plugin(
                info.id(), info.name(), info.version(), info.description(),
                permissions, fields,
                info.skills().stream().map(this::mapItem).toList(),
                info.tools().stream().map(this::mapItem).toList(),
                State.valueOf(info.state().name()), info.error());
    }

    private NamedItem mapItem(PluginInfo.NamedItem item) {
        return new NamedItem(item.name(), item.description());
    }
}
