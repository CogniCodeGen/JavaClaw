package com.javaclaw.server.discovery;

import java.util.List;
import java.util.Objects;

import com.javaclaw.server.extension.LoadedPlugin;
import com.javaclaw.server.extension.PluginCatalog;
import com.javaclaw.server.extension.PluginProcessKind;

/** 将安装目录中的插件声明投影为安全的发现结果，不暴露执行句柄。 */
public final class PluginServerDiscovery implements ServerDiscovery {
    private final PluginCatalog catalog;

    /** 绑定已验证插件目录；发现操作不加载第三方 JVM 代码。 */
    public PluginServerDiscovery(PluginCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public List<PluginView> plugins() {
        return catalog.list().stream()
                .map(plugin -> new PluginView(
                        plugin.manifest().id(),
                        plugin.manifest().name(),
                        plugin.manifest().version(),
                        plugin.manifest().apiVersion(),
                        plugin.signatureVerified(),
                        plugin.processes().stream()
                                .map(value -> process(plugin, value))
                                .toList(),
                        plugin.skills().stream()
                                .map(value -> new SkillView(
                                        value.declaration().id(),
                                        plugin.bundleRoot()
                                                .relativize(value.path())
                                                .toString()))
                                .toList()))
                .toList();
    }

    @Override
    public List<ProcessView> mcpServers() {
        return catalog.list().stream()
                .flatMap(plugin -> plugin.processes().stream()
                        .filter(value -> value.declaration().kind() == PluginProcessKind.MCP_SERVER)
                        .map(value -> process(plugin, value)))
                .toList();
    }

    private static ProcessView process(LoadedPlugin plugin, LoadedPlugin.ResolvedProcess process) {
        return new ProcessView(
                plugin.manifest().id(),
                process.declaration().id(),
                process.declaration().kind().name(),
                process.declaration().workspaceRead(),
                process.declaration().workspaceWrite(),
                List.copyOf(process.declaration().networkAllowlist()));
    }
}
