package com.javaclaw.plugin;

import com.javaclaw.plugin.api.capability.StorageAccess;

/**
 * 为单个插件创建绑定工作区的持久存储能力。
 *
 * <p>实现由根 Spring Context 管理；返回实例随对应 {@link PluginRuntime} 的生命周期
 * 使用，不得动态读取当前工作区。创建过程不执行插件代码。</p>
 */
@FunctionalInterface
public interface PluginStorageFactory {

    StorageAccess create(String pluginId, String workspaceId);
}
