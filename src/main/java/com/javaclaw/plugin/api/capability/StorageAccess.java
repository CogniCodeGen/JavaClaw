package com.javaclaw.plugin.api.capability;

import java.util.Set;

/**
 * STORAGE 能力 —— 插件经此在宿主分配的 H2 键值空间读写数据，替代插件自行操作文件。
 *
 * <p>需在 {@code plugin.json} 声明 {@link com.javaclaw.plugin.api.Capability#STORAGE}。
 * 存储按工作区隔离，落在全局 H2 {@code plugin_storage} 表，插件无须关心路径。</p>
 *
 * @author JavaClaw
 */
public interface StorageAccess {

    /**
     * 读取键值。
     *
     * @param key 键
     * @return 值；不存在时返回空串
     */
    String get(String key);

    /**
     * 写入键值（立即持久化）。
     *
     * @param key   键
     * @param value 值
     */
    void put(String key, String value);

    /**
     * 删除键。
     *
     * @param key 键
     */
    void remove(String key);

    /**
     * @return 当前所有键
     */
    Set<String> keys();
}
