package com.javaclaw.application.plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 插件中心共享的应用入口。
 *
 * <p>实例由根 Spring Context 管理并可并发调用。除 {@link #snapshot()}、
 * {@link #pluginsDirectory()} 和监听登记外，其余方法都可能扫描文件、访问 H2、启动第三方
 * 插件或等待授权，必须从托管 I/O 任务调用。所有返回值都是不可变快照。</p>
 *
 * <p>监听可能由任意后台线程触发，调用方负责切换 UI 线程；关闭订阅是幂等的。启停和配置
 * 保存对同一目标重复执行是幂等的。取消通过中断协作完成，但插件回调无法强制中止时，页面
 * 仍必须丢弃迟到结果。</p>
 */
public interface PluginManagementApplicationService {

    Catalog snapshot();

    Catalog refresh();

    Catalog setEnabled(String pluginId, boolean enabled);

    ApprovalResult approveServicePlugin(String pluginId);

    InstallResult install(Path jar);

    Catalog uninstall(String pluginId);

    Details details(String pluginId);

    void saveConfig(String pluginId, Map<String, String> values);

    Path pluginsDirectory();

    AutoCloseable onCatalogChanged(Runnable listener);

    enum State {
        DISCOVERED,
        PENDING_APPROVAL,
        LOADED,
        ACTIVE,
        STOPPED,
        FAILED
    }

    record Permission(String name, boolean granted) {
        public Permission {
            name = text(name);
        }
    }

    record ConfigField(String key, String label, boolean secret) {
        public ConfigField {
            key = required(key, "配置键");
            label = text(label).isBlank() ? key : text(label);
        }
    }

    record NamedItem(String name, String description) {
        public NamedItem {
            name = required(name, "条目名称");
            description = text(description);
        }
    }

    record Plugin(
            String id,
            String name,
            String version,
            String description,
            List<Permission> permissions,
            List<ConfigField> config,
            List<NamedItem> skills,
            List<NamedItem> tools,
            State state,
            String error) {

        public Plugin {
            id = required(id, "插件 id");
            name = text(name).isBlank() ? id : text(name);
            version = text(version);
            description = text(description);
            permissions = List.copyOf(permissions == null ? List.of() : permissions);
            config = List.copyOf(config == null ? List.of() : config);
            skills = List.copyOf(skills == null ? List.of() : skills);
            tools = List.copyOf(tools == null ? List.of() : tools);
            state = java.util.Objects.requireNonNull(state, "state");
            error = text(error);
        }

        public boolean active() { return state == State.ACTIVE; }
    }

    record Catalog(List<Plugin> plugins) {
        public Catalog {
            plugins = List.copyOf(plugins == null ? List.of() : plugins);
        }

        public Plugin require(String pluginId) {
            return plugins.stream()
                    .filter(plugin -> plugin.id().equals(pluginId))
                    .findFirst()
                    .orElseThrow(() -> new com.javaclaw.application.error.NotFoundException(
                            "未找到插件：" + pluginId));
        }
    }

    record Details(Plugin plugin, Map<String, String> configValues) {
        public Details {
            plugin = java.util.Objects.requireNonNull(plugin, "plugin");
            configValues = Map.copyOf(configValues == null ? Map.of() : configValues);
        }
    }

    record InstallResult(String pluginId, Catalog catalog, boolean servicePlugin) {
        public InstallResult(String pluginId, Catalog catalog) {
            this(pluginId, catalog, false);
        }

        public InstallResult {
            pluginId = required(pluginId, "安装后的插件 id");
            catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        }
    }

    /** 服务插件显式审批结果；拒绝或取消属于正常结果，不伪装成系统失败。 */
    record ApprovalResult(Catalog catalog, boolean approved) {
        public ApprovalResult {
            catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
