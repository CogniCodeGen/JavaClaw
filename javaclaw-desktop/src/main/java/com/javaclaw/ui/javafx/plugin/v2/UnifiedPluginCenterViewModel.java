package com.javaclaw.ui.javafx.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.PluginSummary;
import com.javaclaw.application.plugin.v2.PluginManagementService.State;

import java.util.List;
import java.util.Locale;

/** 插件目录的纯视图模型；筛选与运行状态不依赖 JavaFX 控件。 */
final class UnifiedPluginCenterViewModel {
    static final String ALL_CAPABILITIES = "全部能力";
    private List<PluginSummary> plugins = List.of();

    void apply(List<PluginSummary> values) { plugins = List.copyOf(values); }
    List<PluginSummary> plugins() { return plugins; }
    List<String> capabilityFilters() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(ALL_CAPABILITIES),
                plugins.stream().flatMap(plugin -> plugin.capabilities().stream()).distinct().sorted()).toList();
    }
    List<PluginSummary> filtered(String query, String capability) {
        String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return plugins.stream().filter(plugin -> capability == null || ALL_CAPABILITIES.equals(capability)
                        || plugin.capabilities().contains(capability))
                .filter(plugin -> search.isBlank() || searchable(plugin).contains(search)).toList();
    }
    private static String searchable(PluginSummary plugin) {
        return (plugin.id() + " " + plugin.name() + " " + plugin.description() + " "
                + String.join(" ", plugin.capabilities())).toLowerCase(Locale.ROOT);
    }
    static String stateText(State state) {
        return switch (state) {
            case INSTALLED -> "已安装";
            case STOPPED -> "已停止";
            case STARTING -> "启动中";
            case READY -> "运行正常";
            case DEGRADED -> "运行降级";
            case DRAINING -> "等待执行结束";
            case FAILED -> "运行失败";
            case QUARANTINED -> "已隔离";
            case REVOKED -> "权限已撤销";
            case UNINSTALLING -> "等待卸载";
        };
    }
}
