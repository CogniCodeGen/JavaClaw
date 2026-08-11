package com.javaclaw.application.plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 插件宿主的应用端口。实现必须线程安全，并维持订阅、启停和卸载的资源释放语义；
 * 阻塞方法由调用方安排到托管 I/O 执行器。
 */
public interface PluginManagementPort {

    List<PluginManagementApplicationService.Plugin> list();

    void refresh();

    void setEnabled(String pluginId, boolean enabled);

    String install(Path jar);

    boolean uninstall(String pluginId);

    Map<String, String> config(String pluginId);

    void saveConfig(String pluginId, Map<String, String> values);

    Path pluginsDirectory();

    AutoCloseable onChanged(Runnable listener);
}
